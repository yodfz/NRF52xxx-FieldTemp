# HARDWARE.md — 硬件定义与 Flash 分区

> 本文所有引脚与地址均从**当前仓库实际代码与最近一次构建产物**读取，不从记忆或设计稿抄录。
> 取证来源：`app.overlay`、`sysbuild/mcuboot.overlay`、`src/board_pins.h`、`pm_static.yml`、
> 构建产物 `<sysbuild>/partitions.yml` 与 `pm_config.h`。

---

## 1. 系统组成

| 部件 | 型号 | 说明 |
|---|---|---|
| 模组 | **E104-BT5010A** | 亿佰特蓝牙模组，内置 nRF52810 |
| MCU | **nRF52810**（QFAA，QFN48） | Cortex-M4 @ 64 MHz，**未启用 FPU** |
| 内部 Flash | 192 KiB（`DT_SIZE_K(192)`，`0x00000000` 起） | 见 `zephyr/dts/arm/nordic/nrf52810_qfaa.dtsi` |
| 内部 RAM | 24 KiB（`DT_SIZE_K(24)`，`0x20000000` 起） | 同上 |
| 外部 Flash | **W25Q64**（Winbond，SPI NOR） | 8 MiB / 64 Mbit |
| 温湿度 | AHT30（I2C） | `src/sensors/aht30.c` |
| 气压 | SPL06（I2C） | `src/sensors/spl06.c` |
| 紫外线 | LTR-390UV（I2C） | `src/sensors/ltr390.c` —— 代码在，**当前主流程未调用** |
| 供电 | DCDC（板级默认已开） | 由 `nrf52dk_nrf52810.dts` 提供 `regulator-initial-mode = NRF5X_REG_MODE_DCDC` |

### 硬件限制（决定了本项目的所有取舍）

- **192 KiB 内部 Flash 要同时装 MCUboot(32 KiB) + 主槽(160 KiB)**，应用实际可用 163,328 B，
  当前已用 149,528 B（91.55%）→ 增长空间极小。
- **24 KiB RAM 是最紧约束**：App 已用 22,952 B（**93.39%**），物理余量仅 1,624 B。
  任何新增功能都必须先看 RAM。
- **没有电池电压采样/分压电路**。电池电压改用 SAADC 内部 VDD 通道测量（见 §6）。
- **只有一组 SPI（SPIM0）**，W25Q64 独占；I2C 与 SPI 不冲突。
- nRF52 系列在 NCS 中**未选 `HAS_PM`** → `CONFIG_PM` 依赖不满足，**不要**在 `prj.conf` 里强开
  （`prj.conf` 已有注释说明）。

---

## 2. 引脚表（唯一出处：`app.overlay` + `src/board_pins.h`）

| GPIO | 功能 | 方向 | 说明 |
|---|---|---|---|
| P0.4 | DATA LED | 输出 | 低电平点亮（`PIN_LED_DATA`） |
| P0.5 | LINK LED | 输出 | 低电平点亮（`PIN_LED_LINK`） |
| P0.6 | I2C0 SCL | 双向 | TWIM，`bias-pull-up` |
| P0.7 | I2C0 SDA | 双向 | TWIM，`bias-pull-up` |
| P0.14 | UART0 RX | 输入 | 115200 8N1 |
| P0.18 | UART0 TX | 输出 | 115200 8N1 |
| **P0.28** | **W25Q64 CS** | 输出 | `cs-gpios = <&gpio0 28 GPIO_ACTIVE_LOW>`，低有效 |
| **P0.29** | **W25Q64 MISO** | 输入 | SPIM_MISO |
| **P0.30** | **W25Q64 SCK** | 输出 | SPIM_SCK |
| **P0.31** | **W25Q64 MOSI** | 输出 | SPIM_MOSI |

> `README.rst`、`board_pins.h` 与 `app.overlay` 已统一为上述引脚；P0.30 / P0.31 仅用于 SPI。

### ⚠️ 引脚为什么必须写在 `app.overlay` 里

Zephyr 的 `cmake/modules/configuration_files.cmake` 在找到匹配的
`boards/<board>_<soc>.overlay`（即 `nrf52dk_nrf52810.overlay`）后**就不再读 `app.overlay`**。

- 旧的 `boards/nrf52810dk_nrf52810_cpuapp.overlay` 因 board 名不匹配而永远不会生效，现已删除。
  App 的板级引脚只维护在 `app.overlay`；不要新增会抢占它的匹配 board overlay。
