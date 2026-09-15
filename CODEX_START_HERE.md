# CODEX_START_HERE.md

> 新的接手者（人或 AI）请**从这里开始**。目标：读完这一页就知道项目是什么、
> 当前在哪、下一步做什么、以及去读哪份文档。

---

## Project Overview

三部分构成一个完整的现场测温系统：

- **Firmware**（`NRF52xxx-FieldTemp`）：跑在 **nRF52810**（E104-BT5010A 模组）上的
  Zephyr 应用。1 Hz 采样温湿度/气压，按周期取均值写入外置 **W25Q64**，通过 BLE 上报，
  并支持**自定义 BLE OTA** 现场升级。
- **Android App**（`PandaThemperature-Android`）：配套客户端。BLE 连接与自动重连、
  实时数据、历史同步与曲线、设备配置、**固件 OTA 升级**。
- **Hardware**：E104-BT5010A 模组 + W25Q64（SPI）+ AHT30/SPL06（I2C）+ DAPLink（SWD）。

关键机制：**MCUboot 双槽**。App 把 `zephyr.signed.bin` 写进 W25Q64 的次级槽，
重启后 MCUboot 做 **ECDSA-P256 验签**，通过才覆盖主槽。

---

## Repository Paths

```
Firmware : C:\Users\linckr\NRF52xxx-FieldTemp
Android  : C:\Users\linckr\Documents\Codex\2026-09-08\referenced-chatgpt-conversation-this-is-an\PandaThemperature-Android
           （Gradle 工程在其中的 source/ 子目录）
```

> 上面是**当前开发机**的路径。本套文档里的命令都按这些路径写成**可直接复制执行**的形式；
> 换机器/换目录时需要自行替换。仓库本身不依赖任何绝对路径（构建脚本已把本机路径全部移除）。

远端：`https://github.com/linckr/NRF52xxx-FieldTemp` 与
`https://github.com/linckr/PandaThemperature-Android`
（两者的 `upstream` 都是 yodfz 上游，**推送时注意别推错**）。

---

## 已真机验证的代码基线 commits

| 仓库 | branch | commit |
|---|---|---|
| Firmware | `main` | **`b97088d`** = 已真机验证的固件功能基线；后续文档与清理提交不代表已真机回归 |
| Android | `main` | **`f86f670`** = 上次真机联调的 App 功能基线；后续提交需分别验证 |

> 上述 commit 是**真机验证基线**，不是持续更新的 HEAD。当前 HEAD 请在两个仓库各自运行 `git log -1` 核对；2026-09-15 的配置/文档清理仅完成静态构建与门禁，未重新烧录真机。

固件版本号：**`1.0.8+0`**（来源：仓库根 `VERSION`）。
App 版本：`versionCode 1` / `versionName "1.0"`（**与固件版本无对应关系**）。

---

## Current Status

**已真机验证**：BLE 实时数据、历史写入与回读、时间同步 + 墙钟整分对齐、
记录周期 60.1 s、事件限流、**OTA 完整闭环**（含断点续传、错误密钥/超容量拒绝、
MCUboot 拒绝坏签名）、电池电压端到端、全路径栈高水位。

**2026-09-14 交接批次已真机验证**（两轮连续 OTA，判据 = 主槽 `img_size`）：

| 轮次 | 推送镜像 | 主槽 `ih_img_size` | 结果 |
|---|---|---|---|
| 基线 | — | 149,528 | — |
| 1 | `build-stk8`（149,560 B） | **149,528 → 149,560** | ✅ 上传/END/TRIGGER/搬运全通过 |
| 2 | `build-verify2`（发布镜像，149,528 B） | **149,560 → 149,528** | ✅ 恢复到发布镜像，设备健康 |

两轮 `ih_ver` 都是 `1.0.8+0` —— **版本号没变、二进制变了**，正是"不能只看版本号"的实例；
判决全部依赖主槽内容。OTA 后 BLE 健康检查：状态帧 12 B、能力位 `0x3F`、实时帧 8 B、
电压 3.324 V、记录数 4167，`wallclock=0`（本次上电无手机对时，符合设计）。

**尚未验证**：
1. **VDD 是否等于电池电压**（当前读数疑似调试器稳压 3.3 V）。
2. App 提交 `f86f670` 的**手机端 OTA 客户端**还没在固件 `1.0.8+0` 上跑过真机
   （本轮回归用的是 PC 侧 `tools/ota_host_client.py`）。

**当前最紧的两个约束**：App RAM 余 **1,624 B**；MCUboot 余 **892 B**。

---

