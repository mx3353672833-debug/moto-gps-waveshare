> **Language:** English · [中文](WAVESHARE_DIY_GUIDE.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Waveshare edition DIY guide: buying, flashing, iPhone installation and first use

Applies to the model: **Waveshare ESP32-S3-Touch-AMOLED-1.75C**. This document follows the complete
Mac + iPhone flow and is written for readers meeting ESP32 for the first time. For day-to-day
operation after installation see the [features and user manual](USER_MANUAL.en.md).

## Understand the installation requirements first

The repository currently provides source code. As of the last update of this guide, GitHub Releases
has no prebuilt firmware, and the repository provides no App Store listing, TestFlight invitation or
signed IPA that can be installed directly. The actual path this document uses is:

```text
buy a 1.75C production device → build the firmware on the Mac → back up over USB and flash
                 → install the App on your own iPhone with Xcode → Bluetooth connection → demo
                 → configure your own HTTPS route gateway → search for a destination and navigate
```

Reproducing the demo needs the device, a Mac, an iPhone and an Apple account; real place search and
routing also need your own AMap Web Service key and an HTTPS gateway the phone can reach. With only
a Windows computer you can still build and flash the ESP32, but this document's iPhone installation
path still needs a Mac / Xcode. Downloading the GitHub ZIP to an iPhone cannot install the App.

[What to buy](#1-what-to-buy) · [Prepare the tools](#2-prepare-the-mac-tools-and-the-source) · [Backup and flashing](#3-build-back-up-and-flash-the-firmware) ·
[iPhone installation](#4-install-the-app-on-the-iphone) · [First connection](#5-first-pairing-and-the-desktop-demo) ·
[Live navigation](#6-configure-live-navigation) · [Troubleshooting](#8-frequently-asked-questions)

## 1. What to buy

| Item | Quantity | How to choose |
| --- | --- | --- |
| Waveshare ESP32-S3-Touch-AMOLED-1.75C | 1 unit | The complete device; check the C at the end of the model. This project's configuration requires 32 MB Flash and 8 MB PSRAM |
| USB-C cable | 1 | A cable that can transfer files / connect to a computer; a charge-only cable cannot flash |
| Mac | 1 | Can run an Xcode that supports the iPhone OS version in use; the project needs the Swift 6 toolchain |
| iPhone | 1 | iOS 17 or later; Bluetooth and precise location working |
| Apple account | 1 | For signing and installing on your own phone from Xcode |
| Battery | Optional | USB is enough for the first desktop verification; if you need a battery, have the seller confirm the dimensions, connector and polarity for the 1.75C |
| Handlebar mount | Prepare when fitting it to the bike | Choose it according to the enclosure Waveshare currently offers and test-fit it; the repository's in-house V3 mount is not the same as a ready-made accessory for the Waveshare device |

Where to buy: the [official Waveshare store](https://www.waveshare.net/), search for the complete
model; the [English product page](https://www.waveshare.com/esp32-s3-touch-amoled-1.75c.htm); the
[Chinese official model and resource documentation](https://docs.waveshare.net/ESP32-S3-Touch-AMOLED-1.75C/).
Prices, whether a bundle includes a battery and the shipping region are subject to the purchase page
and confirmation from the seller.

Do not shop by "ESP32 round display" or "1.75 inch" alone. The 1.75, 1.75C, 1.43 and LCD models
differ in driver and wiring. This project is adapted to the CO5300 display + CST9217 touch. There is
no need to buy a bare panel, an LC76G, a magnetometer or an in-house PCB separately.

Check the capacity in particular: the official Chinese page's "Product features" section says 16 MB
while "Onboard resources" says 32 MB, so the text is inconsistent. This repository's
`sdkconfig.defaults` explicitly configures 32 MB; ask the seller to confirm when you order, and run
the `flash_id` below after the device arrives. If you detect another capacity, do not simply apply
this guide's flashing steps.

## 2. Prepare the Mac tools and the source

### 2.1 Install Xcode and the command-line tools

Install the complete Xcode from the Mac App Store, or use the
[Apple developer downloads](https://developer.apple.com/download/). Open Xcode for the first time,
accept the licence and complete the component installation it prompts for. Xcode 16 is the starting
point for this project; if the phone's OS is updated, you need an Xcode that supports that OS and
that meets its macOS requirements.

Run this in the terminal; the paths below assume Xcode is installed in the default location:

```sh
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
xcodebuild -version
```

If Homebrew is not installed yet, install it following the instructions on the
[Homebrew website](https://brew.sh/), then install the tools:

```sh
brew install git cmake ninja dfu-util python@3.12 xcodegen
```

### 2.2 Get the complete project

```sh
mkdir -p ~/Projects
cd ~/Projects
git clone --recurse-submodules https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
git submodule status
```

You should see `third_party/lvgl` and the pinned commit `85aa60d18b3d5e5588d7b247abf90198f07c8a63`.
If you forgot `--recurse-submodules`, run `git submodule update --init --recursive` in the
repository root to catch up. Git clone is recommended for beginners; GitHub's Download ZIP does not
include submodule content.

### 2.3 Install ESP-IDF 5.5.5

ESP-IDF is the toolkit for building and flashing ESP32 firmware. Install the version the project
pins:

```sh
mkdir -p ~/esp
cd ~/esp
git clone --recursive --branch v5.5.5 https://github.com/espressif/esp-idf.git esp-idf-v5.5.5
cd esp-idf-v5.5.5
./install.sh esp32s3
. ./export.sh
idf.py --version
python -m esptool version
```

`idf.py --version` should report v5.5.5. The installation downloads the compiler and the Python
dependencies, so it needs a network connection. The IDF environment this project has checked uses
esptool 4.12.0; the read and write commands in this document use the 4.x underscore form. If the
output is 5.x, use the hyphenated form from the tool's help, for example `flash-id`, `read-flash`,
`write-flash`. Do not upgrade dependencies at will inside the IDF environment. For installation
problems, follow Espressif's
[official v5.5.5 installation instructions](https://docs.espressif.com/projects/esp-idf/en/v5.5.5/esp32s3/get-started/linux-macos-setup.html).

In every new terminal you have to activate the environment and return to the project before
building or flashing:

```sh
. ~/esp/esp-idf-v5.5.5/export.sh
cd ~/Projects/moto-gps-waveshare
```

## 3. Build, back up and flash the firmware

### 3.1 Build MOTO GPS

In a terminal with IDF activated, in the repository root, run:

```sh
idf.py -C platforms/esp32 set-target esp32s3
idf.py -C platforms/esp32 build
```

The first build downloads dependencies such as the Waveshare BSP. The build only counts as
successful when `Project build complete` appears. The application, bootloader, partition table and
flashing parameters are in `platforms/esp32/build/`. What is produced here is MOTO GPS; the factory
test firmware downloadable from Waveshare is for testing / recovering the device, and flashing it
will not give you this project's navigation. `set-target` is for the first configuration; afterwards,
for the same target, you normally just run `build`.

### 3.2 Confirm the USB serial port

Connect the device directly to the Mac with the data cable and run:

```sh
python -m serial.tools.list_ports
```

Compare the list before and after plugging in and look for a newly added `/dev/cu.usbmodem...`. If no
serial port appears, try another cable or USB port first. Change the example below to your own
complete serial path, set only in the current terminal:

```sh
MOTO_PORT=/dev/cu.usbmodemYOUR_DEVICE
```

If the connection tool keeps waiting for sync, hold BOOT and power the device back on to enter
download mode as Waveshare's instructions describe, then release BOOT. With a battery fitted,
unplugging USB does not necessarily mean a complete power cycle; power the device back on following
the official power instructions. A black screen may be normal in download mode. The serial port name
can change; list the ports again and update `MOTO_PORT`. See
[Waveshare's flashing instructions](https://docs.waveshare.net/ESP32-S3-Touch-AMOLED-1.75C/Firmware-Flashing/).

### 3.3 Read the capacity and security state, and back up

The following commands first read the device information:

```sh
python -m esptool --chip esp32s3 --port "$MOTO_PORT" flash_id
python -m esptool --chip esp32s3 --port "$MOTO_PORT" get_security_info
```

Check that the chip is an ESP32-S3, that Flash is 32 MB, and that Flash Encryption and Secure Boot
are not enabled. If the model / capacity does not match or the security state is unclear, resolve the
difference first and do not continue with the overwrite.

Back up the whole Flash of the current device and save it to a separate directory on your own
computer:

```sh
MOTO_BACKUP_DIR=$(mktemp -d "$HOME/moto-gps-backup.XXXXXX")
python -m esptool --chip esp32s3 --port "$MOTO_PORT" read_flash 0 0x2000000 "$MOTO_BACKUP_DIR/waveshare-original.bin"
wc -c "$MOTO_BACKUP_DIR/waveshare-original.bin"
shasum -a 256 "$MOTO_BACKUP_DIR/waveshare-original.bin"
```

The file length should be **33,554,432 bytes**. Write the backup path, the SHA-256 and the device
model down in your own notes, and keep a second copy of the backup. It is deliberately placed outside
the repository here; the backup may contain device pairing or credential information, so do not
upload it publicly.

### 3.4 Write MOTO GPS for real

Once you have confirmed the backup succeeded and you accept replacing the factory software:

```sh
idf.py -C platforms/esp32 -p "$MOTO_PORT" flash monitor
```

Wait for the write and the verification to finish. Exit the serial log with `Ctrl+]`. If the device is
still stuck in download mode, release BOOT and restart the device. After boot you should see the MOTO
GPS logo on a black background, and then the screen waiting for the phone to connect.

This guide lets IDF use this build's images, partition table and offsets automatically, with no need
to fill in addresses by hand. Do not treat a standalone application `.bin` as a merged firmware and
write it to `0x0`, and there is no need for a whole-chip erase first. If flashing fails, close other
programs occupying the serial port first, then retry at a lower speed:

```sh
idf.py -C platforms/esp32 -p "$MOTO_PORT" -b 115200 flash
```

## 4. Install the App on the iPhone

### 4.1 Why not "search for it in the App Store"

What is public today is the iOS source code. The installation method available to you is to compile
and sign it onto your own phone with Xcode. The files on GitHub, a simulator `.app` or an unsigned
IPA are not a general-purpose installer. If a TestFlight release happens later, the repository home
page should provide a clear invitation entry point; until then, follow this section.

Sign in with your own Apple account in Xcode's Settings → Accounts. An account without a paid
developer membership is shown as Personal Team. Apple currently specifies a 7-day validity for the
provisioning profile used to install it, and you have to rebuild and reinstall when it expires; free
accounts also have quota and capability limits. See
[Apple's explanation of personal developer accounts](https://developer.apple.com/help/account/basics/about-your-developer-account).
A membership account also needs its own signing configuration; it does not automatically get the
author's certificate.

### 4.2 Change the project configuration

Open `platforms/ios/project.yml` in the repository, find the three `PRODUCT_BUNDLE_IDENTIFIER` entries
and change them to your own unique values, for example:

| Target | Example Bundle Identifier (replace yourname with your own identifier) |
| --- | --- |
| MotoGPS | `com.yourname.motogps` |
| MotoGPSUITests | `com.yourname.motogps.uitests` |
| MotoGPSUnitTests | `com.yourname.motogps.unittests` |

If you already have a gateway, change `MOTOGPSGatewayBaseURL` to your own HTTPS address, keeping the
trailing `/`. If you are only doing the desktop demo you can keep
`https://example.invalid/moto-gps/api/` for the moment, but it cannot search or plan real routes;
after the demo's online request fails it uses the built-in OSM data. Section 6 must be completed
before live navigation.

`DEVELOPMENT_TEAM` can be left empty for now; choose your own team in Xcode after generating the
project. Do not change only `App/Info.plist`: the next XcodeGen run overwrites it from `project.yml`.

### 4.3 Generate the project and install

```sh
cd ~/Projects/moto-gps-waveshare
(cd platforms/ios && xcodegen generate)
open platforms/ios/MotoGPS.xcodeproj
```

Then do the following in Xcode / on the iPhone, in order:

1. Connect the iPhone with the data cable, unlock the phone and tap "Trust This Computer".
2. In Xcode select the project on the left, then the `MotoGPS` target → Signing & Capabilities.
3. Tick Automatically manage signing and choose your own account / Personal Team for Team. Wait for
   the signing errors to disappear.
4. Choose the `MotoGPS` scheme at the top, and select your own iPhone as the run device; you must not
   choose a simulator or a build-only device option.
5. Following Xcode's prompt, turn on "Settings → Privacy & Security → Developer Mode" on the iPhone
   and restart to confirm. If that entry point is missing, let Xcode finish pairing the device and
   try Run first. See
   [Apple's developer mode instructions](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device).
6. Click ▶ Run at the top of Xcode, or press `⌘R`. Wait for the compile, signing and installation;
   the MOTO GPS App should appear on the phone.
7. If the system says the developer is not trusted, follow the system's guidance to
   "Settings → General → VPN & Device Management", confirm your own developer identity, then open
   the App.

On first launch, allow Bluetooth, location and precise location. First complete the
"While Using the App" location authorisation, then upgrade it to "Always" as the App / system
prompts, for background navigation testing. The Apple Music permission can be granted as needed;
declining it should still let you use the navigation features. The permission entry points change
with the iOS version, and you can also search for MOTO GPS in the system settings.

Success criteria: the App opens on a physical iPhone and shows the destination search and the device
connection state. A successful simulator install only proves that part of the build process works; it
cannot verify the round display's BLE.

## 5. First pairing and the desktop demo

1. Power on the round display and bring the phone and the round display close together. For the first
   joint test, power on only one MOTO GPS device, to avoid connecting to another one.
2. Turn on the phone's Bluetooth and open the MOTO GPS App. The App automatically scans for round
   displays advertising the name `MOTO GPS`.
3. When the iOS Bluetooth pairing prompt appears, tap "Pair" and wait until both the App's connection
   state and the round display reach connected / waiting for destination. The custom BLE connection is
   initiated by the App, so there is no need to search for and connect the device as a headset in the
   system Bluetooth list first.
4. Tap "Demo navigation" in the App. The round display should show the moving route, the turn icons
   and the distance, and the phone keeps indicating that it is a demo.
5. Swipe left and right on the round display and check the speedometer and heading pages; the music
   page is available according to the phone's capabilities.
6. Tap "End navigation" in the App and confirm that the demo has exited.

The current source no longer binds a long press on the round display to the demo toggle. Start from
the App's "Demo navigation" instead, so that you do not follow older records and long-press with no
response. A successful demo shows that the display, the touch and part of the BLE data link work; it
does not mean that real routes, the screen lock and network switching have been accepted.

## 6. Configure live navigation

Real search and routing need your own service; the complete steps are in the
[route gateway setup guide](GATEWAY_SETUP.en.md). The flow is: apply for an AMap **Web Service** key
→ start the repository's `backend` → serve it to the iPhone over an HTTPS domain.

Once configured, open your own `https://nav.example.com/moto-gps/api/healthz` in iPhone Safari:

- `provider` should be `amap`;
- `ready_for_live_navigation` should be `true`;
- then verify a real place search as the gateway guide describes; the health check itself does not
  verify the key's permissions and quota.

Change `MOTOGPSGatewayBaseURL` in `project.yml` to the address above with `healthz` removed, run
XcodeGen again, then Run onto the iPhone. The AMap key goes only into the backend environment
variables; it must not be put into the App. `127.0.0.1` on the phone means the phone itself, not the
Mac; do not put the computer's local listening address straight into the App.

Afterwards, get a position fix in an open, stationary place, search for a nearby place, tap
"Set as destination", look at "Route overview", select a route, then tap "Start navigation". Check the
destination selected on the phone against the round display's state; when you finish, tap
"End navigation" in the App. This currently uses AMap's ordinary driving route, and must not be
treated as motorcycle-specific navigation that automatically avoids motorcycle-prohibited sections.

## 7. Updating, reinstalling and restoring the factory software

Before upgrading the firmware, save your own changes and check `git status`. After updating the
source, run `git submodule update --init --recursive`, then `build` and `flash`; there is no need to
`set-target` every time. The App is updated from the same source, keeping your own Bundle ID, Team and
gateway settings: regenerate the project and Run. An expired free signing profile is handled with the
same installation steps; you usually do not need to delete the App first.

If you need to restore, use the complete factory backup of the **same device** saved in section 3. On
a device whose capacity and security state you have already checked, enter download mode again and
change `MOTO_BACKUP_FILE` to the real backup file path:

```sh
MOTO_BACKUP_FILE=/absolute/path/to/waveshare-original.bin
wc -c "$MOTO_BACKUP_FILE"
shasum -a 256 "$MOTO_BACKUP_FILE"
# Check the length and the SHA-256 saved earlier before writing; this overwrites the current MOTO GPS and the device state.
python -m esptool --chip esp32s3 --port "$MOTO_PORT" write_flash 0 "$MOTO_BACKUP_FILE"
```

Without a backup of your own, get the official test firmware for the exact model and the matching
addresses from [Waveshare's official flashing page](https://docs.waveshare.net/ESP32-S3-Touch-AMOLED-1.75C/Firmware-Flashing/).
The test firmware does not necessarily restore all the personalised configuration from the factory.

## 8. Frequently asked questions

| Symptom | What to check first |
| --- | --- |
| `idf.py: command not found` | Run IDF's `export.sh` in the current terminal; do not only activate it in another window |
| LVGL not found / an empty submodule | Initialise the submodules in the repository root and check the pinned commit |
| The build fails to download dependencies | Network connectivity to GitHub / Espressif / the component repository; keep the failure log and retry after the network recovers |
| The capacity shows 16 MB or another value | It does not match this project's 32 MB configuration; stop applying the flashing steps and contact the seller to confirm the model / revision |
| No serial port appears, or it stays at Connecting | The cable, the port being occupied, BOOT download mode; list the serial ports again |
| Flashing succeeds but the screen stays black | Whether it is still in download mode, whether it is exactly the 1.75C board; look at the serial output for initialisation errors |
| `Signing requires a development team` | The MotoGPS target's Team, automatic signing and the Apple account sign-in |
| The Bundle ID is unavailable | Use your own unique identifier and check the three targets; regenerate after changing the YAML |
| The iPhone is not among the run devices | Unlock the phone and trust the computer, and check whether Xcode supports the current iOS |
| The App will not open after a few days | The personal signing profile may have expired; connect Xcode and Run again with the original configuration |
| The round display waits for the phone / WAITING FOR PHONE | The App in the foreground, the Bluetooth permission, whether another device is nearby; tap "Retry" on the connection and see the known issues |
| Search fails or there is no route | Whether the example domain has been replaced, the gateway's public reachability, the real key's permissions / quota; do not judge that things are fine from healthz alone |
| Only the white line, with no grey roads or buildings | Equivalent offline base-map coverage is not provided outside Jinan; this and route planning are different capabilities |
| No music page, or the buttons do nothing | Play a playable song in the system "Music" app first, grant the media permission, then check the connection |

After you get it working for the first time, do a small-scale verification of the screen lock, a
device restart and a network switch first. Background reconnection and some route issues still exist;
the reproduction records are in [Known issues](KNOWN_ISSUES.en.md). When asking for help, include the
model, the software version, the failing steps and a redacted error log; do not attach keys, a
whole-chip backup or personal location traces.

Next: [features and user manual](USER_MANUAL.en.md) · [route gateway configuration](GATEWAY_SETUP.en.md) · [back to the repository home page](../README.en.md)
