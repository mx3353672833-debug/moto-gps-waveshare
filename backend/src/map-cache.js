import { mkdir, readdir, readFile, rename, stat, unlink, utimes, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

export const MAXIMUM_TILE_BYTES = 8 * 1024 * 1024;

// Only this provider's generated filenames are managed; unrelated files are never removed.
export class MapDiskCache {
  constructor(directory, maximumBytes = 1024 ** 3, maximumEntries = 100_000) {
    if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1024 || maximumBytes > 2 * 1024 ** 3) {
      throw new TypeError("map cache limit must be between 1 KiB and 2 GiB");
    }
    this.directory = directory;
    this.maximumBytes = maximumBytes;
    if (!Number.isSafeInteger(maximumEntries) || maximumEntries < 1 || maximumEntries > 100_000) {
      throw new TypeError("map cache entry limit must be between 1 and 100000");
    }
    this.maximumEntries = maximumEntries;
    this.entries = new Map();
    this.bytes = 0;
    this.lastError = null;
    this.serial = Promise.resolve();
    this.ready = this.initialize();
  }

  async initialize() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const names = await readdir(this.directory);
    for (const name of names) {
      if (!/^map-v1-[a-f0-9]{16}-15-\d+-\d+\.json$/.test(name)) continue;
      const info = await stat(join(this.directory, name)).catch(() => null);
      if (!info?.isFile()) continue;
      this.entries.set(name, { bytes: info.size, used: info.mtimeMs });
      this.bytes += info.size;
    }
    await this.trim();
  }

  mutate(operation) {
    const result = this.serial.then(operation);
    this.serial = result.catch(() => {});
    return result;
  }

  async remove(name) {
    await unlink(join(this.directory, name)).catch((error) => {
      if (error.code !== "ENOENT") throw error;
    });
    this.bytes -= this.entries.get(name)?.bytes ?? 0;
    this.entries.delete(name);
  }

  async trim() {
    if (this.bytes <= this.maximumBytes && this.entries.size <= this.maximumEntries) return;
    const oldest = [...this.entries].sort((a, b) => a[1].used - b[1].used);
    for (const [name] of oldest) {
      if (this.bytes <= this.maximumBytes && this.entries.size <= this.maximumEntries) break;
      await this.remove(name);
    }
  }

  async get(name, { z, x, y }) {
    await this.ready;
    const entry = this.entries.get(name);
    if (!entry) return null;
    try {
      if (entry.bytes > MAXIMUM_TILE_BYTES) throw new Error("oversized cached tile");
      const value = JSON.parse(await readFile(join(this.directory, name), "utf8"));
      if (value.schema_version !== 1 || value.coordinate_system !== "GCJ-02" ||
          value.tile?.z !== z || value.tile?.x !== x || value.tile?.y !== y ||
          !Array.isArray(value.roads) || !Array.isArray(value.buildings) ||
          typeof value.source?.retrieved_at !== "string" || !value.source?.source_revision) {
        throw new Error("invalid cached tile");
      }
      entry.used = Date.now();
      // Persist LRU recency across restarts. Atomic replacement makes concurrent reads safe.
      await utimes(join(this.directory, name), new Date(), new Date()).catch(() => {});
      return value;
    } catch {
      await this.mutate(() => this.entries.get(name) === entry ? this.remove(name) : undefined);
      return null;
    }
  }

  async put(name, value) {
    await this.ready;
    const serialized = JSON.stringify(value);
    const bytes = Buffer.byteLength(serialized);
    if (bytes > MAXIMUM_TILE_BYTES) throw new Error("tile JSON exceeds the size limit");
    if (bytes > this.maximumBytes) return;
    await this.mutate(async () => {
      const temporary = join(this.directory, `${name}.${randomUUID()}.tmp`);
      try {
        await writeFile(temporary, serialized, { mode: 0o600 });
        await rename(temporary, join(this.directory, name));
      } finally {
        await unlink(temporary).catch(() => {});
      }
      this.bytes += bytes - (this.entries.get(name)?.bytes ?? 0);
      this.entries.set(name, { bytes, used: Date.now() });
      await this.trim();
      this.lastError = null;
    });
  }

  status() {
    return { bytes: this.bytes, maximum_bytes: this.maximumBytes, tiles: this.entries.size,
      maximum_tiles: this.maximumEntries,
      last_error: this.lastError };
  }
}
