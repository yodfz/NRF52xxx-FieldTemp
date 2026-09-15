# HANDOFF.md — 工程交接总文档

> **给接手者**：本文以**当前本地仓库实际代码 + 最近一次构建产物**为准写成，
> 不是设计稿。若本文与代码冲突，**以代码为准**，并把冲突记到 §12。
> 第一次接触本项目请先读 `CODEX_START_HERE.md`。

---

## 1. 项目概况

| 项目 | 说明 |
|---|---|
| 固件用途 | 蓝牙低功耗**温湿度/气压采集记录仪**。1 Hz 采样，按可配置周期（默认 60 s）取均值落盘到外置 Flash，支持 BLE 实时上报、历史批量回传、时间同步、极值统计、电池电压上报，以及**自定义 BLE OTA 现场升级** |
| Android App 用途 | 设备端配套客户端：BLE 连接与自动重连、实时数据展示、历史同步与曲线、设备配置、固件 OTA 升级界面；另有若干工具箱功能（营地助手、营地回溯、天气模块） |
| 固件仓库（本地） | `C:\Users\linckr\NRF52xxx-FieldTemp` |
| 固件仓库（远端） | `https://github.com/linckr/NRF52xxx-FieldTemp`（`upstream` = `https://github.com/yodfz/NRF52xxx-FieldTemp`） |
| App 仓库（本地） | `C:\Users\linckr\Documents\Codex\2026-09-08\referenced-chatgpt-conversation-this-is-an\PandaThemperature-Android` |
| App 仓库（远端） | `https://github.com/linckr/PandaThemperature-Android`（`upstream` = `https://github.com/yodfz/PandaThemperature-Android`） |
| 硬件型号 | 模组 **E104-BT5010A**（亿佰特） |
| MCU | **nRF52810**（QFAA，Cortex-M4 @ 64 MHz，192 KiB Flash / 24 KiB RAM） |
| 外部 Flash | **W25Q64**（Winbond SPI NOR，8 MiB / 64 Mbit，JEDEC `ef 40 17`） |
| 当前 BLE 架构 | 单一从设备（`CONFIG_BT_MAX_CONN=1`，无配对无加密），4 个自定义 GATT 服务：配置 / 实时数据 / 清空数据 / OTA；时间同步特征 `12340011` 属于配置服务 |
| 当前 OTA 架构 | **自定义极简 BLE OTA**（非 mcumgr）：三特征（Control/Data/Status），分片写入外置 Flash 的 MCUboot 次级槽，支持断点续传，重启后由 MCUboot 验签并覆盖主槽 |
| MCUboot 的作用 | 启动时校验主槽（`BOOT_VALIDATE_SLOT0=y`）；检测次级槽 trailer magic 触发升级；**用 ECDSA-P256 验签**，通过才把镜像从次级槽覆盖到主槽。它是 OTA 的**真正安全边界** |
| App ↔ 固件关系 | 通过上述 GATT 协议通信。OTA 时发送的文件**必须是** `zephyr.signed.bin`。两侧的 OTA 授权密钥必须一致（各自经 gitignore 文件注入） |

---

## 2. 当前真实状态

> 严格区分「编译通过」与「真机验证通过」。**没有真机验证的一律不算通过。**

### 2.1 已完成并**实际真机验证**

| 项 | 证据 |
|---|---|
| BLE 连接、实时数据上报、状态帧读取 | 手机 App 实测（`PandaTemp_7086 (v8) 3.3V`，帧 `5F 0B 0B 17 72 27 00 0D`） |
| 历史记录写入与回读、NVS 写头持久化 | 复位后写头正确恢复（183 → 184），历史未断 |
| 时间同步 + **墙钟整分对齐** | 三项硬件测试通过：12:34:27 同步 → 首条 12:35:00；60→300 s 落 5 分边界；复位前后写头一致 |
| 记录周期精度 | 实测 **60.1 s/条**（旧实现约 68 s） |
| 事件限流 | 合成 5 Hz 抖动下 130 s 仅 3 条（旧版约 130 条） |
| 外置 W25Q64 连通性 | JEDEC ID 回读 `ef 40 17` |
| **OTA 完整闭环** | MTU 128 协商成功；完整上传 → END 校验通过 → MCUboot 搬运 → 重启后正常广播；主槽 `img_size` 由 144,336 变为 144,272（即次级槽内容），证明真的换了镜像 |
| **OTA 断点续传** | 中途断连后重新 START，设备回报 `confirmed=108,800`（已对齐 256 B），从 75.1% 续传，只补发 36,136 B |
| **OTA 负向用例** | 超容量 → `err=3`（**擦除前**拒绝）；错误密钥 → `err=1`；坏 SHA → `err=8` 且**不重启** |
| **MCUboot 拒绝坏签名** | 把签名 TLV 翻 1 字节后 TRIGGER，**主槽 `img_size` 未变**且设备健康 |
| 电池电压端到端 | 手机界面显示 `3.3V`，帧 offset 6 = `0x0D00`（3328 mV） |
| 栈高水位（含 OTA 全路径） | RX 峰值 648/1024（空闲 376）、workq 968/1280、main 696/1024、mpsl 400/640、ISR 600/1024，全部 ≥128 B |
| **2026-09-14 交接批次的真机 OTA 回归** | 两轮连续 OTA，判据 = 主槽 `img_size`：<br>· 轮 1 推 `build-stk8`（149,560 B）→ 主槽 **149,528 → 149,560** ✅<br>· 轮 2 推 `build-verify2`（发布镜像，149,528 B）→ 主槽 **149,560 → 149,528** ✅（设备恢复发布态）<br>两轮 `ih_ver` 都是 `1.0.8+0` —— **版本号没变、二进制变了**，判决全部依赖主槽内容。OTA 后 BLE 健康检查：状态帧 12 B / 能力位 `0x3F` / 实时帧 8 B / 电压 3.324 V / 记录数 4167 |

### 2.2 已完成并通过**编译/构建验证**（无真机验证）

| 项 | 说明 |
|---|---|
| `.gitignore` 私钥规则收窄 | `git check-ignore` 逐条验证生效 |
| Android 侧密钥外置（`ota.properties` → `BuildConfig`） | `testDebugUnitTest` + `assembleDebug` 全绿；另验证「删除 `ota.properties` 后构建与测试仍通过」 |
| `imgtool verify` | 正向通过（`Image was correctly validated` / `1.0.8+0`）；负向用无关密钥 → `No signature found for the given key` |

