# DEVELOPMENT.md — 开发环境与构建

> 版本号全部从**当前实际安装/配置**读取（NCS 版本文件、`libs.versions.toml`、
> `gradle-wrapper.properties`、`<sysbuild>/*/zephyr/.config` 等），不是凭记忆填写。

---

## 第一部分：Firmware

## 1. 环境清单

| 项目 | 值 |
|---|---|
| 操作系统 | Windows |
| nRF Connect SDK (NCS) | **3.2.1**，安装根 `C:\ncs\v3.2.1` |
| Zephyr | **4.2.99**（`C:\ncs\v3.2.1\zephyr\VERSION`） |
| Zephyr SDK (toolchain) | **0.17.0**（`C:\ncs\toolchains\66cdf9b75e\opt\zephyr-sdk`） |
| 编译器 | `arm-zephyr-eabi-gcc` **GNU 12.2.0** |
| west | **1.4.0** |
| CMake | **3.21.0** |
| 工具链 Python | **3.12.4** —— `C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe` |
| board target | **`nrf52dk/nrf52810`** |
| 调试器 | CMSIS-DAP（枚举名 `jixin.pro CMSIS-DAP_LU` / 唯一 ID `LU_2022_8888`），pyOCD |

### 与构建直接相关的文件

| 文件 | 作用 |
|---|---|
| `prj.conf` | App 的 Kconfig（**每个符号只能出现一次**，重复赋值会让文档与实际不一致） |
| `app.overlay` | **板级引脚与 W25Q64/ADC 定义的实际生效处**（见 `HARDWARE.md` §2） |
| `boards/` | 当前没有生效的 board-specific overlay；唯一生效入口是 `app.overlay` |
| `sysbuild.conf` | sysbuild 级配置：MCUboot / overwrite-only / 外置次级槽 / ECDSA-P256 |
| `sysbuild/mcuboot.conf` | MCUboot 自身的 Kconfig（SPI NOR、布局页 4096、RC 32k、关日志） |
| `sysbuild/mcuboot.overlay` | MCUboot 的 devicetree（**与 App 独立，改引脚要同步改**） |
| `pm_static.yml` | Partition Manager 静态布局（**唯一编辑入口**） |
| `VERSION` | **镜像版本号唯一来源**（当前 `1.0.8` + tweak `0`） |
| `tools/build_sysbuild.py` | 构建包装（引号安全 + 签名私钥注入），见 §2 |

### 关键 Kconfig（摘自实际构建）

| 配置 | 值 | 说明 |
|---|---|---|
| `CONFIG_MAIN_STACK_SIZE` | 1024 | 全工程**唯一**赋值点 |
| `CONFIG_SYSTEM_WORKQUEUE_STACK_SIZE` | 1280 | OTA 异步化需要 |
| `CONFIG_BT_RX_STACK_SIZE` | 1024 | 勿降到 768 |
| `CONFIG_MPSL_WORK_STACK_SIZE` | 640 | 余量最小的栈（240 B） |
| `CONFIG_ISR_STACK_SIZE` | 1024 | |
| `CONFIG_BT_L2CAP_TX_MTU` | 128 | 协商失败自动回退，非升级前提 |
| `CONFIG_BT_BUF_ACL_RX_SIZE` / `TX_SIZE` | 132 / 132 | = MTU + 4 |
| `CONFIG_BT_MAX_CONN` | 1 | 同时只允许一个连接 |
| `CONFIG_SPI_NOR_FLASH_LAYOUT_PAGE_SIZE` | **4096** | 必须等于 W25Q64 真实扇区 |
| `CONFIG_IMG_BLOCK_BUF_SIZE` | 256 | = W25Q64 页大小 |
| `CONFIG_STREAM_FLASH` / `CONFIG_IMG_MANAGER` | y / y | ⚠️ `IMG_MANAGER` 是 **depends on** `STREAM_FLASH`，不显式开会被静默丢弃 |
| `CONFIG_IMG_ENABLE_IMAGE_CHECK` | y | App 侧整镜像 SHA-256 |
| `CONFIG_REBOOT` | y | `sys_reboot()` 需要 |
| `CONFIG_ADC` | y | 电池电压（VDD 通道） |
| `CONFIG_LOG` / `CONFIG_PRINTK` / `CONFIG_CONSOLE` | **n / n / n** | 发布态 |
| `CONFIG_NCS_BOOT_BANNER` / `CONFIG_EARLY_CONSOLE` | **n / n** | 必须一起关，否则它们 `select PRINTK`（白占 17,492 B ROM） |
| `CONFIG_USE_SEGGER_RTT` / `CONFIG_RTT_CONSOLE` | n / n | 发布态 |

