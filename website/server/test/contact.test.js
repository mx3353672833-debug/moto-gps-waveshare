import assert from "node:assert/strict";
import { once } from "node:events";
import http from "node:http";
import test from "node:test";
import { createContactServer, clientAddress, RateBudget } from "../contact.js";
import { createMailer, smtpOptions, RECIPIENT, SUBJECT } from "../mail.js";

const origin = "https://maler.top";
const good = { question: "测试问题：是否支持城市地图下载？", email: "visitor@example.com", name: "访客", website: "" };
const smtpEnv = { SMTP_HOST: "smtp.example.com", SMTP_PORT: "587", SMTP_SECURE: "false",
  SMTP_USER: "sender@example.com", SMTP_PASS: "test-password", SMTP_FROM_EMAIL: "sender@example.com" };

async function fixture(t, options = {}) {
  const sent = [];
  const server = createContactServer({
    mailer: { async send(payload, requestId) { sent.push({ payload, requestId }); return { messageId: "mock@example.com" }; } },
    ...options,
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }));
  return { sent, server, url: `http://127.0.0.1:${server.address().port}/api/contact` };
}

async function post(f, body = good, headers = {}) {
  return fetch(f.url, { method: "POST", headers: { "Content-Type": "application/json", Origin: origin, ...headers }, body: JSON.stringify(body) });
}

test("valid submission waits for acceptance and returns no private form contents", async (t) => {
  const f = await fixture(t);
  const response = await post(f);
  assert.equal(response.status, 200);
  const json = await response.json();
  assert.equal(json.ok, true);
  assert.equal(typeof json.requestId, "string");
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].payload.question, good.question);
  assert.equal(JSON.stringify(json).includes(good.email), false);
});

test("missing or foreign Origin and cross-site browser requests never send", async (t) => {
  const f = await fixture(t);
  for (const headers of [{ Origin: "https://evil.example" }, { Origin: "null" }, { "Sec-Fetch-Site": "cross-site" }]) {
    assert.equal((await post(f, good, headers)).status, 403);
  }
  assert.equal((await fetch(f.url, { method: "POST", body: JSON.stringify(good) })).status, 403);
  assert.equal(f.sent.length, 0);
});

test("honeypot, recipient override and header injection are rejected", async (t) => {
  const f = await fixture(t, { perIpLimit: 20 });
  for (const body of [
    { ...good, website: "https://spam.example" }, { ...good, to: "other@example.com" },
    { ...good, email: "a@example.com\r\nBcc: attacker@example.com" },
    { ...good, name: "hello\r\nBcc: attacker@example.com" },
    { question: " " }, { ...good, question: "x".repeat(4001) },
    { ...good, email: ["visitor@example.com"] }, { ...good, question: "bad\u0000text" },
  ]) assert.equal((await post(f, body)).status, 400);
  assert.equal(f.sent.length, 0);
});

test("malformed JSON, content type and compressed input fail without SMTP", async (t) => {
  const f = await fixture(t, { perIpLimit: 20 });
  assert.equal((await post(f, good, { "Content-Type": "text/plain" })).status, 415);
  assert.equal((await post(f, good, { "Content-Encoding": "gzip" })).status, 415);
  assert.equal((await fetch(f.url, { method: "POST", headers: { Origin: origin, "Content-Type": "application/json" }, body: "{" })).status, 400);
  assert.equal(f.sent.length, 0);
});

test("both declared and chunked oversized requests return 413", async (t) => {
  const f = await fixture(t);
  assert.equal((await post(f, { question: "x".repeat(20_000) })).status, 413);
  const status = await new Promise((resolve, reject) => {
    const request = http.request(f.url, { method: "POST", headers: { Origin: origin, "Content-Type": "application/json", "Transfer-Encoding": "chunked" } }, (response) => {
      response.resume(); response.on("end", () => resolve(response.statusCode));
    });
    request.on("error", reject);
    request.write('{"question":"'); request.write("x".repeat(20_000)); request.end('"}');
  });
  assert.equal(status, 413);
  assert.equal(f.sent.length, 0);
});

test("SMTP errors return explicit failure without leaking provider details", async (t) => {
  const logs = [];
  const f = await fixture(t, { mailer: { async send() { throw new Error("password=secret provider@example.com"); } }, log: (value) => logs.push(value) });
  const response = await post(f);
  assert.equal(response.status, 503);
  const result = await response.json();
  assert.equal(result.ok, false);
  assert.equal(result.code, "delivery_unavailable");
  assert.equal(JSON.stringify([result, logs]).includes("secret"), false);
});