> `tools/build_sysbuild.py` 的 `--signing-key` / `MCU_BOOT_SIGNING_KEY` 改造**已随 §2.1 的两轮真机 OTA 一并验证**（`build-verify2` 就是用新参数构建的）。

### 2.3 已实现但**尚未真机验证**

| 项 | 说明 |
|---|---|
| 电压读数是否等于**电池**电压 | 当前读数 3325~3336 mV、极差仅 11 mV、OTA 重载下也不跌 → 几乎肯定是**调试器供电的稳压 3.3 V**。判定需拔掉调试器供电、让板子跑电池但保留 SWD |
| **手机端** OTA 客户端在固件 `1.0.8+0` 上的真机跑测 | 本批回归用的是**PC 侧** `tools/ota_host_client.py`；App（`f86f670`）的手机端 OTA 流程上一次真机联调是在该提交之前的代码上做的 |

### 2.4 已部分实现

| 项 | 现状 |
|---|---|
| 历史记录含电压字段 | 固件**未实现**（始终 12 B，无电压）；Android 侧 `HistoryRecordFormat.V3`(14 B) 与 `ThermometerV3Profile` 是**预留能力**，当前不被任何固件选中，且明确禁止用版本号/包长推断 |
| `mcuboot_serial`（串口救砖） | 配置存在但**未启用**；`dfu_application.zip` 已生成但用不上 |
| LTR-390UV / SHT40 驱动 | 代码在 `src/sensors/`，**主流程未调用** |

### 2.5 尚未完成

| 项 | 说明 |
|---|---|
| App OTA 客户端与服务端**双向确认**的自动化回归 | 目前靠人工跑 `tools/ota_host_client.py` 与手机操作 |
| 生产签名密钥 | 当前只有开发密钥，**没有**生产密钥；切换需重烧含新公钥的 MCUboot |
| 断电专项（OTA 中途断电）的完整矩阵 | 有 `tools/ota_powerloss_reset.py`，但覆盖的场景未穷举 |
| mcumgr 尺寸评估 | 结论倾向"nRF52810 装不下"（RAM 侧需 +2–4 KiB，而当前只剩 1,624 B），尚未正式出结论 |

### 2.6 已知 bug

**当前没有已确认的、可复现的功能性 bug。**

历史修复记录（避免重复踩坑）：

| 已修复 | 说明 |
|---|---|
| 固件版本号语义坑（Android） | `>= 3 ⇒ 14 字节历史` 的启发式会把新固件的 patch=6 误判成 V3，导致历史全部错位。已改为只看"是否有实时数据服务 / 有效版本号"，并有单测 |
| **`tools/ota_host_client.py` 把成功的 TRIGGER 判成失败** | **本批回归发现并修复**：TRIGGER 后设备立刻 `sys_reboot()`，链路必然断开；PC 客户端未捕获，导致每次成功升级都抛 `OSError [WinError -2147467260] 已中止操作` + traceback + 退出码 1。已按 `OTA.md` §5.5 / `PROTOCOL.md` §7 的口径改为**捕获断开并判成功**（`--only-trigger` 分支同步修复） |

### 2.7 已知风险

| 级别 | 风险 |
|---|---|
| High | **RAM 余量仅 1,624 B（93.39%）**。任何新功能（含继续用 mcumgr、加日志缓冲、加大包缓存）都极易把它打穿 |
| High | **MCUboot 只剩 892 B**。任何往 MCUboot 加功能（serial recovery、加密、shell）都会溢出 |
| High | **overwrite-only 无回滚**：升级过程中断电无法回退到旧固件（设计取舍，见 `OTA.md` §2.1） |
| Medium | 签名镜像相对 app 子区只剩 13,137 B（仅作偏保守参考；正式门禁以 primary slot 与 6 KiB 预留计算）。继续加 ROM 很快触红线 |
| Medium | `OTA_AUTH_KEY` 是**弱凭据**（公开可得），只防误触/DoS；若被恶意反复 START 会反复擦次级槽。真正的防线是 ECDSA 验签 |
| Low | 恢复出厂/换板时若只重烧 App、不清外置 Flash，历史写头可能与存量数据不一致 |
| Low | WinRT BLE 栈成功率约 1/6，联调脚本必须整体重试 |

### 2.8 技术债

| 项 | 说明 |
|---|---|
| `external_flash` 剩余区与业务 history | PM 自动生成的剩余区从 `0x2E000` 开始，业务 history 使用其前 1 MiB；禁止整区 `flash_area_erase/write` |
| 实时帧与历史记录的**气压单位不一致** | 实时帧 = 0.1 hPa；历史记录 = Pa。改任一侧都是**破坏兼容性**的改动，只能靠文档标注 |
| `MainViewModel.kt` 2753 行 | 体量大，职责偏多 |
| Android 历史遗留文档含本机绝对路径 | `source/docs/*.md` 里有多处 `C:\Users\linckr\...`（**无凭据**，且账号名已随仓库公开，判定为无害路径文本） |
| `tools/ota_host_client.py` 的 `--key` 与 `OTA_AUTH_KEY` 双入口 | 与固件/App 的单一注入入口相比略松，但保留了命令行覆盖能力，属可接受 |

---

## 3. 最近一次真机验证的功能基线

| 项目 | 值 |
|---|---|
| Firmware branch | `main` |
| Firmware commit | **`b97088d`** = 已真机验证的固件功能基线。后续文档与清理提交不代表已真机回归；当前 HEAD 用 `git log -1` 查 |
| Firmware commit 时间 | 2026-09-14 |
| Firmware 远端同步 | ✅ 已推送到 `origin/main` |
| Android branch | `main` |
| Android commit | **`f86f670`** = 上次真机联调的 App 功能基线；当前 HEAD 用 `git log -1` 查 |
| Android 远端同步 | ✅ 已推送到 `origin/main` |
| 两侧互相兼容的 commit | 固件 `b97088d` ↔ App `f86f670` 为真机功能基线。2026-09-15 清理仅修正 GATT Status 声明与死别名，UUID、报文及 OTA 命令布局不变；新 HEAD 的真机回归待做 |
| 当前 firmware version | **`1.0.8+0`**（`VERSION` 文件：MAJOR 1 / MINOR 0 / PATCHLEVEL 8 / TWEAK 0） |
| 当前 Android App version | `versionCode = 1`、`versionName = "1.0"`（**与固件版本无对应关系**） |
| 当前 BLE protocol version | **当前没有独立的 BLE protocol version** —— 靠状态帧 `byte5` 能力位与帧长做隐式能力判断，见 `PROTOCOL.md` §6 |
| 当前 OTA protocol version | **当前没有独立的 OTA protocol version**。OTA 服务首次出现在固件 `1.0.6+0`，此后字段布局未变（`1.0.8+0` 仍兼容），但**没有**任何版本字段可供协商 |
| MCUboot / image format 兼容状态 | 镜像格式 = MCUboot `ih_hdr_size=512` + TLV；签名 **ECDSA-P256**；升级模式 **overwrite-only**；`CONFIG_BOOT_MAX_IMG_SECTORS=128`。次级槽与主槽均为 `0x28000`。**更换签名私钥必须重烧 MCUboot** |