> **调试时怎么开日志**：把 `prj.conf` 末尾"Release 配置"段整体注释掉，
> 改用 RTT（`CONFIG_CONSOLE=y` + `CONFIG_USE_SEGGER_RTT=y` + `CONFIG_RTT_CONSOLE=y` +
> `CONFIG_NCS_BOOT_BANNER=n` + `CONFIG_PRINTK=y`）。写法在 `prj.conf` 开头有完整说明。
> **不要**只写 `CONFIG_PRINTK=y` 而留着 `NCS_BOOT_BANNER=y`。

### 签名配置

| 项目 | 值 |
|---|---|
| 算法 | `SB_CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y` |
| 私钥传入方式 | **`MCU_BOOT_SIGNING_KEY` 环境变量** 或 **`--signing-key <path>` 参数** |
| 私钥是否入库 | **否**（`.gitignore` 只按目录/文件名精确忽略，不影响将来入库公钥） |
| 默认私钥路径 | **没有**（刻意不提供） |

---

## 2. 构建：统一走 `tools/build_sysbuild.py`

**不要**直接 `west build`：`SB_CONFIG_BOOT_SIGNATURE_KEY_FILE` 是 Kconfig 字符串，
值必须自带引号，而 PowerShell 会剥掉内层双引号，导致
`malformed string literal in assignment to BOOT_SIGNATURE_KEY_FILE` 构建中止。
该脚本自己拼 `sys.argv`、直接调用 `west.app.main()`，不让 shell 参与引号处理。

### 2.0 先把私钥路径设好（PowerShell）

```powershell
$env:MCU_BOOT_SIGNING_KEY = "D:\keys\root-ec-p256.pem"
```

> 私钥是**凭据**，**不要**复制进仓库，也不要把路径写死进脚本。
> 如需用另一把密钥，加 `--signing-key` 参数覆盖（优先级高于环境变量）。

### 2.1 普通构建（干净重建，最常用）

```powershell
cd C:\Users\linckr\NRF52xxx-FieldTemp
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe tools\build_sysbuild.py build-v9
```

> 脚本内部已经固定 `-p always`（pristine）+ `-b nrf52dk/nrf52810` + `--sysbuild`，
> 因此**这条命令就是 pristine 构建**，无需再单独执行 pristine。

### 2.2 显式 pristine / clean

```powershell
# pristine（脚本默认行为，等价写法）
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe tools\build_sysbuild.py build-v9

# 彻底删除构建目录（clean）
Remove-Item -Recurse -Force .\build-v9
```

### 2.3 MCUboot sysbuild

MCUboot **总是**跟着 App 一起被 sysbuild 构建，产物在
`<build_dir>\mcuboot\zephyr\`。不需要单独构建 MCUboot。
只有在你修改 `sysbuild/mcuboot.conf` 或 `sysbuild/mcuboot.overlay` 后，
重新执行 §2.1 即可。

单独查看 MCUboot 产物：

```powershell
Get-ChildItem C:\Users\linckr\NRF52xxx-FieldTemp\build-v9\mcuboot\zephyr\zephyr.*
```

### 2.4 signed firmware 构建

**签名的产物是同一个命令自动产出的**，无需手工 `imgtool sign`：

```powershell
# 构建完成后，签名镜像已经在这里
Get-Item C:\Users\linckr\NRF52xxx-FieldTemp\build-v9\NRF52xxx-FieldTemp\zephyr\zephyr.signed.bin
```

如需**独立校验**签名（不修改任何产物）：

```powershell
$env:PYTHONPATH = "C:/ncs/v3.2.1/bootloader/mcuboot/scripts"
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe -m imgtool.main verify `
  --key $env:MCU_BOOT_SIGNING_KEY `
  build-v9\NRF52xxx-FieldTemp\zephyr\zephyr.signed.bin