## Build

**Firmware**（PowerShell；必须先给签名私钥路径，脚本没有默认值）：

```powershell
$env:MCU_BOOT_SIGNING_KEY = "D:\keys\root-ec-p256.pem"
cd C:\Users\linckr\NRF52xxx-FieldTemp
C:\ncs\toolchains\66cdf9b75e\opt\bin\python.exe tools\build_sysbuild.py build-v9
```

> ⚠️ 不要直接 `west build`：PowerShell 会剥掉 `-DSB_CONFIG_BOOT_SIGNATURE_KEY_FILE="..."`
> 的内层引号，构建会以 `malformed string literal` 中止。
> ⚠️ `tools/build_sysbuild.py` 刻意**没有默认私钥路径**；没有签名私钥就不能构建发布镜像。

**Android**（PowerShell；JDK 必须是 17，Android Studio 自带的是 JDK 25，Gradle 8.13 不支持）：

```powershell
$env:JAVA_HOME = "C:\Users\linckr\.workbuddy\binaries\jdk\jdk-17.0.20.1+1"
cd C:\Users\linckr\Documents\Codex\2026-09-08\referenced-chatgpt-conversation-this-is-an\PandaThemperature-Android\source
.\gradlew.bat :app:assembleDebug --console=plain
```

### Windows 工作站已知问题（2026-09-15 已验证）

**Firmware Git 显式 Deny ACL**

- 症状：工作树文件可编辑，但 Git 创建 `.git/index.lock` 时返回 `Permission denied`；
  `Get-Acl .git` 能看到旧沙箱 SID 的显式 `Deny` ACE。
- 根因：Codex 受限沙箱在工作区外访问仓库时留下或重新注入隔离 ACL，不是仓库对象损坏。
- 修复原则：用正常 Windows 用户权限，只移除已核对的旧 SID `Deny` 规则；不要对整个 `.git`
  执行 `icacls /reset`。写入探针通过后，先确认工作树与 `origin/main` 内容一致，再更新 HEAD / index
  和分支跟踪关系。
- 本次结果：`C:\Users\linckr\NRF52xxx-FieldTemp` 已对齐 `origin/main`，`main` 正确跟踪
  `origin/main`，工作区 clean，目标 `Deny` ACE 为 0。
- 防止复发：在 Codex 中把 Firmware 仓库本身作为可写 workspace root 打开；否则 Git 操作应在正常
  用户权限下执行。受限沙箱再次访问工作区外的 `.git`，可能重新注入隔离 ACL。

**Android Gradle / Java ZipFS `AccessDeniedException`**

- 症状：Kotlin/KSP 可以完成，但 `compileDebugJavaWithJavac` 在关闭 Gradle transform JAR 时抛
  `java.nio.file.AccessDeniedException` / `GeneratedClassCompilationException`。
- 根因：Codex 受限沙箱与 Java ZipFS 的文件访问冲突；复制 JDK、Gradle distribution 或缓存到新目录
  不能解决。该错误不表示源码、JAR 或 Gradle 缓存损坏。
- 处理：使用上面的 JDK 17，在正常 Windows 用户权限下运行：

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug --no-daemon --console=plain
```

- 本次验证：`BUILD SUCCESSFUL`，47 个任务完成；14 个测试套件、97 项测试全部通过，
  0 failure / 0 error / 0 skipped；`app-debug.apk` 成功生成。现有 deprecated API 警告不影响构建。

---

## Flash

**首次 / 救砖：烧 `merged.hex`（含 MCUboot + 主槽）**

```powershell
$env:JAVA_HOME = "C:\Users\linckr\.workbuddy\binaries\jdk\jdk-17.0.20.1+1"
$PYOCD = "C:\Users\linckr\.workbuddy\binaries\python\envs\default\Scripts\pyocd.exe"
& $PYOCD flash -t nrf52810_xxaa --frequency 500k `
  "C:\Users\linckr\NRF52xxx-FieldTemp\build-v9\merged.hex"
```

> ⚠️ pyOCD 连接会 **halt 目标核**，读完 RAM 记得 `resume`。
> ⚠️ `pyocd erase --chip` / MCU mass erase 会清除 **nRF52810 内部 Flash**（包括 MCUboot 和 App），
> 因此正常升级流程不要使用；它**不会**自动擦除外置 W25Q64。
> 真正禁止的是**通过应用、host DFU 工具或自定义脚本对 W25Q64 整片执行 chip erase**，
> 因为 `0x28000` 以后包含 NVS 和历史数据（且没有备份）。

---

## OTA

