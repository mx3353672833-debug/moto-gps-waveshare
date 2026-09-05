# MOTO GPS 资料总目录

本仓库以 Waveshare 1.75C + iPhone 为当前软件样机，同时保存自研主板和外壳的历史设计。
阅读时先确定版本：旧技术方案中的 LC76G 独立定位、手机热点与自研电源，属于自研硬件路线。
当前微雪样机由 iPhone 定位，通过 BLE 驱动圆屏。

## 产品与技术方案

- [产品说明与技术参数对照](PRODUCT_SPECIFICATIONS.md)：按版本整理器件、屏幕、结构、功能及未验证项。
- [技术方案下载](technical-proposal/README.md)：两版 PDF、Word、Markdown 和配图。
- [当前样机基线](WAVESHARE_IOS_PROTOTYPE.md)：iPhone 与微雪板的职责和验收顺序。
- [自研硬件执行方案](CUSTOM_HARDWARE_PLAN.md)：电源、接口、采购与样机计划的历史和后续范围。

## 自研主板

- [硬件总览](../hardware/README.md)：版本关系和文件用途。
- [H0175 EVT A1 状态单](../hardware/rev_a/H0175_EVT_A1_STATUS.md)：屏幕、器件修订与已有核验记录。
- [A1 审阅包](../hardware/manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/)：`source/` 是可编辑 KiCad 工程，`documents/` 是原理图、装配 PDF、BOM 和 CPL，`review_gerbers_DO_NOT_ORDER/` 是 Gerber 和钻孔，`reports/` 与 `references/` 保存核验依据。
- [R4 审阅包](../hardware/manufacturing/engineering-candidates/rev-a0-20260903-r4/)：A1 的前一版完整路由基线。
- [历史候选包目录](../hardware/manufacturing/engineering-candidates/)：同时保留早期 Rev A0 与 R2，按日期和版本分别存放。
- [器件封装审计](../hardware/rev_a/COMPONENT_PACKAGE_AUDIT.md)、[电气架构](../hardware/rev_a/ARCHITECTURE_FREEZE.md)。
- [询价资料](../hardware/manufacturing/quote-only/)：带 `DO_NOT_ORDER` 标识的历史 Gerber ZIP 与使用说明。

打开 KiCad 工程时保留整个 `source/` 目录，符号表、封装表及 `MOTO_GPS.pretty/` 都是工程依赖。
推荐从完整 A1/R4 候选包开始；`hardware/pcb/` 是早期板框和接口研究，不能作为最新板图。

## 自研外壳

- [机械设计说明](../hardware/mechanical/README.md)。
- [V3 设计决策](../hardware/mechanical/V3_DESIGN.md)与[机械规格](../hardware/mechanical/V3_MECHANICAL_SPEC.md)。
- [V3 STEP / STL / 装配模型](../hardware/mechanical/generated/v3/)：前框、后壳、可替换佳明卡榫和紧固件包络。
- [二维总装图](../hardware/mechanical/drawings/)：PDF、SVG 与 PNG。
- [外观渲染](../hardware/mechanical/renders/)及[参数化生成器](../hardware/mechanical/rev_v3_case.py)。

STEP 适合 CAD 编辑和装配检查，STL 用于原型打印，二维图用于尺寸评审。
效果图只表达外观；首图银色产品效果和归档 V3 四螺钉方案应分别理解，不能从效果图量尺寸。

## 软件与验证

- [iOS App](../platforms/ios/README.md)、[ESP32 固件](../platforms/esp32/README.md)、[路线网关](../backend/README.md)。
- [公开版本架构](ARCHITECTURE.md)、[测试说明](TESTING.md)、[已知问题](KNOWN_ISSUES.md)。
- [硬件导出与发布检查](../scripts/hardware/README.md)。
- [硬件验证记录](../hardware/rev_a/validation/)和各候选包的 `SHA256SUMS`。

## 归档范围与复现

此次公开保存硬件源文件、定版审阅包、模型、图纸、说明、第三方参考材料和生成脚本。
不提交临时自动布线试验、编译缓存、散落的编辑器状态、系统隐藏文件和设备备份。
冻结候选包中已列入 SHA-256 清单的 KiCad `.kicad_prl` 文件原样保留，以便校验归档完整性。
`hardware/rev_a/board/build/` 中仅保留 `release_check_r4/` 与 `h0175_evt_a1/`
两个冻结工程，供旧生成脚本和状态单继续定位原始输入。

历史交付说明中的 `00_先看这里`、`02_机械设计` 等路径属于当时线下交付包；
GitHub 中对应入口按本页定位。归档 PDF/Word 保留当时内容，当前软件状态以顶层 README 为准。

原创设计沿用根目录许可证与 Maler X 署名；第三方数据手册、参考图、库和地图数据保留各自权利，见
[第三方声明](../THIRD_PARTY_NOTICES.md)。
