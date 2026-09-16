import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { isIP } from "node:net";
import { isMailbox } from "./mail.js";

const MAX_BODY_BYTES = 16_384;
const PER_IP_WINDOW_MS = 10 * 60_000;
const GLOBAL_WINDOW_MS = 60 * 60_000;

class RequestError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

export class RateBudget {
  constructor({ limit, windowMs, capacity = 10_000, now = Date.now }) {
    Object.assign(this, { limit, windowMs, capacity, now });
    this.entries = new Map();
  }
  take(key) {
    const now = this.now();
    for (const [id, value] of this.entries) {
      if (value.until <= now) this.entries.delete(id);
    }
    let entry = this.entries.get(key);
    if (!entry) {
      if (this.entries.size >= this.capacity) return Math.ceil(this.windowMs / 1000);
      entry = { used: 0, until: now + this.windowMs };
      this.entries.set(key, entry);
    }
    if (entry.used >= this.limit) return Math.max(1, Math.ceil((entry.until - now) / 1000));
    entry.used += 1;
    return 0;
  }
}

export function clientAddress(request, trustProxy) {
  const peer = request.socket.remoteAddress || "unknown";
  const loopback = ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(peer);
  const forwarded = request.headers["x-real-ip"];
  if (trustProxy && loopback && typeof forwarded === "string" && isIP(forwarded)) return forwarded;
  return peer;
}

function respond(response, status, value, headers = {}) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    ...headers,
  });
  response.end(JSON.stringify(value));
}

async function readJson(request) {
  const contentType = request.headers["content-type"] || "";
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(contentType)) {
    throw new RequestError(415, "invalid_content_type", "请使用网页表单提交。");
  }
  if (request.headers["content-encoding"] && request.headers["content-encoding"] !== "identity") {
    throw new RequestError(415, "invalid_content_encoding", "不支持此提交格式。");
  }
  if (Number(request.headers["content-length"] || 0) > MAX_BODY_BYTES) {
    throw new RequestError(413, "payload_too_large", "内容过长，请缩短后重试。");
  }
  const bytes = await new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    const clean = () => {
      request.off("data", data);
      request.off("end", end);
      request.off("aborted", aborted);
      request.off("error", failed);
    };
    const failed = (error) => { clean(); reject(error); };
    const aborted = () => failed(new RequestError(400, "request_aborted", "提交已中断。"));
    const data = (chunk) => {
      length += chunk.length;
      if (length > MAX_BODY_BYTES) {
        failed(new RequestError(413, "payload_too_large", "内容过长，请缩短后重试。"));
        request.resume();
        return;
      }
      chunks.push(chunk);
    };
    const end = () => { clean(); resolve(Buffer.concat(chunks)); };
    request.on("data", data);
    request.once("end", end);
    request.once("aborted", aborted);
    request.once("error", failed);
  });
  try { return JSON.parse(bytes.toString("utf8")); }
  catch { throw new RequestError(400, "invalid_json", "提交内容格式有误。"); }
}

export function validatePayload(value) {
  const bad = () => { throw new RequestError(400, "invalid_form", "请检查问题、称呼和邮箱格式。"); };
  if (!value || typeof value !== "object" || Array.isArray(value)) bad();
  const allowed = new Set(["question", "email", "name", "website", "token"]);
  if (Object.keys(value).some((key) => !allowed.has(key))) bad();
  if (typeof value.question !== "string") bad();
  for (const field of ["email", "name", "website", "token"]) {
    if (value[field] !== undefined && typeof value[field] !== "string") bad();
  }
  const question = value.question.trim();
  const email = (value.email || "").trim();
  const name = (value.name || "").trim();
  if (question.length < 2 || question.length > 4000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(question)) bad();
  if (name.length > 80 || /[\r\n\u0000-\u001f\u007f]/.test(name)) bad();
  if (email && !isMailbox(email)) bad();
  if ((value.website || "").trim() || (value.token || "").length > 4096) bad();
  // token is reserved for a future challenge integration; it grants no access.
  return { question, email, name };
}

export function createContactServer({ mailer, allowedOrigin = "https://maler.top", trustProxy = false,
  now = Date.now, log = () => {}, perIpLimit = 3, globalLimit = 30 } = {}) {
  if (!mailer?.send) throw new Error("MAILER_REQUIRED");
  if (new URL(allowedOrigin).origin !== allowedOrigin) throw new Error("CONTACT_ORIGIN_INVALID");
  const perIp = new RateBudget({ limit: perIpLimit, windowMs: PER_IP_WINDOW_MS, now });
  const global = new RateBudget({ limit: globalLimit, windowMs: GLOBAL_WINDOW_MS, capacity: 1, now });
  let active = 0;
  const server = createServer(async (request, response) => {
    // A disconnected client may emit an error after the body reader cleans up.
    request.on("error", () => {});
    if (request.url === "/healthz" && request.method === "GET") {
      return respond(response, 200, { ok: true, service: "glimpse-contact" });
    }
    if (request.url !== "/api/contact") return respond(response, 404, { ok: false, code: "not_found" });
    if (request.method !== "POST") return respond(response, 405, { ok: false, code: "method_not_allowed" }, { Allow: "POST" });
    if (request.headers.origin !== allowedOrigin || request.headers["sec-fetch-site"] === "cross-site") {
      request.resume();
      return respond(response, 403, { ok: false, code: "invalid_origin", message: "请从官网提交问题。" });
    }
    const retry = perIp.take(clientAddress(request, trustProxy));
    if (retry) {
      request.resume();
      return respond(response, 429, { ok: false, code: "rate_limited", message: "提交较频繁，请稍后再试。" }, { "Retry-After": String(retry) });
    }
    const requestId = randomUUID();
    try {
      const payload = validatePayload(await readJson(request));
      const globalRetry = global.take("all");
      if (globalRetry || active >= 2) {
        return respond(response, 429, { ok: false, code: "busy", message: "现在提交较多，请稍后再试。" }, { "Retry-After": String(globalRetry || 15) });
      }
      active += 1;
      try {
        const result = await mailer.send(payload, requestId);
        log({ event: "contact_accepted", requestId, messageId: result?.messageId });
        return respond(response, 200, { ok: true, requestId, message: "问题已发送，谢谢你的反馈。" });
      } finally { active -= 1; }
    } catch (error) {
      request.resume();
      if (error instanceof RequestError) {
        return respond(response, error.status, { ok: false, code: error.code, message: error.message });
      }
      // SMTP errors can contain credentials, addresses or server details; never return them.
      log({ event: "contact_failed", requestId });
      return respond(response, 503, { ok: false, code: "delivery_unavailable", requestId,
        message: "暂时未能发送，请稍后重试，或直接发邮件联系。" });
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  return server;
}
