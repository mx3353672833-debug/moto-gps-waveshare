// Read before decoding so an upstream ignoring Range cannot fill server memory.
export async function readLimitedBody(response, maximumBytes) {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > maximumBytes) {
    await response.body?.cancel();
    throw new Error("upstream response exceeds the size limit");
  }
  const chunks = [];
  let size = 0;
  if (!response.body) return Buffer.alloc(0);
  const reader = response.body.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximumBytes) throw new Error("upstream response exceeds the size limit");
      chunks.push(Buffer.from(value));
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks, size);
}

export function createSemaphore(maximum = 4, maximumQueued = 128) {
  let active = 0;
  const waiting = [];
  return async (operation) => {
    if (active >= maximum) {
      if (waiting.length >= maximumQueued) {
        const error = new Error("map upstream queue is full");
        error.code = "MAP_BUSY";
        throw error;
      }
      await new Promise((resolve) => waiting.push(resolve));
    } else {
      active += 1;
    }
    try {
      return await operation();
    } finally {
      const next = waiting.shift();
      if (next) next();
      else active -= 1;
    }
  };
}