# 期望输出：Image was correctly validated / Image version: 1.0.8+0
```

### 2.5 查看构建产物

```powershell
$B = "C:\Users\linckr\NRF52xxx-FieldTemp\build-v9"
Get-ChildItem $B\NRF52xxx-FieldTemp\zephyr\zephyr.bin, `
              $B\NRF52xxx-FieldTemp\zephyr\zephyr.signed.bin, `
              $B\NRF52xxx-FieldTemp\zephyr\zephyr.elf, `
              $B\mcuboot\zephyr\zephyr.hex, `
              $B\merged.hex, `
              $B\dfu_application.zip |
  Select-Object Name, Length, LastWriteTime
```

### 2.6 查看 memory summary

```powershell
# 方式一：生成 Zephyr 的 ram/rom 报告（在构建目录里跑 ninja）
cd C:\Users\linckr\NRF52xxx-FieldTemp\build-v9\NRF52xxx-FieldTemp
C:\ncs\toolchains\66cdf9b75e\opt\bin\ninja.exe ram_report
C:\ncs\toolchains\66cdf9b75e\opt\bin\ninja.exe rom_report

# 方式二（推荐）：项目的体积门禁，一次给出 A–J 共 14 项判定
cd C:\Users\linckr\NRF52xxx-FieldTemp
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe tools\size_summary.py build-v9

# 版本/发布门禁
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe tools\release_gate.py build-v9
```

> `size_summary.py` 也可直接给 sysbuild 根目录，会自动下钻到应用镜像目录。
> 加 `--json` 便于 CI 消费。

### 2.7 构建偶发失败

`ar.exe Permission denied` 之类的中断，用增量续跑即可，不必删目录重来：

```powershell
C:\ncs\toolchains\66cdf9b75e\opt\bin\cmake.exe --build C:\Users\linckr\NRF52xxx-FieldTemp\build-v9
```

---

## 3. 引导加载相关：`VERSION` 与发布门禁

- **改行为必须抬版本**：只改仓库根 `VERSION`（`PATCHLEVEL`）。
  Zephyr 会自动生成 `app_version.h`，并让
  `CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION` 取 `APP_VERSION_TWEAK_STRING`。
- `VERSION` 缺失会**静默**退回 `0.0.0+0` —— `tools/release_gate.py` 的 R1 项专门拦这个。
- 手工 `imgtool --version x.y.z` 产出的 `zephyr.signed.vN.bin` **不是发布对象**（R4 项会拦）。

```powershell
# 发布确认后推进基线（仅在你确实要把它当作"上一生产版本"时执行）
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe tools\release_gate.py build-v9 --promote
```

---

## 4. 烧录与调试（DAPLink / CMSIS-DAP + pyOCD）

pyOCD 装在**项目专用 venv**里（与 bleak 同一个解释器，专供 BLE + SWD 联调脚本）：

```powershell
$PYOCD = "C:\Users\linckr\.workbuddy\binaries\python\envs\default\Scripts\pyocd.exe"
```

| 包 | 版本 |
|---|---|
| pyocd | 0.45.1 |
| cmsis-pack-manager | 0.6.0 |
| bleak | 3.0.2 |

### 4.1 连接 / 确认探针

```powershell
& $PYOCD list
# 期望看到：0  jixin.pro CMSIS-DAP_LU  LU_2022_8888  n/a
```

### 4.2 烧录（**首次/恢复用 `merged.hex`**）

