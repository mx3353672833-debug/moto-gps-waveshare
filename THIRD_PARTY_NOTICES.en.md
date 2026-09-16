> **Language:** English · [中文](THIRD_PARTY_NOTICES.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Third-party notices / third-party materials

The PolyForm Noncommercial licence at the root covers only the original parts of this project. The material listed below keeps its original licence,
and this must not be interpreted as adding a noncommercial restriction to that material. Using it still requires following the original author's copyright and attribution requirements.

| Material | Location / source | Licence |
| --- | --- | --- |
| LVGL | `third_party/lvgl` submodule, commit `85aa60d18b3d5e5588d7b247abf90198f07c8a63` | MIT; see the submodule's `LICENCE.txt` and `LICENSES/LVGL-MIT.txt` |
| Waveshare BSP / CO5300 initialisation | Component Registry `waveshare/esp32_s3_touch_amoled_1_75c` 3.0.0; board-level derived file `platforms/esp32/main/board_port_waveshare_1_75c.cpp` | Apache-2.0; see `LICENSES/Waveshare-Apache-2.0.txt`; that derived file keeps Apache-2.0 |
| Source Han Sans SC font subset | `shared/nav_ui/assets/moto_font_nav_16.c`, generated from the fonts in the LVGL tools directory | SIL OFL 1.1; see `LICENSES/SourceHanSansSC-OFL.txt` |
| Montserrat built-in font | LVGL's built-in font resources | SIL OFL 1.1; see `LICENSES/Montserrat-OFL.txt` |
| OpenStreetMap data / derived database | `shared/offline_map/jinan-v1.sqlite` and its SQL/manifest; the route/road/building geometry in `shared/demo_fixture`; the map data in the generated C++ / Swift constants | ODbL 1.0, © OpenStreetMap contributors |

OSM attribution and licence: <https://www.openstreetmap.org/copyright>.
ODbL text: <https://opendatacommons.org/licenses/odbl/1-0/>.
The complete derived Jinan database is provided with this repository; for the generation process see `shared/offline_map/README.md`.
The licence of all map data layers stays ODbL, while the original logic of the generator code uses this project's licence.
When displaying the map publicly or redistributing it, keep the attribution and links in `shared/offline_map/ATTRIBUTION.txt`.

ESP-IDF, NimBLE and the components downloaded by the Component Manager each follow the LICENSE/NOTICE in their distribution package;
they are not code created by this project, and they do not change to PolyForm along with this repository. Before redistributing a built binary you should collect the licences of the SDKs/components, fonts and linked libraries actually
used, not just copy the root LICENSE.md.
The ESP-IDF dependency versions are recorded in `platforms/esp32/dependencies.lock`.

The Web build uses Emscripten / SDL, and the associated toolchain and runtime also keep their original licences.
The `lv_font_conv` needed to regenerate the fonts is licensed by its publisher; this repository does not relicense that tool.

AMap, Apple Music and Apple MapKit are external services/SDKs and are not covered by the project's licence grant.
Use your own lawful account, key and quota, and check the service agreements and the authorisation requirements for hardware display scenarios.
This repository does not include AMap API raw response caches, map tiles, Apple Music files or copies of proprietary SDKs.
`backend/fixtures` are automated test samples, not service data that can be used for navigation.

MOTO GPS is an independently developed project and does not represent an official product, certification or endorsement by Waveshare, Garmin, Apple or AMap.

## Hardware and technical proposal reference material

`hardware/rev_a/references/`, the `references/` of the manufacturing candidate packages, the figures in the verification reports and
`docs/technical-proposal/assets/` contain supplier datasheets, public schematics, component/footprint material and scene references.
The material of Waveshare, Quectel, Huaxia, connector and other component manufacturers, as well as reference figures carrying the original publisher's mark,
keeps its copyright and conditions of use with each original publisher. The repository provides this material to help understand the design basis; the root licence does not relicense this material.
Third-party symbols, footprints and their derivatives keep their original notices; the remaining original project circuits, mechanical scripts and design documents follow the project licence.
