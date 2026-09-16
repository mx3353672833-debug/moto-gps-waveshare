> **Language:** English · [中文](README.md)

> The Chinese document is authoritative if the two differ.

# Glimpse website

Website: <https://maler.top/>. The public website uses Glimpse; MOTO GPS technical names in the
repository, App project and firmware remain unchanged.

The frontend uses static HTML, CSS and vanilla JavaScript, with no frontend package installation,
analytics scripts or remote fonts. It covers the display and App, an animated usage walkthrough, map
downloads, the B1 custom board, 10 searchable questions and an email contact form. Text and native FAQ
disclosures work without JavaScript; demos, search and form submission require it. Email submission
uses a separate Node.js service and is not a static-only feature; see the [service notes](server/README.md).

## Local preview

```sh
python3 -m http.server 4187 --directory website
```

Open `http://127.0.0.1:4187/` to inspect static content. This command does not start the email service
and cannot validate delivery. Publish only `index.html`, `site.css`, `site.js`, `demos.css`, `demos.js`
and `assets/`. Never copy `server/`, environment files, README files or installed dependencies into
the public static directory.

## Domain migration and legacy tools

Use an independent `site-current/` static release directory for the new website and point the domain
root at it. Manage it separately from the navigation tool; do not use deletion-based synchronisation
against the whole domain directory.

| Path | Deployment behaviour |
| --- | --- |
| `/` | Glimpse website; canonical is `https://maler.top/` |
| `/moto-gps/` homepage | HTTP 301 to `/` for existing links |
| `/glimpse`, `/glimpse/` and descendants | Retire the old site and return HTTP 410 |
| `/moto-gps/ride.html` | Existing browser debugging tool, linked in technical docs only, not on the website |
| `/moto-gps/api/` | Existing navigation gateway proxy, unchanged |
| Legacy scripts, styles, WASM, runtime and other debugging assets under `/moto-gps/` | Keep their paths and contents |
| `/api/contact` | Separate email API, proxied unchanged to loopback `127.0.0.1:3024` for this deployment |

The old tool's `app.js`, `styles.css`, `wasm-loader.js`, `manifest.webmanifest` and `runtime/` still load
from their existing paths. Website `site.js` preserves old `demo`, `deviceState` or `api` query links,
redirecting their query and hash to the absolute `/moto-gps/ride.html` path. This adds no capabilities
to the old tool: it still needs the foreground and cannot replace native background positioning or BLE.

For a fresh tool installation, follow the [web tool guide](../platforms/web/shell/README.en.md)
separately. The website directory does not include that tool or provide a free public navigation gateway.

## Email contact service

The form posts JSON to same-origin `/api/contact`. The service binds only to loopback, independently
of the navigation gateway. Its source default is port 8788; this deployment sets `CONTACT_PORT=3024`,
so the proxy target must also use 3024. See its [README](server/README.md) for dependencies, tests,
environment fields, TLS and proxy requirements. Keep real SMTP credentials and deployment settings
on the server, out of web assets, source control and public logs.

The API reports success only after SMTP accepts the message; the frontend then clears the form.
Failures preserve its contents. HTTP success means handoff to the email service, not guaranteed
inbox delivery. A static preview or health response does not replace one authorised delivery test;
do not repeatedly send messages to poll for a result. A visitor's reply email is optional.

## Images and animated demos

- `assets/glimpse-device.png`: edited from the existing `assets/concept.png` using built-in
  `imagegen`, with a transparent background and the old logo and speed-limit sign removed. It is a
  design concept, not a photograph of production hardware.
- `assets/videos/`: reuses 9 display recordings and 9 posters from the former site, 18 files totalling
  927,742 bytes (about 906 KiB). Recording contents are retained; routes and values are demonstration data.
- `demos.js` / `demos.css`: present page switching and the connection/route-selection/navigation
  walkthrough. Only the visible selected mode loads video; playback pauses offscreen or in hidden
  tabs. Controls support play/pause, reduced motion and data-saving preferences, with poster fallback
  on playback failure. All 9 videos need not download together.
- `assets/board-b1.svg`: the top-layer routing view exported directly from the actual B1 PCB project.
  It depicts an engineering candidate, not a fabricated, powered-on or production-accepted board.
  Custom-board, enclosure and mounting work remain paused.
- App images come from development checks. Keep OpenStreetMap, Protomaps and other provider attribution.

The website replaces old slogans and removes the browser debugging link and questions about trying
without a display, countdown/speed-limit/congestion progress and TestFlight fees. The product-availability
question retains the real release status and links to the GitHub DIY guide. Public source uses PolyForm
Noncommercial 1.0.0; do not describe it as unrestricted open source or grant additional commercial rights.
The Android question links to the [AI development guide](../docs/ANDROID_AI_GUIDE.en.md), with a porting
prompt and GitHub release steps, and states that no ready-to-install Android app is available yet.

Do not add nonexistent App Store / TestFlight download buttons or publish private signing details,
device records, test logs or incomplete policy drafts. This website does not replace the App's formal
privacy policy.

## Deployment checks

1. Back up existing static files and proxy configuration, upload the new release directory and deploy
   the email service separately.
2. Validate proxy configuration before switching the root homepage. Configure the old-homepage 301,
   old `/glimpse` 410 and the two independent API routes.
3. Check the homepage, assets, demos, FAQ search and form success/failure states; review mobile layout,
   keyboard interaction and reduced motion. Use one explicitly marked authorised email delivery test.
4. Verify legacy `ride.html`, scripts, WASM and navigation API, then check redirects and 410 responses.

Local syntax checks:

```sh
node --check website/site.js
node --check website/demos.js
```

These are deployment steps. Files existing or passing syntax checks do not establish successful
production migration or email delivery.