---

## 4. 下一步开发任务（按优先级）

> 每项都给出：为什么 / 涉及文件 / 已有基础 / 不能破坏的接口 / 如何验证 / 完成条件。

### P0 — ✅ **已完成**（2026-09-14 交接批次的真机回归与落地）

按用户规定的顺序执行完毕：

```
构建 → size/release gate → 真机 OTA 回归 → 提交 → push
```

**为什么是这个顺序**：这批改动原本只做到"构建验证、未真机验证"。先提交就等于把一个
还没上过板的状态写成新的稳定交接基线，后面一旦回归失败会污染历史、也会让接手者误以为它已验证。

**实际执行记录**：

| 步骤 | 结果 |
|---|---|
| 构建 | `tools/build_sysbuild.py --signing-key <pem> build-verify2` → **EXIT=0**；`zephyr.bin` 与 `build-v8` **逐字节一致**（sha256 `719b77b6…`） |
| size/release gate | `size_summary` **PASS（0 FAIL / 2 WARN：E MCUboot 892 B、G RAM 93.39%）**；`release_gate` **PASS（0 FAIL / 0 WARN）** |
| `imgtool verify` | 正向 `Image was correctly validated`（1.0.8+0）；负向用无关密钥 → `No signature found for the given key` |
| 真机 OTA 轮 1 | 推 `build-stk8`（149,560 B）→ 主槽 `ih_img_size` **149,528 → 149,560** ✅ |
| 真机 OTA 轮 2 | 推 `build-verify2`（149,528 B）→ 主槽 **149,560 → 149,528** ✅，设备恢复发布镜像 |
| OTA 后健康检查 | 状态帧 12 B / 能力位 `0x3F` / 实时帧 8 B / 电压 3.324 V / 记录数 4167 |
| 提交 + push | 见 §3 |

**顺带发现并修复的真实缺陷**：`tools/ota_host_client.py` 把成功的 TRIGGER 判成失败
（详见 §2.6）。这是这次回归的直接产出。

**未做的一步（有意为之）**：没有读外置 Flash 的次级槽 trailer（用于确认"无待激活升级"）。
该操作属侵入式（SPIM0 poke 会扰动 NVS 写头），且主槽内容变化已足以判决，故按
"只作最后一步、不进常规回归"的既有纪律跳过。

### P1 — 验证「VDD 是否等于电池电压」

- **要实现什么**：拔掉调试器供电，让板子由电池供电、保留 SWD，读 VDD。
- **为什么**：当前读数几乎肯定是调试器的稳压 3.3 V。若 VDD ≠ 电池电压，该功能的产品价值为 0（甚至误导用户）。
- **涉及文件**：`src/vdd.c`、`app.overlay` 的 `&adc`；测量用 pyOCD（`-M attach`，不发 reset，直接读 RAM 里的 `g_vdd_mv`，注意连接会 halt）
- **已有基础**：`vdd_sample_mv()` / `vdd_cached_mv()` / `g_vdd_min_mv` / `g_vdd_max_mv`（诊断用区间统计，见 `src/vdd.h`）
- **不能破坏**：实时帧长度 8、offset 6 的语义、`0xFFFF` 哨兵、量程 1700~3600 mV
- **如何验证**：电池从满电到欠压扫一遍，读值应随电池电压单调变化（而不是恒定 3.3 V）
- **完成条件**：给出"VDD 是否等于电池电压"的明确结论；若不等于，要么改测法，要么在文档/App 中明确标注该读数含义

### P2 — Android 端把「历史含电压」做成**能力位驱动**

- **要实现什么**：固件在状态帧能力字节新增一位（例如 `CAP_HISTORY_VOLTAGE 0x40`），历史记录扩展为 14 B；App 仅在**该能力位置位**时切换到 `HistoryRecordFormat.V3`。
- **为什么**：`ThermometerV3Profile` 已经是预留实现，但当前**没有任何能力位可依据**，只能用 V2。补齐后历史数据也能带电压。
- **涉及文件（固件）**：`src/main.c`（`STATUS_CHAR_LEN` / 能力位 / `status_char_build`）、`src/storage/w25q64.h`（`W25Q64_RECORD_SIZE`）、`src/main.c` 的记录构建与历史回读解析
- **涉及文件（App）**：`DeviceProfileFactory.kt`、`ThermometerV3Profile.kt`、`HistoryDataParser.kt`、`HistoryRecordFormat`
- **已有基础**：App 侧 `V3` 解析器已写好并有单测；固件侧记录构建集中在 `history_push_record()`
- **不能破坏**：⚠️ **这是跨版本不兼容改动**。固件一旦改记录大小，旧 App 会解析错位。
  必须：① 抬固件版本；② 只在能力位置位时 App 才换格式；③ **不得**用版本号或包长推断。
  另注意 `W25Q64_RECORDS_PER_PAGE`（21 条/页）会随记录大小变化，历史区容量口径要重算。
- **如何验证**：单元测试覆盖「能力位为 0 → V2 / 为 1 → V3」；真机同步历史后逐条核对时间戳与数值
- **完成条件**：新旧固件 × 新旧 App 的四种组合都能正确解析（旧 App 遇到新固件时应**安全降级**而不是错位）

### P3 — 断电专项矩阵

