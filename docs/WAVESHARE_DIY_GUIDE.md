# 微雪版 DIY 教程：购买、烧录、iPhone 安装与首次使用

适用型号：**Waveshare ESP32-S3-Touch-AMOLED-1.75C**。本文按 Mac + iPhone 的完整流程编写，
面向第一次接触 ESP32 的读者。安装完成后的日常操作见[功能与使用说明书](USER_MANUAL.md)。

## 先了解安装条件

当前仓库提供源码。截至本教程更新时，GitHub Releases 没有预编译固件，仓库也未提供
App Store、TestFlight 邀请或可直接安装的签名 IPA。本文使用的实际路径是：

```text
买 1.75C 成品设备 → 在 Mac 编译固件 → USB 备份并烧录
                 → 用 Xcode 给自己的 iPhone 安装 App → 蓝牙连接 → 演示
                 → 配置自己的 HTTPS 路线网关 → 搜索目的地并导航
```

复现演示需要设备、Mac、iPhone 和 Apple 账号；真实地点搜索与路线还需要自己的高德 Web 服务 Key
及手机能访问的 HTTPS 网关。只有 Windows 电脑时可以编译和烧录 ESP32，但本文的 iPhone
安装路径仍需要 Mac/Xcode。下载 GitHub ZIP 到 iPhone 不能安装 App。