Android App **必须发送 `zephyr.signed.bin`**（不是 `zephyr.bin`、不是 `merged.hex`）：

```
Android → BLE（服务 12340050，Control/Data/Status 三特征）
        → 写入 W25Q64 次级槽 [0x00000, 0x28000)   ← 160 KiB
        → TRIGGER → 设备重启
        → MCUboot 读次级槽 trailer → ECDSA-P256 验签
        → overwrite primary [0x08000, 0x30000)     ← 163,840 B
        → 新固件启动
```

- 验签失败 → **不搬运**，设备继续跑旧固件（overwrite-only 下不会变砖）。
- 传错文件 → 设备报 `err=6 MAGIC`；App 侧 `OtaImageParser` 会在发送前就拒掉。
- 升级发生在**外置** Flash，主槽只是被覆盖，不会碰 NVS 与历史区。

---

## Architecture Constraints

（完整清单见 `HANDOFF.md` §5，这里只列最要紧的）

1. **MCUboot 分区 = 32 KiB**，已用 31,876 B（97.28%）。**不要再往 MCUboot 加功能**。
2. **主槽 `0x08000`–`0x30000`（163,840 B）**，App 实际可用 `0x08200`–`0x30000`。
3. **次级槽 `0x00000`–`0x28000`（160 KiB，外置 W25Q64）**，**必须与主槽等大**。
4. **NVS `0x28000`–`0x2E000`**（外置）。
5. **history `0x2E000`–`0x12E000`（1 MiB，外置）**，与上面两段**不得重叠**。
6. **ECDSA-P256** 签名；换私钥 = 必须**重烧 MCUboot**。
7. **签名私钥不进仓库**：由 `MCU_BOOT_SIGNING_KEY` 或 `--signing-key` 传入。
8. **`OTA_AUTH_KEY` 不是安全边界**：它只防误触/DoS，真正拦住恶意固件的是 MCUboot 验签。
   它同样不进仓库（固件 `src/ble/ota_auth_key.h`、App `source/ota.properties`，均为 gitignore）。
9. `SPI_NOR_FLASH_LAYOUT_PAGE_SIZE` 在 App 与 MCUboot **两侧都必须是 4096**。
10. 改引脚要**同时**改 `app.overlay` 与 `sysbuild/mcuboot.overlay`。

---

## Current Next Task

**P0（已✅完成）：2026-09-14 交接批次已按 `构建 → size/release gate → 真机 OTA 回归 → 提交 → push`
的顺序落地，两轮真机 OTA 全部通过（证据见上方 Current Status）。**

**下一步是 P1：验证「VDD 是否等于电池电压」。**

为什么优先做它：当前读数恒定在 3325~3336 mV、极差仅 11 mV、OTA 重载下也不跌，
**几乎肯定是调试器供的稳压 3.3 V**，而不是电池。如果结论是"不相等"，
这个功能对用户就是误导，必须重新设计或改标注。

做法：拔掉调试器供电、让板子由电池供电但**保留 SWD**（不发 reset），直接读 RAM 里的
`g_vdd_mv` / `g_vdd_min_mv` / `g_vdd_max_mv`（符号地址随构建变化，用 `nm` 从
`build-verify2/.../zephyr.elf` 现取）。⚠️ pyOCD 连接会 halt 核，读完记得 `resume`。

之后按优先级依次是 P2（历史含电压的能力位驱动）、P3（断电专项矩阵）。P4 的低风险技术债
已于 2026-09-15 清理完成；详情见 `HANDOFF.md` §4。
完整任务清单（含每项的涉及文件 / 不能破坏的接口 / 验证方式 / 完成条件）见 `HANDOFF.md` §4。

---

## Read Next

| 文档 | 什么时候读 |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | **必读第二篇**。状态、任务、禁止改动项、代码地图、技术债、一致性检查 |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | 要构建 / 烧录 / 调试时（含可直接复制的 PowerShell 命令） |
| [`HARDWARE.md`](HARDWARE.md) | 要碰引脚或分区时（含完整分区表与"为什么必须写在这里"） |
| [`OTA.md`](OTA.md) | 要碰升级链路、签名、镜像格式时 |
| [`PROTOCOL.md`](PROTOCOL.md) | 要改 BLE 协议或排查两端不一致时（byte-level 表） |
| `docs/`（仓库内） | ⚠️ **上游历史设计文档**，描述的是旧方案，**不要当当前实现读** |

新接手者的第一条纪律：**文档与代码冲突时以代码为准**，并把冲突补记到 `HANDOFF.md` §12。