- **要实现什么**：把 OTA 各阶段（擦除中 / 写入中 / END 后 TRIGGER 前 / TRIGGER 后）分别断电，验证设备都能回到可用状态。
- **为什么**：overwrite-only 没有回滚，必须用实测说明"最坏情况是什么"。
- **涉及文件**：`tools/ota_powerloss_reset.py`、`tools/w25q64_host_dfu.py`
- **已有基础**：现有脚本能写次级槽 + trailer 并复位，然后逐字节比对主槽
- **不能破坏**：外置 Flash `0x28000` 之后的 NVS 与历史
- **如何验证**：每个阶段重复若干次，复位后检查：主槽是否仍可启动、次级槽 trailer 状态、NVS 写头是否一致
- **完成条件**：产出四个阶段的结论表，明确"哪些阶段断电会导致需要 SWD 救砖"

### P4 — 清扫技术债（已于 2026-09-15 完成低风险项）

已删除死 overlay、改用 PM 宏作为 secondary/NVS/history 基址事实源、清除重叠静态分区条目，
并修正 LED、MCU 型号和 `pressure_pa` 命名。完整 sysbuild、`size_summary.py` H/I 与发布门禁均通过；
地址、报文布局和 MCUboot 配置未改变。

---

## 5. 禁止随意修改的内容（架构约束）

| 约束 | 内容 | 为什么 |
|---|---|---|
| **MCUboot 分区** | `mcuboot` = `0x00000`–`0x08000`，**32 KiB** | MCUboot 已占 31,876 B（97.28%），余 892 B。缩小分区会溢出；任何加功能都会溢出 |
| **mcuboot_pad** | `0x08000`–`0x08200`，**512 B** | MCUboot 镜像头必须落在主槽起始处，去掉它主槽镜像头就没有落脚点 |
| **primary app slot** | `0x08200`–`0x30000`，**163,328 B** | 内部 Flash 只剩这些；主槽容器 `mcuboot_primary` 必须 `0x08000`–`0x30000` |
| **W25Q64 secondary slot** | `0x000000`–`0x028000`，**160 KiB**，且**必须与主槽等大** | overwrite-only 要求两槽尺寸一致（`SB_CONFIG_MCUBOOT_MODE_OVERWRITE_ONLY`）。改一个必须改两个 |
| **NVS 区域** | `0x028000`–`0x02E000`，**24 KiB** | 存写头/配置/时间基准/OTA 续传态。被覆盖 → 历史写头与 OTA 续传全部丢失 |
| **history/data 区域** | `0x02E000`–`0x12E000`，**1 MiB** | 业务自管，**不在 PM 里**，靠 `w25q64.h` 常量定位。与 NVS/次级槽**不得重叠** |
| **ECDSA-P256 签名算法** | `SB_CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y` | 换算法等于换镜像格式，需要重烧 MCUboot 与全部 App 侧工具 |
| **MCUboot 公钥 / 签名流程** | 公钥在构建期从私钥 PEM 提取并编进 MCUboot | 换私钥 = 换公钥 = **必须重烧 MCUboot**，否则设备拒绝所有新镜像 |
| **BLE OTA packet format** | START 严格 61 B（`op1 + key16 + total4 + ih_ver8 + sha256_32`）；Status 严格 12 B | 两端逐字段对齐，改任一字节都会让另一侧解析错位 |
| **Service UUID** | `12340020/30/40/50` 段 | App 的服务发现与降级路径依赖它们 |
| **Characteristic UUID** | `12340011/21/22/23/24/25/26/31/41/51/52/53` | 同上。`12340011`（时间同步）声明在配置服务内部 |
| **firmware image format** | magic `0x96F3B83D`、`ih_hdr_size=512`、`ih_img_size`@`0x0C`、`ih_ver`@`0x14` | MCUboot 与 App 侧校验都按此偏移解析 |
| **OTA 使用 `zephyr.signed.bin`** | App **必须**发 signed bin | 裸 bin 无头无签名；`merged.hex`/zip 格式不对。设备把字节原样写进次级槽，不做改造 |
| **App 与 MCUboot 对 W25Q64 的访问方式** | MCUboot 与 App 都经 `nordic,pm-ext-flash = &w25q64` + `jedec,spi-nor` 驱动；OTA 只经 `flash_area(mcuboot_secondary)` | 换访问方式会让两边对同一颗芯片的理解不一致；绕过分区 API 就会越过安全边界 |
| `SPI_NOR_FLASH_LAYOUT_PAGE_SIZE` = **4096** | App 与 MCUboot **两侧都要** | 默认 65536 与 W25Q64 真实 4 KiB 扇区不符，会让 MCUboot 擦 trailer 静默失败（`-EINVAL`），表现为"升级后镜像不搬运" |
| `CONFIG_IMG_MANAGER` 依赖 | 必须同时开 `CONFIG_STREAM_FLASH=y` | `IMG_MANAGER` 是 **depends on** 而非 select，不显式开会被静默丢弃 |
| 栈配置 | `MAIN_STACK_SIZE=1024`、`SYSTEM_WORKQUEUE_STACK_SIZE=1280`、`BT_RX_STACK_SIZE=1024`、`MPSL_WORK_STACK_SIZE=640` | 全部有实测高水位依据。**尤其不要把 BT_RX 降到 768**（峰值 648 时只剩 120 B） |
| 版本号唯一来源 | 仓库根 `VERSION` 文件 | 手改 Kconfig 版本号会让镜像版本与文档不一致 |
| 外置 Flash 保护 | 禁止对 **W25Q64** 整片 chip erase；禁止擦 `0x28000+` | NVS 与历史在里面，且**没有备份**。<br>⚠️ 注意与另一条区分：`pyocd erase --chip`（MCU mass erase）擦的是 **nRF52810 内部 Flash**（含 MCUboot 与 App），**不会**碰外接 W25Q64 —— 两者是不同的禁令，理由也不同 |

---

## 6. Firmware Code Map