```powershell
$HEX = "C:\Users\linckr\NRF52xxx-FieldTemp\build-v9\merged.hex"
& $PYOCD flash -t nrf52810_xxaa --frequency 500k $HEX
```

`--frequency 500k` 是为了链路稳定（长排线/劣质杜邦线时尤其必要）。

### 4.3 复位

```powershell
& $PYOCD reset -t nrf52810_xxaa
```

### 4.4 调试（GDB server）

```powershell
& $PYOCD gdbserver -t nrf52810_xxaa --frequency 500k --port 3333
# 另一个终端用 arm-zephyr-eabi-gdb 连 localhost:3333
```

### 4.5 ⚠️ pyOCD 的两个致命坑

1. **连接即 halt 目标核。** 读 RAM 得到的是冻结快照，会假造出"采样停摆"。
   读运行态前先查 `DHCSR(0xE000EDF0)` 的 `S_HALT`(bit17)，用完 `t.resume()` 恢复。
2. **`tools/w25q64_host_dfu.py` 的 `read` / `info` 会 reset-and-halt**，
   用完**必须手动 resume**，否则设备不广播、会被误判为"固件坏了"。

另外：**主机侧 poke SPIM0 直读外置 Flash 是侵入式操作**（会覆盖 App 的 SPI 驱动寄存器）。
必须全程保持 halt（`with probe.held():`），只作最后一步、只读一次、读完立即复位。
**不要**进常规回归。

---

## 5. 四个产物的区别（**最容易搞错的地方**）

