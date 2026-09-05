# MOTO GPS 自研电路板与外壳

这里保存已完成的主板设计、制造审阅文件、外壳模型和核验记录。当前软件样机使用微雪成品板；
自研主板与外壳处于归档/暂停状态。完整资料公开供阅读、研究和工程评审。

## 从哪里开始

| 目标 | 推荐文件 | 说明 |
| --- | --- | --- |
| 查看产品参数 | [参数对照](../docs/PRODUCT_SPECIFICATIONS.md) | 微雪样机与自研 A1 分开列出 |
| 打开最新自研 PCB | [A1 KiCad 工程](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/source/moto-gps-rev-a1.kicad_pro) | 下载整个 source 目录，保留符号与封装库 |
| 阅读 A1 电气状态 | [A1 状态单](rev_a/H0175_EVT_A1_STATUS.md) | 器件、接口、ERC/DRC 与待验证项 |
| 查看 BOM、CPL 和装配图 | [A1 documents](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/documents/) | 原理图 PDF、装配 PDF、器件清单与坐标 |
| 查看 Gerber / 钻孔 | [A1 审阅制造文件](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/review_gerbers_DO_NOT_ORDER/) | 保留工程审阅标记，尚未生产放行 |
| 对照前一版 PCB | [Rev A0 R4](manufacturing/engineering-candidates/rev-a0-20260903-r4/) | A1 的冻结路由基线 |
| 编辑/打印外壳原型 | [V3 STEP 与 STL](mechanical/generated/v3/) | 含前框、后壳、佳明卡口与装配模型 |
| 核对机械尺寸 | [V3 机械规格](mechanical/V3_MECHANICAL_SPEC.md)、[二维图](mechanical/drawings/) | 主体 Ø61 × 16 mm，含卡口约 19 mm，均为设计值 |
| 阅读完整方案 | [技术方案 PDF / Word](../docs/technical-proposal/README.md) | A1 与 A0 两版完整资料 |

A1 与 R4 的完整候选包都包含 `SHA256SUMS`，可在各候选包目录运行
`shasum -a 256 -c SHA256SUMS` 校验。封装、线路和规则检查记录随包提供。
优先使用这些完整候选包；下面保留的 `pcb/` 目录属于更早期的板框研究。

## 早期目录与设计记录

本目录承载 MOTO GPS 自研一体主板、制造文件和外壳 CAD。设计基线见 [自研一体硬件与外壳执行方案](../docs/CUSTOM_HARDWARE_PLAN.md)。

计划结构：

```text
hardware/
  pcb/
    moto-gps-rev-a.kicad_pro
    moto-gps-rev-a.kicad_sch
    moto-gps-rev-a.kicad_pcb
  libraries/       经复核的自建符号、封装与 3D 模型
  mechanical/      参数化外壳、STEP、STL 与 2D 加工图
  manufacturing/   Gerber、钻孔、BOM、CPL 和装配说明
  validation/      上电、RF、GNSS、罗盘、功耗和环境测试记录
```

在屏幕 FPC、电池、GNSS 天线和车把卡口四项冻结前，`manufacturing/` 中的任何文件都只能标为草案，不能直接批量下单。

CAD 工具链为 KiCad 10.0.6。`pcb/` 下的早期文件是 52 mm 圆板机械包络和空白原理图骨架。
后续 `rev_a/` 与制造候选包已经包含真实布线和完整电气设计；两类文件的零违规报告含义不同，
查看时请同时核对版本和工程内容。

已冻结/待冻结的关键接口：

- [屏幕与触摸 FPC 接口](pcb/DISPLAY_INTERFACE.md)：电气映射已整理，连接器接触面、Pin 1 与 FPC 厚度仍须用原装屏实物确认。
- [LC76G 与 GNSS RF 接口](pcb/GNSS_INTERFACE.md)：裸模组、低噪声供电、UART/PPS、U.FL 有源天线基线和塑料 RF 窗约束。
- [电源架构冻结条件](pcb/POWER_DECISION.md)：主方案已改为 BQ25628E + TPS63070 + TUSB320LAI，完整主板前须先通过独立电源测试券。
- [Rev A GPIO 预算](pcb/REV_A_PIN_BUDGET.md)。
- [Rev A0 参数化外壳](mechanical/README.md)：铝前框、塑料后壳、独立 RF 舱和可替换车把卡口概念件。