```
NRF52xxx-FieldTemp/
├── VERSION                     # 镜像版本号唯一来源（1.0.8+0）
├── prj.conf                    # App Kconfig（每个符号只出现一次）
├── app.overlay                 # ⭐ 板级引脚 + W25Q64 + SAADC（唯一实际生效的 overlay）
├── pm_static.yml               # ⭐ Partition Manager 静态布局（唯一编辑入口）
├── sysbuild.conf               # sysbuild 级：MCUboot / overwrite-only / 外置次级槽 / ECDSA-P256
├── CMakeLists.txt              # 源文件清单（新增 .c 必须在这里登记）
├── sysbuild/
│   ├── mcuboot.conf            # MCUboot Kconfig（SPI NOR / 布局页 4096 / RC 32k / 关日志 / 栈 4096）
│   └── mcuboot.overlay         # MCUboot 的 devicetree（与 App 独立，改引脚要同步）
├── src/
│   ├── main.c                  # ⭐ 3166 行：采集调度、历史窗口结算、全部 GATT 服务定义与回调
│   ├── common.h                # struct data_record（12 B，历史记录格式）
│   ├── board_pins.h            # 引脚定义中心（LED/I2C/UART/SPI）
│   ├── led.c/.h                # LED 指示
│   ├── vdd.c/.h                # 电池电压（SAADC 内部 VDD，只读缓存给 BLE）
│   ├── ble/
│   │   ├── ble_adv.c/.h        # 广播与动态设备名（PandaTemp_XXXX）
│   │   ├── ble_services.c/.h   # 服务/UUID 头定义、历史传输上下文
│   │   ├── ble_ota.c/.h        # ⭐ 自定义 BLE OTA（803 行，异步工作队列模型）
│   │   ├── ota_auth_key.h      # 🔑 OTA 授权密钥（gitignore，不入库）
│   │   └── ota_auth_key.h.example  # 🔑 密钥模板（入库）
│   ├── storage/
│   │   ├── w25q64.c/.h         # ⭐ W25Q64 驱动 + 外置 Flash 布局常量（次级槽/NVS/历史基址）
│   │   └── nvs_config.c/.h     # ⭐ NVS：写头/配置/时间基准/版本宏（FIRMWARE_VERSION）
│   └── sensors/
│       ├── aht30.c/.h          # 温湿度（主用）
│       ├── spl06.c/.h          # 气压（主用）
│       ├── sht40.c/.h          # 温湿度备选（未使用）
│       └── ltr390.c/.h         # 紫外线（未使用）
├── tools/                      # 见 DEVELOPMENT.md §6（构建包装/门禁/OTA 客户端/栈扫描）
└── docs/                       # 上游历史设计文档（**非当前状态**，勿当实现说明读）
```

### 最常需要动的三个文件

| 需求 | 文件 |
|---|---|
| 改引脚 | `app.overlay` **和** `sysbuild/mcuboot.overlay` |
| 改分区 | `pm_static.yml` + `src/storage/w25q64.h`（两处必须同步） |
| 改 GATT 服务/帧格式 | `src/main.c`（服务定义与回调集中在这里） |

---

## 7. Android Code Map

App 工程根目录 = 仓库根的 `source/`（Gradle 工程在 `source/`，不在仓库根）。

### 7.1 关键类速查表

| 文件 | 类 / 对象 | 作用 |
|---|---|---|
| `MainActivity.kt` | `MainActivity` | 应用入口，Compose 宿主，挂载 `MainScreen` |
| `service/BleConnectionForegroundService.kt` | `BleConnectionForegroundService` | **BLE 连接保活前台服务**（`connectedDevice` 类型） |
| `data/bluetooth/BleManager.kt` | `BleManager` | ⭐ **BLE 入口**（单例，701 行）：扫描 / 连接 / 断开 / 服务发现 / 读写特征 / 订阅通知 / 请求 MTU / 连接优先级 |
| `data/bluetooth/BleConstants.kt` | `BleConstants` | ⭐ **全部 UUID 与设备名过滤常量的唯一出处**（含 OTA UUID、`HISTORY_END_FLAG`） |
| `data/bluetooth/BleFrameDiagnostics.kt` | `BleFrameDiagnostics` | 帧收发诊断（联调用） |
| `data/device/parser/RealtimeDataParser.kt` | `RealtimeDataParserV2` / `V1` | 实时帧解析：6 B 基础 + 可选 2 B 电压（哨兵 `0xFFFF` + 量程二道防线） |
| `data/device/parser/DeviceStatusParser.kt` | `DeviceStatusParser` | 状态帧（12 B）解析，含能力位与墙钟可信位 |
| `data/device/parser/HistoryDataParser.kt` | `HistoryDataParser` / `HistoryRecordFormat` | 历史记录解析，支持 V1(8)/V2(12)/V3(14) 三种布局 |
| `data/device/parser/MaxMinTempParser.kt` | `MaxMinTempParser` | 温度极值帧解析（4 B 旧 / 12 B 新） |
| `data/device/parser/DataParser.kt` | `DataParser<T>` | 解析器接口（`expectedMinLength` / `canParse` / `parse`） |
| `data/device/profile/DeviceProfileFactory.kt` | `DeviceProfileFactory` | ⭐ **Profile 选择**：只按"是否有实时数据服务/有效版本号"选 V1 或 V2，**禁止按版本号推断历史长度** |
| `data/device/profile/thermometer/ThermometerV2Profile.kt` | `ThermometerV2Profile` | 当前**唯一生效**的 Profile（12 B 历史） |
| `data/device/profile/thermometer/ThermometerV3Profile.kt` | `ThermometerV3Profile` | 预留能力（14 B 历史），**当前不被选中** |
| `data/device/model/RealtimeData.kt` | `ThermometerData` | 实时数据模型（含 `batteryVoltage` / `batteryVoltageReported`） |
| `data/device/model/BatteryVoltageDisplay.kt` | `BatteryVoltageDisplay` | 电压显示工具（无效值显示 `--`） |
| `data/model/DeviceStatus.kt` | `DeviceStatus` | 状态模型（含 `capabilityFlags` / `isWallClockTrusted` / `retentionDays`） |
| `data/ota/OtaConstants.kt` | `OtaConstants` | ⭐ OTA 协议常量；`DEFAULT_AUTH_KEY` 从 **`BuildConfig`** 读取 |
| `data/ota/OtaAuthKey.kt` | `OtaAuthKey` | 授权密钥文本编解码（16 字符 ASCII 或 32 位 hex） |
| `data/ota/OtaMessages.kt` | `OtaMessages` | Control 报文构造（START 61 B / END / CANCEL / TRIGGER） |
| `data/ota/OtaStatus.kt` | `OtaState` / `OtaError` / `OtaStatusParser` | 状态机与错误码枚举、Status 12 B 解析 |
| `data/ota/OtaImageParser.kt` | `OtaImageParser` | 发送前校验 magic 与容量，拒绝非 `zephyr.signed.bin` |
| `data/ota/OtaFlowController.kt` | `OtaFlowController` | ⭐ 流控与续传偏移计算（窗口 4 KiB，对齐 256 B） |
| `data/ota/OtaTransport.kt` | `OtaTransport` | 传输抽象接口（便于单测注入 Fake） |
| `data/ota/AndroidOtaTransport.kt` | `AndroidOtaTransport` | 把 `BleManager` 适配成 `OtaTransport` |
| `data/ota/OtaRunner.kt` | `OtaRunner` | ⭐ **OTA 主流程**：START→等 READY→分片→END→TRIGGER，含超时/重试/OVERRUN 续传 |
| `ui/viewmodel/FirmwareOtaViewModel.kt` | `FirmwareOtaViewModel` | ⭐ **OTA 入口（UI 侧）**：选文件、密钥输入（SharedPreferences `auth_key`）、进度、触发升级 |
| `ui/screen/FirmwareUpdateScreen.kt` | `FirmwareUpdateScreen` | 固件升级界面 |
| `ui/viewmodel/MainViewModel.kt` | `MainViewModel` | ⭐ 主 ViewModel（2753 行）：连接状态、数据订阅、历史同步、配置读写、日志 |
| `ui/screen/MainScreen.kt` / `SettingsScreen.kt` / `HistoryScreen.kt` 等 | 各 Screen | Compose 界面 |
| `data/database/AppDatabase.kt` + `dao/*` | Room | 本地历史记录持久化 |
| `data/weather/*` | Waps* | 天气模块（与固件无关） |
| `app/build.gradle.kts` | — | ⭐ **build 配置**：SDK 版本、`buildConfigField` 注入 OTA 密钥 |
| `source/ota.properties` | — | 🔑 OTA 授权密钥（gitignore，不入库） |
| `source/ota.properties.example` | — | 🔑 密钥模板（入库） |

