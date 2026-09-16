> **Language:** English · [中文](README_嘉立创仅询价.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# JLCPCB quote-only instructions

Uploaded file: `MOTO_GPS_RevA0_R4_仅询价_DO_NOT_ORDER_Gerber.zip`

This ZIP is only for the JLCPCB PCB page to recognise the board outline and layer count and to see the
bare-board price. It comes from the same set of review Gerber/drill files as Rev A0 R4, but the
physical, power, RF, footprint and procurement releases are not yet complete, so **do not submit an
order, do not pay, and do not move it into PCBA production**.

After uploading, check:

- Layer count: 4 layers;
- Outline: a round irregular board of about 52 × 52 mm, with crescent-shaped reliefs for the screw posts in four places;
- Quotation choice for board thickness: 1.0 mm;
- Quotation choice for surface finish: ENIG;
- You can first select a quantity of 5 to see the price.

If the preview identifies it as 2 layers, a rectangular board, or a size clearly not around 52 × 52 mm,
stop immediately and do not continue to quote or order.

Seeing the bare PCB price does not need the BOM/CPL. If you only want to estimate the placement cost,
you can separately look at `documents/engineering-bom-review.csv` and
`documents/engineering-cpl-review.csv` in the R4 review package, but there are still 28
physical/verification/procurement gates in them, so the current result can only be treated as an
estimate and not as a production-ready bill of materials.

