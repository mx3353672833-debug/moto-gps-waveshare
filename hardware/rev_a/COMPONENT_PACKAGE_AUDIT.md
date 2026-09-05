# Rev A 元件、封装与贴装审计

状态：**工程审计稿，不是投板放行单**  
审计基准：2026-09-03  
当前机械前提：完整 `Ø52 × 1.0 mm` 四层主板，取消中央穿板电池窗口；电池叠放在 PCB 后方。按当前 `27 × 32 × 7 mm` 电池与 `3.2 mm` 高 WROOM-1U 的跨接关系，后腔深度暂按约 `14–15 mm` 预算，最终数值必须以电池保护板、导线、绝缘托盘、泡棉预压和鼓胀余量的实测堆叠冻结，不能承诺只增厚 1–2 mm。

本文只冻结器件身份、封装依据和布局边界。LCSC/JLCPCB 编号用于采购检索，不代表审计日一定有库存、一定支持经济贴装或属于基础库；下单前仍须在 JLCPCB PCBA 页面用**完整 MPN + 封装 + 厂牌**三项复核。

## 结论先行

1. 当前完整圆板方案的首轮 EVT 主控推荐并冻结为 **`ESP32-S3-WROOM-1U-N16R8`**。它有官方推荐 land pattern、成熟模组贴装形式，并集成晶振、Flash、PSRAM 和 RF 匹配；相较 PICO，首轮样机的封装、射频和返修风险明显更低。
2. `18.0 × 19.2 mm` 的 WROOM-1U 已在 R4 完整 Ø52 mm 圆板上完成真实摆件和全连接布线；KiCad courtyard/DRC 已闭合。显示 FPC、USB-C、电池叠板、同轴线弯曲和天线净空仍必须用实物与整机 3D 闭环，不能只凭 PCB 视图放行。
3. 如果以后恢复约 `27 × 32 mm` 的中央穿板电池窗口，WROOM-1U 无法落在剩余环形板带上；该条件下必须改为 **`ESP32-S3-PICO-1-N8R8`** 或改变外壳/电池结构。PICO 不是当前 Rev A 主选。
4. `BQ25628E`、`TPS63070`、`TUSB320LAI` 均为异形 HR/QFN 类小封装，必须使用 TI 官方 package drawing 生成并审核项目内 footprint，不能拿“同尺寸 QFN”替代。
5. 两项最直接的机械投板阻断是：**原屏 31-pin FPC 的接触面/厚度/插入方向未由实物量测闭环**，以及 **USB-C 母座与壳体 Z 高/开口未冻结**。31 脚电气顺序已经按微雪 2026-01 官方原理图逐脚复核。除此之外，电源券、RF/天线、全部自建封装第二人签核和下单日库存/代采也必须关闭；即使 R4 DRC 为零也不得绕过这些门槛。

## 审计状态定义

- **A — 可冻结**：MPN、封装和候选 footprint 均有直接官方依据；完成常规逐脚比对即可入库。
- **B — 需自建/复核**：器件可用，但必须按原厂 drawing 建 footprint，或库存/贴装状态仍需下单前复核。
- **C — 阻断**：实物或机械定义不足，不能据现有信息生成可投板封装。

## 器件/封装总表