### 7.2 各环节实现位置

| 环节 | 位置 |
|---|---|
| BLE 扫描 | `BleManager.startScan()` / `stopScan()`（按 `BleConstants` 的名称常量过滤） |
| 连接 | `BleManager.connect(device)` / `connect(address)`（首次连接后自动同步时间 + 历史） |
| 服务发现 | `BleManager` 内部（连接回调里 `discoverServices`）；`isCharacteristicAvailable(uuid)` 查询 |
| 通知订阅 | `BleManager.enableNotification(uuid, enable, onNotification)` 与 `enableNotificationAsync` |
| 普通数据解析 | `data/device/parser/*`，由 `DeviceProfile` 组合 |
| OTA 选文件 | `FirmwareOtaViewModel.loadImage(uri)` → `OtaImageParser.parse()` 前置校验 |
| OTA 传输 | `OtaRunner` + `AndroidOtaTransport` + `OtaFlowController` |
| OTA 认证 | `OtaAuthKey.parse()` → `OtaMessages.buildStart(authKey=...)` |
| 进度 | `OtaStatusParser` 解析 confirmed offset → `OtaFlowController.progressPercent()` |
| 超时 | `OtaRunner(statusTimeoutMs = 8_000, readyTimeoutMs = OtaConstants.START_READY_TIMEOUT_MS = 15_000)` |
| 重试 / 错误恢复 | `OtaRunner` 内的 OVERRUN 重试预算（`MAX_OVERRUN_RESTARTS = 2`）与整体重连（每次重新扫描） |
| TRIGGER 后断连 | `TRIGGER_DISCONNECT_GRACE_MS = 100` × 15 步，**应判为成功** |
| 固件版本显示 | `DeviceStatusParser` 取状态帧 byte6 → `MainViewModel` / 日志展示 |
| 权限 | `AndroidManifest.xml`（见 `DEVELOPMENT.md` §9） |

---

## 8. 已知技术约束（容量取自 2026-09-15 `build-conflict-fix`；构建目录本地忽略）

| 项目 | 值 |
|---|---|
| nRF52810 内部 Flash | 192 KiB（`0x30000`） |
| nRF52810 RAM | 24 KiB（`0x6000`） |
| MCUboot 分区 | 32 KiB（32,768 B） |
| **MCUboot 当前大小** | **31,876 B（97.28%）**，剩余 **892 B** |
| MCUboot RAM | **10,432 B / 24,576 B = 42.45%** |
| App 分区（app slot） | 163,328 B（`0x27E00`） |
| **App 当前 Flash 占用** | **149,528 B / 163,328 B = 91.55%**，剩余 **13,800 B** |
| **App 当前 RAM 占用** | **22,952 B / 24,576 B = 93.39%**，剩余 **1,624 B** |
| **本次 signed image 大小** | **150,191 B**（ECDSA DER 长度可能逐次变化）；红线 157,696 B（= 主槽 163,840 − 6 KiB 预留），本次余 **7,505 B** |
| BLE buffer 调优 | `BT_BUF_ACL_RX/TX_SIZE = 132`（= MTU 128 + 4）、`L2CAP_TX_MTU = 128`、`ACL_TX_COUNT = 4`、`L2CAP_TX_BUF_COUNT = 4`、`BT_BUF_CMD_TX_SIZE = 65`、`GATT_CACHING=n`、`ATT_PREPARE_COUNT=0` |
| stack size 调整 | main 1024、system workqueue 1280、BT RX 1024、MPSL work 640、ISR 1024、idle 128 |
| logging 当前状态 | **全关**：`CONFIG_LOG=n` |
| `CONFIG_PRINTK` | **`n`**（前提：`CONFIG_NCS_BOOT_BANNER=n` + `CONFIG_EARLY_CONSOLE=n`，否则会被 `select` 强制为 y） |
| UART console 当前状态 | **关**（`CONFIG_CONSOLE=n`、`CONFIG_UART_CONSOLE=n`、`CONFIG_SERIAL=y` 但无 console 后端） |
| RTT 当前状态 | **关**（`CONFIG_USE_SEGGER_RTT=n`、`CONFIG_RTT_CONSOLE=n`） |
| W25Q64 secondary 与 history | **不得重叠**：secondary `[0,0x28000)`、NVS `[0x28000,0x2E000)`、history `[0x2E000,0x12E000)`；均 4096 B 对齐 |
| NVS 地址 | **`0x28000`（外置 W25Q64）**。⚠️ **不得**改回内部 Flash 硬编码地址（原版就是那么做的，因为内部空间不够且与外置布局冲突） |
| App 与 MCUboot 的外置 Flash 定义 | 必须使用**兼容**的 devicetree（同一个 `nordic,pm-ext-flash` 节点）与**相同**的 `SPI_NOR_FLASH_LAYOUT_PAGE_SIZE=4096` |
| MCUboot 32 KiB 余量 | 仅 **892 B** —— 极小 |
| 不应轻易启用 | `serial recovery` / `shell` / `logging` / `mcumgr` / `CONFIG_ASSERT`：都会显著增加 MCUboot 或 App 尺寸 |

