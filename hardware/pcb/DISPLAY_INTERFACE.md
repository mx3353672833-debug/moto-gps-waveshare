# H0175Y003AMT003 V1 display interface

状态：**Rev A1 EVT 设计基准 / 连接器实物方向待到货签核**  
受控资料：`hardware/rev_a/references/H0175Y003AMT003_V1.pdf`  
资料 SHA-256：`3e08cf17e99822025f1b2c1781c538004b4bed50ea2776c94abda12583350fbe`

目标屏幕为华夏彩光电 `H0175Y003AMT003 V1`：1.75 英寸全圆 AMOLED、466 × 466、CO5300、CST820 单点触摸、31 Pin / 0.30 mm 主接口。Rev A1 只使用 QSPI，不布 MIPI。

## 连接器编号规则

规格书第 5 页把 `OK-F302-31115` 标在模组主柔性板上，外观和注释均表明 31 Pin 端很可能是已安装的 ZIF 母座，而不是可直插主板的裸金手指。如果屏端和板端均为母座，中间必须使用 31 芯、0.30 mm 节距、约 0.20 mm 厚的 FFC/FPC 跳线。

现 R4 电气表按下列“端到端反序”假设布线：

`Panel pin = 32 - J3 pad`

在该假设成立时，下表 31 路功能全部匹配。但这不是已冻结的物理事实：最终同号还是反序由模组端座方向、主板端座方向以及跳线两端露铜同面（Type A）/异面（Type B）共同决定。必须等屏、线、座形成完整链路后用多个 GND 针通断确认；未确认时严禁插屏上电。

| 模组座 pin | 模组信号 | J3 pad | Rev A1 net | 说明 |
| ---: | --- | ---: | --- | --- |
| 1 | GND | 31 | `GND` | 地 |
| 2 | TP_INT | 30 | `TP_INT_N` | CST820 中断 |
| 3 | TP_RST | 29 | `TP_RESET_N` | CST820 复位 |
| 4 | TP_SDA | 28 | `I2C_SDA` | 触控 I²C 数据 |
| 5 | TP_SCL | 27 | `I2C_SCL` | 触控 I²C 时钟 |
| 6 | TP_3.3V | 26 | `3V3_DISPLAY` | 触控供电 |
| 7 | GND | 25 | `GND` | 地 |
| 8 | IOVCC | 24 | `3V3_DISPLAY` | 显示逻辑供电 |
| 9 | VCI | 23 | `3V3_DISPLAY` | 显示模拟供电 |
| 10 | VBAT | 22 | `3V3_DISPLAY` | 屏内 BV6802W 输入，按 p7 QSPI 参考图先用 3.3 V |
| 11 | VCI_EN | 21 | `3V3_DISPLAY` | 显示电源使能 |
| 12 | GND | 20 | `GND` | 地 |
| 13 | MTP_PWR | 19 | `NC` | 正常工作悬空 |
| 14 | ERSRT / RESET | 18 | `LCD_RESET_N` | CO5300 复位，低有效 |
| 15 | CSX | 17 | `LCD_CS_N` | QSPI 片选，低有效 |
| 16 | SDI/RDX | 16 | `LCD_SIO0` | QSPI data 0 |
| 17 | DCX | 15 | `LCD_SIO1` | QSPI data 1 |
| 18 | WRX/SCL | 14 | `LCD_CLK` | QSPI 时钟 |
| 19 | D(1) | 13 | `LCD_SIO3` | QSPI data 3 |
| 20 | D(0) | 12 | `LCD_SIO2` | QSPI data 2 |
| 21 | TE | 11 | `LCD_TE` | tearing-effect 同步输出 |
| 22 | GND | 10 | `GND` | 地 |
| 23 | D1P | 9 | `NC` | MIPI，QSPI 模式悬空 |
| 24 | D1N | 8 | `NC` | MIPI，QSPI 模式悬空 |
| 25 | GND | 7 | `GND` | 地 |
| 26 | CLKP | 6 | `NC` | MIPI，QSPI 模式悬空 |
| 27 | CLKN | 5 | `NC` | MIPI，QSPI 模式悬空 |
| 28 | GND | 4 | `GND` | 地 |
| 29 | D0P | 3 | `NC` | MIPI，QSPI 模式悬空 |
| 30 | D0N | 2 | `NC` | MIPI，QSPI 模式悬空 |
| 31 | GND | 1 | `GND` | 地 |

