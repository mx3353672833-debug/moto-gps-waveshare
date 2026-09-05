# WebAssembly device target

This directory compiles the exact shared LVGL device UI into JavaScript and
WebAssembly. The HTML debug shell is separate and must not implement device
features with DOM or CSS.

```sh
emcmake cmake -S . -B build/web -DMOTO_BUILD_WEB=ON -DMOTO_BUILD_TESTS=OFF
cmake --build build/web -j
```

The generated `moto-gps-device.js` and `.wasm` files are written to
`platforms/web/shell/runtime/` (ignored by Git).
