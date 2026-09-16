> **Language:** English · [中文](README.md)

> The Chinese document is authoritative if the two differ.

# MOTO GPS project website

Live URL: <https://maler.top/moto-gps/>.

Static HTML, CSS and a small amount of vanilla JavaScript. No package installation, analytics scripts or remote fonts. The page includes a project overview, native app screenshots, round-display views, online/offline map guidance, three setup steps, development status and 13 searchable questions. All content and native disclosure controls remain available without JavaScript.

```sh
python3 -m http.server 4187 --directory website
```

Open `http://127.0.0.1:4187/`. Publish only `index.html`, `site.css`, `site.js` and `assets/`; the README files do not need to be deployed.

## Keeping the browser debugging tool

The website uses the existing `/moto-gps/` static path. Before its first deployment, preserve the original navigation tool's `index.html` unchanged as `ride.html`, together with `app.js`, `styles.css`, `wasm-loader.js`, `manifest.webmanifest` and `runtime/`. Relative assets and the `./api/` endpoint still resolve from the same directory. The new homepage uses separate `site.css` and `site.js` files.

For a fresh installation, build and deploy the debugging tool using `platforms/web/shell/README.md` first, then preserve `ride.html` and add this website. A standalone local preview of this directory does not include that tool; its link requires the complete deployment.

Legacy `?demo=...`, `?deviceState=...` and `?api=...` links are redirected to `ride.html` with their parameters intact. This preserves entry points without adding features to the old tool. The browser tool needs to remain in the foreground and does not replace native background location or BLE display support. Keep the separate `/moto-gps/api/` reverse proxy unchanged.

Back up the current site before deployment. Upload assets and styles first, then replace the homepage. Verify the homepage, `ride.html`, legacy scripts, WASM and API health. Do not use synchronization options that delete the existing tool's files.

## Content and images

- Existing project artwork is explicitly labelled as a concept; its speed-limit sign does not represent a connected feature.
- App images come from development checks; round-display numbers are demonstrations. Keep data-provider attribution.
- Do not add App Store or TestFlight buttons until valid public links exist.
- Keep private signing data, device records, test logs, personal screenshots and incomplete privacy-policy drafts out of the public directory.
- This page does not replace the app's formal privacy policy. Publisher identity, contact details and supplier processing arrangements still need to be completed for distribution.

Check JavaScript with `node --check website/site.js`. This update checked local assets, anchors, desktop and 390px mobile layouts, FAQ search/empty/reset states and legacy-query redirection.