## 电源基准与首板保护

- 规格书 p7 的 QSPI 参考接线明确给出：TP_3.3V=3.3 V、IOVCC=1.65–3.3 V、VCI=2.7–3.6 V、VBAT=3.3–5.5 V、VCI_EN=3.3 V，因此首版 EVT 将五路经 `FB1` 接至同一 `3V3_DISPLAY`。
- 同一规格书 p6/p8 又把 VBAT 正常范围写成 3.7–4.5 V，而 5.5 V 同时是绝对最大值；这是厂家文档矛盾。首板不向屏幕施加 5 V，也不直接接裸电池，先按 p7 参考图和商品标称的 3.3 V 输入上电。
- `FB1` 后保留本地 10 µF + 100 nF 去耦。首次上电使用限流电源，记录黑屏、全白和 700 nit 高亮画面下的稳态/峰值电流。
- QSPI 的 `CLK/SIO0..3/CS` 保留靠近 ESP32 的 0 Ω 串联位；首板先贴 0 Ω，只有示波器确认振铃时再调整。
- MIPI 六个引脚及 MTP_PWR 必须悬空。
- `VBAT` 在下一版原理图中应从其余四路屏电源分开，经 0 Ω选择位与测试点可选 3.3 V 或受控高些的试验电源；不允许在未限流的情况下尝试 5 V/裸电池。

## 显示软件基准

- 驱动 IC：CO5300；接口：QSPI；物理画布：466 × 466；首版像素格式：RGB565。
- 卖家未提供本面板专用初始化数组，但这不阻塞购买和 PCB 电气设计。公开的 `kodediy/esp_lcd_co5300` 和多款 CO5300/CST820 开源板可作为点亮基线。
- 初始化数组仍可能包含面板厂专属 gamma、扫描方向或窗口偏移。首屏到货后依次验证红/绿/蓝/白/黑纯色、1 px 外框、四角坐标、刷新撕裂和休眠唤醒，再把最终数组固化到固件。
- 规格书注明扫描方向不支持反扫；UI 旋转优先在软件坐标/绘制层处理，不依赖 CO5300 反向扫描命令。

## 触控软件基准

- 触控 IC：CST820；按卖家确认作为单点触控使用。
- 默认候选 7-bit I²C 地址为 `0x15`；固件 bring-up 先以 100 kHz 扫描总线，确认地址后升至 400 kHz。
- CST820 可使用公开的 ESP-IDF/Arduino CST816 系列兼容驱动；不需要等待卖家驱动才能购买。
- 首板验证 INT 极性、复位时序、坐标交换/镜像和边缘坐标。产品交互不得依赖双指缩放。

## 到货后的十分钟签核

1. 确认 31 Pin 端是已装 ZIF 座还是裸金手指、是否随屏附线，并记录跳线长度、厚度及两端露铜同面/异面。
2. 不插屏，确认 J3 的 1/4/7/10/20/25/31 全为 GND，21/22/23/24/26 为 3.3 V，且供电对地无短路。
3. 用万用表从屏幕金属背板/模组座的已知 GND 端，通过实际跳线反查到 J3 的多个 GND，确认端到端网表；任何一项不符都禁止插屏上电。
4. 设 3.3 V、150 mA 初始限流点亮；观察浪涌后再逐步放宽。
5. 完成五色、边框、触摸四边、休眠/唤醒和 30 分钟高亮温升测试。

参考实现：

- [ESP-IDF CO5300 QSPI driver](https://github.com/kodediy/esp_lcd_co5300)
- [ESP-IDF CST820 touch driver](https://github.com/kodediy/esp_lcd_touch_cst820)
- [Waveshare CO5300/CST820 examples](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.8)