- MCUboot 与 App 是**两套独立 devicetree**，改引脚必须同时改
  `app.overlay` 与 `sysbuild/mcuboot.overlay`。

---

## 3. SPI / W25Q64

| 项目 | 值 | 出处 |
|---|---|---|
| Zephyr devicetree 节点 | `&spi0` 下的 `w25q64: w25q64@0` | `app.overlay:118`、`sysbuild/mcuboot.overlay` |
| compatible | `jedec,spi-nor` | 同上 |
| 使用的驱动 | Zephyr `CONFIG_SPI_NOR`（`jedec,spi-nor` 驱动） | `prj.conf` |
| SPI controller | `spi0`，`compatible = "nordic,nrf-spim"` | `app.overlay:109` |
| `spi-max-frequency` | **4,000,000 Hz（4 MHz）** | `app.overlay:122` |
| `jedec-id` | **`ef 40 17`**（实测回读一致） | `app.overlay:124` |
| `size` | `<0x4000000>` —— 该属性单位为 **bit**，即 64 Mbit = **8 MiB** | `app.overlay:123` |
| `has-dpd` / `t-exit-dpd` | `has-dpd;` / `<3000>` | 软件复位不会唤醒处于 deep power-down 的 W25Q64，故显式声明 |
| chosen 节点 | `nordic,pm-ext-flash = &w25q64` | `app.overlay:12` |
| alias | `w25q64-spi = &spi0` | `app.overlay:7` |

### MCUboot 与 App 是否共用同一个 flash device？

**是。** 两者都通过 `nordic,pm-ext-flash = &w25q64` 指向**同一个** devicetree 节点、
使用同一套 SPI0 引脚与同一个 `jedec,spi-nor` 驱动。差异只在 Kconfig：

- App 侧由 `prj.conf` 打开 `CONFIG_SPI_NOR=y` 等；
- MCUboot 侧由 `sysbuild/mcuboot.conf` 单独打开同一批开关
  （`FLASH / SPI / SPI_NRFX / SPI_NOR / FLASH_JESD216_API / GPIO`）。

⚠️ 因此**两边的引脚与 `SPI_NOR_FLASH_LAYOUT_PAGE_SIZE` 必须保持一致**，
只改一边会导致 MCUboot 与 App 对同一颗芯片的理解不同。

### 业务侧（NVS / 历史）如何访问同一颗芯片

| 使用者 | 访问方式 | 代码 |
|---|---|---|
| MCUboot（次级槽） | `flash_area`（PM 分区 `mcuboot_secondary`） | MCUboot 内部 |
| App OTA（写次级槽） | `flash_area_open(FIXED_PARTITION_ID(mcuboot_secondary))` + `stream_flash` | `src/ble/ble_ota.c` |
| NVS（配置/写头/时间/续传态） | Zephyr NVS，分区 `nvs_storage`（PM） | `src/storage/nvs_config.c` |
| 历史记录 | **裸地址读写**：`w25q64_read / w25q64_page_program / w25q64_sector_erase`，基址 `W25Q64_STORAGE_BASE` | `src/storage/w25q64.c`、`src/main.c` |

> 历史区是**唯一不走 PM/flash_area 的**使用者，靠 `src/storage/w25q64.h` 里的常量定位。
> 三者的互斥由 `w25q64_get_mutex()` 保证。

---

## 4. UART

| 项目 | 值 |
|---|---|
| TX | **P0.18** |
| RX | **P0.14** |
| 波特率 | 115200（`current-speed = <115200>`） |
| 用途 | 调试日志（`printk`）、`zephyr,shell-uart` |
| 是否用于日志 | **发布态不用**：`CONFIG_CONSOLE=n` / `CONFIG_PRINTK=n` / `CONFIG_LOG=n`，串口无输出 |
| 是否用于 bootloader | **否** |
| 是否支持首次烧录 | **否**（无 bootloader 串口协议；首次烧录走 SWD，见 §7） |
| MCUboot serial recovery | **未启用**（`<sysbuild>/mcuboot/zephyr/.config` 中无 `CONFIG_MCUBOOT_SERIAL` / `CONFIG_BOOT_SERIAL_*`） |

### 为什么现在不启用 serial recovery