| 功能 | 冻结 MPN | 原厂封装与焊盘数 | LCSC/JLCPCB 检索号 | KiCad 处理 | 状态 |
|---|---|---|---|---|---|
| 主控模组 | `ESP32-S3-WROOM-1U-N16R8` | 18.0 × 19.2 × 3.2 mm；40 周边焊盘 + 中央 GND 41，共 41 pin | `C3013946` | 上游候选 `RF_Module:ESP32-S3-WROOM-1U`；复制进项目库并按官方 v1.8 图 11-2 逐项复核 | A |
| 紧凑主控备选 | `ESP32-S3-PICO-1-N8R8` | 7 × 7 mm LGA；56 周边 pad + 中央 GND pad 57，共 57 个 PCB pad | `C7545129`；JLC 另见 `C9900172181` | 上游库未找到可直接冻结的专用 footprint；必须自建 | B |
| 充电/电源路径 | `BQ25628ERYKR` | TI `RYK0018A`，18-pin WQFN-HR，2.5 × 3.0 mm | `C18221178` | 必须按 `RYK0018A` 自建；禁止普通对称 QFN 代换 | B |
| 3.3 V 升降压 | `TPS63070RNMR` | TI `RNM0015A`，15-pin VQFN-HR，2.5 × 3.0 mm | `C109322` | 必须按 `RNM0015A` 自建；禁止普通 QFN 代换 | B |
| Type-C 电流识别 | `TUSB320LAIRWBR` | TI `RWB0012A`，12-pin X2QFN，约 1.6 × 1.6 mm | `C132554` | 按 `RWB0012A` 自建并审核阻焊桥能力 | B |
| 六轴 IMU | `QMI8658A` | 14-pin LGA，2.5 × 3.0 mm，0.5 mm pitch | `C3021082` | 延用已审核的项目派生 14-pin land pattern；作为 EVT 受控直替，仍须按 QMI8658A 原厂 drawing 复核 | B（固件回归） |
| 三轴磁力计 | `IIS2MDCTR` | ST LGA-12L，2.0 × 2.0 × 0.7 mm，12 pad，0.5 mm pitch | `C2655002` | 当前 I²C 接法和项目派生 LGA-12 land pattern兼容；按 IIS2MDC datasheet/TN0018 复核 | B（固件/标定） |
| GNSS | `LC76GABMD` | 10.1 × 9.7 × 2.4 mm；18 LCC + 10 LGA，共 28 pad | `C7437114` 仅为现有候选，当前公开目录未独立闭环 | 必须按 Quectel LC76G Series Hardware Design 图 18 自建 | B |
| GNSS 3.0 V LDO | `TPS7A2030PDBVR` | TI `DBV0005A`，5-pin SOT-23，2.90 × 1.60 mm | `C963429` | 候选 `Package_TO_SOT_SMD:SOT-23-5`；按 DBV pin 1 和焊盘尺寸复核 | A |
| GNSS UART 电平转换 | `TXU0202DCUR` | TI `DCU0008A`，8-pin VSSOP，2.30 × 2.00 mm | `C5186957` | 候选 `Package_SO:VSSOP-8_2.3x2mm_P0.5mm`；按 DCU0008A 审核 | A |
| 轻触开关（SW1–SW4） | `Panasonic EVQP7C01P` | 3.6 × 3.5 mm 本体，1.35 mm 总高；执行部 1.1 ± 0.1 mm | `C388883` | 延用 `Button_Switch_SMD:SW_SPST_EVQP7C`；焊盘兼容不等于按键柱高度已冻结 | B（装壳门槛） |
| USB-C 母座候选 | `XKB U262-161N-4BVC11` | 16 信号触点 + 4 外壳固定焊脚，另有 2 NPTH 定位柱 | `C319148` | 项目 footprint 历史名称仍为 `Connector_USB:USB_C_Receptacle_XKB_U262-16XN-4BVC11`；不得据库名反向改写官方 MPN | C（机械未冻结） |
| 显示 FPC 候选 | `XUNPU FPC-0.3FX-31PWBH10` | 0.3 mm pitch、31 电气触点、下接触、翻盖，另有固定焊脚 | `C5343228` | 必须按原厂/JLC drawing 自建；不可用 Hirose 31P footprint 猜代 | C（实屏未核） |

## 1. 主控选择审计

### 1.1 当前 EVT：ESP32-S3-WROOM-1U-N16R8

推荐理由：

- 官方模组已集成 16 MB Quad SPI Flash、8 MB Octal PSRAM、40 MHz 晶振及 RF 匹配，首板无需解决 0.4 mm pitch LGA 与独立射频匹配的双重风险。
- `-1U` 使用 U.FL / I-PEX MHF I / Amphenol AMC 兼容的一代微型天线座，适合把 2.4 GHz 天线放进壳体塑料 RF 窗，而不是让铝壳屏蔽板载天线。
- 官方 KiCad 库已有专用 footprint 候选，仍需将其复制到项目库，保存审计过的几何版本，不能依赖未来系统库自动变化。
- N16R8 官方额定环境温度为 `-40…65 °C`。若启用 PSRAM ECC，官方说明最高环境温度可提高到 85 °C，但可用 PSRAM 减少 1/16；密封黑色车载壳仍必须做日晒和满载温升试验。