| 文件 | 内容 | 用在哪 |
|---|---|---|
| `zephyr.bin` | **裸应用镜像**（无 MCUboot 头、无签名） | 只用于比对/分析；**不能**用于 OTA |
| `zephyr.signed.bin` | 应用镜像 + MCUboot 头(512 B) + TLV(SHA-256/KEYHASH/**ECDSA256 签名**) | ✅ **现场 OTA（BLE）就传它** |
| `merged.hex` | **MCUboot + 主槽**（带绝对地址的 Intel HEX） | ✅ **首次 SWD 烧录 / 救砖** |
| `dfu_application.zip` | 供 MCUboot **serial recovery** 的容器（含 manifest + 镜像） | ❌ 当前未启用 serial recovery，用不上 |

参考大小（`build-pub`，2026-09-14；签名 TLV 长度可能逐次变化，应以当前构建产物为准）：

| 产物 | 大小 |
|---|---|
| `zephyr.bin` | 149,528 B |
| `zephyr.signed.bin` | 150,191 B |
| `merged.hex` | 512,122 B |
| `dfu_application.zip` | 150,996 B |
| `mcuboot/zephyr/zephyr.bin` | 31,876 B |

---

## 6. 常用工具（`tools/`）

| 工具 | 用途 |
|---|---|
| `build_sysbuild.py` | 引号安全的 sysbuild 构建包装（**唯一推荐构建入口**） |
| `size_summary.py` | 体积/布局门禁 A–J（真 CI 门禁，FAIL 返回非零） |
| `release_gate.py` | 发布门禁 R1–R5（版本单一源、无手工签名残留） |
| `stack_hwm.py` | 0xAA 扫描线程栈高水位（**必须传 `--config`**，否则 MPU 守卫区会误报 100%） |
| `ram_breakdown.py` | RAM 构成分解（注意：`nm` 不加 `-t` 时地址与尺寸**都是十六进制**） |
| `size_diff.py` | 两个构建的 ROM/RAM 差异对比 |
| `ota_host_client.py` | PC 侧 BLE OTA 客户端（需要装了 bleak 的解释器） |
| `ota_mkbadsig.py` | 造"坏签名"镜像（只翻 ECDSA 签名 TLV 1 字节） |
| `ota_powerloss_reset.py` | 断电专项 |
| `w25q64_host_dfu.py` | 主机侧外置 Flash 读写（**有运行扰动**，见 §4.5） |
| `free_ble.py` | **PC 侧 BLE 测试前必用**：确保手机让出 BLE（`am force-stop` 不够） |
| `verify_v6_timing.py` | 记录周期与事件限流验证 |
| `verify_v7_wallclock.py` | 墙钟整分对齐三项测试 |
| `dfu_e2e_verify.py` | 写次级槽 + trailer → 复位 → 逐字节比对主槽 |

需要 **bleak** 的脚本（`ota_host_client.py` / `verify_v7_wallclock.py` / `free_ble.py`）
必须用同时装了 bleak 与 pyocd 的解释器：

```powershell
C:\Users\linckr\.workbuddy\binaries\python\envs\default\Scripts\python.exe tools\ota_host_client.py --file <zephyr.signed.bin>
```

---

## 第二部分：Android App

## 7. 环境清单（全部读自项目实际配置）

| 项目 | 值 | 来源 |
|---|---|---|
| Android Studio | `AI-261.26222.65.2613.16025427`（build `261.26222.65.2613.16025427`） | `C:\Program Files\Android\Android Studio\product-info.json` |
| Gradle | **8.13** | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | **8.13.2** | `gradle/libs.versions.toml` |
| Kotlin | **2.0.21** | 同上 |
| KSP | **2.0.21-1.0.27** | 同上 |
| Compose BOM | 2024.09.00 | 同上 |
| Room | 2.6.1 | 同上 |
| **JDK** | **17** | ⚠️ 见下方警告 |
| `compileSdk` | **36** | `app/build.gradle.kts` |
| `targetSdk` | **36** | 同上 |
| `minSdk` | **26** | 同上 |
| `applicationId` / `namespace` | `com.example.pandatemperature` | 同上 |
| `versionCode` / `versionName` | **1 / "1.0"** | 同上（**与固件版本无关**） |
| Java / Kotlin target | 11 | 同上 |
| 构建变体 | `debug` / `release` | 无 flavor |

### ⚠️ JDK 必须用 17

Android Studio 自带的 `jbr` 现在是 **JDK 25.0.2**，Gradle 8.13 **不支持**，
症状是构建失败且 `What went wrong:` 下面**只有一行版本号**（如 `25.0.2`），没有任何上下文。

本机可用的 JDK 17：

```powershell
$env:JAVA_HOME = "C:\Users\linckr\.workbuddy\binaries\jdk\jdk-17.0.20.1+1"
```

**不要**为了迁就 JDK 25 去升级 Gradle wrapper（会牵动 AGP 兼容性）。

### SDK 位置

```properties
# source/local.properties（已被 .gitignore 排除；下面是示例，实际为本机 SDK 路径）
sdk.dir=<你的 Android SDK 路径>
```

已安装：`build-tools/35.0.0`、`build-tools/36.0.0`、`platforms/android-36`。

---

## 8. 构建（PowerShell）

先设置两个环境变量（每次新开终端都要设）：

```powershell
$env:JAVA_HOME   = "C:\Users\linckr\.workbuddy\binaries\jdk\jdk-17.0.20.1+1"
$SRC = "C:\Users\linckr\Documents\Codex\2026-09-08\referenced-chatgpt-conversation-this-is-an\PandaThemperature-Android\source"
```

### 8.1 Debug 构建

```powershell
cd $SRC
.\gradlew.bat :app:assembleDebug --console=plain
```

### 8.2 Debug 单元测试

```powershell
cd $SRC
.\gradlew.bat :app:testDebugUnitTest --console=plain
# 报告：app\build\reports\tests\testDebugUnitTest\index.html
```

### 8.3 Release 构建

```powershell
cd $SRC
.\gradlew.bat :app:assembleRelease --console=plain
```

- 若存在 `source/keystore.properties` → 用正式签名；
- **不存在 → 回退 Android 默认调试密钥签名**（产物可 `adb install`，但**不可分发**）。
- ⚠️ 将来补齐正式签名后，因为签名不同，设备上的旧包**必须先卸载**才能安装。

### 8.4 AAB（上架用）

```powershell
cd $SRC
.\gradlew.bat :app:bundleRelease --console=plain
```

### 8.5 产物路径

| 产物 | 路径 |
|---|---|
| Debug APK | `source\app\build\outputs\apk\debug\app-debug.apk` |
| Release APK | `source\app\build\outputs\apk\release\app-release.apk` |
| Release AAB | `source\app\build\outputs\bundle\release\app-release.aab` |

### 8.6 安装到真机

```powershell
$ADB = "C:\Users\linckr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $ADB devices
& $ADB install -r -t -g "$SRC\app\build\outputs\apk\debug\app-debug.apk"
```

`-t` 允许 test-only 包，`-g` 直接授予已声明的运行时权限。

**国内 ROM（MIUI/澎湃、ColorOS 等）会拒绝 `adb install`**，报
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。这**不是**签名或 APK 问题，
需要在手机上手动开：**开发者选项 → USB 安装**（部分机型还要开「USB调试(安全设置)」）。
兜底路径：

```powershell
& $ADB push "$SRC\app\build\outputs\apk\debug\app-debug.apk" /sdcard/Download/
# 然后在手机上用文件管理器点安装（需允许「安装未知应用」）
```

---

## 9. Android 权限要求

声明在 `app/src/main/AndroidManifest.xml`：

| 权限 | 用途 | 生效范围 |
|---|---|---|
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | 旧版蓝牙权限 | **API ≤ 30** |
| `BLUETOOTH_SCAN`（`neverForLocation`） | 扫描 | **API ≥ 31** |
| `BLUETOOTH_CONNECT` | 连接/读写特征 | **API ≥ 31** |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 记录 GPS 坐标；**API ≤ 30 时扫描 BLE 必需** | 全版本声明 |
| `FOREGROUND_SERVICE` + `_CONNECTED_DEVICE` + `_LOCATION` | 后台保活前台服务 | API ≥ 29 / 34 分型 |
| `POST_NOTIFICATIONS` | 天气预警通知 | **API ≥ 33**（需运行时申请） |
| `VIBRATE` | 通知振动 | |
| `RECORD_AUDIO` | 工具箱「营地助手」风向检测 | 运行时申请 |
| `CAMERA` | 「营地回溯」相机 | 运行时申请 |

`uses-feature android:hardware.bluetooth_le required="true"`。

**BLE 权限兼容性要点**

- `minSdk = 26`：API 26–30 上 `BLUETOOTH_SCAN/CONNECT` 被忽略，
  扫描需要 `ACCESS_FINE_LOCATION` **且**系统定位开关打开；
  API 31+ 上反过来，`neverForLocation` 让扫描不再依赖定位权限。
- 组件：`MainActivity`（LAUNCHER）、`service.BleConnectionForegroundService`
  （`foregroundServiceType="connectedDevice"`，`exported="false"`）。
- ⚠️ Android 12+ 下 App 不在前台时启动前台服务会抛
  `ForegroundServiceStartNotAllowedException`（屏幕熄灭即算后台）——这是系统限制，
  不是 bug；点亮屏幕正常启动即可。

---

## 10. PC 侧做 BLE 测试前必读

Windows 上跑 `tools/ota_host_client.py` / `free_ble.py` 之前：

1. **`adb shell am force-stop` 不足以让手机让出 BLE**（App 前台保活会自动拉起并重连）。
2. 判据是**两个条件同时满足**：`pidof <pkg>` 为空 **且** 设备在广播
   （`CONFIG_BT_MAX_CONN=1` 下「在广播」等价于「未被连接」）。
3. 用 `tools/free_ble.py` 做这件事，退出码非 0 就不要开始测试，
   否则会把「连不上/掉线」误判成固件问题。
4. WinRT BLE 栈成功率约 1/6，**只重试 `connect()` 不够**
   （常见"连上立刻掉"，表现为 `start_notify` 抛 Not connected）
   → 必须把 `connect + start_notify + START` 作为**整体**重试。
5. 客户端脚本要加 `python -u`，否则重定向到文件时看不到进度。