---

## 9. 安全检查结果

（详细扫描过程与命令见 §10；此处是结论）

| 问题 | 结论 |
|---|---|
| 是否存在 ECDSA **私钥** | **不存在于任何仓库**。私钥在仓库外的 `formal-sysbuild/root-ec-p256.pem`，路径已从脚本中移除，改由 `MCU_BOOT_SIGNING_KEY` / `--signing-key` 传入。两仓库历史中也从未出现 |
| 是否存在 Android 签名 keystore | **不存在**。仅有 `keystore.properties.example`（值 `CHANGE_ME`） |
| 是否存在真实 password | **不存在**。`storePassword`/`keyPassword` 只作为属性名出现在 `build.gradle.kts` 的读取代码里 |
| `OTA_AUTH_KEY` 现在存在于哪些地方 | ①固件 `src/ble/ota_auth_key.h`（**gitignore，未入库**）②App `source/ota.properties`（**gitignore，未入库**）③主机脚本由 `--key`/环境变量提供。仓库内**只有 `.example` 模板与占位值** |
| `OTA_AUTH_KEY` 是否影响真实固件安全 | **不影响最终安全边界**。真正的边界是 MCUboot ECDSA-P256 验签：知道密钥也无法让设备接受未合法签名的固件。它的作用是防误触/DoS |
| build script 是否还有私钥绝对路径 | **没有**。已删除硬编码路径，且不提供任何默认路径 |
| `.gitignore` 是否正确 | **正确**。私钥按目录/文件名精确忽略（保留公钥例外），构建产物/日志/`__pycache__`/本机报告全部忽略；`git check-ignore` 逐条验证过 |

---

## 10. Git 与仓库清理

### 10.1 2026-09-14 已提交内容（历史记录）

> ✅ **提交时机已满足**：这一批已按 §4 P0 跑完「构建 → 门禁 → 真机 OTA 回归（两轮通过）」，
> 并已提交推送。以下是该提交的内容清单。

**Firmware**
- `tools/build_sysbuild.py`（签名密钥解析改为 `--signing-key` / `MCU_BOOT_SIGNING_KEY`，移除本机路径与本机默认值）
- `tools/ota_host_client.py`（TRIGGER 后链路断开改判为成功；`--only-trigger` 同步修复）
- `tools/size_summary.py`（C1 文案区分「span 容器」与「本工具启发式伞形区」；**判定逻辑未变**）
- `.gitignore`（私钥规则收窄 + 公钥例外）
- `CLAUDE.md`（工程指令文件，事实性修正：**194KB → 192 KiB Flash / 24 KiB RAM**、`SLK` → `SCK`）
- `docs/2026-1-30优化方案.md`、`docs/GPIO引脚定义.md`（同一类事实性修正：194KB → 192 KiB）
- `README.rst`（顶部加交接入口 + 标注 LED 引脚描述与代码不符）
- 新增：`CODEX_START_HERE.md`、`HANDOFF.md`、`DEVELOPMENT.md`、`HARDWARE.md`、`OTA.md`、`PROTOCOL.md`

**Android**
- `source/.gitignore`（新增 `.env` / `*.env` / `secrets.properties` / `google-services.json`）
- `README.md` 重写为入口文档

> 如果这批改动需要再次验证：`build-verify2` 与 `build-stk8` 仍在本地（已被 gitignore），
> 可重放 §4 P0 的两轮流程。

### 10.2 不要提交

| 类别 | 例子 | 已忽略 |
|---|---|---|
| 固件构建产物 | `build/`、`build-v*/`、`build-ota*/`、`build-stk*/`、`build-pub/`、`build-verify*/`（合计约 750 MB） | ✅ `build/` + `build-*/` |
| 构建日志 | `build-*.log` | ✅ `*.log` |
| Python 缓存 | `tools/__pycache__/` | ✅ `__pycache__/` |
| 本机分析报告 | `ram_report.txt`、`rom_report.txt` | ✅ 已显式忽略 |
| 私钥 | `root-ec-p256.pem`、`*-priv.pem`、`*.key`、`keys/` | ✅ |
| OTA 授权密钥 | `src/ble/ota_auth_key.h` | ✅ |
| Android 构建产物 | `source/app/build/`、`.gradle/`、`.kotlin/` | ✅ `build/` + `**/build/` |
| Android 本地配置 | `local.properties`、`keystore.properties`、`*.jks`、`ota.properties` | ✅ |
| APK / AAB / IDE 临时文件 | `*.apk`、`*.aab`、`.idea/` | ✅（`build/` 覆盖） |

---

