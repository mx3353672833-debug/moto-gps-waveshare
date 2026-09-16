> **Language:** English · [中文](PROJECT_DOCUMENTATION.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS documentation index

When building the Waveshare edition for the first time, read these in order:

1. [DIY build guide](WAVESHARE_DIY_GUIDE.en.md): which device to buy, flashing the firmware, installing the iPhone app and the first pairing.
2. [Features and user manual](USER_MANUAL.en.md): search, candidate routes, the four pages, music, connection and everyday operation.
3. [Route gateway configuration](GATEWAY_SETUP.en.md): the key, service, HTTPS and verification steps needed for real search and navigation.

This repository takes Waveshare 1.75C + iPhone as the current software prototype, and at the same time keeps the historical design of the in-house main board and enclosure.
Establish the version first when reading: the LC76G standalone positioning, phone hotspot and in-house power supply in the old technical proposals belong to the in-house hardware route.
The current Waveshare prototype is positioned by the iPhone and drives the round display over BLE.

## Product and technical proposals

- [Product description and technical parameters comparison](PRODUCT_SPECIFICATIONS.en.md): components, screen, structure, features and unverified items organised by version.
- [Technical proposal download](technical-proposal/README.en.md): two versions of PDF, Word, Markdown and figures.
- [Current prototype baseline](WAVESHARE_IOS_PROTOTYPE.en.md): the responsibilities of the iPhone and the Waveshare board, and the acceptance order.
- [In-house hardware execution plan](CUSTOM_HARDWARE_PLAN.en.md): the history and the later scope of the power, interface, purchasing and prototype plans.

## In-house main board

- [Hardware overview](../hardware/README.en.md): the version relationships and what the files are for.
- [H0175 EVT A1 status sheet](../hardware/rev_a/H0175_EVT_A1_STATUS.en.md): the screen, component revisions and existing verification records.
- [A1 review package](../hardware/manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/): `source/` is the editable KiCad project, `documents/` holds the schematic, assembly PDF, BOM and CPL, `review_gerbers_DO_NOT_ORDER/` holds the Gerber and drill files, and `reports/` and `references/` keep the verification evidence.
- [R4 review package](../hardware/manufacturing/engineering-candidates/rev-a0-20260903-r4/): the previous complete routing baseline before A1.
- [Historical candidate package index](../hardware/manufacturing/engineering-candidates/): keeps the earlier Rev A0 and R2 as well, stored separately by date and version.
- [Component footprint audit](../hardware/rev_a/COMPONENT_PACKAGE_AUDIT.en.md), [electrical architecture](../hardware/rev_a/ARCHITECTURE_FREEZE.md).
- [Quote-only material](../hardware/manufacturing/quote-only/): historical Gerber ZIPs carrying the `DO_NOT_ORDER` marking, with instructions for use.

When opening the KiCad project, keep the whole `source/` directory; the symbol table, the footprint table and `MOTO_GPS.pretty/` are all project dependencies.
It is recommended to start from the complete A1/R4 candidate package; `hardware/pcb/` is early board-outline and interface research and must not be used as the latest board drawing.

## In-house enclosure

- [Mechanical design notes](../hardware/mechanical/README.md).
- [V3 design decisions](../hardware/mechanical/V3_DESIGN.md) and [mechanical specification](../hardware/mechanical/V3_MECHANICAL_SPEC.en.md).
- [V3 STEP / STL / assembly model](../hardware/mechanical/generated/v3/): front bezel, rear shell, replaceable Garmin tabs and the fastener envelope.
- [2D assembly drawings](../hardware/mechanical/drawings/): PDF, SVG and PNG.
- [Appearance renders](../hardware/mechanical/renders/) and the [parametric generator](../hardware/mechanical/rev_v3_case.py).

STEP suits CAD editing and assembly checking, STL is for prototype printing and the 2D drawings are for dimensional review.
Renders express appearance only; the silver product render in the first image and the archived V3 four-screw scheme must be understood separately, and dimensions must not be measured from a render.

## Software and verification

- [Build an Android version with AI](ANDROID_AI_GUIDE.en.md): a copyable prompt, code reuse, hardware verification and GitHub release steps; there is no ready-to-install Android app yet.
- [iOS app](../platforms/ios/README.en.md), [ESP32 firmware](../platforms/esp32/README.en.md), [route gateway](../backend/README.en.md).
- [Public-edition architecture](ARCHITECTURE.en.md), [test notes](TESTING.en.md), [known issues](KNOWN_ISSUES.en.md).
- [Hardware export and release checks](../scripts/hardware/README.md).
- [Hardware verification records](../hardware/rev_a/validation/) and the `SHA256SUMS` of each candidate package.

## Archive scope and reproduction

This publication keeps the hardware source files, the frozen review packages, models, drawings, notes, third-party reference material and generation scripts.
Temporary autorouting experiments, build caches, scattered editor state, system hidden files and device backups are not committed.
The KiCad `.kicad_prl` files already listed in the SHA-256 manifest of a frozen candidate package are kept as they are, so that the integrity of the archive can be checked.
Under `hardware/rev_a/board/build/` only the two frozen projects `release_check_r4/` and `h0175_evt_a1/` are kept,
so that the old generation scripts and the status sheet can continue to locate the original inputs.

Paths such as `00_先看这里` and `02_机械设计` in the historical delivery notes belong to the offline delivery package of that time;
the corresponding entry points in GitHub are located from this page. The archived PDF/Word keeps its content from that time; for the current software state the top-level README is authoritative.

Original designs follow the root licence and the Maler X attribution; third-party datasheets, reference figures, libraries and map data keep their own rights, see
[third-party notices](../THIRD_PARTY_NOTICES.en.md).
