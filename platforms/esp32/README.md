# Waveshare ESP32-S3-Touch-AMOLED-1.75C 固件

第一次烧录请看[微雪版 DIY 完整教程](../../docs/WAVESHARE_DIY_GUIDE.md)，
已安装后的操作见[功能说明书](../../docs/USER_MANUAL.md)。

只适用于 **1.75C**：CO5300 QSPI、CST9217、466×466、32 MB Flash、8 MB PSRAM。
使用 ESP-IDF **5.5.5**、BSP **3.0.0** 和根目录 LVGL 固定子模块。

## 编译，不会刷板

先按 Espressif 的安装说明准备并激活 ESP-IDF 5.5.5 环境，然后在仓库根目录执行：

```sh
git submodule update --init --recursive
idf.py -C platforms/esp32 set-target esp32s3
idf.py -C platforms/esp32 build
```

首次构建会下载 Component Manager 依赖，产物在 `platforms/esp32/build/`。
`dependencies.lock` 中 LVGL 是项目根目录下的相对路径，若本地工具重写成绝对路径，
不要把个人路径提交回仓库。不要手动修改 `managed_components` 来维持编译。

## 备份和刷写

刷写会替换原厂应用。先确认准确板型、USB 串口、Flash 容量与加密/安全启动状态，
自行保存原厂全片备份；备份可能包含设备凭证，**不要上传 GitHub**。
以下 PORT 是占位符，请换成实际 USB 串口；命令需要已激活 IDF 的 Python 环境。

```sh
python -m esptool --chip esp32s3 --port PORT flash_id
python -m esptool --chip esp32s3 --port PORT get_security_info
mkdir -p backups
# 仅限已确认 32 MB、未启用 Flash 加密/安全启动的开发板：
python -m esptool --chip esp32s3 --port PORT read_flash 0 0x2000000 backups/waveshare-original.bin
```

上述语法对应 ESP-IDF 5.5.5 环境中的 esptool 4.x。若自行使用 esptool 5.x，子命令
改为 `flash-id` / `get-security-info` / `read-flash`，以该版本 `--help` 为准。
安全状态、容量或型号不符时停止；备份文件应为 33,554,432 字节，并另存校验值。
备份成功且接受覆盖原厂固件后才执行：

```sh
idf.py -C platforms/esp32 -p PORT flash monitor
```

不需要 `erase-flash`；串口日志用 Ctrl+] 退出。只使用本机这次编译生成的 flash 参数，
不要套用其他板卡镜像偏移。首次使用在 iPhone 中完成系统蓝牙配对。

## 行为与限制

- 广播名称 `MOTO GPS`；iPhone 通过 BLE 提供定位、路线、场景与音乐状态。
- 开机黑白动画后进入连接页；连接成功后等待手机选路线，不把空路线画成 `0 m`。
- 左右滑动换页，指示点五秒后隐藏。音乐页是否可用取决于手机声明的能力。
- PWR 持续按住约三秒请求 AXP2101 关机；USB 供电下有深睡兜底。不同供电组合仍需实测。
- QMI8658 相对角速度辅助转向；行驶航向由手机定位锚定，不提供静止绝对北向。
- 使用 PSRAM 双缓冲和 CO5300 TE 同步降低撕裂；目标节拍不等于实测持续帧率保证。

关键引脚：QSPI D0–D3 为 GPIO4–7；SCLK38、CS12、RESET1、TE13；I2C SDA15/SCL14；
触摸 INT11/RESET2。不要把这些引脚当作可任意外接的空闲 GPIO。

上游：[微雪官方工程](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C)，
[Waveshare BSP](https://github.com/waveshareteam/Waveshare-ESP32-components)。
已知后台断连和路线问题见根目录 `docs/KNOWN_ISSUES.md`。构建成功不等于骑行验收通过。
