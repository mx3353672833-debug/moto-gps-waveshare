import test from "node:test";
import assert from "node:assert/strict";

import { clientAddress, createRateLimiter } from "../src/gateway.js";

function fakeRequest({ remoteAddress, realIp } = {}) {
  const headers = {};
  if (realIp !== undefined) headers["x-real-ip"] = realIp;
  return { headers, socket: { remoteAddress } };
}

test("a direct client cannot spoof its rate-limit address", () => {
  const request = fakeRequest({
    remoteAddress: "203.0.113.7",
    realIp: "9.9.9.9",
  });
  assert.equal(clientAddress(request), "203.0.113.7");
});

test("the loopback nginx proxy may provide the real client address", () => {
  for (const loopback of ["127.0.0.1", "::1", "::ffff:127.0.0.1"]) {
    const request = fakeRequest({
      remoteAddress: loopback,
      realIp: "198.51.100.23",
    });
    assert.equal(clientAddress(request), "198.51.100.23");
  }
});

test("rotating spoofed headers cannot evade the route limit", () => {
  const rateLimit = createRateLimiter();
  let allowed = 0;
  for (let i = 0; i < 40; i += 1) {
    const request = fakeRequest({
      remoteAddress: "203.0.113.7",
      realIp: `10.0.0.${i}`,
    });
    if (rateLimit(request, "routes", 30, 1_000)) allowed += 1;
  }
  assert.equal(allowed, 30);
});

test("the rate-limit table remains bounded under address rotation", () => {
  const rateLimit = createRateLimiter();
  for (let i = 0; i < 6_000; i += 1) {
    rateLimit(
      fakeRequest({
        remoteAddress: `198.51.${Math.floor(i / 256) % 256}.${i % 256}`,
      }),
      "routes",
      30,
      5_000,
    );
  }
  assert.ok(rateLimit.size <= 4_096);
});