[购买清单](#1-购买清单) · [准备工具](#2-准备-mac-工具和源码) · [备份烧录](#3-编译备份并烧录固件) ·
[iPhone 安装](#4-把-app-安装到-iphone) · [首次连接](#5-首次配对与桌面演示) ·
[真实导航](#6-配置真实导航) · [排错](#8-常见问题)

## 1. 购买清单

| 物品 | 数量 | 怎么选 |
| --- | --- | --- |
| 微雪 ESP32-S3-Touch-AMOLED-1.75C | 1 台 | 完整设备，核对型号末尾的 C；本项目配置要求 32 MB Flash、8 MB PSRAM |
| USB-C 数据线 | 1 条 | 能传文件/连接电脑的线，仅充电线无法烧录 |
| Mac | 1 台 | 能运行支持所用 iPhone 系统版本的 Xcode；工程需要 Swift 6 工具链 |
| iPhone | 1 台 | iOS 17 或更新系统；蓝牙、精确定位可用 |
| Apple 账号 | 1 个 | 用于在 Xcode 给自己的手机签名安装 |
| 电池 | 可选 | 桌面首次验证用 USB 即可；需要电池时让商家按 1.75C 配套确认尺寸、接口和极性 |
| 车把固定件 | 装车时准备 | 根据微雪现有外壳选择并实测固定；仓库自研 V3 卡口不等于微雪设备现成配件 |

购买入口：[微雪官方商城](https://www.waveshare.net/)，搜索完整型号；
[英文产品页](https://www.waveshare.com/esp32-s3-touch-amoled-1.75c.htm)；
[中文官方型号与资源说明](https://docs.waveshare.net/ESP32-S3-Touch-AMOLED-1.75C/)。
价格、套餐是否包含电池和运输范围以购买页面及商家确认为准。

不要只按“ESP32 圆屏”“1.75 英寸”选购。1.75、1.75C、1.43、LCD 型号的驱动与接线不同。
本项目适配的是 CO5300 显示 + CST9217 触控。无需另购裸屏、LC76G、磁力计或自研 PCB。

容量尤其要核对：官方中文页面的“产品特性”写过 16 MB，而“板载资源”写 32 MB，
存在文字不一致。本仓库的 `sdkconfig.defaults` 明确配置 32 MB；下单时请商家确认，
到货后再执行下文 `flash_id`。检测到其他容量时不要直接套用本教程烧录。

## 2. 准备 Mac 工具和源码

### 2.1 安装 Xcode 与命令行工具

从 Mac App Store 安装完整 Xcode，或使用 [Apple 开发者下载](https://developer.apple.com/download/)。
首次打开 Xcode，接受许可并完成提示的组件安装。Xcode 16 是本工程的起点；若手机系统更新，
需使用支持该系统的 Xcode，并满足它的 macOS 要求。

在终端执行，以下路径假设 Xcode 安装在默认位置：

```sh
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
xcodebuild -version
```

如果尚未安装 Homebrew，先按 [Homebrew 官网](https://brew.sh/)的说明安装，再安装工具：

```sh
brew install git cmake ninja dfu-util python@3.12 xcodegen
```

### 2.2 获取完整项目

```sh
mkdir -p ~/Projects
cd ~/Projects
git clone --recurse-submodules https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
git submodule status
```

应看到 `third_party/lvgl` 和固定提交 `85aa60d18b3d5e5588d7b247abf90198f07c8a63`。
如果忘了 `--recurse-submodules`，在仓库根目录补执行 `git submodule update --init --recursive`。
新手推荐 Git clone；GitHub 的 Download ZIP 不带子模块内容。

### 2.3 安装 ESP-IDF 5.5.5

ESP-IDF 是编译和烧录 ESP32 固件的工具包。按项目固定版本安装：

```sh
mkdir -p ~/esp
cd ~/esp
git clone --recursive --branch v5.5.5 https://github.com/espressif/esp-idf.git esp-idf-v5.5.5
cd esp-idf-v5.5.5
./install.sh esp32s3
. ./export.sh
idf.py --version
python -m esptool version
```

`idf.py --version` 应报告 v5.5.5。安装会下载编译器与 Python 依赖，需要联网。
本项目已检查的 IDF 环境使用 esptool 4.12.0；本文的读写命令使用 4.x 下划线写法。
如果输出为 5.x，使用工具帮助中的连字符写法，例如 `flash-id`、`read-flash`、`write-flash`。
不要在 IDF 环境中随意升级依赖。安装异常按
[乐鑫 v5.5.5 官方安装说明](https://docs.espressif.com/projects/esp-idf/en/v5.5.5/esp32s3/get-started/linux-macos-setup.html)排查。

每次新开终端，编译/烧录前都需要激活环境并回到项目：

```sh
. ~/esp/esp-idf-v5.5.5/export.sh
cd ~/Projects/moto-gps-waveshare
```

## 3. 编译、备份并烧录固件

### 3.1 编译 MOTO GPS

在已激活 IDF 的终端、仓库根目录执行：

```sh
idf.py -C platforms/esp32 set-target esp32s3
idf.py -C platforms/esp32 build
```

第一次构建会下载微雪 BSP 等依赖。出现 `Project build complete` 才算构建成功。
应用、bootloader、分区表与烧录参数在 `platforms/esp32/build/`。
这里生成的是 MOTO GPS；微雪官方下载的出厂测试固件用来测试/恢复设备，刷它不会得到本项目导航。
`set-target` 用于首次配置；后续同一目标通常直接执行 `build`。

### 3.2 确认 USB 串口

用数据线将设备直接接入 Mac，执行：

```sh
python -m serial.tools.list_ports
```

对比插拔前后的列表，寻找新增的 `/dev/cu.usbmodem...`。如果看不到串口，先换数据线或 USB 口。
将下面示例改成自己的完整串口路径，只在当前终端设置：

```sh
MOTO_PORT=/dev/cu.usbmodemYOUR_DEVICE
```

如果连接工具一直等待同步，按微雪说明按住 BOOT 重新上电进入下载模式，再松开 BOOT。
带电池时，拔掉 USB 可能并不等于完全断电；按官方电源操作说明重新上电。
下载模式下黑屏可能是正常现象。串口名称可能变化，重新列出并更新 `MOTO_PORT`。
参见[微雪烧录说明](https://docs.waveshare.net/ESP32-S3-Touch-AMOLED-1.75C/Firmware-Flashing/)。

### 3.3 读取容量、安全状态与备份

以下命令先读取设备信息：

```sh
python -m esptool --chip esp32s3 --port "$MOTO_PORT" flash_id
python -m esptool --chip esp32s3 --port "$MOTO_PORT" get_security_info
```

核对芯片为 ESP32-S3、Flash 为 32 MB，Flash Encryption 和 Secure Boot 未启用。
型号/容量不符或安全状态不清楚时，先解决差异，不继续覆盖。

备份当前设备的整片 Flash，保存到自己电脑的独立目录：

```sh
MOTO_BACKUP_DIR=$(mktemp -d "$HOME/moto-gps-backup.XXXXXX")
python -m esptool --chip esp32s3 --port "$MOTO_PORT" read_flash 0 0x2000000 "$MOTO_BACKUP_DIR/waveshare-original.bin"
wc -c "$MOTO_BACKUP_DIR/waveshare-original.bin"
shasum -a 256 "$MOTO_BACKUP_DIR/waveshare-original.bin"
```

文件长度应为 **33,554,432 字节**。把备份路径、SHA-256、设备型号记在自己的笔记里，
另存一份备份。这里故意放在仓库之外；备份可能包含设备配对或凭证信息，不要公开上传。

### 3.4 正式写入 MOTO GPS

确认备份成功并接受替换出厂软件后：

```sh
idf.py -C platforms/esp32 -p "$MOTO_PORT" flash monitor
```

等待写入和校验完成。串口日志用 `Ctrl+]` 退出。若仍停在下载模式，松开 BOOT 并重新启动设备。
启动后应看到黑底 MOTO GPS Logo，然后出现等待手机连接的界面。

本教程让 IDF 自动使用这次构建的镜像、分区表和偏移，无需手填地址。
不要把单独的应用 `.bin` 当成合并固件写到 `0x0`，也不需要先整片擦除。
烧录失败时先关闭其他占用串口的程序，可降低速度重试：

```sh
idf.py -C platforms/esp32 -p "$MOTO_PORT" -b 115200 flash
```

## 4. 把 App 安装到 iPhone

### 4.1 为什么不是“在 App Store 搜索下载”

当前公开的是 iOS 源码。可用安装方式是 Xcode 编译并签名到自己的手机。
GitHub 上的文件、模拟器 `.app` 或未签名 IPA 都不是通用安装包。
后续如发布 TestFlight，仓库首页应提供明确邀请入口；在此之前按本节操作。

在 Xcode 的 Settings → Accounts 中登录自己的 Apple 账号。
没有付费开发者会员的账号会显示 Personal Team。Apple 当前规定其安装用描述文件有效期为 7 天，
到期需要重新构建安装；免费账号还有限额和能力限制。参见
[Apple 个人开发账号说明](https://developer.apple.com/help/account/basics/about-your-developer-account)。
会员账号也需要自己的签名配置，不会自动得到作者的证书。

### 4.2 修改项目配置

打开仓库中的 `platforms/ios/project.yml`，找到三个 `PRODUCT_BUNDLE_IDENTIFIER` 并改成自己的唯一值，例如：

| Target | 示例 Bundle Identifier（把 yourname 改成你的标识） |
| --- | --- |
| MotoGPS | `com.yourname.motogps` |
| MotoGPSUITests | `com.yourname.motogps.uitests` |
| MotoGPSUnitTests | `com.yourname.motogps.unittests` |

如已有网关，把 `MOTOGPSGatewayBaseURL` 改为自己的 HTTPS 地址，保留末尾 `/`。
只做桌面演示时可暂时保留 `https://example.invalid/moto-gps/api/`，但它不能搜索或规划真实路线；
演示在线请求失败后使用内置 OSM 数据。真实导航前必须完成第 6 节。

`DEVELOPMENT_TEAM` 可先留空，生成工程后在 Xcode 中选择自己的团队。
不要只改 `App/Info.plist`：下次 XcodeGen 会按 `project.yml` 覆盖它。

### 4.3 生成工程并安装

```sh
cd ~/Projects/moto-gps-waveshare
(cd platforms/ios && xcodegen generate)
open platforms/ios/MotoGPS.xcodeproj
```

随后在 Xcode / iPhone 中依次操作：

1. 用数据线连接 iPhone，解锁手机并点“信任此电脑”。
2. 在 Xcode 左侧选择工程，再选择 `MotoGPS` target → Signing & Capabilities。
3. 勾选 Automatically manage signing，Team 选择自己的账号/Personal Team。等待签名错误消失。
4. 在顶部选择 `MotoGPS` scheme，运行设备选自己的 iPhone，不能选模拟器或仅构建设备选项。
5. 按 Xcode 提示在 iPhone 的“设置 → 隐私与安全性 → 开发者模式”开启并重启确认。
   如果没有这个入口，先让 Xcode 完成设备配对并尝试 Run。参见
   [Apple 开发者模式说明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。
6. 点击 Xcode 顶部 ▶ Run，或按 `⌘R`。等待编译、签名、安装，手机应出现 MOTO GPS App。
7. 如系统提示不信任开发者，按系统指引到“设置 → 通用 → VPN 与设备管理”确认自己的开发者身份，再打开 App。

首次打开允许蓝牙、定位和精确位置。先完成“使用 App 期间”定位授权，再按 App/系统提示升级为“始终”，
用于后台导航测试。Apple Music 权限可按需授权，拒绝后应继续使用导航功能。
权限入口会随 iOS 版本变化，也可以在系统设置搜索 MOTO GPS。

成功标准：App 能在实体 iPhone 上打开并显示目的地搜索和设备连接状态。
模拟器安装成功只能证明部分编译流程通过，不能验证圆屏 BLE。

## 5. 首次配对与桌面演示

1. 给圆屏上电，让手机与圆屏靠近。首次联调只开一台 MOTO GPS 设备，避免连接到另一台。
2. 打开手机蓝牙和 MOTO GPS App。App 会自动扫描广播名为 `MOTO GPS` 的圆屏。
3. 出现 iOS 蓝牙配对提示时点“配对”，等待 App 连接状态和圆屏都进入已连接/等待目的地。
   自定义 BLE 由 App 发起连接，不需要把设备先当耳机在系统蓝牙列表里搜索连接。
4. 在 App 点“演示导航”。圆屏应显示移动路线、转向图标和距离，手机持续标识为演示。
5. 左右滑动圆屏，检查马表、航向页；音乐页随手机能力开放。
6. 在 App 点“结束导航”，确认退出演示。

当前源码不再把圆屏长按绑定为演示开关。请从 App 的“演示导航”进入，避免照着旧记录长按无反应。
演示成功说明显示、触摸和一部分 BLE 数据链路已通，不代表真实路线、锁屏和网络切换已经验收。

## 6. 配置真实导航

真实搜索和路线需要自己的服务，完整步骤见[路线网关配置教程](GATEWAY_SETUP.md)。
流程是申请高德 **Web 服务** Key → 启动仓库 `backend` → 用 HTTPS 域名提供给 iPhone。

配置完成后，在 iPhone 的 Safari 打开自己的 `https://nav.example.com/moto-gps/api/healthz`：

- `provider` 应为 `amap`；
- `ready_for_live_navigation` 应为 `true`；
- 再按网关教程验证真实地点搜索；健康检查本身不验证 Key 权限和配额。

把 `project.yml` 的 `MOTOGPSGatewayBaseURL` 改为上述地址去掉 `healthz` 的部分，
重新执行 XcodeGen，再 Run 到 iPhone。高德 Key 只填在后端环境变量里，不能填进 App。
手机上的 `127.0.0.1` 指手机自己，不是 Mac；不要把电脑的本地监听地址直接填到 App。

完成后在开阔静止环境取得定位，搜索附近地点，点“选为终点”，查看“路线全览”，
选择一条路线，再点“开始导航”。核对手机所选终点和圆屏状态；结束时在 App 点“结束导航”。
当前使用高德普通驾车路线，不能视为已自动规避摩托车禁行路段的摩托专用导航。

## 7. 更新、重新安装与恢复出厂软件

固件升级前先保存自己的修改，检查 `git status`。更新源码后运行
`git submodule update --init --recursive`，再 `build` 和 `flash`；无需每次 `set-target`。
App 使用同一套源码更新，保留自己的 Bundle ID、Team 和网关设置，重新生成工程、Run。
免费签名到期也按同一安装步骤处理，通常无需先删除 App。

若需要恢复，使用第 3 节保存的**同一台设备**的完整原厂备份。在已核对容量和安全状态的设备上，
重新进入下载模式，将 `MOTO_BACKUP_FILE` 改成真实备份文件路径：

```sh
MOTO_BACKUP_FILE=/absolute/path/to/waveshare-original.bin
wc -c "$MOTO_BACKUP_FILE"
shasum -a 256 "$MOTO_BACKUP_FILE"
# 核对长度和此前保存的 SHA-256 后再写入；会覆盖当前 MOTO GPS 及设备状态。
python -m esptool --chip esp32s3 --port "$MOTO_PORT" write_flash 0 "$MOTO_BACKUP_FILE"
```

没有自己的备份时，按[微雪官方烧录页](https://docs.waveshare.net/ESP32-S3-Touch-AMOLED-1.75C/Firmware-Flashing/)取得
准确型号的官方测试固件与它对应的地址。测试固件未必恢复出厂时的全部个性化配置。

## 8. 常见问题

| 现象 | 先检查什么 |
| --- | --- |
| `idf.py: command not found` | 当前终端执行 IDF 的 `export.sh`；不要只在另一个窗口激活 |
| 找不到 LVGL / 子模块为空 | 在仓库根目录初始化子模块，核对固定提交 |
| 构建下载依赖失败 | GitHub/乐鑫/组件仓库的网络连接；保留失败日志，恢复网络后重试 |
| 容量显示 16 MB 或其他值 | 与本项目 32 MB 配置不符；停止套用烧录步骤，联系商家确认型号/修订 |
| 串口不存在或一直 Connecting | 数据线、端口占用、BOOT 下载模式；重新列出串口 |
| 烧录成功仍黑屏 | 是否仍在下载模式、是否为准确 1.75C 板型；看串口是否有初始化错误 |
| `Signing requires a development team` | MotoGPS target 的 Team、自动签名及 Apple 账号登录 |
| Bundle ID 不可用 | 使用自己的唯一标识，检查三个 target；更改 YAML 后重新生成 |
| iPhone 不在运行设备中 | 手机解锁并信任电脑，检查 Xcode 是否支持当前 iOS |
| App 过几天打不开 | 个人签名可能到期，连接 Xcode 用原配置重新 Run |
| 圆屏等待手机 / WAITING FOR PHONE | App 前台、蓝牙权限、附近是否有另一台设备；点连接“重试”，参考已知问题 |
| 搜索失败或没有路线 | 示例域名是否已替换、网关公网可达性、真实 Key 权限/配额；不要仅凭 healthz 判断正常 |
| 只有白线、没有灰路建筑 | 济南之外未提供同等离线底图覆盖；这与路线规划是不同能力 |
| 没有音乐页或按钮无效 | 在系统“音乐”App 先播放一首可播放歌曲，授予媒体权限，再检查连接 |

首次跑通后请先做锁屏、设备重启和网络切换的小范围验证。当前仍存在后台重连与部分路线问题，
复现记录见[已知问题](KNOWN_ISSUES.md)。需要求助时附型号、软件版本、失败步骤和脱敏错误日志，
不要附 Key、整片备份或个人位置轨迹。

下一步：[功能与使用说明书](USER_MANUAL.md) · [路线网关配置](GATEWAY_SETUP.md) · [回到仓库主页](../README.md)
