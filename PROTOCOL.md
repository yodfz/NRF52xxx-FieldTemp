# PROTOCOL.md — Firmware ↔ Android 接口协议

> 本文**从固件与 Android 两端代码逐字段交叉核对**得出，不是设计稿。
> 核对来源：
> - 固件：`src/ble/ble_ota.h`、`src/ble/ble_ota.c`、`src/ble/ble_services.h`、`src/main.c`、
>   `src/common.h`、`src/storage/w25q64.h`
> - Android：`data/bluetooth/BleConstants.kt`、`data/device/parser/*.kt`、`data/ota/*.kt`
>
> 全部数值一律 **小端序（little-endian）**，除非特别注明。

---

## 0. 总览：服务与特征

| 服务 | 服务 UUID | 特征 | 特征 UUID | 属性 | CCC | 用途 |
|---|---|---|---|---|---|---|
| 配置 | `12340020-1234-5678-1234-56789abcdef0` | 历史记录间隔 | `12340021-…` | READ / WRITE | 无 | uint16 秒（60~3600） |
| | | 设备状态 | `12340022-…` | READ / NOTIFY | 有 | 12 B |
| | | 历史数据 | `12340023-…` | WRITE / NOTIFY | 有 | 写 4 B 起始时间戳；通知 60 B 数据包 |
| | | 温度极值 | `12340024-…` | READ / NOTIFY | 有 | 12 B |
| | | 温度极值重置 | `12340025-…` | WRITE | 无 | 写任意值即重置 |
| | | 历史信息 | `12340026-…` | READ | 无 | 8 B |
| | | **时间同步** | `12340011-…` | WRITE | 无 | 4 B Unix 秒 |
| 实时数据 | `12340030-1234-5678-1234-56789abcdef0` | 实时数据 | `12340031-…` | READ / NOTIFY | 有 | **8 B**（6 B 基础 + 2 B 电压） |
| 清空数据 | `12340040-1234-5678-1234-56789abcdef0` | 清空数据 | `12340041-…` | WRITE | 无 | 写任意值触发异步清空 |
| **OTA** | `12340050-1234-5678-1234-56789abcdef0` | Control | `12340051-…` | WRITE | 无 | 控制命令 |
| | | Data | `12340052-…` | **WRITE WITHOUT RESPONSE** | 无 | 镜像分片 |
| | | Status | `12340053-…` | NOTIFY | 有 | 12 B 状态 |

UUID 全称规则：`1234 00XX 1234 5678 1234 56789abcdef0`，完整写法例如
`12340021-1234-5678-1234-56789abcdef0`。固件与 Android 各自声明这些 UUID；任何变更都必须
同时核对 `ble_services.h` / `ble_ota.h` 与 `BleConstants.kt`。

### 时间同步与电池电压的归属

- 固件没有 `12340010` 服务；时间同步特征 `12340011` 属于配置服务 `12340020`。
- `12340026` 只表示历史信息。Android 已删除早期与它冲突的 `BATTERY_CHAR` 别名。
- 电池电压通过实时数据帧的 offset 6 上报。

---

## 1. 广播与设备名

| 项目 | 值 |
|---|---|
| 广播名 | `PandaTemp_XXXX`，`XXXX` = `%02X%02X` 形式的蓝牙地址低两字节（**反序**：`val[1]` 在前） |
| 名称来源 | `src/ble/ble_adv.c:60`，`snprintf(..., "%s_%02X%02X", DEVICE_NAME_BASE, addrs[0].a.val[1], addrs[0].a.val[0])` |
| 实测例 | `PandaTemp_7086` |
| 连接数 | `CONFIG_BT_MAX_CONN=1` —— **同一时刻只允许 1 个连接** |
| 配对/绑定 | 关闭（`CONFIG_BT_SMP=n`、`CONFIG_BT_BONDABLE=n`、`CONFIG_BT_MAX_PAIRED=0`），**无加密** |
| 广播标志 | `BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR` |