test("per-address limits cannot be bypassed with untrusted forwarded headers", async (t) => {
  const f = await fixture(t);
  for (let i = 0; i < 3; i++) assert.equal((await post(f, good, { "X-Real-IP": `192.0.2.${i + 1}`, "X-Forwarded-For": `192.0.2.${i + 1}` })).status, 200);
  const limited = await post(f, good, { "X-Real-IP": "192.0.2.99" });
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get("retry-after")) > 0);
  assert.equal(f.sent.length, 3);
});

test("global delivery budget applies across proxy-supplied visitor addresses", async (t) => {
  const f = await fixture(t, { trustProxy: true, globalLimit: 1 });
  assert.equal((await post(f, good, { "X-Real-IP": "192.0.2.1" })).status, 200);
  assert.equal((await post(f, good, { "X-Real-IP": "192.0.2.2" })).status, 429);
  assert.equal(f.sent.length, 1);
});

test("trusted proxy address is used only from loopback and only when valid", () => {
  const req = { socket: { remoteAddress: "192.0.2.9" }, headers: { "x-real-ip": "192.0.2.10" } };
  assert.equal(clientAddress(req, true), "192.0.2.9");
  req.socket.remoteAddress = "127.0.0.1";
  assert.equal(clientAddress(req, true), "192.0.2.10");
  req.headers["x-real-ip"] = "192.0.2.10, 192.0.2.11";
  assert.equal(clientAddress(req, true), "127.0.0.1");
});

test("rate-limit memory is bounded and expires without allowing address rotation overflow", () => {
  let now = 0;
  const limiter = new RateBudget({ limit: 1, capacity: 2, windowMs: 1000, now: () => now });
  assert.equal(limiter.take("a"), 0); assert.equal(limiter.take("b"), 0);
  assert.equal(limiter.take("c"), 1); assert.equal(limiter.entries.size, 2);
  now = 1000; assert.equal(limiter.take("c"), 0); assert.equal(limiter.entries.size, 1);
});

test("mail transport fixes recipient, sender and subject while keeping question in plain text", async () => {
  let message;
  const mailer = createMailer(smtpEnv, () => ({ async sendMail(value) { message = value; return { accepted: [RECIPIENT], rejected: [], messageId: "test@example.com" }; } }));
  await mailer.send({ ...good, question: "Hello\nBcc: this is body text" }, "test-id");
  assert.equal(message.to, RECIPIENT);
  assert.deepEqual(message.envelope.to, [RECIPIENT]);
  assert.equal(message.from.address, smtpEnv.SMTP_FROM_EMAIL);
  assert.equal(message.subject, SUBJECT);
  assert.equal(message.replyTo.address, good.email);
  assert.equal(message.html, undefined);
  assert.equal(message.attachments, undefined);
});

test("SMTP recipient rejection cannot be reported as success", async () => {
  for (const result of [{ accepted: [], rejected: [RECIPIENT] }, { accepted: ["other@example.com"], rejected: [] }]) {
    const mailer = createMailer(smtpEnv, () => ({ async sendMail() { return result; } }));
    await assert.rejects(mailer.send(good, "test-id"), /SMTP_RECIPIENT_NOT_ACCEPTED/);
  }
});

test("SMTP requires authenticated TLS and refuses unsafe sender configuration", () => {
  const options = smtpOptions(smtpEnv);
  assert.equal(options.transport.requireTLS, true);
  assert.equal(options.transport.tls.rejectUnauthorized, true);
  assert.equal(options.transport.disableFileAccess, true);
  assert.equal(options.transport.disableUrlAccess, true);
  assert.throws(() => smtpOptions({ ...smtpEnv, SMTP_FROM_EMAIL: "a@example.com\nBcc:b@example.com" }));
  assert.throws(() => smtpOptions({ ...smtpEnv, SMTP_PASS: "" }));
});

test("an aborted request cannot crash the service or send a partial question", async (t) => {
  const f = await fixture(t);
  await new Promise((resolve) => {
    const request = http.request(f.url, { method: "POST", headers: { Origin: origin, "Content-Type": "application/json" } });
    request.on("error", () => {});
    request.on("close", resolve);
    f.server.once("request", () => request.destroy());
    request.write('{"question":"unfinished');
  });
  assert.equal((await fetch(f.url.replace("/api/contact", "/healthz"))).status, 200);
  assert.equal(f.sent.length, 0);
});

test("only two SMTP sends may be in flight and a third gets a retry response", async (t) => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  let ready;
  const active = new Promise((resolve) => { ready = resolve; });
  let count = 0;
  const f = await fixture(t, { mailer: { async send() {
    count += 1;
    if (count === 2) ready();
    await gate;
    return { messageId: "mock@example.com" };
  } } });
  const first = post(f); const second = post(f);
  await active;
  const third = await post(f);
  assert.equal(third.status, 429);
  assert.equal(third.headers.get("retry-after"), "15");
  release();
  assert.deepEqual((await Promise.all([first, second])).map((response) => response.status), [200, 200]);
  assert.equal(count, 2);
});