MCUboot 分区只有 **32 KiB**，当前已用 **31,876 B（97.28%），余量 892 B**。
serial recovery 会拉进 UART 驱动、帧协议、`boot_serial` 与命令处理，**远超余量**。
当前救砖路径是 SWD（`pyocd`），见 `DEVELOPMENT.md`。**不要**在没有重新评估体积前打开它。

---

## 5. I2C

| 项目 | 值 |
|---|---|
| controller | `i2c0`，`compatible = "nordic,nrf-twim"` |
| SCL | **P0.6** |
| SDA | **P0.7** |
| 时钟 | `I2C_BITRATE_STANDARD`（100 kHz） |
| 上拉 | 由 pinctrl `bias-pull-up` 提供 |

挂载器件（均在 I2C 上，地址见各自驱动）：

| 器件 | 用途 | 代码 |
|---|---|---|
| **AHT30** | 温湿度（主采集源） | `src/sensors/aht30.c` |
| **SPL06** | 气压 | `src/sensors/spl06.c` |
| SHT40 | 温湿度（备选，**当前主流程未使用**） | `src/sensors/sht40.c` |
| LTR-390UV | 紫外线（**当前主流程未使用**） | `src/sensors/ltr390.c` |

---

## 6. 电池电压（无外部电路）

**板上没有电压采样/分压电路。** 电压来自 nRF52810 的 SAADC **内部 VDD 通道**：

```dts
&adc {
    status = "okay";
    channel@0 {
        zephyr,gain = "ADC_GAIN_1_6";
        zephyr,reference = "ADC_REF_INTERNAL";
        zephyr,input-positive = <NRF_SAADC_VDD>;
        zephyr,resolution = <12>;
    };
};
```

- 换算：`mV = raw * 3600 / 4096`（0.6 V 内部基准 × 1/6 增益 → 量程 3.6 V）。
  代码刻意**不用** `adc_raw_to_millivolts_dt()`（gain 语义跨版本有差异，容易算错一倍）。
- 驱动在每次转换 DONE 后 `nrfy_saadc_disable()`，**空闲不耗电**。
- 采样只在系统工作队列（1 Hz 采集路径）里做，默认 60 s 一次；BLE 侧只读缓存。
- ⚠️ **前提**：VCC 必须**直接由电池供电**（中间无 LDO/稳压）。
  当前开发板用调试器供电时读数恒在 3325~3336 mV、极差仅 11 mV，
  **不能证明**它就是电池电压（详见 `HANDOFF.md` 的"尚未验证"清单）。

---

## 7. SWD / 首次烧录

| 项目 | 值 |
|---|---|
| 调试器 | DAPLink（本机枚举名 `LU_2022_8888`） |
| target | `nRF52810_xxAA` |
| 接口 | SWD，时钟 500 kHz |
| SWDIO / SWCLK / GND / VTref | 按调试器 20 pin 标准排针与模组 SWD 脚位对接（模组侧丝印为准） |
| RESET | **不使用**（`app.overlay` 里 `&uicr { /delete-property/ gpio-as-nreset; }`，P0.21 不作复位） |

**首次烧录 = 烧 `merged.hex`**（含 MCUboot + 主槽，带绝对地址），命令见 `DEVELOPMENT.md` §4。

> ⚠️ **两种"擦除"要分开看，别混为一谈：**
>
> - **`pyocd erase --chip`（= MCU mass erase）**：擦除的是 **nRF52810 的内部 Flash**
>   （含 MCUboot 与 App），因此**正常升级流程不要使用**。
>   它**不会**通过 SPIM0 去擦外接的 W25Q64 —— pyOCD 的 `erase` 只作用于目标 MCU 的内部存储。
> - **真正禁止的是"对 W25Q64 整片执行 chip erase"**：通过应用、host DFU 工具
>   （`tools/w25q64_host_dfu.py`）或任何自定义脚本都不允许。因为 `0x28000` 之后包含
>   **NVS 与历史数据**，且**没有备份**。

---

## 8. Flash 分区（**以实际构建产物为准，请勿凭记忆**）

数据来源（三者一致，已交叉核对）：

- `pm_static.yml`（人工维护的 PM 静态布局，唯一编辑入口）
- `<sysbuild>/partitions.yml`（PM 生成）
- `<sysbuild>/*/zephyr/include/generated/pm_config.h`（PM 生成的宏，即代码实际看到的）

### 8.1 内部 nRF52810 Flash（192 KiB = `0x00000`–`0x30000`）