Android 侧的名称过滤常量（`BleConstants`）：`PandaTemperatureEX` / `PandaTemperature` /
前缀 `Panda` / `yodfz-temp` / 前缀 `yodfz`。当前固件名 `PandaTemp_7086` 命中前缀 `Panda`。

---

## 2. 配置服务

### 2.1 历史记录间隔 `12340021`（READ / WRITE，uint16）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 2 | uint16 | LE | 历史记录落盘周期（秒） |

- 有效范围 **60 ~ 3600**（`MIN_HISTORY_INTERVAL = 60`，`MAX_HISTORY_INTERVAL = 3600`）。
  下限 60 是**容量红线**（65000 × 60 s = 45.1 天，产品承诺 30 天），不是性能取舍。
- 写入后固件会**立即通知**同特征上的新值（`interval_char_data`）。
- ⚠️ 语义变更：旧固件（v2 及更早）该特征表示"采集间隔"，采样与落盘共用同一周期。
  新固件（1.0.6+0 起）表示"历史记录落盘周期"，采样**固定 1 秒**。
  App 通过状态帧 `byte5` 的 `bit0 (CAP_HISTORY_INTERVAL)` 区分。

### 2.2 设备状态 `12340022`（READ / NOTIFY，**12 字节**）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 2 | uint16 | LE | 历史记录间隔（秒） |
| 2 | 2 | uint16 | LE | 已存储记录数（超过 65535 时截断为 0xFFFF） |
| 4 | 1 | uint8 | — | 状态位，见下表 |
| 5 | 1 | uint8 | — | 能力位，见下表（旧固件恒 0） |
| 6 | 2 | uint16 | LE | 固件版本号 —— **新固件为 patch 号**（1.0.8 → `8`；旧固件为主/次编码） |
| 8 | 2 | uint16 | LE | 采样间隔（恒为 `1`） |
| 10 | 2 | uint16 | LE | 名义预计保留天数（`65000 × interval / 86400`，上限 0xFFFF） |

**byte4 状态位**

| Bit | 宏 | 含义 |
|---|---|---|
| 0 | — | 已连接（`bt_connected`） |
| 1 | — | 时间已同步（含仅从 NVS 恢复的旧时间） |
| 2 | — | 正在清空数据 |
| 3 | — | **墙钟可信**：本次上电收到过手机对时。仅从 NVS 恢复旧时间时为 0 |

**byte5 能力位**（`src/main.c:882-887`）