布局/电气强制项：

- 严格使用官方图 11-2 推荐 land pattern；中央 41 脚接连续 GND，附近布地过孔阵列，阻焊/钢网开窗以模组与贴装厂建议为准。
- `3V3` 就近放置储能和高频去耦；供电按 Wi-Fi 瞬态设计，稳压与电源路径不得按平均电流选型。
- `EN` 不得悬空，按 Espressif 外围参考实现 RC 与复位；启动绑带脚需审查外设上电默认电平。
- USB D+/D− 从模组 GPIO19/20 到 USB-C 走短、等长、连续参考面的 90 Ω 差分线，避开电感、SW 铜和板边螺钉。
- 模组本体虽没有 WROOM-1 板载天线的 base-board keepout，但天线座、同轴插拔空间、线缆弯曲半径、天线塑料窗及远离 GNSS 天线的隔离必须在 3D/实物中验证。
- 完整 Ø52 mm 板可容纳该模组，但布局阶段必须提供一张按真实 courtyard 计算的“全部关键器件 + 两根天线 + FPC/USB”密度图，作为机械评审项。

官方依据：[ESP32-S3-WROOM-1/1U Datasheet v1.8](https://www.espressif.com/sites/default/files/documentation/esp32-s3-wroom-1_wroom-1u_datasheet_en.pdf)、[Espressif ESP32-S3 Hardware Design Guidelines](https://docs.espressif.com/projects/esp-hardware-design-guidelines/en/latest/esp32s3/)。

### 1.2 条件备选：ESP32-S3-PICO-1-N8R8

只有在恢复中央穿板电池窗口、WROOM 面积确实无法布通，或产品二版必须进一步缩小时再切换 PICO。

- 官方资料把器件称为 7 × 7 mm LGA；原理图脚号包含 1–56，另有中央 `GND 57`，所以 PCB footprint 必须有 57 个编号 pad，不能把“LGA56”理解为只有 56 个 PCB 焊盘。
- N8R8 为 8 MB Flash + 8 MB Octal PSRAM；GPIO33–37 与 SPICS1 被 OSPI PSRAM占用，切换前必须重新跑引脚预算。
- 项目需按官方封装图自建 57-pad footprint，审核顶视图/底视图、Pin 1、中央 GND、钢网分窗、阻焊开窗与贴装公差，并做 LGA 焊接测试券/X-ray 检查。
- PICO 仍需要 50 Ω 射频走线、匹配/预留网络和外置 2.4 GHz 天线接口；小封装不等于取消射频设计。
- N8R8 同样是 `-40…65 °C`，启用 PSRAM ECC 可将最高环境温度提高到 85 °C，同时损失 1/16 可用 PSRAM。

官方依据：[ESP32-S3-PICO-1 Series Datasheet v1.2](https://documentation.espressif.com/esp32-s3-pico-1_datasheet_en.pdf)。

## 2. 电源链

### 2.1 BQ25628ERYKR

- `RYK0018A` 是 18-pin WQFN-HR 的异形功率封装；多个脚/裸露铜区承担不同的大电流节点，不能创建成带单一中心 EP 的普通 18-pin QFN。
- 以 TI 封装图与 datasheet layout example 为唯一几何/拓扑依据，自建焊盘、阻焊和分块钢网；提交贴装前让 JLC 工程确认最小阻焊桥和 paste aperture。
- 输入电容、SYS/BAT 电容、REGN 电容、BTST 电容必须紧贴对应脚；最小化 `PMID/SW/BTST` 高频环路；TS、ILIM、I²C 等小信号远离 SW 铜。
- 高电流走线使用短宽铜皮，热路径按 TI 建议铺铜/过孔。密封壳内“充电 + 屏幕 + Wi-Fi”同时运行必须在独立电源券上验证启动、USB 拔插、负载阶跃、电池切换、热降额及保护行为。

官方依据：[TI BQ25628E Datasheet](https://www.ti.com/lit/ds/symlink/bq25628e.pdf)。

### 2.2 TPS63070RNMR

- 精确订单号为 `TPS63070RNMR`，封装为 `RNM0015A`、15-pin VQFN-HR；不要误选同系列 `TPS630701/702`。
- 电感、VIN/VOUT 陶瓷电容围绕芯片形成最小热回路；两个开关节点面积最小，反馈分压从干净 VOUT Kelvin 取样并远离 SW/电感。
- AGND/PGND 与散热铜按 TI layout example 收敛，不得靠自动铺铜自行决定回流路径。
- 1.5 A 系统峰值、最低电池电压、Wi-Fi 发射、屏幕高亮同时发生时，要检查电感饱和、输入限流、输出跌落、效率和密封壳温升。

官方依据：[TI TPS63070 Datasheet](https://www.ti.com/lit/ds/symlink/tps63070.pdf)。

### 2.3 TUSB320LAIRWBR

- 精确订单号 `TUSB320LAIRWBR`，封装 `RWB0012A`、12-pin X2QFN；必须按 TI drawing 自建，重点审查细小 pad 的阻焊与钢网。
- CC1/CC2 到 Type-C 插座短且对称；`VBUS_DET`、`PORT/ADDR/EN_N` 及去耦按 TI 参考电路取值。CC 网络不能一边接 TUSB320、一边又按“纯 5.1 kΩ Rd 方案”重复设计，必须冻结一种架构。
- 固件只有在识别到上游 1.5 A/3 A 能力后才允许提高 BQ25628E 输入限流；默认仍按安全电流启动。

官方依据：[TI TUSB320LAI Datasheet](https://www.ti.com/lit/ds/symlink/tusb320lai.pdf)。

## 3. 姿态与航向传感器

### 3.1 QMI8658A（C3021082）

- H0175 EVT A1 精确器件改为 `QMI8658A`、JLC/LCSC `C3021082`，14-pin LGA、2.5 × 3.0 mm、0.5 mm pitch。它按当前焊盘/引脚连接作为 QMI8658C 的受控直替，但“同系列、同封装”不等于无需验证；必须按 QMI8658A package/land data 再做逐 pad 校核。
- 放在 PCB 机械中心附近、远离螺钉受力点、板边和大开槽；明确丝印 X/Y 轴、Pin 1 和产品“车头方向”，装配图不得只画圆点。
- 去耦紧贴，局部地连续；I²C 上只放一组总线拉高。固定地址为 `0x6B`，避免与 BQ25628E `0x6A` 冲突，并把两个中断脚引出。
- EVT 固件必须回归 WHO_AM_I/芯片 revision、初始化寄存器、两个中断脚、量程/ODR、静态零偏、六面姿态和板级 X/Y/Z 轴映射。车把转动/颠簸条件下不能只依赖陀螺积分当绝对航向。

官方依据：[QST QMI8658A Datasheet](https://www.qstcorp.com/upload/pdf/202301/13-52-25%20QMI8658A%20Datasheet%20Rev%20A.pdf)、[JLCPCB C3021082](https://jlcpcb.com/partdetail/QST-/C3021082)。

### 3.2 IIS2MDCTR（C2655002）

- H0175 EVT A1 精确器件为 `IIS2MDCTR`、JLC/LCSC `C2655002`，LGA-12L、2 × 2 × 0.7 mm、12 pad。当前项目的 I²C 接法与焊位可兼容该器件；仍须按 IIS2MDC datasheet/TN0018 逐 pad 复核位置、尺寸和 Pin 1。
- `C1 = 220 nF` 按 ST 参考连接紧贴器件；I²C 模式下按 IIS2MDC 数据手册处理 CS/接口选择脚，地址按 `0x1E` 验证。
- 放在圆板外周、尽量靠产品 12 点方向，并与 TPS63070 电感、BQ25628 高频电流、USB/VBUS、扬声器磁体、电池保护板、钢制螺钉和 GNSS/2.4 GHz 同轴接头保持最大可实现距离。
- 大电流走线不得从 IIS2MDC 下方或近旁穿越。EVT 固件必须验证 WHO_AM_I、数据就绪/自检和产品轴向；最终使用非磁性紧固件，并在完整 PCB、外壳、佳明底座及装车通电状态做硬铁/软铁全方向标定。

官方依据：[ST IIS2MDC Datasheet](https://www.st.com/resource/en/datasheet/iis2mdc.pdf)、[JLCPCB C2655002](https://jlcpcb.com/partdetail/STMicroelectronics-IIS2MDCTR/C2655002)、[ST TN0018 MEMS LGA mounting guideline](https://www.st.com/resource/en/technical_note/tn0018-surface-mounting-guidelines-for-mems-sensors--in-an-lga-package--stmicroelectronics.pdf)。

### 3.3 EVQP7C01P（SW1–SW4）

- `EVQP7C01P`（`C388883`）保留当前 `SW_SPST_EVQP7C` land pattern、本体 `3.6 × 3.5 mm`、总高 `1.35 mm`、约 `2.2 N` 操作力和 `0.2 mm` 行程。
- P 后缀执行部为 `1.1 ± 0.1 mm`，与已停产/缺货 K 后缀的 `1.2 ± 0.1 mm` 不同。PCB 焊盘可延用，但外壳按键柱不能照抄旧高度。
- EVT 装壳后检查静态预压、空行程、完全按下余量、复位、侧向偏载和重复操作；任何常压误触或触底前无法动作都必须先改按键柱再冻结结构。

官方依据：[Panasonic EVQP7C01P](https://industry.panasonic.com/global/en/products/control/switch/light-touch/number/evqp7c01p)、[JLCPCB C388883](https://jlcpcb.com/partdetail/PANASONIC-EVQP7C01P/C388883)。

## 4. GNSS 与电平接口

### 4.1 LC76GABMD

- 精确后缀是 `LC76GABMD`（LC76G AB 版本），不要把 PA/PB 版本当作同封装直接替换；它们的电源和功耗条件不同。
- 模组为 `10.1 × 9.7 × 2.4 mm`，18 个 LCC + 10 个 LGA，共 28 pad。必须根据采购批次对应的 Quectel Hardware Design 推荐 footprint 自建，不从商品图或第三方 EasyEDA 封装反推。
- Quectel 建议模组与周围器件留至少 3 mm，以改善焊接质量和维修便利性；这是装配/返修 DFM 建议，不是 RF 电气间距。A1 为保持 RF_IN 匹配链最短，L3、R33、C31 及若干低速电阻小于 3 mm。5 片 EVT 可在嘉立创书面接受局部 DFM/返修豁免后保留，量产 Rev B 必须围绕 U10 重排或取得正式工艺批准；不能用“DRC 0”冒充该项已满足。
- 裸模组没有可用内置 GNSS 天线。`RF_IN` 必须接 1559–1606 MHz 天线；预留靠近天线的 π 匹配，低电容 ESD（Quectel 建议结电容不高于 0.6 pF），全程 50 Ω、短线、连续参考地。
- 电源按官方推荐在 VCC 近端布置 `10 µF + 100 nF + 33 pF` 等去耦，最小值器件最靠近电源脚；不得从高噪声开关节点直接喂电。GND pad 1、10、12、28 接连续地；保留脚按官方要求悬空。
- `RESET_N` 内部拉至 1.8 V，不能外加 3.3 V 上拉或直接以 3.3 V push-pull 拉高；按官方参考电路控制。
- `C7437114` 目前只可保留为 BOM 候选号；BOM 发布时必须确认页面 MPN、厂牌 Quectel、后缀 ABMD 和实际可贴装状态四项一致。不得悄悄用可搜到的 PA 版替代。

官方依据：[Quectel LC76G Series Hardware Design v1.3](https://www.quectel.com/content/uploads/2023/05/Quectel_LC76G_Series_Hardware_Design_V1.3.pdf)、[Quectel LC76G 产品页](https://www.quectel.com/product/gnss-lc76g-series/)。

### 4.2 TPS7A2030PDBVR

- `TPS7A2030PDBVR` 是固定 3.0 V、300 mA LDO，`DBV0005A` SOT-23-5。标准 KiCad SOT-23-5 可用，但必须检查 pin 1、EN 和固定输出版本的 pin map。
- 输入/输出各至少使用数据手册允许的 1 µF 低 ESR 陶瓷并贴近引脚；GNSS 电源走线短，远离 TPS63070/BQ25628 的 SW 铜。
- 从 3.3 V 升降压降到 3.0 V 的裕量较小，按 LC76G 峰值电流、线损、LDO dropout、低温与公差做 worst-case；不满足时不能靠典型值放行。

官方依据：[TI TPS7A20 产品页/数据表](https://www.ti.com/product/TPS7A20/part-details/TPS7A2030PDBVR)。

### 4.3 TXU0202DCUR

- `TXU0202DCUR` 为 `DCU0008A` 8-pin VSSOP，2.30 × 2.00 mm；候选 KiCad VSSOP-8 footprint 与 TI drawing 逐项比对后可冻结。
- `VCCA = 3.3 V`、`VCCB = 3.0 V`。TXU0202 两通道方向相反：A1→B1 用于 ESP TX 到 GNSS RX，B2→A2 用于 GNSS TX 到 ESP RX；不要把 A2/B2 当成第二路 A→B。
- 两侧电源各放 100 nF 就近去耦，OE 默认下拉使两电源未稳定时输出高阻；器件放在 GNSS 数字接口边界附近。1PPS 另用经验证的 3.0→3.3 V 接收方案。

官方依据：[TI TXU0202 Datasheet](https://www.ti.com/lit/ds/symlink/txu0202.pdf)。

## 5. 连接器

### 5.1 USB-C 候选：XKB U262-161N-4BVC11

该 MPN/footprint 只能冻结为**电气候选**，不能据此开板：

- 这是 USB 2.0 16-contact Type-C 母座，带 4 个外壳固定焊脚和 2 个 NPTH 定位柱；现有 KiCad 专用 footprint 是合理起点。
- 商品/库中的这只候选是卧式 SMT、外壳脚穿孔固定的结构，不应在技术图中误称“中沉/沉板型”。如果外壳必须使用真正 mid-mount，应重新选确切 MPN 并重做 footprint/开口。
- 机械必须闭环：PCB 板边到座体基准、舌片中心 Z、外壳开口尺寸、插头包络、胶塞/防水结构、固定脚与底座/电池的干涉。
- A6/B6 在座边合并为 D+，A7/B7 合并为 D−，随后以 90 Ω 差分对走线；低电容 ESD 紧靠接口，VBUS 做浪涌/ESD，CC1/CC2 直达 TUSB320LAI，外壳接地策略单独审核。

`C319148` 的官方 MPN 为 `U262-161N-4BVC11`。项目 footprint 的历史库名仍含 `U262-16XN-4BVC11`；该字符串只标识既有焊盘资源，不是允许采购 `16XN` 的依据。

候选资料：[JLCPCB C319148](https://jlcpcb.com/partdetail/XKBIndustrialPrecision-U262_161N4BVC11/C319148)、[KiCad 官方 footprints](https://gitlab.com/kicad/libraries/kicad-footprints)。

**解除 C 阻断所需输入**：最终外壳 STEP/截面、PCB Z 高、实物连接器尺寸或原厂尺寸图，以及确认“顶贴/中沉”的书面选择。

### 5.2 显示 0.3 mm / 31-pin FPC 候选

候选 `XUNPU FPC-0.3FX-31PWBH10` 为 0.3 mm pitch、31 个电气触点、下接触、翻盖、约 1.0 mm 高连接器；但它不能在原屏实物没有量测前直接冻结：

- 电气映射已于 2026-09-03 对照微雪官方 `ESP32-S3-Touch-AMOLED-1.75C` 原理图第 1 页逐脚复核，1–31 脚与 `schematic/pin-net-map.csv` 一致。
- 候选 footprint 已与 JLCPCB `C5343228` 的 EasyEDA 封装数据及讯普原厂 `FPC-0.3FX-NPWBH10 Rev A` 推荐 PCB 图核对：31 个交错信号焊盘、0.30 mm X pitch、两排中心相对位置、焊盘尺寸及两个机械焊盘均吻合。可重复报告为 `validation/fpc-footprint-audit.txt`。

- 必须确认原屏 FPC 的 pitch、总触点数、裸金手指宽度、FPC 厚度、接触面朝上/朝下、插入方向、Pin 1 位置和加强板厚度。
- 31 个电气 pad 之外还有机械固定焊脚；symbol/footprint 中机械脚不得误编号为第 32/33 个信号。
- 不可拿 `FH26-31S-0.3SHW` 等 Hirose footprint 猜代。不同厂牌即使“0.3 mm / 31P”相同，触点排布、固定脚、锁扣和可接受 FPC 厚度也可能不同。
- footprint 必须按最终连接器原厂/JLC drawing 自建，使用实物打印 1:1 叠放检查；至少焊一块 FPC breakout coupon 验证 Pin 1、插拔和接触方向。
- 显示 QSPI 约 40 MHz 的线在连续地平面上短走，预留靠近主控的串联电阻位；屏幕电源去耦靠近 FPC，保持锁扣可操作空间和合理弯折半径。
- H0175 EVT A1 依据受控供应商 PDF 第 7 页，把 `TP3.3`、`IOVCC`、`VCI`、`VBAT` 和 `VCI_EN` 从 `3V3_DISPLAY` 直接供电，不加入未经屏厂确认的 4 V 选择器。由于同一 PDF 其他页对 VBAT 的表述不完全一致，首板必须做反复冷启动、低温启动和最大亮度测试；测试不通过时再以实测/屏厂书面资料修订 Rev B 电源，而不是在 A1 猜电压。

候选资料：[JLCPCB C5343228](https://jlcpcb.com/partdetail/XUNPU-FPC_0_3FX31PWBH10/C5343228)。

**解除 C 阻断所需输入**：原装 1.75C 屏幕 FPC 正反面微距照片、卡尺量测、触点朝向与 Pin 1 连通性测试；电气 symbol 已按官方图建立，实测用于最终确认 footprint 朝向和机械配合。

## 6. Rev A footprint 放行流程

每个项目自建 footprint 必须同时留存：原厂 package drawing 截图/页码、焊盘坐标表、KiCad 1:1 PDF、Pin 1 标识、courtyard、元件高度、阻焊与 paste 规则、3D STEP（若有）以及第二人复核记录。

放行顺序：

1. 冻结精确 MPN，禁止用系列名代替订单号。
2. 按官方 top/bottom view 和 pin table 做逐脚检查；对 WROOM、PICO、LC76G 特别检查中央/底部 pad。
3. 用 1:1 打印件与实物叠放；连接器再做 mating/插拔包络检查。
4. 对 BQ25628E、TPS63070、TUSB320LAI、PICO（若启用）、31P FPC 制作测试券，确认钢网、回流和可检测性。
5. 把 footprint 复制到版本化的项目库，锁定库 commit/hash；原理图和 PCB 不引用用户电脑上的浮动全局库。
6. 跑 ERC/DRC 后仍要做独立的 pin-number audit、机械 3D 干涉检查、电源回路审查和 RF review。
7. 下单当天重新查询 LCSC/JLCPCB 料号、库存、贴装类别、替代料和最小起订量；任何后缀变化都重新审计。

## 7. 当前投板门槛

在以下项目全部关闭前，文件只能标注 `ENGINEERING / NOT FOR FABRICATION`：

- [x] WROOM-1U 在完整 Ø52 mm R4 圆板上的 courtyard 摆件与全连接布线通过 KiCad 检查。
- [ ] 电池叠板、后盖增厚、连接器插拔和两根同轴线弯曲空间通过实物驱动的整机 3D 检查。
- [ ] 31-pin 原屏 FPC 实物定义闭环，并通过 breakout coupon。
- [ ] USB-C 精确结构/MPN 与壳体截面冻结。
- [ ] BQ25628E + TPS63070 + TUSB320LAI 电源券通过冷启动、热插拔、负载阶跃、充放电切换、限流和密封温升测试。
- [ ] QMI8658A 完成器件 ID/revision、中断、量程和板级轴向回归。
- [ ] IIS2MDC 在整机装配状态通过器件 ID/自检、轴向和硬铁/软铁校准，确认螺钉、扬声器残留件与电源电感不会使航向失真。
- [ ] EVQP7C01P 与外壳按键柱完成预压、完整行程、复位和重复操作验证。
- [ ] H0175 的 `3V3_DISPLAY` 直供在冷启动、低温和最大亮度条件下通过验证。
- [ ] LC76GABMD 采购料号闭环，GNSS 天线、50 Ω 走线、塑料 RF 窗和整机定位测试完成；A1 的局部 3 mm DFM/返修间距由嘉立创接受豁免，或在 Rev B 重排关闭。
- [ ] 所有自建 footprint 有第二人逐脚签核，JLC DFM/贴装能力无未确认项。

满足这些条件后，才可以把 Rev A 从“可设计”提升为“可投板”。
