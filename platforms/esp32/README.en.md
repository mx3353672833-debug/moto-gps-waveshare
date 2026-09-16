> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Waveshare ESP32-S3-Touch-AMOLED-1.75C firmware

For a first flash see the [complete Waveshare edition DIY guide](../../docs/WAVESHARE_DIY_GUIDE.en.md),
and for operation once installed see the [user manual](../../docs/USER_MANUAL.en.md).

Applies only to the **1.75C**: CO5300 QSPI, CST9217, 466×466, 32 MB Flash, 8 MB PSRAM.
It uses ESP-IDF **5.5.5**, BSP **3.0.0** and the pinned LVGL submodule in the repository root.

## Build without flashing the board

First prepare and activate an ESP-IDF 5.5.5 environment following Espressif's installation
instructions, then run this in the repository root:

```sh
git submodule update --init --recursive
idf.py -C platforms/esp32 set-target esp32s3
idf.py -C platforms/esp32 build
```

The first build downloads the Component Manager dependencies; the output is in `platforms/esp32/build/`.
In `dependencies.lock` LVGL is a relative path under the project root; if a local tool rewrites it to
an absolute path, do not commit your personal paths back to the repository. Do not modify
`managed_components` by hand to keep the build going.

## Backup and flashing

Flashing replaces the factory application. First confirm the exact board model, USB serial port, Flash
capacity and encryption/secure boot state, and save a complete factory backup yourself; a backup may
contain device credentials, so **do not upload it to GitHub**.
The PORT below is a placeholder, replace it with the actual USB serial port; the commands need the
Python environment of an activated IDF.

```sh
python -m esptool --chip esp32s3 --port PORT flash_id
python -m esptool --chip esp32s3 --port PORT get_security_info
mkdir -p backups
# Only for boards confirmed to be 32 MB with Flash encryption/secure boot not enabled:
python -m esptool --chip esp32s3 --port PORT read_flash 0 0x2000000 backups/waveshare-original.bin
```

The syntax above corresponds to esptool 4.x in an ESP-IDF 5.5.5 environment. If you use esptool 5.x
yourself, the subcommands become `flash-id` / `get-security-info` / `read-flash`; rely on that version's
`--help`.
Stop if the security state, capacity or model does not match; the backup file should be 33,554,432
bytes, and save the check value separately.
Only after the backup succeeds and you accept overwriting the factory firmware, run:

```sh
idf.py -C platforms/esp32 -p PORT flash monitor
```

`erase-flash` is not needed; leave the serial log with Ctrl+]. Use only the flash parameters generated
by this local build, and do not apply another board's image offsets. On first use complete the system
Bluetooth pairing in the iPhone.

## Behaviour and limitations

- Advertised name `MOTO GPS`; the iPhone provides positioning, routes, scene and music state over BLE.
- After the black-and-white boot animation it enters the connection page; once connected it waits for
  the phone to select a route and does not draw an empty route as `0 m`.
- Swipe left and right to change pages; the page dots hide after five seconds. Whether the music page
  is available depends on the capabilities the phone declares.
- Holding PWR for about three seconds requests shutdown from the AXP2101; on USB power there is a
  deep-sleep fallback. Different power supply combinations still need to be measured.
- The QMI8658 relative angular rate assists turning; the heading while moving is anchored by phone
  positioning, and no absolute north reference at rest is provided.
- PSRAM double buffering and CO5300 TE synchronisation are used to reduce tearing; the target tick is
  not a guarantee of the measured sustained frame rate.

Key pins: QSPI D0–D3 are GPIO4–7; SCLK38, CS12, RESET1, TE13; I2C SDA15/SCL14;
touch INT11/RESET2. Do not treat these pins as free GPIOs you can connect to anything.

Upstream: [Waveshare official project](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C),
[Waveshare BSP](https://github.com/waveshareteam/Waveshare-ESP32-components).
For the known background disconnection and route issues see `docs/KNOWN_ISSUES.md` in the repository
root. A successful build does not mean riding acceptance has passed.