| Bit | 宏 | 含义 |
|---|---|---|
| 0 | `CAP_HISTORY_INTERVAL 0x01` | `12340021` 已改为"历史记录间隔"语义 |
| 1 | `CAP_SAMPLE_FIXED 0x02` | 采样固定 1 秒，不可配置 |
| 2 | `CAP_EVENT_RECORDS 0x04` | 支持突变/越限事件记录 |
| 3 | `CAP_AVERAGE_RECORDS 0x08` | 历史记录为周期均值 |
| 4 | `CAP_WALLCLOCK_ALIGN 0x10` | 周期记录时间戳按墙钟边界对齐（60→`:00`；300→`:00/:05/…） |
| 5 | `CAP_PARTIAL_WINDOW 0x20` | 对时后首个窗口可能短于一个周期（保留而非丢弃） |

当前固件恒上报 `caps = 0x3F`（六位全置）。
**注意：没有"历史记录含电压"的能力位** —— 见 §3.3。

Android 解析：`DeviceStatusParser`（支持 5 / 7 / 8 / 10 / 12 字节，缺失字段取默认值）。

### 2.3 历史信息 `12340026`（READ，**8 字节**）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 4 | uint32 | LE | 总记录数 |
| 4 | 2 | uint16 | LE | 起始扇区（有数据时为最旧扇区号，否则 0） |
| 6 | 2 | uint16 | LE | 起始记录索引（恒为 0：从最旧扇区的第 0 条线性读） |

### 2.4 温度极值 `12340024`（READ / NOTIFY，**12 字节**）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 2 | int16 | LE | 最高温度（0.01 °C） |
| 2 | 4 | uint32 | LE | 最高温度发生时间（Unix 秒） |
| 6 | 2 | int16 | LE | 最低温度（0.01 °C） |
| 8 | 4 | uint32 | LE | 最低温度发生时间（Unix 秒） |

特殊值（`MaxMinTempParser`）：最高温和 `-32768(0x8000)` 且时间戳 0 → 未记录/已重置；
最低温和 `32767(0x7FFF)` 且时间戳 0 → 未记录/已重置。

> 当前固件发送 12 字节。Android 解析器仍兼容旧固件的 4 字节格式。

### 2.5 温度极值重置 `12340025`（WRITE）

写入**任意长度任意内容**即触发重置，并通知手机新值。无固定报文格式。

### 2.6 时间同步 `12340011`（WRITE，**4 字节**）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 4 | uint32 | LE | Unix 时间戳（秒） |

- 长度必须 **恰好 4**，否则返回 `BT_ATT_ERR_INVALID_ATTRIBUTE_LEN`。
- 小于 `946684800`（2000-01-01 UTC）时**只打印警告、仍然接受**。
- 效果：置 `time_base_timestamp` / `time_base_uptime`、`time_synced = true`、
  **`wall_clock_aligned = true`**（状态帧 bit3 置 1）、**重算历史结算网格**、立即写入 NVS。
- 只动聚合窗口与结算网格，**不碰 NVS 写头与已有历史**。

### 2.7 清空数据 `12340041`（WRITE）

写入任意值即触发**异步**清空（`clear_data_work_handler`，逐扇区擦除 256 个扇区）。
立即返回，不阻塞 GATT 响应。清空期间状态帧 bit2 置 1。
无固定报文格式。

---

## 3. 历史数据（`12340023`）

### 3.1 记录格式 —— **12 字节**（`struct data_record`，`__packed`）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 4 | uint32 | LE | Unix 时间戳（秒） |
| 4 | 2 | int16 | LE | 温度（0.01 °C，有符号） |
| 6 | 2 | uint16 | LE | 湿度（0.01 %RH） |
| 8 | 4 | uint32 | LE | 气压（**Pa**） |

- 结构定义：`src/common.h:23`，由 `storage_write_batch()` 原样写入 Flash。
- 固件字段名是 `pressure_pa`，数值单位是 Pa；Android `HistoryDataParser`（V2 分支）按
  `pressureRaw / 100.0f` 得到 hPa。
- 同一条 12 字节既用于 Flash 存储，也用于 BLE 通知，二者**同源同布局**。

### 3.2 传输协议

**分包：每包 5 条记录 = 60 字节**（`STREAM_RECORDS_PER_PACKET = 5`，
`STREAM_PACKET_SIZE = 60`），通过 `12340023` 的 **NOTIFY** 发送。

**结束标志：单独一包、长度 1、内容 `0xFF`**（`uint8_t end_mark[1] = {0xFF}`）。
Android 端 `BleConstants.HISTORY_END_FLAG = 0xFF.toByte()`。

**开始传输**：向 `12340023` **WRITE** 4 字节起始时间戳（uint32 LE）。

| 值 | 含义 |
|---|---|
| `0` | 发送全部历史（清除二分查找标记，从 `oldest_sector` 开始） |
| `> 0` | 只发送时间戳**大于**该值的数据（增量同步） |

长度必须恰好 4 字节。

**包长度不固定**：最后一包可能不足 60 字节（不足部分由固件按记录边界截断）。

### 3.3 ⚠️ 关于"历史记录含电压"（**当前未实现**）

固件历史记录**始终是 12 字节，不含电压**（`W25Q64_RECORD_SIZE = 12`），
状态帧能力位里**也没有**对应的能力标志。

Android 侧 `HistoryRecordFormat` 定义了三种：

| 枚举 | 记录大小 | 布局 |
|---|---:|---|
| `V1` | 8 | timestamp(4) + temperature(2) + humidity(2) |
| `V2` | **12** | timestamp(4) + temperature(2) + humidity(2) + pressure(4) |
| `V3` | 14 | V2 + 电压 uint16 LE（mV） |

- **只有 `V2` 会被真正选中**：`DeviceProfileFactory.createThermometerProfile()` 明确只返回
  `ThermometerV2Profile`（合并实时数据固件）或 `ThermometerV1Profile`（老 ESS 固件）。
- `ThermometerV3Profile` / `HistoryRecordFormat.V3` 是**预留能力，当前不被任何固件选中**，
  其类注释明确写着"未来只有在固件显式提供「历史含电压」能力标志时才可接入，禁止用版本号或包长推断"。
- ⚠️ **禁止**用固件版本号推断 14 字节格式：新固件上报的是 **patch 号**（6 表示 1.0.6），
  老固件是主/次版本编码（12 表示 v1.2）。历史上 `>= 3 ⇒ V3` 的启发式曾把 patch=6 误判成
  14 字节，导致历史全部错位。**已修复**，不要改回去。

---

## 4. 实时数据 `12340031`（READ / NOTIFY）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 2 | int16 | LE | 温度（0.01 °C，有符号） |
| 2 | 2 | uint16 | LE | 湿度（0.01 %RH） |
| 4 | 2 | uint16 | LE | 气压（**0.1 hPa**，由 Pa/10 得到，下限 0、上限 0xFFFF） |
| 6 | 2 | uint16 | LE | **电池电压（mV）** —— 仅当 `CONFIG_ADC=y` 时存在 |

- 帧长：`REALTIME_FRAME_LEN` = **8**（有 ADC）/ **6**（无 ADC）。当前发布构建为 **8**。
- 传感器读取失败时对应字段填 **0**（不是哨兵值）。
- 电压无效哨兵 **`0xFFFF`**；App 必须显示 `--`，绝不能显示成 65.535 V。
- ⚠️ 注意偏移 4 的单位与 §3.1 不同：**实时帧是 0.1 hPa，历史记录是 Pa**。
  这是历史遗留的不一致，两侧代码都按此解析，**当前不改**（改就是破坏兼容性）。
- 主动上报：需要在 `12340031` 上订阅 NOTIFY；1 Hz 采样触发通知。
  也可在 `12340031` 上直接 READ（读时现场刷新）。

Android 解析：`RealtimeDataParserV2`（`data/device/parser/RealtimeDataParser.kt`）。
它先判 `0xFFFF` 哨兵，再用 **1700~3600 mV** 量程做第二道防线（nRF52810 工作电压范围），
任一命中即置 `null`。

---

## 5. OTA 协议

服务 `12340050`，三特性：Control `12340051`（WRITE）、Data `12340052`（WRITE WITHOUT RESPONSE）、
Status `12340053`（NOTIFY）。

### 5.1 Control 命令（写 `12340051`）

| 操作码 | 名称 | 长度 | 有效状态 |
|---|---|---:|---|
| `0x01` | START | **61** | 任意（会中止上一次会话） |
| `0x02` | END | 1 | `READY` / `RECEIVING` |
| `0x03` | CANCEL | 1 | 任意 |
| `0x04` | TRIGGER | 1 | **仅 `VERIFY`** |

**START 报文（61 字节）**

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 1 | uint8 | — | `0x01` |
| 1 | **16** | bytes | — | OTA 授权密钥（与固件 `OTA_AUTH_KEY` 逐字节相同） |
| 17 | 4 | uint32 | LE | 镜像总字节数 `total` |
| 21 | **8** | bytes | — | 镜像头偏移 `0x14` 起的 8 字节 `ih_ver`（**原样透传，不解析**） |
| 29 | **32** | bytes | — | **整个 `zephyr.signed.bin`** 的 SHA-256 |

长度不等于 61 → `err=9 (LEN)`；密钥不符 → `err=1 (AUTH)`。

**START 是异步的**：回调只做校验 + 拷贝 + `k_work_submit()`，整槽擦除（约 1~2 s）在工作队列执行。
**在收到 `state=READY` 之前，Data 写入会被拒绝**（回调返回 `BT_ATT_ERR_UNLIKELY`）。
客户端**必须**等 `READY`，`IDLE 且 err=0` 不等于可以发数据。

### 5.2 Data（写 `12340052`）

- 内容：`zephyr.signed.bin` 的顺序分片，纯追加，**无序号、无 offset、无每包校验**。
- 分片大小 = 协商 MTU − 3（ATT 写头）。MTU 128 → 125 B；回退 MTU 65 → 62 B。
- 只允许在 `READY` / `RECEIVING` 状态写。
- 累计字节数超过 START 声明的 `total` → `err=5 (SIZE)`。
- 环形缓冲（2 × 256 B）被打满 → **`err=11 (OVERRUN)` 保护性中止**（不静默丢数据），
  客户端应从 START 重发（设备按已确认偏移续传）。

### 5.3 Status 通知（`12340053`，**12 字节**）

| 偏移 | 长度 | 类型 | 端序 | 说明 |
|---|---:|---|---|---|
| 0 | 4 | uint32 | LE | **已写入 Flash 的连续偏移**（confirmed offset） |
| 4 | 4 | uint32 | LE | 镜像总字节数 `total` |
| 8 | 1 | uint8 | — | `state` |
| 9 | 1 | uint8 | — | `err` |
| 10 | 1 | uint8 | — | 进度百分比（`confirmed × 100 / total`） |
| 11 | 1 | uint8 | — | flags，**预留，恒 0** |

⚠️ `offset` 是**已落 Flash** 的连续偏移，**刻意滞后**于已接收字节数
（数据先进 256 B 缓冲，凑满一页才落盘）。**流控必须以它为准**，不能用"我发了多少"。

**发送时机**：`START` 完成（READY）、状态每次变化、以及**每收满 8 个 Data 包**
（`OTA_ACK_PACKETS = 8`）各通知一次。不逐包确认是刻意的（逐包 notify 会抢 ACL 缓冲与射频时间）。

**state（`enum ota_state`）**

| 值 | 名称 | 含义 |
|---:|---|---|
| 0 | `IDLE` | 空闲（START 准备期也报这个） |
| 1 | `READY` | 已擦除/已定位，**可以发数据** |
| 2 | `RECEIVING` | 接收中 |
| 3 | `VERIFY` | 字节数 / magic / 版本 / SHA-256 全部通过，可 TRIGGER |
| 4 | `PENDING` | 已 `boot_request_upgrade()`，即将重启 |
| 5 | `ERROR` | 出错，看 `err` |

**err（`enum ota_err`）**

| 值 | 名称 | 含义 |
|---:|---|---|
| 0 | `NONE` | 无错误 |
| 1 | `AUTH` | 密钥不匹配 |
| 2 | `STATE` | 当前状态不允许该操作 |
| 3 | `TOO_LARGE` | 超过次级槽容量（**擦除前**拒绝） |
| 4 | `FLASH` | 擦/写失败 |
| 5 | `SIZE` | 接收字节数与声明不符 |
| 6 | `MAGIC` | 镜像 magic 不对 |
| 7 | `VERSION` | 镜像 `ih_ver` 与 START 声明不符 |
| 8 | `HASH` | App 侧 SHA-256 校验失败（`CONFIG_IMG_ENABLE_IMAGE_CHECK=y` 时启用） |
| 9 | `LEN` | Control 报文长度非法 |
| 10 | `INTERNAL` | 内部错误 |
| 11 | `OVERRUN` | 接收环形缓冲被打满，保护性中止（可从已确认偏移重发 START 续传） |

### 5.4 断点续传

- 续传状态存在 **NVS `NVS_ID_OTA`**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `offset` | uint32 | 已写入的连续偏移 |
| `total` | uint32 | 声明的总字节数 |
| `ih_ver[8]` | bytes | 镜像版本 |
| `img_id[16]` | bytes | **SHA-256 的前 16 字节** |
| `valid` | uint8 | 1 = 有效 |

- **续传判定必须同时比对 `total` + `ih_ver` + `img_id`**。
  只用 `(total, ih_ver)` 会把两个"同版本同大小但内容不同"的镜像错误拼接。
- 命中续传时，`resume_off = offset & ~(IMG_BLOCK_BUF_SIZE - 1)`（**向下对齐到 256 B**），
  **跳过整槽擦除**，从该偏移继续。
- 任一项不符 → 清掉续传状态、整槽重来。
- `CANCEL` 与 `TRIGGER` 都会清除续传状态。

### 5.5 重连 / 超时 / 重启

| 行为 | 固件侧 | Android 侧（`OtaRunner` / `OtaConstants`） |
|---|---|---|
| 断线重连 | 设备侧续传状态保留在 NVS，重连后重新 START 即可 | 每次重试**重新扫描**，不沿用旧 `BLEDevice` |
| 等待 READY 超时 | — | `START_READY_TIMEOUT_MS = 15_000` |
| Status 超时 | — | `statusTimeoutMs = 8_000`（构造参数默认值） |
| 流控窗口 | 每 8 包通知一次已落盘偏移 | `FLOW_WINDOW_BYTES = 4 KiB`（未确认字节不超过该值） |
| OVERRUN 重试 | 置 `err=11` 并停止接收 | `MAX_OVERRUN_RESTARTS = 2`（从已确认偏移续传重发） |
| MTU | 请求 128，失败回退默认 | `PREFERRED_MTU=128`、`FALLBACK_MTU=65`、`MIN_START_MTU=64` |
| TRIGGER 后 | `sys_reboot(SYS_REBOOT_WARM)` | 写 TRIGGER 后设备复位会拉断链路，**应判为成功**。表象有两种：BLE 写返回 `status=133 (GATT_ERROR)`（Android 侧，见 `TRIGGER_DISCONNECT_GRACE_MS=100` × 15 步），或 WinRT 直接抛 `OSError [WinError -2147467260]`（PC 侧，已由 `tools/ota_host_client.py` 捕获） |

### 5.6 镜像类型（**必须传 `zephyr.signed.bin`**）

Android **必须**发送 `zephyr.signed.bin`，不能是：

| 文件 | 为什么不行 |
|---|---|
| `zephyr.bin` | 裸应用镜像，**没有 MCUboot 头与签名** → 设备 `err=6 MAGIC` |
| `merged.hex` | 是 hex 文本且包含 MCUboot，格式与分区语义都不同 |
| `zephyr.elf` | 调试用 ELF |
| `dfu_application.zip` | 是给 MCUboot **serial recovery** 用的容器；当前未启用该模式 |

原因：设备把收到的字节**原样写进次级槽**，然后由 MCUboot 按固定头
（magic `0x96F3B83D`、`ih_hdr_size=512`、`ih_img_size`@`0x0C`、`ih_ver`@`0x14`）
解析并做 **ECDSA-P256 验签**。只有 `zephyr.signed.bin` 同时具备头与签名。

Android 侧 `OtaImageParser` 在**发送前**就校验 magic 与大小，magic 不符会直接以
`OtaImageError.BadMagic` 拒绝，而不是等设备报错。

---

## 6. 版本与兼容性协商

### 6.1 有没有独立的 protocol version？

**没有。** 本项目**没有**独立的 protocol version 字段，也**没有** capability negotiation 握手。
双方靠以下**隐式信号**判断能力：

| 判断依据 | 位置 | 说明 |
|---|---|---|
| 状态帧长度 | `12340022` 的读出长度 | 5 / 7 / 8 / 10 / 12 字节，缺字段取默认值 |
| 状态帧 `byte5` 能力位 | 见 §2.2 | 旧固件恒 0 → App 走旧语义分支 |
| 状态帧 `byte6` 固件版本 | patch 号 | **只用于显示**，不用于推断协议形态 |
| 实时帧长度 | `12340031` | ≥ 8 字节才读取电压字段 |
| 是否有实时数据服务 `12340030` | 服务发现 | 无则按老 ESS 固件处理 |
| OTA 服务 `12340050` 是否存在 | 服务发现 | 无则无 OTA 能力 |

⚠️ **固件版本号不能用来推断协议**：新固件是 **patch 号**（`APP_PATCHLEVEL`），
老固件是主/次版本编码，两者共用同一个 `uint16` 位段。这是本项目最容易踩的坑
（历史记录 V3 错位事故的根因）。

### 6.2 Android 如何判断固件版本

1. 读 `12340022` → `DeviceStatusParser` 取 `byte6` → `firmwareVersion`（patch 号）。
2. 仅用于**界面显示**（`v8` 之类）与**日志**。
3. **不参与**任何协议分支判断。

### 6.3 Android 如何判断 OTA 能力

服务发现时看是否存在 `12340050`。当前没有"OTA 能力位"这种东西。
`FirmwareUpdateScreen` 在有 OTA 服务时可用。

### 6.4 镜像兼容性 / 最低固件要求

- 镜像与设备的兼容性**完全由 MCUboot 保证**：分区大小（≤ 160 KiB 次级槽）+ `ih_ver` +
  ECDSA-P256 签名（必须由同一把私钥签出）。
- **没有**"最低固件版本"检查，也**没有**防回滚（downgrade prevention）配置。
  推一个版本号更低的已签名镜像，MCUboot 会正常搬运。
- 次级槽容量上限 `0x28000` = 163,840 B；App 签名镜像红线 157,696 B。

---

## 7. 错误与边界行为速查

| 场景 | 固件行为 | App 应如何处理 |
|---|---|---|
| 写入长度非法的 Control 报文 | `err=9`；START 长度不对时不改状态 | 按 `err` 文案提示 |
| Data 在非 READY/RECEIVING 时写入 | 回调返回 `BT_ATT_ERR_UNLIKELY`，**不发通知** | 超时 → 提示"设备未就绪" |
| 设备状态长时间停在 `IDLE` | START 正在擦除（1~2 s） | 等 READY，最多 15 s |
| 收到 `err=11 OVERRUN` | 已停止接收，Flash 中数据完整 | 提示后从 START 重发（自动续传），最多 2 次 |
| 收到 `err=8 HASH` | 次要校验失败（MCUboot 才是最终防线） | 提示重传，**设备不会重启** |
| TRIGGER 后链路断开（`status=133` / WinRT `已中止操作`） | 设备正常复位 | **判为成功**，不要报错 |
| 广播里看不到设备 | `MAX_CONN=1`，可能已被别的手机占用 | 先断开其它连接 |

---

## 8. 已清理的遗留冲突

2026-09-15 已同步修正 README 的 LED 引脚、最高最低温度特征长度、MCU 型号、历史气压字段名，
并删除冲突的 Android `BATTERY_CHAR` 与未注册的固件 `12340010` 声明。历史设计文档仍可能描述
旧协议；当前协议只以本文件与两端代码为准。`OTA_AUTH_KEY` 继续由忽略文件注入，固件可信边界
仍是 MCUboot ECDSA-P256 验签。