## 11. 逐项一致性检查结论（代码 ↔ 文档 ↔ 双端）

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 文档中的 Flash 地址是否与 `partitions.yml` 一致 | ✅ `HARDWARE.md` §8 三处交叉核对（`pm_static.yml` / `partitions.yml` / `pm_config.h`） |
| 2 | 文档中的 pin 是否与 overlay 一致 | ✅ `app.overlay`、`HARDWARE.md` 与 `README.rst` 已统一 |
| 3 | BLE UUID 是否与两端一致 | ✅ 两端逐条一致；`12340026` 只表示历史信息，且不存在 `12340010` 服务 |
| 4 | OTA packet 格式是否与两端代码一致 | ✅ START 61 B / Status 12 B / 操作码 0x01–0x04 / 错误码 0–11 全部逐字段核对 |
| 5 | Android 发送的固件文件类型是否正确 | ✅ `OtaImageParser` 强制校验 magic，`OtaConstants.REQUIRED_FILE_HINT = "zephyr.signed.bin"` |
| 6 | build command 是否能够实际执行 | ✅ 用新 `--signing-key` 参数实际跑通 pristine sysbuild，**并用其产物完成两轮真机 OTA**；Android `testDebugUnitTest`+`assembleDebug` 实际跑通 |
| 7 | signing key 传入方式是否与构建脚本一致 | ✅ `DEVELOPMENT.md` §2 与脚本实现一致（CLI > 环境变量 > 报错） |
| 8 | 文档中是否还残留废弃设计 | ✅ 所有历史方案均标注「历史方案（已废弃）」：硬编码私钥路径、明文 `OTA_AUTH_KEY`、旧裸 SPI 驱动、旧内部 NVS、旧历史区 `0x0` 起点、`>=3 ⇒ V3` 启发式 |
| 9 | 是否存在重要 TODO 未记录 | ✅ §4 列出 P0–P4 |
| 10 | 是否存在 hardcoded address | ✅ secondary/NVS/history 基址均来自 PM 宏；业务自管 history 的 1 MiB 容量仍是显式常量 |
| 11 | 是否存在 magic number | ⚠️ 有且**必须有**：`IMAGE_MAGIC 0x96F3B83D`、`IH_*_OFF`、`0x27FF0`（trailer）等，均与 MCUboot `bootutil/include/image.h` 对齐，已有注释指明出处 |
| 12 | 是否存在旧 W25Q64 layout | ✅ 无。当前布局为 secondary `0x0` / NVS `0x28000` / history `0x2E000`；旧版历史从 `0x000000` 起，已在文档中标注为历史 |
| 13 | 是否存在旧内部 NVS 地址 | ✅ 无。`git grep -E "NVS_PARTITION_SIZE"` 为空 |
| 14 | private key 路径硬编码 | ✅ 已清除 |
| 15 | 真实 secret | ✅ 无（见 §9） |
| 16 | 两端 OTA auth key 是否一致 | ✅ 本地忽略文件已逐字节比对一致；值不写入文档或仓库。换密钥时必须同时改两端与主机脚本 |
| 17 | MCUboot public key 与 signing key 流程是否一致 | ✅ 公钥由构建期从同一把 PEM 提取并编进 MCUboot；已用 `imgtool verify` 正/负向验证 |
| 18 | OTA 使用的 binary 是否真的是 signed image | ✅ `imgtool verify` 通过；且设备端曾用坏签名镜像反证 MCUboot 确实拒绝 |
| 19 | history storage 是否可能覆盖 secondary / NVS | ✅ 不会。三层保证（分区 API 边界 + 容量预检 + OTA 期间暂停写历史），且三段地址无重叠（门禁 H 项 PASS） |
| 20 | App OTA 写入是否做了 secondary slot 边界检查 | ✅ 固件侧 `ota_total > ota_fa->fa_size → err=3`（**擦除前**）；App 侧 `OtaImageParser` 用 `SECONDARY_SLOT_CAPACITY = 163,840` 提前拒绝 |

---

## 12. 发现的文档与代码不一致

| # | 位置 | 文档/注释 | 实际 | 处理 |
|---|---|---|---|---|
| 1 | README LED、MCU 型号、历史气压字段名 | 与代码事实不一致 | 已修正；字段纯改名，12 B 布局不变 |
| 2 | Android 极值长度与 `BATTERY_CHAR` | 注释长度错误，且 UUID 与历史信息冲突 | 注释已修正，冲突别名与空实现已删除 |
| 3 | 固件 `12340010` 声明 | 声明成服务但从未注册 | 声明已删除；`12340011` 仍在配置服务内 |
| 4 | `pm_static.yml` 显式 `external_flash` | 与 NVS 重叠 | 显式条目已删除；PM 自动剩余区从 `0x2E000` 开始 |
| 5 | 实时帧 vs 历史记录的气压单位 | — | 实时帧 = **0.1 hPa**；历史记录 = **Pa** | 属**协议事实**，不能悄悄统一（会破坏兼容）。已在 `PROTOCOL.md` §4 明确警示 |
| 6 | 旧文档（本任务前的交接/说明文档） | `OTA_AUTH_KEY` 写在 `ble_ota.h`、构建脚本默认使用本机私钥路径 | 均已外置/移除 | 已在本套文档中标注为「历史方案（已废弃）」 |
| 7 | `CLAUDE.md`（工程指令文件） | `E104-BT5010A 194KB FLASH 24K RAM`、`W25Q64 SLK 30` | 实际 **192 KiB Flash / 24 KiB RAM**；引脚名是 **SCK** | **已修正**（同类错误也在 `docs/2026-1-30优化方案.md`、`docs/GPIO引脚定义.md` 一并修正） |

---

## 13. 尚未解决的问题（按严重程度分级）

### Blocker
**无。** 当前固件与 App 均可构建、可烧录、可 OTA，核心链路真机验证通过。

### High
1. **RAM 余量仅 1,624 B（93.39%）** —— 下一次功能扩展大概率超限。
2. **MCUboot 仅剩 892 B** —— 任何往 MCUboot 加东西的尝试都会失败。
3. **VDD ≠ 电池电压 待验证** —— 若结论是"不等于"，则该功能对用户是误导，需重新设计。

### Medium
4. **手机端** OTA 客户端尚未在固件 `1.0.8+0` 上做真机回归（PC 侧 `ota_host_client.py` 已完成两轮）。
5. overwrite-only 无回滚能力，断电场景未做完整矩阵。
6. 历史记录不含电压（`HistoryRecordFormat.V3` 预留但无能力位可用）。
7. 生产签名密钥不存在，上线前必须生成并重烧 MCUboot。
8. 存量设备若只重烧 App、不清外置 Flash，历史写头可能不一致。

### Low
9. `MainViewModel.kt` 体量大，职责偏多。
10. Android 历史遗留文档含本机绝对路径（无凭据）。
11. `tools/ota_host_client.py` 的密钥入口（`--key` 与环境变量）与固件/App 的单一注入入口略不一致。

---

## 14. 给接手者的最短路径

1. 读 `CODEX_START_HERE.md`（1 分钟）→ 建立全局印象。
2. 读本文 §3（版本状态）与 §5（禁止改动的约束）。
3. 按 `DEVELOPMENT.md` 跑一次构建，确认工具链可用
   （⚠️ 固件必须给签名私钥：`MCU_BOOT_SIGNING_KEY` 或 `--signing-key`；Android 必须用 JDK 17）。
4. 按 `HARDWARE.md` §8 核对一次分区（**不要凭记忆**）。
5. 动 OTA 之前读 `OTA.md`，改协议之前读 `PROTOCOL.md`。
6. **改动落地顺序固定为：构建 → `size_summary.py` + `release_gate.py` → 真机回归 → 提交 → push。**
   没上过板的改动不要先提交成新基线。
