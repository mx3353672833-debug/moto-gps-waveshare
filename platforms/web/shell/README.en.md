> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Web debugging shell

This is the scene console for the web platform only. The round-display UI is compiled from the shared LVGL code to WebAssembly and then takes over the canvas; this directory implements only the browser platform shell, not the iPhone background BLE companion.

## Local preview

First initialise the LVGL submodule, install and activate Emscripten, then build the shared LVGL WebAssembly runtime in the repository root:

```sh
./scripts/build_web.sh
python3 -m http.server 4173 --directory .
```

Open the local HTTP address in a browser (do not open it through file://):

```text
http://127.0.0.1:4173/platforms/web/shell/index.html
```

The debugging shell does not depend on external fonts or frameworks. WASM must be loaded over HTTP; when it has not been built the page keeps the temporary wiring screen and explicitly shows "WASM not built".

To watch the round-display navigation simulation locally without enabling positioning and route services, open:

```text
/platforms/web/shell/index.html?demo=1
```

The connection lifecycle is rendered by the same LVGL firmware UI, and can be reproduced with the following acceptance addresses:

```text
/platforms/web/shell/index.html?deviceState=offline
/platforms/web/shell/index.html?deviceState=connecting
/platforms/web/shell/index.html?deviceState=success
/platforms/web/shell/index.html?deviceState=ready
/platforms/web/shell/index.html?deviceState=planning
```

`success` is a real, brief green transition that automatically enters "ready to ride" after about 0.9 seconds;
`ready` likewise plays one connection success first when opened, then stays on "ready to ride".

## WASM takeover conventions

The fixed mount point is:

```text
#canvas (466 × 466)
```

Call before WASM initialisation:

```js
const canvas = window.MotoNavShell.claimCanvas();
```

`claimCanvas()` replaces the temporary 2D wiring screen with a clean new canvas, so that the old 2D context does not stop Emscripten/SDL from obtaining a WebGL context. The SDL Emscripten backend looks for `#canvas` by design, so this ID is part of the platform adaptation contract.

The shell sends the following `window` events; all event names start with `motonav:` and the data is in `event.detail`:

- `scene-change`
- `traffic-change`
- `playback-change`
- `progress-change`
- `speed-change`
- `deviation-change`
- `network-change`
- `canvas-claimed`

External code can also read `window.MotoNavShell.getState()`, or call the exposed scene, progress and network state methods. The debug log keeps at most 40 entries.