| 分区 | 起始 | 结束 | 大小 | PM ID | 说明 |
|---|---|---|---|---|---|
| `mcuboot` | `0x00000` | `0x08000` | **32,768 B (32 KiB)** | 0 | Bootloader，当前实占 31,876 B |
| `mcuboot_pad` | `0x08000` | `0x08200` | **512 B** | 2 | 镜像头（header）预留区 |
| `app` | `0x08200` | `0x30000` | **163,328 B (0x27E00)** | 4 | 主槽应用区，当前实占 149,528 B |
| `mcuboot_primary` | `0x08000` | `0x30000` | **163,840 B (0x28000)** | 3 | 容器：span `[mcuboot_pad, app]` |
| `mcuboot_primary_app` | `0x08200` | `0x30000` | **163,328 B** | 5 | 容器：span `[app]` |
| `sram_primary` | `0x20000000` | `0x20006000` | **24,576 B (24 KiB)** | — | 非 Flash，仅列出完整布局 |

主槽镜像头位于 **`0x8000`**（不是 `0x8200`），版本字段在 **`0x8014`**。

### 8.2 外置 W25Q64（8 MiB = `0x000000`–`0x800000`）

| 区域 | 起始 | 结束 | 大小 | PM ID | 说明 |
|---|---|---|---|---|---|
| `mcuboot_secondary` | `0x000000` | `0x028000` | **163,840 B (160 KiB)** | 1 | OTA 下载目标槽；与主槽严格等大 |
| `nvs_storage` | `0x028000` | `0x02E000` | **24,576 B (24 KiB)** | 6 | Zephyr NVS：写头/配置/时间基准/OTA 续传态 |
| **history records** | `0x02E000` | `0x12E000` | **1,048,576 B (1 MiB)** | **无 PM 条目** | 业务自管，基址 `W25Q64_STORAGE_BASE` |
| 剩余未分配 | `0x12E000` | `0x800000` | **7,151,616 B（约 6.82 MiB）** | — | 当前未使用 |

> 历史区**不在 `pm_static.yml` 里**。`src/storage/w25q64.h` 直接引用 PM 生成的
> `PM_NVS_STORAGE_END_ADDRESS` 作为 `W25Q64_STORAGE_BASE`（当前为 `0x2E000`），并用编译期检查
> 保证 secondary 与 NVS 相邻。历史区大小仍为 1 MiB、256 个 4 KiB 扇区。

### 8.3 `external_flash` 剩余区

`pm_static.yml` 只显式固定 `mcuboot_secondary` 与 `nvs_storage`。Partition Manager 会把余下空间
自动生成为 `external_flash [0x02E000, 0x800000)`；它不再与 NVS 重叠。构建门禁把它作为普通叶子
分区参与重叠和边界检查，不再用“包含另一分区即视为容器”的启发式豁免。

业务历史区 `[0x02E000, 0x12E000)` 由原始 W25Q64 驱动管理，位于该剩余区前 1 MiB。当前代码没有
通过 `FIXED_PARTITION_ID(external_flash)` 访问剩余区；后续也不得对整个 `external_flash` 执行
`flash_area_erase/write`，否则会覆盖历史记录。

---

## 9. 分区约束（改动前必读）

1. `mcuboot_primary.size == mcuboot_secondary.size` **必须恒成立**
   （受 `SB_CONFIG_MCUBOOT_MODE_OVERWRITE_ONLY` 约束，改一个必须改两个）。
2. 两槽都是 `0x28000`（163,840 B）。
3. 外置 Flash 上 `mcuboot_secondary`（`0x0`–`0x28000`）、`nvs_storage`（`0x28000`–`0x2E000`）、
   history（`0x2E000`–`0x12E000`）**三段不得重叠**。三者当前全部 4096 B 对齐。
4. `SPI_NOR_FLASH_LAYOUT_PAGE_SIZE` 在 App 与 MCUboot 两侧都必须为 **4096**
   （W25Q64 真实扇区）。设为默认 65536 会让 MCUboot 擦除次级槽 trailer 时
   `-EINVAL` 静默失败，表现为"升级后 trailer magic 还在、镜像不搬运"。
5. App 侧 `app.overlay` 的 `&flash0/partitions` 只声明 `image-0`（`0x8200`，`0x27E00`），
   内部 Flash **不再放 NVS**。
