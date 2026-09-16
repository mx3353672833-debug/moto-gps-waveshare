> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Route gateway

Node.js 20+, no third-party runtime dependencies; Node.js 24+, which supports `--env-file`, is recommended.
The AMap key lives only here and must not be written into the iOS app, the web page or the firmware.

## Run the local tests first

```sh
cd backend
npm test
MOTO_PROVIDER=fixture WEB_ORIGIN=http://127.0.0.1:4173 npm start
```

The service listens on `127.0.0.1:8787`. The fixture returns a fixed synthetic test route and empty
search results; it is not live navigation. When `MOTO_PROVIDER` is not set it also defaults to
fixture, so always configure it explicitly when deploying.

## Configure the live service

```sh
cp .env.example .env
# Edit .env locally: fill in your own AMAP_WEB_SERVICE_KEY and set MOTO_PROVIDER=amap.
# Change WEB_ORIGIN to your web page's actual Origin; do not commit .env to Git.
node --env-file=.env src/server.js
```

`npm start` does not read `.env` automatically; you can also inject environment variables from your
own service manager.
While the key is not ready keep `MOTO_PROVIDER=disabled`, and do not treat the fixture as a
production fallback.

Then use your own HTTPS reverse proxy to forward `/moto-gps/api/` to `http://127.0.0.1:8787/`.
Mind the trailing `/` and the prefix rewrite. For example the client's `/moto-gps/api/v1/routes` should be
forwarded to the backend's `/v1/routes`.
Use your own domain certificate, and configure the same HTTPS base address in the iPhone; do not use
the computer's localhost directly.

```sh
curl https://YOUR-DOMAIN/moto-gps/api/healthz
```

Check that `provider` is `amap` and `ready_for_live_navigation` is `true`.
The health check means the configuration is ready; it **does not replace one real search and route
request** to verify key permissions, quota and network.
This repository does not provide a public gateway. Basic rate limiting and CORS cannot replace
authentication; before publishing to the public internet add access control, TLS, quota limits, log
redaction and alerting yourself. Do not log complete travel traces or keys.

## API

| Endpoint | Purpose |
| --- | --- |
| `GET /healthz` | Service mode and configuration readiness |
| `GET /v1/places?keywords=...&longitude_deg=...&latitude_deg=...&region=...` | POI search biased by the current position; the input coordinates are WGS84 and must be passed in pairs |
| `POST /v1/route-options` | Up to three candidate ordinary driving routes for the app to show in full and choose from |
| `POST /v1/routes` | A single route, off-route recomputation and periodic route / traffic refresh |

The request and response schemas are in `shared/protocol`; a route request example is in
`fixtures/route-request-v1.json`.
Input positions are WGS84 and route geometry is output as GCJ-02; the gateway handles the boundary
conversion.
`request_id` is returned unchanged, and the navigation core rejects stale responses.

Ordinary driving route planning is not a motorcycle-specific route and cannot guarantee that
motorcycle prohibition rules are respected. Traffic refresh is not an unlimited free live traffic
feed; the actual service permissions, frequency and quota are decided by your AMap account and
agreement.
The surrounding grey roads / buildings use the OSM data provided with the repository, and no AMap
map tiles or raw API responses are cached.
Before going live or using it in smart hardware you must check AMap's authorisation for the
corresponding usage scenario yourself.
