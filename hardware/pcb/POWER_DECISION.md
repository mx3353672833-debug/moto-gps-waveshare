# Rev A 电源架构：BQ25628E + TPS63070

当前状态：`SELECTED / COUPON NOT YET PASSED`。主方案已选定，但在独立电源测试券完成前，完整主板仍不得投产。

## 为什么不能直接照抄微雪

AXP2101 的充电模式、默认输出、启动行为等由出厂 EFUSE 配置决定。线性充电版和开关充电版需要不同外围，EFUSE 烧录后不可由普通固件改成另一种。微雪原理图证明它的特定芯片版本可以工作，但不证明嘉立创库存 `C3036461` 与其配置一致。

另外，AXP2101 的 3.3 V DCDC 是降压而非升降压；单节锂电接近低电量时，系统会进入掉压区。可以通过约 3.4 V 提前关机规避，但会牺牲一部分可用电量。

## 已选主方案

```text
USB-C 5 V
  -> TUSB320LAI（识别 Default / 1.5 A / 3 A）
  -> BQ25628E（单节锂电开关充电 + NVDC power-path）
  -> SYS
  -> TPS63070（3.3 V buck-boost）
  -> ESP32-S3 / AMOLED / 触摸 / 传感器
  -> TPS7A2030 类低噪声 LDO（3.0 V）
  -> LC76GABMD
```

候选嘉立创贴装料：

| 功能 | 器件 | 嘉立创/LCSC 号 |
| --- | --- | --- |
| 充电与电源路径 | BQ25628ERYKR | C18221178 |
| 3.3 V 升降压 | TPS63070RNMR | C109322 |
| USB-C 电流能力识别 | TUSB320LAIRWBR | C132554 |
| 两处 1 µH 功率电感候选 | 252012CDMCDDS-1R0MC | C492725 |

BQ25628E 默认硬件输入限流设为 500 mA；ESP32 读取 TUSB320LAI 并确认上游支持 1.5 A/3 A 后，固件才允许提高充电器输入限流。不能仅凭两个 5.1 kΩ Rd 就固定抽取 1.5 A。

## 电源测试券

先制作约 20 × 20 mm 的独立测试板，验证：

- 电池冷启动、USB 插入自动启动、USB 拔出无缝切换；
- 3.3 V 空载、1 A 持续、1.5 A 阶跃和 Wi-Fi 等效脉冲负载；
- USB-C Default/1.5 A/3 A 三种来源的输入限流；
- 500/800 mA 充电、NTC 停充和密封环境温升；
- 4.2 V 至低电量全过程的 3.3 V 轨、关机点和复位行为；
- 同批至少 5 片一致性。

只有全部通过，才能把该外围移入 Rev A 主板。密封小壳的初始充电电流限制为 500 mA；温升允许后再评估 800 mA。

## 无论选哪条路线都必须满足

- USB-C CC1、CC2 各自 5.1 kΩ 下拉；D+/D− 90 Ω 差分并加低电容 ESD；
- 单节 4.2 V 锂聚合物，电池包带独立过充、过放、短路保护和 10 kΩ NTC；
- Type-C 电流识别失败或未启动时，USB 输入限流必须保持不高于 500 mA；
- GNSS 主电源使用 3.3 V 主轨输入、3.0 V 输出的独立低噪声 LDO；ESP32 与 GNSS UART 通过 TXU0202 双电源定向电平转换，不能直接短接两个电压域；
- PMIC/电感置于 6 点方向，磁力计置于 12 点外缘，两者尽量保持 25–30 mm 间距；
- 必须保留 VBUS、BAT、SYS、3V3、GND、I²C/状态脚测试点。

## AXP2101 记录

AXP2101 不再进入主板主方案。其充电模式、默认输出和启动行为依赖出厂 EFUSE，不能证明普通库存 `C3036461` 与微雪定制版本相同；同时其 3.3 V DCDC 只是降压，单节锂电低电量会掉压。保留这段记录是为了防止后续误把它重新加入 BOM。

正式生产包必须在本文件状态改成 `RELEASED` 后才能生成。

## 主要依据

- [TI BQ25628E 产品页与数据手册](https://www.ti.com/product/BQ25628E)
- [TI TPS63070 产品页与数据手册](https://www.ti.com/product/TPS63070)
- [TI TUSB320LAI 产品页与数据手册](https://www.ti.com/product/TUSB320LAI)
- [TI TXU0202 双电源定向电平转换器](https://www.ti.com/product/TXU0202)
- [Quectel LC76G Series Hardware Design](https://www.quectel.com/content/uploads/2023/05/Quectel_LC76G_Series_Hardware_Design_V1.3.pdf)
