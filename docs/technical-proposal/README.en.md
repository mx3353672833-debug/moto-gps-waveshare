> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Product technical proposals: reading and download

This directory keeps two complete versions of the proposal, each provided as Markdown, PDF and editable Word.
The content covers the product positioning, navigation architecture, software/hardware division of labour, main-board components, screen interface, structural design, Garmin mount,
parameter tables, phase plan and verification records.

| Version | Read online | PDF | Word |
| --- | --- | --- | --- |
| H0175 EVT A1 | [Markdown](MOTO_GPS_Portable_Navigation_Terminal_Technical_Proposal_H0175_EVT_A1.en.md) | [PDF (Chinese)](MOTO_GPS_摩托车便携导航终端技术方案_H0175_EVT_A1.pdf) | [DOCX (Chinese)](MOTO_GPS_摩托车便携导航终端技术方案_H0175_EVT_A1.docx) |
| Rev A0 | [Markdown](MOTO_GPS_Portable_Navigation_Terminal_Technical_Proposal_RevA0.en.md) | [PDF (Chinese)](MOTO_GPS_摩托车便携导航终端技术方案_RevA0.pdf) | [DOCX (Chinese)](MOTO_GPS_摩托车便携导航终端技术方案_RevA0.docx) |

It is recommended to read A1 first; Rev A0 is kept for version comparison. The [staged delivery notes](DELIVERY_NOTES_RevA0.en.md) keep RevA0 in the file name,
while the body already records the A1 delivery. `assets/` keeps the original figures, and `build_moto_gps_proposal.py` keeps the document generation code.

Both versions are historical in-house hardware proposals, in which the descriptions of standalone GNSS, the phone hotspot and in-house power do not represent the current
Waveshare + iPhone BLE implementation. For the current version comparison see [product technical parameters](../PRODUCT_SPECIFICATIONS.en.md),
and for the complete file entry points see the [documentation index](../PROJECT_DOCUMENTATION.en.md). The numbered directories of the historical delivery package
were not copied into the repository as they were; their circuits, enclosure and drawings have been collected under `hardware/` classified by purpose.

The PDF/Word keeps the archived content; to download it, click Download raw file on the GitHub file page.
