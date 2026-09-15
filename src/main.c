/*
 * 参考 RIOT-2020.10-RC3 的 E104-BT5010A 板级配置实现蓝牙温度计程序
 * 
 * 重要配置说明（参考 periph_conf.h）：
 * - 时钟配置：E104-BT5010A 模块没有外部晶振，必须使用内部 RC 振荡器
 *   在 prj.conf 中已配置：
 *   CONFIG_CLOCK_CONTROL_NRF_K32SRC_RC=y  (使用内部 RC 作为 32kHz 时钟源)
 *   这确保系统不会因等待外部晶振起振而卡死
 * 
 * - LED 配置：DATA LED: P0.4, LINK LED: P0.5
 *   LED 为低电平有效（低电平点亮，高电平熄灭）
 *   DATA LED 用于指示数据状态
 *   LINK LED 用于指示蓝牙连接状态
 */

#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/sys/util.h>
#include <zephyr/sys/byteorder.h>
#include <string.h>
#include <limits.h>
#include <hal/nrf_gpio.h>
#include <hal/nrf_twim.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gap.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/drivers/spi.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/storage/flash_map.h>
// PM 设备电源管理（用于I2C低功耗控制）
#include <zephyr/pm/device.h>

#include <zephyr/device.h>

/* 拆分模块头文件 */
#include "common.h"
#include "sensors/aht30.h"
#include "sensors/spl06.h"
#include "storage/w25q64.h"
#include "storage/nvs_config.h"
#include "ble/ble_adv.h"
#include "ble/ble_services.h"
#include "ble/ble_ota.h"
#include "led.h"
#include "vdd.h"

// RAM Buffer Definitions
// 注意：RAM_BUFFER_SIZE 由 common.h 统一定义（此处曾重复定义，属"同一宏两处定义"，已清理）。
// 语义变更（D-2）：本缓冲区**不再是"攒满一批再写"的批量缓冲**，而是 Flash 临时失败后的**重试区**；
// 正常情况下每形成一条历史记录就立即落盘，缓冲区随即清空。
static struct data_record ram_buffer[RAM_BUFFER_SIZE];
static uint8_t ram_buffer_count = 0;

// 前向声明
static void history_send_work_handler(struct k_work *work);

// UUID 宏定义使用 ble/ble_services.h 中的版本
// UUID 结构体变量（用于 BT_GATT_SERVICE_DEFINE）
static struct bt_uuid_128 time_sync_char_uuid = BT_UUID_INIT_128(BT_UUID_TIME_SYNC_CHAR_VAL);

static struct bt_uuid_128 config_service_uuid = BT_UUID_INIT_128(BT_UUID_CONFIG_SERVICE_VAL);
static struct bt_uuid_128 interval_char_uuid = BT_UUID_INIT_128(BT_UUID_INTERVAL_CHAR_VAL);
static struct bt_uuid_128 status_char_uuid = BT_UUID_INIT_128(BT_UUID_STATUS_CHAR_VAL);
static struct bt_uuid_128 history_char_uuid = BT_UUID_INIT_128(BT_UUID_HISTORY_CHAR_VAL);
static struct bt_uuid_128 temp_range_char_uuid = BT_UUID_INIT_128(BT_UUID_TEMP_RANGE_CHAR_VAL);
static struct bt_uuid_128 temp_range_reset_char_uuid = BT_UUID_INIT_128(BT_UUID_TEMP_RANGE_RESET_CHAR_VAL);
static struct bt_uuid_128 history_info_char_uuid = BT_UUID_INIT_128(BT_UUID_HISTORY_INFO_CHAR_VAL);
static struct bt_uuid_128 realtime_service_uuid = BT_UUID_INIT_128(BT_UUID_REALTIME_SERVICE_VAL);
static struct bt_uuid_128 realtime_char_uuid = BT_UUID_INIT_128(BT_UUID_REALTIME_CHAR_VAL);
static struct bt_uuid_128 clear_data_service_uuid = BT_UUID_INIT_128(BT_UUID_CLEAR_DATA_SERVICE_VAL);
static struct bt_uuid_128 clear_data_char_uuid = BT_UUID_INIT_128(BT_UUID_CLEAR_DATA_CHAR_VAL);

// I2C 设备（供传感器模块使用）
static const struct device *i2c_dev;

// 蓝牙连接状态
static bool bt_connected = false;
static bool bt_ready = false;  // 蓝牙是否就绪

// 时间同步相关变量
static uint32_t time_base_timestamp = 0;  // 基准时间戳（Unix 时间戳，秒）
static int64_t time_base_uptime = 0;      // 基准时间对应的系统运行时间（毫秒）
static bool time_synced = false;           // 时间是否已同步（含从 NVS 恢复的旧时间）
/* 时间基准是否来自**本次上电的手机对时**（决定是否按墙钟边界结算、是否对外宣称对齐）。
 * 仅从 NVS 恢复的旧时间不满足：设备停机期间秒数停走，恢复出来的时间必然偏慢，
 * 此时若仍按整分网格结算，会造出"看起来权威但绝对时刻是错的"时间戳。
 * 故只做严格等间隔（v6 行为），不宣称落在 :00 —— 见 §12.9。 */
static bool wall_clock_aligned = false;

// 配置相关变量
// FIRMWARE_VERSION / SAMPLE_INTERVAL_FIXED / DEFAULT_HISTORY_INTERVAL / MIN|MAX_HISTORY_INTERVAL
// 统一定义在 storage/nvs_config.h（此处曾重复定义同一批宏，已清理，避免"文档与实现不一致"）。
static uint16_t sample_interval = SAMPLE_INTERVAL_FIXED;      // 采样周期：固定 1 秒（实时显示 + 最高最低温度）
static uint16_t history_interval = DEFAULT_HISTORY_INTERVAL;  // 历史记录落盘周期（秒），60~3600，BLE 可配
static uint16_t record_count = 0;  // 已落盘记录计数（诊断用；对外以 storage_get_total_records() 为准）
static int16_t max_temperature = INT16_MIN;  // 最高温度（0.01°C），初始化为最小值
static int16_t min_temperature = INT16_MAX;  // 最低温度（0.01°C），初始化为最大值
static uint32_t max_temperature_timestamp = 0;  // 最高温度发生时间（Unix时间戳，秒）
static uint32_t min_temperature_timestamp = 0;  // 最低温度发生时间（Unix时间戳，秒）
static uint16_t firmware_version = FIRMWARE_VERSION;  // 固件版本号
static struct k_timer sample_timer;  // 采集定时器
static struct k_work sample_work;  // 采集工作队列
static struct k_work_delayable sample_start_work;  // 首次采集启动延迟工作队列
static struct k_work_delayable config_save_work;  // 配置延迟保存工作队列
static bool config_dirty = false;  // 配置是否已修改但未保存

// ========== 历史记录聚合状态（1 秒采样 -> history_interval 一条均值记录）==========
// 三个通道**独立累加**：某个传感器（尤其气压）故障时，其余通道仍能正常成记录。
// 这是对原实现的修正：原代码气压读失败时写 0，随后又被"气压范围校验"整条丢弃，
// 结果是气压传感器一坏就完全不落盘。
static int32_t  acc_temp_sum;      // 窗口内温度累加（0.01°C）
static uint32_t acc_hum_sum;       // 窗口内湿度累加（0.01%RH）
static uint32_t acc_press_sum;     // 窗口内气压累加（Pa）
static uint16_t acc_temp_n;        // 温度有效样本数
static uint16_t acc_hum_n;         // 湿度有效样本数
static uint16_t acc_press_n;       // 气压有效样本数
static uint16_t window_ticks;      // 窗口内**实际聚合到**的采样节拍数（诊断用；不再是结算依据）
static uint32_t window_deadline_ms;  // 本窗口的结算时刻（uptime ms，绝对网格；回绕安全）
/* 本窗口结算后要写入记录的**墙钟边界时间戳**（interval 的整数倍）。
 * 仅当 wall_clock_aligned 为真时有效；否则为 0，记录时间戳退回"结算时刻的真实秒"。
 * 周期记录用边界值而不是"实际执行完成的时刻"——后者会带 0~1 秒的执行抖动。 */
static uint32_t window_boundary_ts;
static uint32_t last_valid_pressure_pa;  // 最近一次有效气压（气压通道故障时的回退值）
static uint32_t dropped_records;   // Flash 持续失败导致丢弃的记录数（诊断用）

// 事件记录（突变/越限）判定基准
static int16_t  last_record_temp;  // 上一条已落盘记录的温度
static uint16_t last_record_hum;   // 上一条已落盘记录的湿度
static uint32_t last_record_ts;    // 上一条已落盘记录的时间戳
static bool     last_record_valid; // 是否已有可比对的基准记录
static uint8_t  alarm_state;       // 0=正常，1=越限（用于边沿触发）
static uint32_t last_event_uptime_ms;  // 上一条事件记录的时刻（uptime，免疫对时跳变）
static bool     last_event_seen;       // 是否已发过事件记录（限流基准是否有效）
static uint8_t  event_pending_kind;    // 被限流挡下、待补发的事件类型（见 EVENT_KIND_*）

// 写头持久化：storage_write_batch() 发生扇区切换时置位
static bool sector_switched_flag;

// 清空数据控制标志位
static bool data_clear_in_progress = false;  // 清空数据进行中标志，用于停止采集和存储

// 历史数据传输相关变量
static struct k_work_delayable history_send_work;
static bool history_transfer_active = false;
static uint16_t history_transfer_sector = 0;      // 当前传输扇区
static uint16_t history_transfer_record_idx = 0; // 当前扇区内的记录索引
static const struct bt_gatt_attr *history_char_attr = NULL;
static uint32_t history_start_timestamp = 0;  // 起始时间戳（0表示发送全部数据）
static bool history_binary_search_done = false;   // 二分查找是否已完成
static uint32_t history_total_sent_packets = 0;   // 累计发送包数
static uint32_t history_total_sent_records = 0;   // 累计发送记录数
static uint32_t history_expected_records = 0;     // 预期发送总记录数（用于进度显示）
static uint32_t history_last_sent_timestamp = 0;  // 上一条发送记录的时间戳（用于检测跨包乱序）
static uint32_t history_skipped_invalid = 0;      // 跳过的无效记录数（用于统计）
static uint32_t history_skipped_filtered = 0;     // 时间戳过滤跳过的记录数
// 历史数据通知发送控制（使用 bt_gatt_notify_cb 做“发完再发”流控）
static struct bt_gatt_notify_params history_notify_params;
static bool history_sending = false;                      // 是否有在途的历史通知
static uint8_t history_tx_buf[60];                        // 历史数据发送缓冲区（需保持到回调结束）

// W25Q64存储管理变量（提前声明，供history_char_ccc_cfg_changed使用）
static uint16_t next_sector = 0;      // 下一个写入扇区
static uint16_t oldest_sector = 0;    // 最旧扇区
static uint16_t next_record_in_sector = 0;  // 当前扇区内的记录索引

// 统一的历史总记录数计算函数（基于存储位置推算可读记录数）
static uint32_t storage_get_total_records(void)
{
	// 无论是否环形覆盖，总是从 oldest_sector 开始写到 next_sector:next_record_in_sector
	if (next_sector == oldest_sector) {
		// 所有数据都在同一个扇区，或完全为空（next_record_in_sector == 0）
		return next_record_in_sector;
	}

	uint16_t sector_count;
	if (next_sector > oldest_sector) {
		sector_count = next_sector - oldest_sector;
	} else {
		// 环形情况：数据范围 [oldest_sector, MAX) + [0, next_sector)
		sector_count = W25Q64_MAX_SECTORS_LIMIT - oldest_sector + next_sector;
	}

	// 有效记录数 = 完整扇区数 * 每扇区容量 + 写入扇区内的记录数
	return (uint32_t)sector_count * W25Q64_RECORDS_PER_SECTOR + next_record_in_sector;
}

// 配置数据结构已移至 storage/nvs_config.h (device_config_t)

// 蓝牙广播数据使用 ble/ble_adv 模块管理

// // Flash设备
// static const struct device *flash_dev;

// W25Q64 常量使用 storage/w25q64.h 中的定义

// Flash 存储相关声明
static int storage_init(void);
static void storage_write_batch(void);
static void storage_read_history(void);
static uint32_t get_current_timestamp(void);

// NVS 相关已移至 storage/nvs_config.c
// 使用 nvs_config_init(), nvs_config_is_ready() 等接口
static bool position_dirty = false;

/* ================== 历史记录：聚合、事件与落盘策略（D-2）==================
 *
 * 采样：固定 1 秒一次（周期定时器；实时 BLE 显示 + 最高最低温度跟踪）。
 * 记录：窗口结算走 **绝对网格**（见 history_window_realign/advance_deadline），
 *       而不是"数够 N 个节拍"。每个周期**恰好** history_interval 秒，且不累积漂移。
 *       每条记录生成后**立即落盘**，故正常掉电最多丢失不足一个 history_interval 的数据。
 *
 *       【时间戳语义：周期记录 = 边界值，事件记录 = 真实时刻】
 *       有可信墙钟（本次上电收到过手机对时）时，结算网格锚定在**墙钟边界**上：
 *       60 秒 → 每分钟 :00，300 秒 → :00/:05/:10…，公式 `((now/iv)+1)*iv`。
 *       周期记录写入的是**边界时间戳**，不是"工作执行完成的时刻"（后者带 0~1 秒抖动，
 *       可能跨分钟落到错误的一格）。对时或改 history_interval 后**重算下一边界**。
 *       首个窗口可能不足一个周期（12:34:27 对时 → 12:35:00 出首条）：刻意保留这 33 秒，
 *       比"先等到整分再开始累计"少丢一段数据；仅在统计严谨性要求极高时才该丢弃首窗。
 *       事件记录**仍用真实发生时刻**，不做任何对齐。
 *
 *       仅从 NVS 恢复旧时间时**不宣称墙钟对齐**，也不按边界结算（退回严格等间隔）。
 *       设备停机期间秒数停走，恢复出来的基准必然偏慢，按它算整分只会造出
 *       "看起来权威、绝对时刻是错的"时间戳。该状态通过状态帧能力位对外披露。
 *       **存量记录不重写**：允许升级点前后时间戳规则不同，整齐与否交给 App 显示层分桶。
 *
 *       【与存储无关】以上全部只影响"记录里写什么时间戳"和"何时结算"，
 *       **绝不触碰 NVS 写头 / 环形缓冲位置**。写头只表示下一条物理写入位置，
 *       与分钟边界没有任何关系，不得为了时间对齐而移动或归零。
 *
 *       【为什么必须换成绝对网格】早期实现是"单次定时器 + 工作完成后重武装 1 秒"，
 *       于是实际周期 = 1 秒 + 工作耗时 ≈ 1.13 秒，60 拍实测约 68 秒/条。
 *       周期定时器可以消掉"重武装"这一项，但只要某个节拍因为工作队列忙而被丢弃
 *       （k_work_submit 对正在运行的 work 返回 -EBUSY），"数节拍"仍会漂移。
 *       只有按 uptime 结算才能对"真实分钟边界"给出硬保证。
 *
 * 事件：温湿度突变或越限时，额外立即追加一条**瞬时**记录，不打断均值窗口。
 *       所有事件共用一个限流闸门（EVENT_KIND_* + gap 判定），越限/恢复边沿**不例外**；
 *       被闸门挡下的事件不丢弃，记入 event_pending_kind 延后补发（见 history_maybe_event）。
 *
 * 缓冲：RAM 缓冲（RAM_BUFFER_SIZE 条）只作为 Flash 临时失败后的**重试区**，
 *       写入失败时保留未写入部分、下一拍继续尝试，不再作为批量写入条件。
 * 写头：NVS 中的写头位置在**扇区切换时立即持久化**，其余由 30 秒定时 flush 兜底。
 *
 * 事件记录的容量影响（必须知情）：事件记录会额外占用容量。
 *   名义（无事件）：65000 × history_interval / 86400 天 —— 60 秒时 45.1 天。
 *   限流上限为"**任意两条事件记录之间至少间隔一个 history_interval**"（单一闸门，
 *   越限/恢复/突变一视同仁），故最坏情况记录速率为名义的 **2 倍**。
 *   这条 2 倍上界是"30 天承诺"的推导前提：一旦闸门被绕过（历史上越限边沿就绕过过），
 *   抖动信号可以每秒追加一条，速率变成名义的 60 倍，容量口径直接失效。
 *   产品承诺 30 天按 60 秒周期 + 现实事件率成立；若事件长期饱和，保留期会缩短。
 */

/* 事件记录阈值（初值，可按现场标定） */
#define EVENT_RECORDS_ENABLE        1        /* 0 = 关闭事件记录，只落周期均值 */
#define EVENT_SUDDEN_TEMP_DELTA     100      /* 温度突变阈值：1.00°C */
#define EVENT_SUDDEN_HUM_DELTA      500      /* 湿度突变阈值：5.00%RH */
#define EVENT_TEMP_ALARM_LOW        (-2000)  /* 越限下限：-20.00°C */
#define EVENT_TEMP_ALARM_HIGH       6000     /* 越限上限：+60.00°C */
#define EVENT_HUM_ALARM_LOW         500      /* 越限下限：5.00%RH */
#define EVENT_HUM_ALARM_HIGH        9500     /* 越限上限：95.00%RH */

/* 待补发事件的类型。优先级：越限/恢复 > 突变（越限信息量更大，不能被突变挤掉） */
enum {
	EVENT_KIND_NONE = 0,
	EVENT_KIND_ALARM,        /* 进入越限 */
	EVENT_KIND_ALARM_CLEAR,  /* 越限恢复 */
	EVENT_KIND_SUDDEN,       /* 温湿度突变 */
};

/* 预计保留天数（名义速率，供 BLE 状态帧上报） */
static uint16_t history_retention_days(void)
{
	uint32_t days = ((uint32_t)W25Q64_MAX_RECORDS * history_interval) / 86400U;
	return (days > 0xFFFFU) ? 0xFFFFU : (uint16_t)days;
}

/* 四舍五入的整数除法：温度可负，除法向零截断 */
static int32_t div_round_s32(int32_t sum, uint16_t n)
{
	if (n == 0U) {
		return 0;
	}
	if (sum >= 0) {
		return (sum + (int32_t)(n / 2U)) / (int32_t)n;
	}
	return -(((-sum) + (int32_t)(n / 2U)) / (int32_t)n);
}

static uint32_t div_round_u32(uint32_t sum, uint16_t n)
{
	if (n == 0U) {
		return 0U;
	}
	return (sum + (uint32_t)(n / 2U)) / (uint32_t)n;
}

/* 清空当前聚合窗口的累加器。
 * 注意：**不动 window_deadline_ms** —— 结算时刻由网格推进，不随结算成功与否改变。
 * 需要"从现在起重新数一个完整周期（并对齐到墙钟边界）"时，请用 history_window_realign()。 */
static void history_window_reset(void)
{
	acc_temp_sum = 0;
	acc_hum_sum = 0;
	acc_press_sum = 0;
	acc_temp_n = 0;
	acc_hum_n = 0;
	acc_press_n = 0;
	window_ticks = 0;
}

/* 归一化后的历史周期（秒）：防御 history_interval 越界或为 0。
 * 0 会让网格推进变成死循环（+0 永远追不上 now），而它跑在系统工作队列上，会把系统拖死。 */
static inline uint16_t history_period_sec(void)
{
	uint16_t iv = history_interval;
	if (iv < MIN_HISTORY_INTERVAL || iv > MAX_HISTORY_INTERVAL) {
		iv = DEFAULT_HISTORY_INTERVAL;
	}
	return iv;
}

static inline uint32_t history_period_ms(void)
{
	return (uint32_t)history_period_sec() * 1000U;
}

/* 是否具备**可信墙钟**：时间基准来自本次上电的手机对时。
 * 只有它成立时才按墙钟边界结算，并对外宣称"周期记录落在整分/整 5 分"。 */
static inline bool wallclock_ready(void)
{
	return time_synced && wall_clock_aligned;
}

/* 下一个墙钟边界：`((now / interval) + 1) * interval`。
 * 60 秒 → 下一分钟 :00；300 秒 → 下一个 :00/:05/:10…；3600 秒 → 下一个整点。
 * 用整数除法直接取整，不依赖任何日历运算。 */
static uint32_t next_wallclock_boundary(uint32_t now_ts)
{
	uint32_t iv = history_period_sec();
	return ((now_ts / iv) + 1U) * iv;
}

/* 把墙钟秒换算成 uptime 毫秒。
 * 用 int32 求"秒差"再乘 1000：直接对 uint32 秒做 (ts - base) * 1000 会溢出。
 * int32 秒差的可表达范围是 ±68 年，足够覆盖对时偏差。 */
static uint32_t wallclock_ts_to_uptime_ms(uint32_t ts)
{
	int64_t delta_ms = (int64_t)(int32_t)(ts - time_base_timestamp) * 1000;
	return (uint32_t)(time_base_uptime + delta_ms);
}

/* 重新对齐窗口：清累加器 + 把结算时刻落到网格上。
 * 用于对时、周期配置变更、清空数据、首次启动等会打断节奏的场景。
 *
 * 有可信墙钟 → 对齐到**下一个整边界**。首个窗口可能不足一个周期（例如 12:34:27 对时，
 *   12:35:00 就出第一条），这是刻意的：比"先等到整分再开始累计"少丢 33 秒数据，
 *   而首条记录的时间戳依然整齐。事件记录不受影响（仍用真实发生时刻）。
 * 无可信墙钟 → 退回"从现在起一个完整周期"：严格等间隔，但**不宣称**落在 :00。 */
static void history_window_realign(void)
{
	history_window_reset();

	if (!wallclock_ready()) {
		window_boundary_ts = 0;
		window_deadline_ms = k_uptime_get_32() + history_period_ms();
		return;
	}

	window_boundary_ts = next_wallclock_boundary(get_current_timestamp());
	window_deadline_ms = wallclock_ts_to_uptime_ms(window_boundary_ts);
}

/* 窗口结算时刻是否已到（uptime ms 回绕安全：用有符号差判正负） */
static inline bool history_window_due(void)
{
	return (int32_t)(k_uptime_get_32() - window_deadline_ms) >= 0;
}

/* 按**绝对网格**推进结算时刻：跳到严格晚于 now 的下一个网格点
 * （而非 deadline = now + period）。这样节拍抖动、丢拍、本函数被迟调用都不会累积成漂移
 * —— 这正是"真实分钟边界"的来源。
 *
 * 用整除一次算到位，而不是 do-while 循环：设备可能被调试器 halt 住很久或长时间阻塞，
 * 那时差可能是几十万毫秒，循环要跑上千次。取法保证推进后**严格晚于** now，
 * 即一个周期只结算一条记录，不会为"错过的窗口"补出一串空记录
 * （那段时间本来就没采到数据，补记录等于伪造数据点）。 */
static void history_window_advance_deadline(void)
{
	uint32_t period = history_period_ms();

	if (!wallclock_ready()) {
		/* 无墙钟基准：纯 uptime 网格，严格等间隔（v6 行为） */
		int32_t behind_ms = (int32_t)(k_uptime_get_32() - window_deadline_ms);

		if (behind_ms >= 0) {
			window_deadline_ms += ((uint32_t)behind_ms / period + 1U) * period;
		} else {
			window_deadline_ms += period;
		}
		return;
	}

	uint32_t iv     = history_period_sec();
	uint32_t now_ts = get_current_timestamp();
	int32_t  behind = (int32_t)(now_ts - window_boundary_ts);

	if (behind < 0) {
		/* 时钟被往回对时：重算边界，而不是在当前边界上硬加一个周期 */
		window_boundary_ts = next_wallclock_boundary(now_ts);
		window_deadline_ms = wallclock_ts_to_uptime_ms(window_boundary_ts);
		return;
	}

	window_boundary_ts += ((uint32_t)behind / iv + 1U) * iv;
	window_deadline_ms = wallclock_ts_to_uptime_ms(window_boundary_ts);
}

/* 把 ram_buffer 中待写记录写入 Flash。
 * storage_write_batch() 的约定：失败时把**未写入**的记录保留在缓冲区前部（ram_buffer_count > 0）。*/
static void history_flush_buffer(void)
{
	if (ram_buffer_count == 0U) {
		return;
	}
	if (!time_synced) {
		/* 记录只有在时间已同步时才会生成；此处保留缓冲，等时间同步后补写 */
		return;
	}
	if (!w25q64_is_ready()) {
		static int not_ready_log;
		if (not_ready_log++ % 30 == 0) {
			printk("[存储] W25Q64 未就绪，%d 条记录留在缓冲区等待重试\r\n",
			       ram_buffer_count);
		}
		return;
	}

	uint8_t pending = ram_buffer_count;

	storage_write_batch();

	if (sector_switched_flag) {
		/* 写头跨扇区：立即持久化，缩短掉电后写头不一致的窗口 */
		sector_switched_flag = false;
		storage_position_t pos = {
			.next_sector = next_sector,
			.oldest_sector = oldest_sector,
			.next_record_in_sector = next_record_in_sector
		};
		nvs_save_storage_position(&pos);
		position_dirty = false;
	}

	if (ram_buffer_count > 0U) {
		printk("[存储] 落盘未完成: %d 条待重试 (本次待写 %d 条)\r\n",
		       ram_buffer_count, pending);
	}
}

/* 追加一条记录到缓冲区并立即尝试落盘 */
static void history_push_record(uint32_t ts, int16_t temperature,
				uint16_t humidity, uint32_t pressure_pa)
{
	/* OTA 期间暂停历史落盘：与 OTA 共用同一颗 W25Q64，且 SPI 争用会造成
	 * OTA 写入的延迟抖动。这里只跳过本窗口的采样，不进重试缓冲 ——
	 * 避免 OTA 结束后一次性补写，重新制造争用。 */
	if (ble_ota_in_progress()) {
		return;
	}

	if (ram_buffer_count >= RAM_BUFFER_SIZE) {
		/* 缓冲区满：说明 Flash 连续失败。丢弃最旧一条，优先保住最新数据 */
		memmove(&ram_buffer[0], &ram_buffer[1],
			sizeof(ram_buffer[0]) * (RAM_BUFFER_SIZE - 1));
		ram_buffer_count = RAM_BUFFER_SIZE - 1;
		dropped_records++;
		printk("[存储] 警告: 重试缓冲已满，丢弃最旧记录 (累计丢弃 %u 条)\r\n",
		       dropped_records);
	}

	ram_buffer[ram_buffer_count].timestamp = ts;
	ram_buffer[ram_buffer_count].temperature = temperature;
	ram_buffer[ram_buffer_count].humidity = humidity;
	ram_buffer[ram_buffer_count].pressure_pa = pressure_pa;
	ram_buffer_count++;
	record_count++;

	history_flush_buffer();
}

/* 窗口结束：生成周期均值记录。
 * 成记录条件：本窗口有**温度**有效样本且时间已同步。
 * 湿度/气压缺失时分别回退到上一条记录值与最近一次有效气压，避免因单一传感器故障丢整条记录。
 *
 * 时间戳取**窗口边界**（wallclock_ready 时为 interval 的整数倍），而不是"工作执行完成的时刻"：
 * 结算发生在边界之后的第一个采样拍上，用完成时刻会引入 0~1 秒抖动，
 * 甚至跨分钟落到错误的一格里。无可信墙钟时退回真实秒（此时本来就不宣称对齐）。*/
static void history_commit_average(void)
{
	uint32_t ts = (wallclock_ready() && window_boundary_ts != 0U)
			      ? window_boundary_ts
			      : get_current_timestamp();

	if (acc_temp_n == 0U) {
		static int empty_log;
		if (empty_log++ % 5 == 0) {
			printk("[存储] 本窗口无有效温度采样或无时间基准，跳过均值记录\r\n");
		}
		history_window_reset();
		return;
	}
	if (ts == 0U) {
		printk("[存储] 时间未同步，丢弃本窗口均值记录\r\n");
		history_window_reset();
		return;
	}

	int32_t  t_avg = div_round_s32(acc_temp_sum, acc_temp_n);
	uint32_t h_avg = (acc_hum_n > 0U)
			 ? div_round_u32(acc_hum_sum, acc_hum_n)
			 : (uint32_t)(last_record_valid ? last_record_hum : 0U);
	uint32_t p_avg = (acc_press_n > 0U)
			 ? div_round_u32(acc_press_sum, acc_press_n)
			 : (last_valid_pressure_pa ? last_valid_pressure_pa : 101325U);

	printk("[存储] 周期记录(均值 %u 点 / 温%u 湿%u 压%u): 时间戳=%u T=%d.%02d H=%u.%02u P=%uPa\r\n",
	       window_ticks, acc_temp_n, acc_hum_n, acc_press_n, ts,
	       t_avg / 100, (t_avg >= 0 ? t_avg : -t_avg) % 100,
	       h_avg / 100, h_avg % 100, p_avg);

	history_push_record(ts, (int16_t)t_avg, (uint16_t)h_avg, p_avg);

	/* 更新突变判定基准：与"上一条已落盘记录"比较才有意义 */
	last_record_temp = (int16_t)t_avg;
	last_record_hum = (uint16_t)h_avg;
	last_record_ts = ts;
	last_record_valid = true;

	history_window_reset();
}

/* 事件记录：越限/恢复边沿 与 温湿度突变，共用**同一个限流闸门**。
 *
 * 关键约束（30 天容量口径的推导前提）：任意两条事件记录之间至少间隔一个 history_interval，
 * 因此总记录速率上界 = 名义的 2 倍。越限/恢复边沿**不享受任何豁免** —— 早期实现里
 * `if (alarm_edge) { emit = true; }` 完全绕过 gap 判定，阈值附近抖动的信号会每秒追加一条，
 * 速率变成名义的 60 倍，既推翻文档承诺的 2 倍上界，也让保留期失去可预测性。
 *
 * 为不让"越限边沿被限流直接丢掉"，被挡下的意图记入 event_pending_kind，
 * 间隔满足后的第一拍补发（最坏延后一个 history_interval，事件不丢）。
 *
 * 限流基准用 **uptime** 而非记录时间戳：对时基准被改写（手机重新对时）时
 * 时间戳可能跳变，用它算间隔会得出错误结果。 */
static void history_maybe_event(int32_t temperature, uint32_t humidity,
				uint32_t pressure_pa, uint32_t ts)
{
#if EVENT_RECORDS_ENABLE
	if (ts == 0U) {
		return;
	}

	bool alarm = (temperature < EVENT_TEMP_ALARM_LOW) ||
		     (temperature > EVENT_TEMP_ALARM_HIGH) ||
		     (humidity < EVENT_HUM_ALARM_LOW) ||
		     (humidity > EVENT_HUM_ALARM_HIGH);
	bool alarm_edge = (alarm != (alarm_state != 0U));

	/* 无论本拍是否真的落记录，越限状态都要跟着走：
	 * 否则被限流挡下的边沿会在下一拍重复判成"边沿"，边沿语义就没了。 */
	alarm_state = alarm ? 1U : 0U;

	bool sudden = false;
	if (last_record_valid) {
		int32_t dt = (int32_t)temperature - (int32_t)last_record_temp;
		int32_t dh = (int32_t)humidity - (int32_t)last_record_hum;
		if (dt < 0) {
			dt = -dt;
		}
		if (dh < 0) {
			dh = -dh;
		}
		sudden = (dt >= EVENT_SUDDEN_TEMP_DELTA) || (dh >= EVENT_SUDDEN_HUM_DELTA);
	}

	/* 登记事件意图（不立即发送）：越限/恢复覆盖突变，突变不挤掉越限 */
	if (alarm_edge) {
		event_pending_kind = alarm ? EVENT_KIND_ALARM : EVENT_KIND_ALARM_CLEAR;
	} else if (sudden && event_pending_kind == EVENT_KIND_NONE) {
		event_pending_kind = EVENT_KIND_SUDDEN;
	}

	if (event_pending_kind == EVENT_KIND_NONE) {
		return;
	}

	/* 限流闸门：所有事件类型共用，越限/恢复不例外。
	 * "是否发过第一条"用独立布尔判定，不用 last_event_uptime_ms == 0 当哨兵 ——
	 * 万一第一条事件恰好发生在 uptime 0 ms，哨兵就永远为真，闸门形同虚设。 */
	bool gap_ok = !last_event_seen ||
		      ((uint32_t)(k_uptime_get_32() - last_event_uptime_ms) >= history_period_ms());
	if (!gap_ok) {
		return;   /* 保留 event_pending_kind，下一拍继续尝试补发 */
	}

	const char *why = (event_pending_kind == EVENT_KIND_ALARM)        ? "越限"
			  : (event_pending_kind == EVENT_KIND_ALARM_CLEAR) ? "越限恢复"
									  : "突变";
	event_pending_kind = EVENT_KIND_NONE;

	printk("[事件] %s: 时间戳=%u T=%d.%02d H=%u.%02u P=%uPa\r\n",
	       why, ts,
	       temperature / 100,
	       (temperature >= 0 ? temperature : -temperature) % 100,
	       humidity / 100, humidity % 100, pressure_pa);

	history_push_record(ts, (int16_t)temperature,
			    (uint16_t)humidity, pressure_pa);

	last_event_uptime_ms = k_uptime_get_32();
	last_event_seen = true;
	last_record_temp = (int16_t)temperature;
	last_record_hum = (uint16_t)humidity;
	last_record_ts = ts;
	last_record_valid = true;
#else
	(void)temperature;
	(void)humidity;
	(void)pressure_pa;
	(void)ts;
#endif
}

// RAM_BUFFER_SIZE moved to top
// struct data_record moved to top
// ram_buffer moved to top


// ========== I2C 设备电源管理（使用 Zephyr PM 子系统）==========
// 预期功耗收益：I2C空闲时从 ~70-200μA 降至 <1μA

// 恢复 I2C 设备（从挂起状态唤醒）
// 与“读特征”路径一致：两条路径都是 resume → [延时] → AHT30 → SPL06；若延时过短，
// SPL06 冷启动后首读易不稳定（如 600 vs 1100），故预留足够时间再读（SPL06 手册建议上电稳定后再转换）
// static int i2c_power_resume(void)
// {
// 	if (i2c_dev == NULL) {
// 		return -ENODEV;
// 	}
// 	int ret = pm_device_action_run(i2c_dev, PM_DEVICE_ACTION_RESUME);
// 	if (ret < 0 && ret != -EALREADY) {
// 		printk("[I2C PM] 恢复失败: %d\r\n", ret);
// 		return ret;
// 	}
// 	// 等待 I2C 及 SPL06 上电稳定（约 15ms），避免冷启动首读偏差大（如 600/1100 差异）
// 	k_busy_wait(15000);  // 15 ms
// 	return 0;
// }

// 挂起 I2C 设备（进入低功耗状态）
// static int i2c_power_suspend(void)
// {
// 	if (i2c_dev == NULL) {
// 		return -ENODEV;
// 	}
// 	int ret = pm_device_action_run(i2c_dev, PM_DEVICE_ACTION_SUSPEND);
// 	if (ret < 0 && ret != -EALREADY) {
// 		printk("[I2C PM] 挂起失败: %d\r\n", ret);
// 		return ret;
// 	}
// 	return 0;
// }

/* AHT30 的 I2C 地址，与 sensors/aht30.h 一致，用于扫描结果提示 */
#define I2C_SCAN_AHT30_ADDR  0x38U

/* 启动时 I2C 总线扫描（7-bit 地址 0x08~0x77），并提示 AHT30(0x38) 是否在总线上 */
static void i2c_scan_bus(const struct device *dev)
{
	uint8_t addr;
	uint8_t dummy;
	bool aht30_found = false;

	printk("[I2C] 扫描总线 (0x08~0x77)...\r\n");
	for (addr = 0x08U; addr <= 0x77U; addr++) {
		int ret = i2c_read(dev, &dummy, 1, addr);
		if (ret == 0) {
			printk("[I2C] 发现设备: 0x%02X\r\n", addr);
			if (addr == I2C_SCAN_AHT30_ADDR) {
				aht30_found = true;
			}
		}
	}
	printk("[I2C] 扫描完成");
	if (aht30_found) {
		printk(" | AHT30 (0x38): 已发现\r\n");
	} else {
		printk(" | AHT30 (0x38): 未发现，请检查接线与供电\r\n");
	}
}



// ========== 配置持久化函数（使用 storage/nvs_config 模块）==========

// 本地 NVS flush 包装（配置 + 位置）
static void local_nvs_flush(void)
{
	if (!nvs_config_is_ready()) {
		return;
	}
	/* OTA 期间**延迟** NVS 写入：历史/NVS 与 OTA 共用同一颗 W25Q64 与同一条 SPI
	 * （nvs_config.c 用的就是 w25q64 的互斥量），并发访问会互相破坏。
	 * 这里是延迟而非丢弃 —— dirty 标志保持，OTA 结束或重启前会再落盘。 */
	if (ble_ota_in_progress()) {
		return;
	}
	if (config_dirty) {
		device_config_ctx_t ctx = {
			.sample_interval = sample_interval,
			.history_interval = history_interval,
			.max_temperature = max_temperature,
			.min_temperature = min_temperature,
			.max_temperature_timestamp = max_temperature_timestamp,
			.min_temperature_timestamp = min_temperature_timestamp,
			.firmware_version = firmware_version,
			.config_dirty = true,
			.position_dirty = false
		};
		nvs_flush(&ctx, NULL);
		if (!ctx.config_dirty) {
			config_dirty = false;
			printk("[NVS] 配置已写入 采样=%d秒 历史=%d秒 最高=%d.%02d(时间戳:%u) 最低=%d.%02d(时间戳:%u)\r\n",
			       sample_interval,
			       history_interval,
			       max_temperature / 100, (max_temperature >= 0 ? max_temperature : -max_temperature) % 100,
			       max_temperature_timestamp,
			       min_temperature / 100, (min_temperature >= 0 ? min_temperature : -min_temperature) % 100,
			       min_temperature_timestamp);
		}
	}
	if (position_dirty) {
		storage_position_t pos = {
			.next_sector = next_sector,
			.oldest_sector = oldest_sector,
			.next_record_in_sector = next_record_in_sector
		};
		nvs_save_storage_position(&pos);
		position_dirty = false;
	}
}

// 加载配置（从 NVS 读取，无 NVS 时用默认值）
static void load_config(void)
{
	device_config_ctx_t ctx;
	nvs_load_config(&ctx);
	
	// 从上下文复制到全局变量
	sample_interval = SAMPLE_INTERVAL_FIXED;   // 采样固定 1 秒，不再来自 NVS
	history_interval = ctx.history_interval;   // 历史记录落盘周期（60~3600 秒）
	max_temperature = ctx.max_temperature;
	min_temperature = ctx.min_temperature;
	max_temperature_timestamp = ctx.max_temperature_timestamp;
	min_temperature_timestamp = ctx.min_temperature_timestamp;
	firmware_version = ctx.firmware_version;
}

// NVS 定时 flush 工作函数（配置 + 位置，有 dirty 才写）
static void config_save_work_handler(struct k_work *work)
{
	local_nvs_flush();
}

// 30s 定时 flush 定时器回调
static void nvs_flush_timer_handler(struct k_timer *timer);
static K_TIMER_DEFINE(nvs_flush_timer, nvs_flush_timer_handler, NULL);

static void nvs_flush_timer_handler(struct k_timer *timer)
{
	k_work_schedule(&config_save_work, K_NO_WAIT);
}

// 保存配置（NVS 定时写入，不立即写除非 immediate）
// immediate: true 立即 flush；false 仅标记 dirty，等 5s 延迟或 30s 定时 flush
static void save_config(bool immediate)
{
	config_dirty = true;
	if (immediate) {
		k_work_cancel_delayable(&config_save_work);
		config_save_work_handler(NULL);
	} else {
		k_work_cancel_delayable(&config_save_work);
		k_work_schedule(&config_save_work, K_SECONDS(5));
	}
}

// ========== 配置服务GATT回调函数 ==========

// 历史记录间隔特性数据缓冲区（uint16_t，2字节）
// 注意：UUID 12340021 与 2 字节格式保持不变（对旧 App 影响最小），但**语义已变更**：
// v3 起该特性表示"历史记录落盘间隔（秒）"，不再表示采样间隔（采样固定 1 秒）。
// App 侧可通过状态特性的能力标志（byte5 bit0）判定固件是否已采用新语义。
static uint8_t interval_char_data[2];

// 历史记录间隔特性读取回调
static ssize_t interval_char_read_cb(struct bt_conn *conn,
				     const struct bt_gatt_attr *attr,
				     void *buf, uint16_t len, uint16_t offset)
{
	// 更新数据
	sys_put_le16(history_interval, interval_char_data);
	
	return bt_gatt_attr_read(conn, attr, buf, len, offset,
				 interval_char_data, sizeof(interval_char_data));
}

// 历史记录间隔特性写入回调
static ssize_t interval_char_write_cb(struct bt_conn *conn,
				       const struct bt_gatt_attr *attr,
				       const void *buf, uint16_t len, uint16_t offset,
				       uint8_t flags)
{
	if (len != 2) {
		printk("[配置] 错误: 数据长度不正确 (%d 字节，需要 2 字节)\r\n", len);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	
	// 解析新的历史记录间隔
	uint16_t new_interval = sys_get_le16(buf);
	
	// 验证范围。下限 60 秒是**容量红线**（65000 条 × 60 秒 = 45.1 天），低于它无法兑现 30 天保留承诺
	if (new_interval < MIN_HISTORY_INTERVAL || new_interval > MAX_HISTORY_INTERVAL) {
		printk("[配置] 错误: 历史记录间隔超出范围 (%d，有效范围: %d-%d)\r\n",
		       new_interval, MIN_HISTORY_INTERVAL, MAX_HISTORY_INTERVAL);
		return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
	}
	
	// 更新配置
	history_interval = new_interval;
	printk("[配置] 历史记录间隔已更新: %d 秒（预计保留 %u 天）\r\n",
	       history_interval, history_retention_days());
	
	// 保存到Flash（立即保存，因为是用户主动操作）
	save_config(true);
	
	// 重置聚合窗口，让新周期立即生效（丢弃当前未满一周期的样本，最多损失 history_interval 秒）
	// 必须用 realign 而非 reset：周期变了，结算网格要按**新周期重算下一边界**，
	// 否则 deadline 还停留在旧网格上，改大周期后会立刻结算出一条只覆盖几秒的假记录。
	// 这里就是"60 秒改 300 秒后，下一条落在最近的 5 分钟边界"的实现点。
	history_window_realign();
	
	// 不再重启采样定时器：采样周期固定 1 秒，与历史记录周期完全解耦。
	
	// 更新特性数据并通知客户端（修复：原实现通知的是上次读取留下的旧值）
	sys_put_le16(history_interval, interval_char_data);
	bt_gatt_notify(NULL, attr, interval_char_data, sizeof(interval_char_data));
	
	return len;
}

// 当前状态特性数据缓冲区（提前定义，供其他函数使用）
//
// v3 帧格式（12 字节；**向后兼容**：前 7 字节与 v2 布局一致，App 按偏移解析不受影响）：
//   [0:2]   历史记录间隔（秒）—— v2 语义为"采集间隔"，App 应结合能力标志改名显示
//   [2:4]   已存储记录条数（截断到 uint16）
//   [4]     状态位：bit0 已连接 / bit1 时间已同步 / bit2 清空中
//   [5]     能力标志（v3 新增；v2 固件该字节恒为 0）
//   [6:8]   固件版本号
//   [8:10]  采样间隔（v3 新增，恒为 1 秒）
//   [10:12] 名义预计保留天数（v3 新增）
#define STATUS_CHAR_LEN 12

/* 状态特性能力标志（byte5）；旧固件该字节为 0，App 据此区分语义 */
#define CAP_HISTORY_INTERVAL  0x01U  /* 12340021 已改为"历史记录间隔"语义 */
#define CAP_SAMPLE_FIXED      0x02U  /* 采样固定 1 秒，不可配置 */
#define CAP_EVENT_RECORDS     0x04U  /* 支持突变/越限事件记录 */
#define CAP_AVERAGE_RECORDS   0x08U  /* 历史记录为周期均值 */
#define CAP_WALLCLOCK_ALIGN   0x10U  /* 周期记录时间戳按墙钟边界对齐（60→:00；300→:00/:05/…）*/
#define CAP_PARTIAL_WINDOW    0x20U  /* 对时后首个窗口可能短于一个周期（保留部分窗口而非丢弃）*/

static uint8_t status_char_data[STATUS_CHAR_LEN];

// 保存状态特性属性指针，供通知使用
static const struct bt_gatt_attr *status_char_attr = NULL;

// 统一构造状态帧（原先在读回调/采集/清空三处各写一遍，容易漂移）
static void status_char_build(void)
{
	uint32_t stored = storage_get_total_records();

	sys_put_le16(history_interval, &status_char_data[0]);
	sys_put_le16((uint16_t)MIN(stored, (uint32_t)0xFFFF), &status_char_data[2]);
	status_char_data[4] = (bt_connected ? 0x01 : 0x00) |
			      (time_synced ? 0x02 : 0x00) |
			      (data_clear_in_progress ? 0x04 : 0x00) |
			      /* bit3：墙钟可信（本次上电收到过手机对时）。
			       * 仅从 NVS 恢复旧时间时为 0 —— 此时周期记录不按整分网格结算，
			       * App 不应假定时间戳落在 :00。 */
			      (wallclock_ready() ? 0x08 : 0x00);
	status_char_data[5] = CAP_HISTORY_INTERVAL | CAP_SAMPLE_FIXED |
			      CAP_EVENT_RECORDS | CAP_AVERAGE_RECORDS |
			      CAP_WALLCLOCK_ALIGN | CAP_PARTIAL_WINDOW;
	sys_put_le16(firmware_version, &status_char_data[6]);
	sys_put_le16(SAMPLE_INTERVAL_FIXED, &status_char_data[8]);
	sys_put_le16(history_retention_days(), &status_char_data[10]);
}

// 当前状态特性读取回调
static ssize_t status_char_read_cb(struct bt_conn *conn,
				   const struct bt_gatt_attr *attr,
				   void *buf, uint16_t len, uint16_t offset)
{
	// 保存属性指针（第一次调用时）
	if (status_char_attr == NULL) {
		status_char_attr = attr;
	}
	
	status_char_build();
	
	return bt_gatt_attr_read(conn, attr, buf, len, offset,
				 status_char_data, sizeof(status_char_data));
}

// 当前状态特性通知配置回调
static void status_char_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	bool notify_enabled = (value == BT_GATT_CCC_NOTIFY);
	printk("[配置] 状态通知: %s\r\n", notify_enabled ? "启用" : "禁用");
}

// 历史数据特性写入回调（用于接收起始时间戳）
static ssize_t history_char_write_cb(struct bt_conn *conn,
				      const struct bt_gatt_attr *attr,
				      const void *buf, uint16_t len, uint16_t offset,
				      uint8_t flags)
{
	// 检查数据长度（必须是4字节，表示Unix时间戳）
	if (len != 4) {
		printk("[历史数据] 错误: 数据长度不正确 (%d 字节，需要 4 字节)\r\n", len);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}

	// 解析起始时间戳（小端序）
	uint32_t timestamp = sys_get_le32(buf);
	
	// 保存起始时间戳
	history_start_timestamp = timestamp;
	
	printk("[历史数据] 设置起始时间戳: %u\r\n", timestamp);
	
	// 如果时间戳为0，表示发送全部数据；清除二分查找标记，让下次传输从 oldest_sector 开始
	if (timestamp == 0) {
		history_binary_search_done = false;
		printk("[历史数据] 将发送全部历史数据\r\n");
	} else {
		printk("[历史数据] 将只发送时间戳大于 %u 的数据\r\n", timestamp);
	}
	
	return len;
}

// 历史记录信息特性读取回调（返回总记录数、起始扇区、起始记录索引）
static ssize_t history_info_char_read_cb(struct bt_conn *conn,
					  const struct bt_gatt_attr *attr,
					  void *buf, uint16_t len, uint16_t offset)
{
	// 统一使用 storage_get_total_records() 计算总记录数
	uint32_t total_records = storage_get_total_records();
	uint16_t start_sector = 0;
	uint16_t start_record_idx = 0;

	if (total_records > 0) {
		// 有数据时，从最旧扇区的第0条开始线性读取
		start_sector = oldest_sector;
		start_record_idx = 0;
	}
	
	// 准备返回数据：总记录数(4字节) + 起始扇区(2字节) + 起始记录索引(2字节) = 8字节
	uint8_t data[8];
	sys_put_le32(total_records, &data[0]);
	sys_put_le16(start_sector, &data[4]);
	sys_put_le16(start_record_idx, &data[6]);
	
	printk("[历史信息] 读取: 总记录数=%u, 起始扇区=%u, 起始索引=%u\r\n",
	       total_records, start_sector, start_record_idx);
	
	return bt_gatt_attr_read(conn, attr, buf, len, offset, data, sizeof(data));
}

// 历史数据特性通知配置回调
static void history_char_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	// 注意：attr 是 CCC 描述符的属性，不是特征值的属性
	// 为了发送通知，我们需要特征值的属性
	// 在这里，我们通过向前查找来找到特征值属性
	// 布局通常是：... [特征声明] [特征值] [CUD] [CCC] ...
	// 或者如果没有 CUD：... [特征声明] [特征值] [CCC] ...
	
	// 更安全的方法是遍历服务属性找到匹配 UUID 的属性
	// 但在这里我们简单地假设特征值就在 CCC 之前几个位置
	// 根据定义：
	// BT_GATT_CHARACTERISTIC(...) -> 声明 + 值
	// BT_GATT_CUD(...) -> CUD (可选)
	// BT_GATT_CCC(...) -> CCC
	
	// 在我们的定义中：
	// BT_GATT_CHARACTERISTIC(&history_char_uuid.uuid, ...)
	// BT_GATT_CUD("History Data", ...)
	// BT_GATT_CCC(history_char_ccc_cfg_changed, ...)
	
	// 所以顺序是：[声明] [值] [CUD] [CCC(当前attr)]
	// 因此特征值属性应该是 attr - 2
	
	// 验证一下 UUID 是否匹配
	const struct bt_gatt_attr *val_attr = attr - 2;
	
	// 检查 val_attr 的 UUID 是否是 history_char_uuid
	if (bt_uuid_cmp(val_attr->uuid, &history_char_uuid.uuid) == 0) {
		history_char_attr = val_attr;
		printk("[配置] 已定位到历史数据特征值句柄: %d (CCC句柄: %d)\r\n", 
		       bt_gatt_attr_get_handle(val_attr), bt_gatt_attr_get_handle(attr));
	} else {
		// 如果不匹配，尝试 attr - 1 (如果没有 CUD)
		val_attr = attr - 1;
		if (bt_uuid_cmp(val_attr->uuid, &history_char_uuid.uuid) == 0) {
			history_char_attr = val_attr;
			printk("[配置] 已定位到历史数据特征值句柄 (无CUD): %d\r\n", 
			       bt_gatt_attr_get_handle(val_attr));
		} else {
			printk("[配置] 错误: 无法定位历史数据特征值属性! 使用 CCC 属性代替 (可能导致通知失败)\r\n");
			history_char_attr = attr;
		}
	}

	bool notify_enabled = (value == BT_GATT_CCC_NOTIFY);
	printk("[历史] 数据通知: %s\r\n", notify_enabled ? "启用" : "禁用");
	
	if (notify_enabled) {
		// 启用通知，开始发送历史数据
		// 强制重置状态，确保每次启用通知都能从头开始传输
		if (history_transfer_active) {
			// 如果之前正在传输，先取消
			k_work_cancel_delayable(&history_send_work);
		}
		history_transfer_active = true;
		// 重置二分查找标记，允许重新定位
		history_binary_search_done = false;
		history_total_sent_packets = 0;
		history_total_sent_records = 0;
		history_last_sent_timestamp = 0;
		history_skipped_invalid = 0;
		history_skipped_filtered = 0;
		
		// 计算预期发送的总记录数（用于进度显示）
		history_expected_records = storage_get_total_records();
		
		// 从最旧扇区开始发送（如果有时间戳过滤，二分查找会重新定位）
		history_transfer_sector = oldest_sector;
		history_transfer_record_idx = 0;
		if (history_start_timestamp == 0) {
			printk("[历史] 开始传输（全部），共 %u 条记录，从扇区 %d 开始\r\n", 
			       history_expected_records, history_transfer_sector);
		} else {
			printk("[历史] 开始传输（时间戳 > %u），预计 <= %u 条记录\r\n", 
			       history_start_timestamp, history_expected_records);
		}
		
		// 补发总记录数通知（4字节），解决手机端卡在“正在同步”的问题
		// 手机端可能在等待这个总数包来初始化进度条
		uint8_t count_data[4];
		sys_put_le32(history_expected_records, count_data);
		if (history_char_attr) {
			int err = bt_gatt_notify(NULL, history_char_attr, count_data, sizeof(count_data));
			if (err) {
				printk("[历史] 警告: 发送总记录数通知失败: %d\r\n", err);
			} else {
				printk("[历史] 已发送总记录数通知: %u\r\n", history_expected_records);
			}
		}

		// 立即启动发送任务
		k_work_schedule(&history_send_work, K_NO_WAIT);
	} else {
		// 禁用通知
		if (history_transfer_active) {
			history_transfer_active = false;
			k_work_cancel_delayable(&history_send_work);
			// 打印最终统计信息
			printk("[历史] 用户停止传输: 已发送 %u/%u 条记录 (%u 包)\r\n",
			       history_total_sent_records, history_expected_records, history_total_sent_packets);
		} else {
			// 传输可能已经自动完成，或者从未开始
			printk("[历史] 数据通知已禁用 (传输不在进行中)\r\n");
		}
		
		// 无论如何都重置传输状态，确保下次启用时从头开始
		history_transfer_sector = 0;
		history_transfer_record_idx = 0;
		history_binary_search_done = false;
		history_total_sent_packets = 0;
		history_total_sent_records = 0;
		history_last_sent_timestamp = 0;
		// 注意：不清除 history_start_timestamp，允许手机端在下次启用通知前重新设置
	}
}

// 时间特性数据缓冲区
static uint8_t time_char_data[4];  // Unix 时间戳（uint32_t，4字节）

// 时间特性写入回调函数
static ssize_t time_char_write_cb(struct bt_conn *conn,
				   const struct bt_gatt_attr *attr,
				   const void *buf, uint16_t len, uint16_t offset,
				   uint8_t flags)
{
	// 检查数据长度（必须是4字节）
	if (len != 4) {
		printk("[时间同步] 错误: 数据长度不正确 (%d 字节，需要 4 字节)\r\n", len);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}

	// 复制数据到缓冲区
	memcpy(time_char_data, buf, 4);

	// 解析 Unix 时间戳（小端序）
	uint32_t timestamp = sys_get_le32(time_char_data);

	// 验证时间戳范围（1970-01-01 ~ 2106-02-07）
	// Unix 时间戳范围：0 ~ 0xFFFFFFFF (uint32_t)
	// 但合理范围：1970-01-01 (0) ~ 2106-02-07 (约 0xFFFFFFFF)
	if (timestamp < 946684800) {  // 2000-01-01 00:00:00 UTC
		printk("[时间同步] 警告: 时间戳可能不合理 (%u)\r\n", timestamp);
		// 仍然接受，但给出警告
	}

	// 保存基准时间戳和对应的系统运行时间
	time_base_timestamp = timestamp;
	time_base_uptime = k_uptime_get();  // 获取当前系统运行时间（毫秒）
	time_synced = true;
	/* 手机实时对时 → 墙钟可信。**必须重算下一边界**：对时可能把基准往前或往后拨，
	 * 不重算的话 deadline 还挂在旧网格上，会立刻结算出一条时间戳错乱的记录。
	 * 顺带这也实现了"对时后立即开始累计，到下一边界出首条记录"。
	 * 注意：只动聚合窗口与结算网格，**不碰 NVS 写头与已有历史**。 */
	wall_clock_aligned = true;
	history_window_realign();

	// 保存时间到 NVS（立即保存，确保重启后能恢复）
	if (nvs_config_is_ready()) {
		int ret = nvs_save_time(timestamp);
		if (ret >= 0) {
			printk("[时间同步] 时间已保存到 NVS\r\n");
		} else {
			printk("[时间同步] 警告: 时间保存到 NVS 失败: %d\r\n", ret);
		}
	}

	// 计算并显示时间信息（简化显示）
	uint32_t days = timestamp / 86400;
	uint32_t seconds = timestamp % 86400;
	uint32_t hours = seconds / 3600;
	uint32_t minutes = (seconds % 3600) / 60;
	uint32_t secs = seconds % 60;

	printk("[时间同步] 成功！时间戳: %u\r\n", timestamp);
	printk("[时间同步] UTC 时间: 自1970-01-01起 %u 天 %02u:%02u:%02u\r\n",
	       days, hours, minutes, secs);

	// 低功耗优化：不再闪烁 DATA LED（时间同步成功）
	// led_start_data_blink();

	return len;
}

// 最高最低温度特性数据缓冲区（12字节：最高温度(2) + 最高温度时间戳(4) + 最低温度(2) + 最低温度时间戳(4)）
static uint8_t temp_range_char_data[12];

// 保存最高最低温度特性属性指针，供通知使用
static const struct bt_gatt_attr *temp_range_char_attr = NULL;

// 最高最低温度特性读取回调
static ssize_t temp_range_char_read_cb(struct bt_conn *conn,
				       const struct bt_gatt_attr *attr,
				       void *buf, uint16_t len, uint16_t offset)
{
	// 保存属性指针（第一次调用时）
	if (temp_range_char_attr == NULL) {
		temp_range_char_attr = attr;
	}
	
	// 更新数据：最高温度(2) + 最高温度时间戳(4) + 最低温度(2) + 最低温度时间戳(4)
	sys_put_le16(max_temperature, &temp_range_char_data[0]);
	sys_put_le32(max_temperature_timestamp, &temp_range_char_data[2]);
	sys_put_le16(min_temperature, &temp_range_char_data[6]);
	sys_put_le32(min_temperature_timestamp, &temp_range_char_data[8]);
	
	return bt_gatt_attr_read(conn, attr, buf, len, offset,
				 temp_range_char_data, sizeof(temp_range_char_data));
}

// 重置最高最低温度特性写入回调
static ssize_t temp_range_reset_char_write_cb(struct bt_conn *conn,
					      const struct bt_gatt_attr *attr,
					      const void *buf, uint16_t len, uint16_t offset,
					      uint8_t flags)
{
	// 任何写入操作都触发重置（写入任意值都可以）
	if (len > 0) {
		// 重置最高最低温度
		max_temperature = INT16_MIN;
		min_temperature = INT16_MAX;
		max_temperature_timestamp = 0;
		min_temperature_timestamp = 0;
		
		printk("[温度记录] 已重置最高最低温度记录\r\n");
		
		// 保存到Flash（立即保存，因为是用户主动操作）
		save_config(true);
		
		// 更新GATT特性数据并通知
		sys_put_le16(max_temperature, &temp_range_char_data[0]);
		sys_put_le32(max_temperature_timestamp, &temp_range_char_data[2]);
		sys_put_le16(min_temperature, &temp_range_char_data[6]);
		sys_put_le32(min_temperature_timestamp, &temp_range_char_data[8]);
		
		if (bt_connected && temp_range_char_attr != NULL) {
			bt_gatt_notify(NULL, temp_range_char_attr, 
				       temp_range_char_data, sizeof(temp_range_char_data));
		}
	}
	
	return len;
}


// ========== 实时数据服务GATT回调函数 ==========

// 实时数据特性数据缓冲区
// 基础 6 字节：温度(2) + 湿度(2) + 气压(2)
// 启用电池电压后**末尾追加** 2 字节小端毫伏 → 8 字节（App 按帧长自动兼容，旧 6 字节不变）
#ifdef CONFIG_ADC
#define REALTIME_FRAME_LEN 8
#else
#define REALTIME_FRAME_LEN 6
#endif
static uint8_t realtime_char_data[REALTIME_FRAME_LEN];

// 保存实时数据特征属性指针，供通知使用
static const struct bt_gatt_attr *realtime_char_attr = NULL;

/* 把电池电压写进实时帧尾部。
 * **只读缓存**：绝不在 GATT 回调里调用 adc_read() —— 那会把 ADC 采样与校准
 * 塞进 BT RX 上下文，既顶高 RX 栈，又长时间阻塞蓝牙接收。 */
static inline void realtime_fill_voltage(void)
{
#ifdef CONFIG_ADC
	sys_put_le16(vdd_cached_mv(), &realtime_char_data[6]);
#endif
}

// 实时数据特性读取回调（返回与通知/存储一致的最新缓存值，不做二次采样）
// 原因：若在此处重新读传感器，会与定时器采样的“写入/存储”不一致（例如存储 600 hPa，手机读特征得到 1100 hPa）
static ssize_t realtime_char_read_cb(struct bt_conn *conn,
				     const struct bt_gatt_attr *attr,
				     void *buf, uint16_t len, uint16_t offset)
{
	// 保存属性指针（第一次调用时）
	if (realtime_char_attr == NULL) {
		realtime_char_attr = attr;
	}
	
	// 恢复 I2C 设备电源（暂时注释：保持 I2C 常开，观察 SPL06 冷启动问题）
	// i2c_power_resume();
	
	// 实时读取AHT30传感器获取温度和湿度
	int32_t temperature, humidity;
	int err_aht = aht30_read(&temperature, &humidity);
	
	k_msleep(10); // 增加间隔，避免 I2C 冲突
	
	if (err_aht == 0) {
		int16_t temp_value = (int16_t)temperature;
		uint16_t humidity_value = (uint16_t)humidity;
		sys_put_le16(temp_value, &realtime_char_data[0]);
		sys_put_le16(humidity_value, &realtime_char_data[2]);
	} else {
		// 读取失败时使用0值
		printk("[实时数据] AHT30读取失败: %d\r\n", err_aht);
		sys_put_le16(0, &realtime_char_data[0]);
		sys_put_le16(0, &realtime_char_data[2]);
	}
	
	// 实时读取SPL06传感器获取气压
	int32_t pressure, spl06_temp;
	int err_spl = spl06_read(&pressure, &spl06_temp);
	if (err_spl != 0) {
		// 读取失败时使用默认值（标准大气压 101325 Pa）
		printk("[实时数据] SPL06读取失败: %d，使用默认气压值\r\n", err_spl);
		pressure = 0;
	}
	// pressure_dhpa = Pa/10 (0.1hPa)
	int32_t p_dhpa_i = pressure / 10;
	if (p_dhpa_i < 0) {
		p_dhpa_i = 0;
	} else if (p_dhpa_i > 0xFFFF) {
		p_dhpa_i = 0xFFFF;
	}
	uint16_t pressure_dhpa = (uint16_t)p_dhpa_i;
	sys_put_le16(pressure_dhpa, &realtime_char_data[4]);

	// 电池电压（缓存值，不触发 ADC 采样）
	realtime_fill_voltage();
	
	// 挂起 I2C 设备电源（低功耗）（暂时注释：保持 I2C 常开）
	// i2c_power_suspend();
	
	return bt_gatt_attr_read(conn, attr, buf, len, offset,
				 realtime_char_data, sizeof(realtime_char_data));
}

// 实时数据特性通知配置回调
static void realtime_char_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	bool notify_enabled = (value == BT_GATT_CCC_NOTIFY);
	printk("[实时数据] 实时数据通知: %s\r\n", notify_enabled ? "启用" : "禁用");
}

// 最高最低温度特性通知配置回调
static void temp_range_char_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	bool notify_enabled = (value == BT_GATT_CCC_NOTIFY);
	printk("[温度记录] 最高最低温度通知: %s\r\n", notify_enabled ? "启用" : "禁用");
}

// 配置服务定义
BT_GATT_SERVICE_DEFINE(config_service,
	BT_GATT_PRIMARY_SERVICE(&config_service_uuid),
	BT_GATT_CHARACTERISTIC(&interval_char_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
			       interval_char_read_cb, interval_char_write_cb, NULL),
	BT_GATT_CUD("History Record Interval", BT_GATT_PERM_READ),
	BT_GATT_CHARACTERISTIC(&status_char_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       status_char_read_cb, NULL, NULL),
	BT_GATT_CUD("Device Status", BT_GATT_PERM_READ),
	BT_GATT_CCC(status_char_ccc_cfg_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&history_char_uuid.uuid,
			       BT_GATT_CHRC_NOTIFY | BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
			       NULL, history_char_write_cb, NULL),
	BT_GATT_CUD("History Data", BT_GATT_PERM_READ),
	BT_GATT_CCC(history_char_ccc_cfg_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&history_info_char_uuid.uuid,
			       BT_GATT_CHRC_READ,
			       BT_GATT_PERM_READ,
			       history_info_char_read_cb, NULL, NULL),
	BT_GATT_CUD("History Info", BT_GATT_PERM_READ),
	BT_GATT_CHARACTERISTIC(&time_sync_char_uuid.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE,
			       NULL, time_char_write_cb, NULL),
	BT_GATT_CUD("Time Sync", BT_GATT_PERM_READ),
	BT_GATT_CHARACTERISTIC(&temp_range_char_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       temp_range_char_read_cb, NULL, NULL),
	BT_GATT_CUD("Temperature Range", BT_GATT_PERM_READ),
	BT_GATT_CCC(temp_range_char_ccc_cfg_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&temp_range_reset_char_uuid.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE,
			       NULL, temp_range_reset_char_write_cb, NULL),
	BT_GATT_CUD("Reset Temperature Range", BT_GATT_PERM_READ),
);

// 实时数据服务定义
BT_GATT_SERVICE_DEFINE(realtime_service,
	BT_GATT_PRIMARY_SERVICE(&realtime_service_uuid),
	BT_GATT_CHARACTERISTIC(&realtime_char_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       realtime_char_read_cb, NULL, NULL),
	BT_GATT_CCC(realtime_char_ccc_cfg_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CUD("Realtime Data", BT_GATT_PERM_READ),
);

// W25Q64 函数和宏定义使用 storage/w25q64.h 模块

// 清空数据工作项及相关变量
static struct k_work_delayable clear_data_work;
static uint16_t clear_data_progress = 0;
static uint16_t clear_data_erased_count = 0;
static uint16_t clear_data_failed_count = 0;

// 清空数据工作函数（异步执行，分步处理避免阻塞系统工作队列）
static void clear_data_work_handler(struct k_work *work)
{
	if (clear_data_progress == 0) {
		// 初始化：停止采集和历史传输
		k_timer_stop(&sample_timer);
		printk("[清空数据] 已停止采集定时器\r\n");
		
		k_work_cancel(&sample_work);
		
		if (history_transfer_active) {
			history_transfer_active = false;
			k_work_cancel_delayable(&history_send_work);
			printk("[清空数据] 已停止历史数据传输\r\n");
		}
		
		ram_buffer_count = 0;
		printk("[清空数据] 已清空RAM缓冲区\r\n");

		// 重置聚合窗口与事件判定基准，避免清空后立刻用旧基准误判"突变"
		history_window_reset();
		last_record_valid = false;
		alarm_state = 0;
		last_event_uptime_ms = 0;
		last_event_seen = false;
		event_pending_kind = EVENT_KIND_NONE;

		// 提前重置存储位置变量和 NVS，防止擦除过程中断电导致状态不一致
		// (如果先擦除后重置，中途断电会导致 Flash 已空但 NVS 仍有记录数，出现"有记录无法读取"的现象)
		next_sector = 0;
		oldest_sector = 0;
		next_record_in_sector = 0;
		position_dirty = false; // 清除脏标志，防止定时任务写回旧值
		
		// 清空NVS中的位置信息
		nvs_clear_storage_position();
		printk("[清空数据] 存储位置已重置: next=0 oldest=0 rec=0\r\n");
		
		// 重置记录数
		record_count = 0;
		
		printk("[清空数据] 开始擦除Flash存储...\r\n");
		
		k_mutex_lock(w25q64_get_mutex(), K_FOREVER);
		w25q64_wakeup();
		k_mutex_unlock(w25q64_get_mutex());
		
		clear_data_erased_count = 0;
		clear_data_failed_count = 0;
		
		// 发送状态通知（开始清空，status bit 2 = 1）
		if (bt_ready && bt_connected && status_char_attr != NULL) {
			// data_clear_in_progress 已在写回调中置位，build() 会自动把 bit2 置 1
			status_char_build();
			bt_gatt_notify(NULL, status_char_attr, status_char_data, sizeof(status_char_data));
		}
	}

	// 每次处理一个扇区，避免长时间阻塞系统工作队列
	// 256个扇区，每次耗时约45-400ms
	if (clear_data_progress < W25Q64_MAX_SECTORS) {
		uint32_t erase_addr = W25Q64_STORAGE_BASE + clear_data_progress * W25Q64_SECTOR_SIZE;
		
		// 加锁保护 Flash 操作
		k_mutex_lock(w25q64_get_mutex(), K_FOREVER);
		int ret = w25q64_sector_erase(erase_addr);
		k_mutex_unlock(w25q64_get_mutex());
		
		if (ret == 0) {
			clear_data_erased_count++;
			// 每擦除32个扇区打印一次进度
			if ((clear_data_progress + 1) % 32 == 0) {
				printk("[清空数据] 清空进度: %d/%d 扇区\r\n", clear_data_progress + 1, W25Q64_MAX_SECTORS);
			}
		} else {
			clear_data_failed_count++;
			printk("[清空数据] 警告: 扇区 %d 擦除失败: %d\r\n", clear_data_progress, ret);
		}
		
		clear_data_progress++;
		
		// 继续调度下一个扇区
		k_work_schedule(&clear_data_work, K_NO_WAIT);
		return;
	}
	
	// 所有扇区擦除完成
	k_mutex_lock(w25q64_get_mutex(), K_FOREVER);
	w25q64_sleep();
	k_mutex_unlock(w25q64_get_mutex());
	
	if (clear_data_failed_count == 0) {
		printk("[清空数据] Flash清空完成: 成功擦除 %d 个扇区\r\n", clear_data_erased_count);
	} else {
		printk("[清空数据] Flash清空完成: 成功 %d 个，失败 %d 个\r\n", clear_data_erased_count, clear_data_failed_count);
	}
	
	// 清空标志位，恢复采集和存储
	data_clear_in_progress = false;
	printk("[清空数据] 清空完成，已恢复采集和存储功能\r\n");
	
	// 重新启动采集定时器（周期定时器，见 sample_start_work_handler）
	if (bt_ready) {
		history_window_realign();   // 清空后重新对齐结算网格（有可信墙钟则落到整分边界）
		k_timer_start(&sample_timer, K_SECONDS(sample_interval),
			      K_SECONDS(sample_interval));
		printk("[清空数据] 已重新启动采样定时器，采样周期: %d 秒\r\n", sample_interval);
		
		// 发送状态通知（结束清空，status bit 2 = 0）
		if (bt_connected && status_char_attr != NULL) {
			// data_clear_in_progress 已清零，build() 会自动把 bit2 清 0
			status_char_build();
			bt_gatt_notify(NULL, status_char_attr, status_char_data, sizeof(status_char_data));
		}
	}
}

// 清空数据特性写入回调
static ssize_t clear_data_char_write_cb(struct bt_conn *conn,
					const struct bt_gatt_attr *attr,
					const void *buf, uint16_t len, uint16_t offset,
					uint8_t flags)
{
	// 任何写入操作都触发清空（写入任意值都可以）
	if (len == 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	
	if (data_clear_in_progress) {
		printk("[清空数据] 警告: 清空操作正在进行中，忽略重复请求\r\n");
		return BT_GATT_ERR(BT_ATT_ERR_WRITE_NOT_PERMITTED);
	}
	
	printk("[清空数据] 收到清空请求，启动异步清空任务...\r\n");
	
	// 设置清空标志位
	data_clear_in_progress = true;
	clear_data_progress = 0;
	
	// 启动异步任务
	k_work_schedule(&clear_data_work, K_NO_WAIT);
	
	// 立即返回成功，避免阻塞 GATT 响应
	return len;
}

// 清空数据服务定义
BT_GATT_SERVICE_DEFINE(clear_data_service,
	BT_GATT_PRIMARY_SERVICE(&clear_data_service_uuid),
	BT_GATT_CHARACTERISTIC(&clear_data_char_uuid.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE,
			       NULL, clear_data_char_write_cb, NULL),
	BT_GATT_CUD("Clear All Data", BT_GATT_PERM_READ),
);

// 首次采集启动工作函数
static void sample_start_work_handler(struct k_work *work)
{
	// 确保蓝牙就绪后再启动采集
	if (bt_ready) {
		printk("[采集] 启动采集任务：采样 %d 秒 / 历史记录 %d 秒（预计保留 %u 天）\r\n",
		       sample_interval, history_interval, history_retention_days());

		/* 起始对齐：把结算网格锚到墙钟边界（有可信墙钟时）或"此刻 + 一个周期"，
		 * 然后启动**周期**采样定时器。
		 *
		 * 周期定时器取代了早期"单次触发 + 工作完成后重武装 1 秒"的写法：
		 * 后者把真实周期变成 1 秒 + 工作耗时，60 拍实测约 68 秒/条。 */
		history_window_realign();
		k_timer_start(&sample_timer, K_SECONDS(sample_interval),
			      K_SECONDS(sample_interval));
	}
}

// 采集定时器回调
static void sample_timer_handler(struct k_timer *timer)
{
	/* 唤醒系统，触发采集任务。
	 * 注意这是**周期**定时器的回调：若上一拍的 sample_work 仍在执行，
	 * k_work_submit() 会返回 -EBUSY 并丢弃这一拍 —— 这是刻意的自我保护，
	 * 且不会破坏记录节奏，因为窗口结算是按 uptime 网格判定的。 */
	k_work_submit(&sample_work);
}

// 采集工作函数
static void sample_work_handler(struct k_work *work)
{
	// 检查是否正在清空数据，如果是则停止采集
	// （定时器在 clear_data_work_handler 里已 k_timer_stop，这里只是防御）
	if (data_clear_in_progress) {
		printk("[采集] 清空数据进行中，跳过本次采集\r\n");
		return;
	}
	
	// 检查蓝牙是否已就绪，避免在蓝牙未初始化时访问 GATT
	if (!bt_ready) {
		/* 蓝牙未就绪：停掉周期定时器，避免空转唤醒。
		 * 采集会在 bt_ready_cb → sample_start_work_handler 里重新启动。 */
		k_timer_stop(&sample_timer);
		printk("[采集] 蓝牙未就绪，跳过本次采集\r\n");
		return;
	}
	
	// 恢复 I2C 设备电源（低功耗优化）（暂时注释：保持 I2C 常开，观察 SPL06 冷启动问题）
	// i2c_power_resume();
	
	// 读取传感器
	// 为避免 I2C 时序冲突，AHT30 与 SPL06 读取之间增加微小延时
	int32_t temperature = 0, humidity = 0;
	int err_aht = aht30_read(&temperature, &humidity);
	
	k_msleep(10); // 增加间隔，确保 I2C 总线彻底释放

	/* 窗口节拍在**每个采样节拍**上推进（含 AHT30 读取失败、时间未同步），
	 * 这样记录节奏恒为 history_interval，不会因间歇性传感器失败而被拉长。
	 *
	 * 这里必须放在 `if (err_aht == 0)` **之外**：早期实现把它放在成功分支内，
	 * 后果是 AHT30 一旦持续失败（实测出现过 I2C 锁死、window_ticks 长期卡在 4），
	 * 窗口永不推进 → **历史记录整体停摆**。节拍与"有没有有效样本"是两件事。 */
	if (window_ticks < 0xFFFFU) {
		window_ticks++;
	}

	/* 电池电压：同样放在 `if (err_aht == 0)` **之外**，
	 * 否则 AHT30 一旦失败，电压也会跟着停更（两件事互不相关）。
	 * 这里跑在系统工作队列上，按 VDD_SAMPLE_PERIOD_S（默认 60 s）节流。 */
	vdd_periodic_tick();

	if (err_aht == 0) {
		// 温度和湿度已放大100倍
		// printk("[AHT30] 温度: %d.%02d°C, 湿度: %d.%02d%%\r\n",
		//        temperature / 100, (temperature >= 0 ? temperature : -temperature) % 100,
		//        humidity / 100, humidity % 100);
		
		// 读取 SPL06-001 气压传感器（补偿后输出 Pa）
		int32_t pressure = 0, spl06_temp = 0;
		int err_spl = spl06_read(&pressure, &spl06_temp);
		if (err_spl != 0) {
			// 读取失败：置 0 作为"无效哨兵值"，随后的气压范围校验会把它判定为无效，
			// 从而**只把气压通道**排除在聚合之外（温湿度照常成记录）。
			// 注意：原实现注释写"使用默认值 101325 Pa"但代码实际写 0，导致整条记录被丢弃——
			// 即气压传感器一坏就完全不落盘，此处已改为按通道独立处理。
			pressure = 0;
			spl06_temp = 0;
			// 限制日志打印频率，避免刷屏（每60次打印一次）
			static int spl06_err_count = 0;
			if (spl06_err_count++ % 60 == 0) {
				printk("[SPL06] 读取失败: %d，本拍气压通道不计入聚合 [已发生 %d 次]\r\n", 
				       err_spl, spl06_err_count);
			}
		}
		
		// 更新最高最低温度
		int16_t temp_value = (int16_t)temperature;
		bool temp_updated = false;
		uint32_t current_time = get_current_timestamp();
		if (temp_value > max_temperature || max_temperature == INT16_MIN) {
			max_temperature = temp_value;
			max_temperature_timestamp = current_time;
			temp_updated = true;
			printk("[温度记录] 更新最高温度: %d.%02d°C (时间戳: %u)\r\n", 
			       max_temperature / 100, (max_temperature >= 0 ? max_temperature : -max_temperature) % 100,
			       max_temperature_timestamp);
			// 延迟保存到Flash（避免频繁擦写，5秒后保存）
			save_config(false);
		}
		if (temp_value < min_temperature || min_temperature == INT16_MAX) {
			min_temperature = temp_value;
			min_temperature_timestamp = current_time;
			temp_updated = true;
			printk("[温度记录] 更新最低温度: %d.%02d°C (时间戳: %u)\r\n", 
			       min_temperature / 100, (min_temperature >= 0 ? min_temperature : -min_temperature) % 100,
			       min_temperature_timestamp);
			// 延迟保存到Flash（避免频繁擦写，5秒后保存）
			save_config(false);
		}
		
		// 更新实时数据特征（即使SPL06读取失败也使用默认值）
		uint16_t humidity_value = (uint16_t)humidity;
		// pressure_dhpa = Pa/10 (0.1hPa)
		int32_t p_dhpa_i = pressure / 10;
		if (p_dhpa_i < 0) {
			p_dhpa_i = 0;
		} else if (p_dhpa_i > 0xFFFF) {
			p_dhpa_i = 0xFFFF;
		}
		uint16_t pressure_dhpa = (uint16_t)p_dhpa_i;
		
		// 更新实时数据特征：温度(2) + 湿度(2) + 气压(2) [+ 电压(2)]
		sys_put_le16(temp_value, &realtime_char_data[0]);
		sys_put_le16(humidity_value, &realtime_char_data[2]);
		sys_put_le16(pressure_dhpa, &realtime_char_data[4]);
		realtime_fill_voltage();
		
		// 发送实时数据通知（包含温度+湿度+气压）
		// 注意：如果正在传输历史数据，暂停实时数据通知，避免客户端数据乱序
		// 气压传感器失败时使用默认值，仍然发送通知
		if (bt_ready && bt_connected && err_aht == 0 && 
		    realtime_char_attr != NULL && !history_transfer_active) {
			bt_gatt_notify(NULL, realtime_char_attr, 
				       realtime_char_data, sizeof(realtime_char_data));
		}
		
		// ================= 历史记录：聚合 / 事件 / 落盘（D-2）=================
		// 与蓝牙是否连接无关（连接/未连接都会记录）；仅要求时间已同步，否则时间戳无意义。
		// 条件：AHT30 成功。若“连接后不写数据”，多半是 time_synced 仍为 false：
		//       需手机端在连接后写入“时间同步”特性。
		{
			// 逐通道有效性校验（不再"一票否决"整条记录）
			bool t_ok = (temperature >= -5000 && temperature <= 15000);   // -50.00~150.00°C
			bool h_ok = (humidity >= 0 && humidity <= 10000);              // 0.00~100.00%RH
			bool p_ok = (pressure >= 10000 && pressure <= 200000);         // 100~2000 hPa

			if (!t_ok) {
				printk("[存储] 警告: 温度数据异常 (%d)，本拍温度通道不计入聚合\r\n",
				       (int)temperature);
			}
			if (!h_ok) {
				printk("[存储] 警告: 湿度数据异常 (%d)，本拍湿度通道不计入聚合\r\n",
				       (int)humidity);
			}
			if (!p_ok && pressure != 0) {
				printk("[存储] 警告: 气压数据异常 (%d Pa)，本拍气压通道不计入聚合\r\n",
				       (int)pressure);
			}

			if (time_synced) {
				if (t_ok) {
					acc_temp_sum += temperature;
					acc_temp_n++;
				}
				if (h_ok) {
					acc_hum_sum += (uint32_t)humidity;
					acc_hum_n++;
				}
				if (p_ok) {
					acc_press_sum += (uint32_t)pressure;
					acc_press_n++;
					last_valid_pressure_pa = (uint32_t)pressure;
				}

				/* 事件记录：突变/越限时立即追加一条瞬时记录（需温度通道有效） */
				if (t_ok) {
					uint32_t ev_hum = h_ok
						? (uint32_t)humidity
						: (uint32_t)(last_record_valid ? last_record_hum : 0U);
					uint32_t ev_press = p_ok
						? (uint32_t)pressure
						: (last_valid_pressure_pa ? last_valid_pressure_pa : 101325U);
					history_maybe_event(temperature, ev_hum, ev_press,
							    get_current_timestamp());
				}
			}

			if (!time_synced) {
				static int no_sync_log_count = 0;
				if (no_sync_log_count++ % 60 == 0) {
					printk("[存储] 时间未同步，本拍不聚合 (请 APP 连接后写入时间同步特性) [已拦截 %d 次]\r\n",
					       no_sync_log_count);
				}
			}
		}
		
		// 更新状态特性（通知客户端）- 仅在蓝牙就绪时
		if (bt_ready) {
			status_char_build();
			
			// 发送配置状态通知（如果已连接）
			// 注意：实际应该检查CCC状态，这里简化实现
			if (bt_connected && status_char_attr != NULL) {
				// 通过配置服务中的状态特性发送通知
				bt_gatt_notify(NULL, status_char_attr, 
					       status_char_data, sizeof(status_char_data));
			}
			
			// 如果温度记录更新了，通知最高最低温度特性
			if (temp_updated && bt_connected && temp_range_char_attr != NULL) {
				uint8_t temp_range_data[12];
				sys_put_le16(max_temperature, &temp_range_data[0]);
				sys_put_le32(max_temperature_timestamp, &temp_range_data[2]);
				sys_put_le16(min_temperature, &temp_range_data[6]);
				sys_put_le32(min_temperature_timestamp, &temp_range_data[8]);
				bt_gatt_notify(NULL, temp_range_char_attr, temp_range_data, sizeof(temp_range_data));
			}
		}
		
		// 低功耗优化：不再闪烁 DATA LED（数据更新）
		// if (bt_connected) {
		// 	led_data_flash();
		// }
	} else {
		printk("[AHT30] 读取失败: %d\r\n", err_aht);
	}

	/* 窗口到期 → 生成一条周期均值记录（内部立即落盘）。
	 *
	 * 判定用 **uptime 绝对网格**（history_window_due），不是"数够 history_interval 个节拍"：
	 * 工作队列忙时 k_work_submit 会丢掉那一拍，数节拍会让周期被拉长；按 uptime 结算则
	 * 无论丢不丢拍，每个周期都恰好是 history_interval 秒。
	 *
	 * 同样放在 AHT30 成功分支**之外**：本拍即使读失败，只要到期就照常结算
	 * （`history_commit_average()` 会在无有效温度样本时自行跳过并重置窗口）。
	 * deadline 无条件推进——即便本窗口一条有效样本都没有，"真实分钟边界"也不能被破坏。 */
	if (history_window_due()) {
		history_commit_average();
		history_window_advance_deadline();
	}

	/* 上一拍落盘失败的记录，本拍继续重试（不产生新记录） */
	if (ram_buffer_count > 0U) {
		history_flush_buffer();
	}

	// 挂起 I2C 设备电源（低功耗优化）（暂时注释：保持 I2C 常开）
	// i2c_power_suspend();

	/* 这里**不再重武装定时器**：sample_timer 已是周期定时器（见 sample_start_work_handler）。
	 * 早期在函数末尾 k_timer_start(..., K_NO_WAIT) 的写法会让周期 = 1 s + 本函数耗时。 */
}

// 蓝牙连接回调函数
static void bt_connected_cb(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		printk("蓝牙连接失败 (错误码: %d)\r\n", err);
		return;
	}

	bt_connected = true;
	led_set_bt_connected(true);
	printk("蓝牙已连接\r\n");
	
	// 停止闪烁，LINK灯常亮
	led_stop_link_blink();
	led_on(LINK_LED_MASK);
	
	// 已连接时停止间歇广播，持续保持连接状态
	ble_adv_set_connected(true);
	ble_adv_stop_intermittent();
	printk("[蓝牙] 已连接，停止间歇广播，保持连接状态\r\n");
}

// 蓝牙断开回调函数
static void bt_disconnected_cb(struct bt_conn *conn, uint8_t reason)
{
	bt_connected = false;
	led_set_bt_connected(false);
	printk("蓝牙已断开 (原因: %d)\r\n", reason);
	
	// 低功耗优化：断开后熄灭LINK灯（不再闪烁）
	led_stop_link_blink();
	led_off(LINK_LED_MASK);
	
	// 重置历史数据传输的时间戳过滤条件
	// 避免下次连接时如果不设置时间戳，残留的旧值导致无法获取数据
	if (history_start_timestamp != 0) {
		history_start_timestamp = 0;
		printk("[历史] 断开连接，重置起始时间戳为 0\r\n");
	}
	
	// 断开后重新启动间歇广播
	// 先启动快速广播30秒，便于重新连接
	ble_adv_set_connected(false);
	if (bt_ready) {
		printk("[蓝牙] 断开后重新启动广播，30秒后切换到间歇广播模式\r\n");
		ble_adv_schedule_restart();
		ble_adv_schedule_slow(30);
	}
}

// 蓝牙就绪回调函数
static void bt_ready_cb(int err)
{
	if (err) {
		printk("蓝牙初始化失败 (错误码: %d)\r\n", err);
		return;
	}

	// 初始化广播模块（设置设备名称和广播数据）
	err = ble_adv_init();
	if (err) {
		printk("广播模块初始化失败 (错误码: %d)\r\n", err);
		return;
	}

	printk("蓝牙初始化完成，设备名称: %s\r\n", ble_adv_get_device_name());
	
	// 启动蓝牙广播 - 使用快速可连接广播（前30秒）
	err = ble_adv_start_fast();
	if (err) {
		printk("蓝牙广播启动失败 (错误码: %d)\r\n", err);
		return;
	}

	printk("蓝牙广播已启动（快速模式），设备可被发现并可连接\r\n");
	
	// 30秒后切换到慢速广播（降低功耗）
	ble_adv_schedule_slow(30);
	
	// 打印服务信息
	printk("温湿度服务已注册\r\n");
	printk("  服务 UUID: 12340001-1234-5678-1234-56789ABCDEF0\r\n");
	printk("  温度特性 UUID: 12340002-1234-5678-1234-56789ABCDEF0 (可读/可通知)\r\n");
	printk("  湿度特性 UUID: 12340003-1234-5678-1234-56789ABCDEF0 (可读/可通知)\r\n");
	printk("配置服务已注册\r\n");
	printk("  服务 UUID: 12340020-1234-5678-1234-56789ABCDEF0\r\n");
	printk("  历史记录间隔特性 UUID: 12340021-1234-5678-1234-56789ABCDEF0 (读写, 60~3600秒；采样固定1秒)\r\n");
	printk("  当前状态特性 UUID: 12340022-1234-5678-1234-56789ABCDEF0\r\n");
	printk("  历史数据特性 UUID: 12340023-1234-5678-1234-56789ABCDEF0 (通知)\r\n");
	printk("  历史记录信息特性 UUID: 12340026-1234-5678-1234-56789ABCDEF0 (读取)\r\n");
	printk("  时间同步特性 UUID: 12340011-1234-5678-1234-56789ABCDEF0\r\n");
	printk("  最高最低温度特性 UUID: 12340024-1234-5678-1234-56789ABCDEF0 (可读/可通知)\r\n");
	printk("  重置最高最低温度特性 UUID: 12340025-1234-5678-1234-56789ABCDEF0 (可写)\r\n");
	
	// 蓝牙初始化完成
	bt_ready = true;
	led_set_bt_ready(true);
	
	// 低功耗优化：停止初始化闪烁，LED保持熄灭（等待蓝牙连接时才点亮）
	led_stop_init_blink();
	led_off(LED_MASK);  // 熄灭所有LED
	
	// 延迟启动第一次采集任务（确保系统完全稳定）
	// 注意：在回调函数中直接提交工作队列可能不安全，使用延迟工作队列
	printk("[采集] 蓝牙已就绪，1秒后启动采集任务：采样 %d 秒 / 历史记录 %d 秒\r\n",
	       sample_interval, history_interval);
	k_work_schedule(&sample_start_work, K_SECONDS(1));
}

// 蓝牙连接回调结构体
BT_CONN_CB_DEFINE(conn_callbacks) = {
	.connected = bt_connected_cb,
	.disconnected = bt_disconnected_cb,
};

// 获取当前时间戳（基于同步后的时间）
static uint32_t __maybe_unused get_current_timestamp(void)
{
	if (!time_synced) {
		return 0;  // 时间未同步
	}

	// 当前时间 = 基准时间戳 + (当前运行时间 - 基准运行时间) / 1000
	int64_t current_uptime = k_uptime_get();
	int64_t elapsed_ms = current_uptime - time_base_uptime;
	uint32_t elapsed_sec = elapsed_ms / 1000;

	return time_base_timestamp + elapsed_sec;
}

// ========== Flash 存储功能（使用W25Q64） ==========

// W25Q64存储布局：
// - 每个扇区(4KB)可以存储 512 条记录 (4096 / 8)
// - 使用前1MB存储区域，共256个扇区
// - 每条记录8字节：timestamp(4) + temperature(2) + humidity(2)
// - 使用环形缓冲区，按扇区擦除

// 存储管理变量（改用扇区索引）- 已在前面声明

// 根据 Flash 实际内容扫描定位写头（二分找最后有数据的扇区 + 扇区内线性找最后一条记录）
// assumed_oldest_sector: 假定数据起始扇区，扫描范围从该扇区开始
// 返回时更新 next_sector、next_record_in_sector（不修改 oldest_sector，由调用方设置）
static void storage_scan_flash_for_write_head(uint16_t assumed_oldest_sector)
{
	uint8_t b;
	int ret;
	uint16_t n_sec = (next_sector >= assumed_oldest_sector)
		? (next_sector - assumed_oldest_sector)
		: (W25Q64_MAX_SECTORS_LIMIT - assumed_oldest_sector + next_sector);
	if (n_sec == 0) {
		n_sec = W25Q64_MAX_SECTORS_LIMIT;
	}
	uint16_t low = 0;
	uint16_t high = n_sec;
	while (low < high) {
		uint16_t mid = low + (high - low) / 2;
		uint16_t s = (assumed_oldest_sector + mid) % W25Q64_MAX_SECTORS_LIMIT;
		uint32_t addr = W25Q64_STORAGE_BASE + s * W25Q64_SECTOR_SIZE;
		ret = w25q64_read(addr, &b, 1);
		if (ret != 0) {
			break;
		}
		if (b != 0xFF) {
			low = mid + 1;
		} else {
			high = mid;
		}
	}
	uint16_t last_sec = (assumed_oldest_sector + (low > 0 ? low - 1 : 0)) % W25Q64_MAX_SECTORS_LIMIT;
	uint16_t last_rec = 0;
	uint32_t sec_base = W25Q64_STORAGE_BASE + last_sec * W25Q64_SECTOR_SIZE;
	for (uint16_t i = 0; i < W25Q64_RECORDS_PER_SECTOR; i++) {
		uint16_t pi = i / W25Q64_RECORDS_PER_PAGE;
		uint16_t ip = i % W25Q64_RECORDS_PER_PAGE;
		uint32_t addr = sec_base
			+ (uint32_t)pi * W25Q64_PAGE_SIZE
			+ (uint32_t)ip * W25Q64_RECORD_SIZE;
		ret = w25q64_read(addr, &b, 1);
		if (ret != 0) {
			break;
		}
		if (b == 0xFF) {
			last_rec = i;
			break;
		}
		last_rec = i + 1;
	}
	if (last_rec >= W25Q64_RECORDS_PER_SECTOR) {
		next_sector = (last_sec + 1) % W25Q64_MAX_SECTORS_LIMIT;
		next_record_in_sector = 0;
	} else {
		next_sector = last_sec;
		next_record_in_sector = last_rec;
	}
	printk("[存储] Flash 扫描定位写头完成 next=%d rec=%d (假定 oldest=%d)\r\n",
	       next_sector, next_record_in_sector, assumed_oldest_sector);
}

// 初始化 Flash 存储
static int storage_init(void)
{
	printk("[存储] 开始初始化W25Q64 Flash存储...\r\n");
	// 初始化W25Q64
	int ret = w25q64_init();
	if (ret != 0) {
		printk("[存储] W25Q64初始化失败: %d，历史数据将无法保存\r\n", ret);
		return ret;
	}
	printk("[存储] W25Q64初始化成功\r\n");

#if STORAGE_CLEAR_ON_BOOT
	// 启动时强制清空所有Flash数据（清除旧版8字节格式的脏数据）
	printk("[存储] 启动时强制清空Flash（清除所有历史数据）...\r\n");
	w25q64_wakeup();
	
	// 擦除所有256个扇区（1MB存储区域）
	uint16_t erased_count = 0;
	uint16_t failed_count = 0;
	for (uint16_t i = 0; i < W25Q64_MAX_SECTORS; i++) {
		uint32_t erase_addr = W25Q64_STORAGE_BASE + i * W25Q64_SECTOR_SIZE;
		ret = w25q64_sector_erase(erase_addr);
		if (ret == 0) {
			erased_count++;
			// 每擦除32个扇区打印一次进度（避免输出过多）
			if ((i + 1) % 32 == 0) {
				printk("[存储] 清空进度: %d/%d 扇区\r\n", i + 1, W25Q64_MAX_SECTORS);
			}
		} else {
			failed_count++;
			printk("[存储] 警告: 扇区 %d 擦除失败: %d\r\n", i, ret);
		}
	}
	
	w25q64_sleep();
	
	if (failed_count == 0) {
		printk("[存储] Flash清空完成: 成功擦除 %d 个扇区\r\n", erased_count);
	} else {
		printk("[存储] Flash清空完成: 成功 %d 个，失败 %d 个\r\n", erased_count, failed_count);
	}
	
	// 重置存储位置变量
	next_sector = 0;
	oldest_sector = 0;
	next_record_in_sector = 0;
	
	// 清空NVS中的位置信息
	nvs_clear_storage_position();
	
	printk("[存储] 存储位置已重置: next=0 oldest=0 rec=0\r\n");
	printk("[存储] W25Q64初始化完成（已清空所有历史数据）\r\n");
	printk("[存储] 缓冲区状态: %d/%d 条记录\r\n", ram_buffer_count, RAM_BUFFER_SIZE);
	return 0;  // 清空后直接返回，跳过后续的写头定位逻辑
#endif

	// 启动时自动找回写头：先校验 NVS 位置，若不一致则二分法 + 线性扫精确定位
	// 重要：使用与写入时相同的地址计算方式（页索引+页内索引），确保地址一致
	uint16_t page_index = next_record_in_sector / W25Q64_RECORDS_PER_PAGE;
	uint16_t index_in_page = next_record_in_sector % W25Q64_RECORDS_PER_PAGE;
	uint32_t base = W25Q64_STORAGE_BASE + next_sector * W25Q64_SECTOR_SIZE
		+ (uint32_t)page_index * W25Q64_PAGE_SIZE
		+ (uint32_t)index_in_page * W25Q64_RECORD_SIZE;
	uint8_t b;
	ret = w25q64_read(base, &b, 1);

	bool check_passed = (ret == 0 && b == 0xFF);

	// 增强校验：如果写头不是扇区首条，检查前一条记录是否有效
	// 防止 Flash 被擦除但 NVS 未更新导致的位置错误
	if (check_passed && next_record_in_sector > 0) {
		uint16_t prev_idx = next_record_in_sector - 1;
		uint16_t prev_page = prev_idx / W25Q64_RECORDS_PER_PAGE;
		uint16_t prev_in_page = prev_idx % W25Q64_RECORDS_PER_PAGE;
		uint32_t prev_addr = W25Q64_STORAGE_BASE + next_sector * W25Q64_SECTOR_SIZE
			+ (uint32_t)prev_page * W25Q64_PAGE_SIZE
			+ (uint32_t)prev_in_page * W25Q64_RECORD_SIZE;
		
		uint8_t prev_b;
		int prev_ret = w25q64_read(prev_addr, &prev_b, 1);
		
		// 如果前一条记录也是 0xFF，说明数据可能已丢失，校验失败
		if (prev_ret != 0 || prev_b == 0xFF) {
			printk("[存储] NVS 校验失败: 前一条记录无效 (0xFF)，可能数据已丢失\r\n");
			check_passed = false;
		}
	}

	if (check_passed) {
		printk("[存储] NVS 位置校验通过 (写头 0xFF，前序有效)\r\n");
	} else {
		// NVS 落后或与 Flash 不一致：扫描 Flash 重新定位写头
		storage_scan_flash_for_write_head(oldest_sector);
	}

	// 检查并调整：如果超过最大记录数限制，调整到限制范围内
	if (next_sector >= W25Q64_MAX_SECTORS_LIMIT) {
		printk("[存储] 警告: next_sector (%d) 超过限制 (%d)，重置为0\r\n", 
		       next_sector, W25Q64_MAX_SECTORS_LIMIT);
		next_sector = 0;
		next_record_in_sector = 0;
		oldest_sector = 0;
	}
	if (oldest_sector >= W25Q64_MAX_SECTORS_LIMIT) {
		printk("[存储] 警告: oldest_sector (%d) 超过限制 (%d)，重置为0\r\n", 
		       oldest_sector, W25Q64_MAX_SECTORS_LIMIT);
		oldest_sector = 0;
	}

	uint32_t total_records = storage_get_total_records();

	// 一次性修复：若推算总记录数为 0 但 Flash 扇区 0 首字节非 0xFF，说明 NVS/位置与 Flash 不一致，强制清 NVS 并重新从 Flash 扫描定位写头
	if (total_records == 0 && nvs_config_is_ready()) {
		uint8_t first_byte;
		ret = w25q64_read(W25Q64_STORAGE_BASE, &first_byte, 1);
		if (ret == 0 && first_byte != 0xFF) {
			printk("[存储] 检测到 Flash 有数据但总记录数为 0，执行 NVS/位置一次性修复...\r\n");
			nvs_clear_storage_position();
			oldest_sector = 0;
			next_sector = 0;
			next_record_in_sector = 0;
			storage_scan_flash_for_write_head(0);
			position_dirty = true;
			total_records = storage_get_total_records();
			// 立即写回 NVS，使修复后的位置持久化
			storage_position_t pos = {
				.next_sector = next_sector,
				.oldest_sector = oldest_sector,
				.next_record_in_sector = next_record_in_sector
			};
			nvs_save_storage_position(&pos);
			position_dirty = false;
			printk("[存储] 一次性修复完成，当前总记录数: %u\r\n", total_records);
		}
	}

	printk("[存储] W25Q64初始化完成 next=%d oldest=%d rec=%d (当前估算总记录数: %u, 最大记录数限制: %d条)\r\n",
	       next_sector, oldest_sector, next_record_in_sector, total_records, W25Q64_MAX_RECORDS);
	printk("[存储] 缓冲区状态: %d/%d 条记录\r\n", ram_buffer_count, RAM_BUFFER_SIZE);
	return 0;
}

/* 写入中断时，把缓冲区中"尚未写入"的记录（从索引 written 起）前移，等待下一拍重试。
 * 写头 next_record_in_sector 只在写入成功后推进，因此重试会从同一地址继续；
 * 这使 5 条 RAM 缓冲真正成为"Flash 临时失败的重试区"，而不是丢了就算了。 */
static void storage_keep_unwritten(uint16_t written, uint16_t nr)
{
	uint16_t left = (uint16_t)(nr - written);

	if (written > 0U && left > 0U) {
		memmove(&ram_buffer[0], &ram_buffer[written],
			(size_t)left * sizeof(ram_buffer[0]));
	}
	ram_buffer_count = (uint8_t)left;
}

// 批量写入数据到 W25Q64（环形扇区）
// 策略：平时只管写；写完一个扇区才擦下一个（跨扇区时强制 Sector Erase）。无 Read-before-Write，无 0xFF 检查。
// 低功耗：唤醒 -> 操作 -> 睡眠。按页边界分块写入，避免跨页 partial program。
static void storage_write_batch(void)
{
	printk("[存储] storage_write_batch() 被调用，缓冲区记录数: %d\r\n", ram_buffer_count);

	// 检查是否正在清空数据，如果是则停止存储
	if (data_clear_in_progress) {
		printk("[存储] 清空数据进行中，跳过本次存储\r\n");
		// 清空缓冲区，避免数据残留
		ram_buffer_count = 0;
		return;
	}

	if (ram_buffer_count == 0) {
		printk("[存储] 警告: 缓冲区为空，无需写入\r\n");
		return;
	}
	
	// 时间未同步时不落盘，但**保留**缓冲数据（等时间同步后补写），不再直接丢弃
	if (!time_synced) {
		printk("[存储] 时间未同步，保留 %d 条记录在缓冲区等待补写\r\n",
		       ram_buffer_count);
		return;
	}
	
	// 闪烁 LED 指示写入活动（闪烁3次）
	led_start_data_blink();
	
	// 过滤掉无效记录（时间戳为0或数据异常）
	uint8_t valid_count = 0;
	for (uint8_t i = 0; i < ram_buffer_count; i++) {
		bool is_valid = true;
		
		// 1. 检查时间戳
		if (ram_buffer[i].timestamp == 0) {
			printk("[存储] 警告: 发现无效时间戳记录 (索引 %d)，已过滤\r\n", i);
			is_valid = false;
		}
		
		// 2. 检查湿度 (0-10000)
		if (ram_buffer[i].humidity > 10000) {
			printk("[存储] 警告: 发现无效湿度记录 (%d, 索引 %d)，已过滤\r\n", 
			       ram_buffer[i].humidity, i);
			is_valid = false;
		}
		
		// 3. 检查温度 (-5000 ~ 15000)
		if (ram_buffer[i].temperature < -5000 || ram_buffer[i].temperature > 15000) {
			printk("[存储] 警告: 发现无效温度记录 (%d, 索引 %d)，已过滤\r\n", 
			       ram_buffer[i].temperature, i);
			is_valid = false;
		}
		
		// 4. 检查气压 (10000 ~ 200000)
		if (ram_buffer[i].pressure_pa < 10000 || ram_buffer[i].pressure_pa > 200000) {
			printk("[存储] 警告: 发现无效气压记录 (%u, 索引 %d)，已过滤\r\n", 
			       ram_buffer[i].pressure_pa, i);
			is_valid = false;
		}

		if (is_valid) {
			if (valid_count != i) {
				// 将有效记录前移
				ram_buffer[valid_count] = ram_buffer[i];
			}
			valid_count++;
		}
	}
	
	if (valid_count == 0) {
		printk("[存储] 警告: 缓冲区中所有记录的时间戳都无效，不写入Flash\r\n");
		ram_buffer_count = 0;
		return;
	}
	
	if (valid_count < ram_buffer_count) {
		printk("[存储] 已过滤无效记录: %d -> %d 条有效记录\r\n", ram_buffer_count, valid_count);
		ram_buffer_count = valid_count;
	}
	
	if (!w25q64_is_ready()) {
		printk("[存储] 错误: W25Q64设备未就绪，无法写入\r\n");
		return;
	}

	k_mutex_lock(w25q64_get_mutex(), K_FOREVER);

	w25q64_wakeup();

	int ret;

	/* 3. 按“页内索引”分块写入，严格避免跨页 partial program */
	uint32_t sector_base = W25Q64_STORAGE_BASE + next_sector * W25Q64_SECTOR_SIZE;
	uint16_t written = 0;
	uint16_t nr = ram_buffer_count;

	while (written < nr) {
		/* 检查扇区是否已满或需要切换 */
		if (next_record_in_sector >= W25Q64_RECORDS_PER_SECTOR) {
			// 限制最大记录数：使用限制的扇区数进行环形覆盖
			next_sector = (next_sector + 1) % W25Q64_MAX_SECTORS_LIMIT;
			if (next_sector == oldest_sector) {
				oldest_sector = (oldest_sector + 1) % W25Q64_MAX_SECTORS_LIMIT;
				printk("[存储] 存储已满，环形覆盖最旧扇区，oldest -> %d\r\n", oldest_sector);
			}
			next_record_in_sector = 0;
			// 更新扇区基地址
			sector_base = W25Q64_STORAGE_BASE + next_sector * W25Q64_SECTOR_SIZE;
			// 写头跨扇区：调用方据此立即把写头持久化到 NVS
			sector_switched_flag = true;
		}

		/* 如果在扇区起始位置，强制擦除（确保新扇区干净） */
		if (next_record_in_sector == 0) {
			printk("[存储] 准备写入新扇区 %d，执行擦除 (Addr: 0x%06X)\r\n", next_sector, (unsigned)sector_base);
			ret = w25q64_sector_erase(sector_base);
			if (ret != 0) {
				printk("[存储] 扇区擦除失败: %d\r\n", ret);
				w25q64_sleep();
				goto out_unlock;
			}
		}

		/* 扇区内记录号 → 页号 + 页内索引，避免线性地址取模导致 records_in_page 为 0 */
		uint16_t page_index    = next_record_in_sector / W25Q64_RECORDS_PER_PAGE;
		uint16_t index_in_page = next_record_in_sector % W25Q64_RECORDS_PER_PAGE;

		uint32_t write_addr = sector_base
			+ (uint32_t)page_index * W25Q64_PAGE_SIZE
			+ (uint32_t)index_in_page * W25Q64_RECORD_SIZE;

		/* 当前页剩余可写记录数（至少 1），与剩余待写记录数取较小值 */
		uint16_t records_in_page = W25Q64_RECORDS_PER_PAGE - index_in_page;
		uint16_t chunk = (nr - written) < records_in_page ? (nr - written) : records_in_page;
		size_t   chunk_len = (size_t)chunk * W25Q64_RECORD_SIZE;

		// printk("[存储] 按页写入: page=%u, index_in_page=%u, addr=0x%06X, chunk=%u 条, len=%u 字节\r\n",
		//        (unsigned)page_index, (unsigned)index_in_page,
		//        (unsigned)write_addr, (unsigned)chunk, (unsigned)chunk_len);

		ret = w25q64_page_program(write_addr, (const uint8_t *)&ram_buffer[written], chunk_len);
		if (ret != 0) {
			printk("[存储] 页编程失败: %d，保留 %d 条待重试\r\n", ret, nr - written);
			storage_keep_unwritten(written, nr);
			w25q64_sleep();
			goto out_unlock;
		}
		ret = w25q64_wait_ready();
		if (ret != 0) {
			printk("[存储] 等待就绪失败: %d，保留 %d 条待重试\r\n", ret, nr - written);
			storage_keep_unwritten(written, nr);
			w25q64_sleep();
			goto out_unlock;
		}

		// printk("[存储] 本页写入完成: page=%u, index_in_page=%u, addr=0x%06X, chunk=%u 条\r\n",
		//        (unsigned)page_index, (unsigned)index_in_page,
		//        (unsigned)write_addr, (unsigned)chunk);

		written += chunk;
		next_record_in_sector += chunk;
	}

	// printk("[存储] 批量写入成功 扇区=%d 记录=%d-%d 共 %d 条\r\n",
	// 	next_sector, next_record_in_sector - nr, next_record_in_sector - 1, nr);

	position_dirty = true;  // 参与 NVS 定时 flush
	ram_buffer_count = 0;
	w25q64_sleep();

out_unlock:
	k_mutex_unlock(w25q64_get_mutex());
}

// 估算历史记录数量（基于存储位置，不实际读取数据）
// 注意：此函数已废弃，不再使用。保留函数声明以避免编译错误。
__maybe_unused static void storage_read_history(void)
{
	// 已废弃：启动时不再统计历史记录
	// 原因：
	// 1. 统计信息未被使用，仅打印一次
	// 2. 遍历所有记录会消耗启动时间和Flash读取次数
	// 3. 系统已通过 storage_init() 获取存储位置信息
	// 4. 历史数据可通过蓝牙连接后使用历史数据特性获取
	// 
	// 如果需要估算记录数，可以基于存储位置计算：
	// - 扇区数 = (next_sector - oldest_sector + W25Q64_MAX_SECTORS) % W25Q64_MAX_SECTORS
	// - 估算记录数 ≈ 扇区数 * W25Q64_RECORDS_PER_SECTOR + next_record_in_sector
}

// ========== 流式传输与按时间戳定位 ==========
// 流式传输参数使用 ble/ble_services.h 中的定义：
// STREAM_RECORDS_PER_PACKET = 5, STREAM_PACKET_SIZE = 60

// 线性查找：严格逐条按传输顺序遍历，找到第一条 timestamp > target 的有效记录
// 不做扇区级跳过，避免扇区缺失/空洞导致漏传；保证每条存储记录都有机会传到手机
// 返回：true 表示找到，*out_sector / *out_record_idx 为起始位置；false 表示无数据或全部<=target
static bool linear_find_first_after_timestamp(uint32_t target_timestamp,
					       uint16_t *out_sector, uint16_t *out_record_idx)
{
	if (next_sector == oldest_sector && next_record_in_sector == 0) {
		*out_sector = oldest_sector;
		*out_record_idx = 0;
		return false;  /* 无数据 */
	}
	if (target_timestamp == 0) {
		*out_sector = oldest_sector;
		*out_record_idx = 0;
		return true;   /* 要全部，从 oldest 开始 */
	}

	uint16_t cur_sector = oldest_sector;
	uint16_t cur_idx = 0;
	struct data_record rec;
	int ret;

	for (;;) {
		uint16_t max_in_sector = (cur_sector == next_sector)
			? next_record_in_sector
			: W25Q64_RECORDS_PER_SECTOR;

		while (cur_idx < max_in_sector) {
			uint32_t sector_addr = W25Q64_STORAGE_BASE + cur_sector * W25Q64_SECTOR_SIZE;
			uint16_t page_index = cur_idx / W25Q64_RECORDS_PER_PAGE;
			uint16_t index_in_page = cur_idx % W25Q64_RECORDS_PER_PAGE;
			uint32_t addr = sector_addr
				+ (uint32_t)page_index * W25Q64_PAGE_SIZE
				+ (uint32_t)index_in_page * W25Q64_RECORD_SIZE;

			ret = w25q64_read(addr, (uint8_t *)&rec, sizeof(rec));
			if (ret != 0) {
				cur_idx++;
				continue;
			}
			if (rec.timestamp == 0xFFFFFFFF) {
				/* 无效/空，本扇区后续不再有效，跳到下一扇区 */
				cur_idx = max_in_sector;
				break;
			}
			if (rec.timestamp > target_timestamp) {
				*out_sector = cur_sector;
				*out_record_idx = cur_idx;
				return true;
			}
			cur_idx++;
		}

		/* 当前扇区已读完，切到下一扇区 */
		if (cur_sector == next_sector && cur_idx >= next_record_in_sector) {
			*out_sector = next_sector;
			*out_record_idx = next_record_in_sector;
			return false;  /* 没有 > target 的记录 */
		}
		cur_sector = (cur_sector + 1) % W25Q64_MAX_SECTORS_LIMIT;
		cur_idx = 0;
	}
}

// 保留原二分查找实现仅作参考，实际已改用线性查找
#if 0
// 二分查找：在扇区内查找第一个时间戳大于target的记录位置
static uint16_t binary_search_sector(uint16_t sector, uint32_t target_timestamp)
{
	uint32_t sector_addr = W25Q64_STORAGE_BASE + sector * W25Q64_SECTOR_SIZE;
	uint16_t low = 0;
	uint16_t high = W25Q64_RECORDS_PER_SECTOR;  // 336 (页对齐: 16页×21条)
	struct data_record rec;
	int ret;
	
	// 首先检查扇区第一条记录
	ret = w25q64_read(sector_addr, (uint8_t *)&rec, sizeof(rec));
	if (ret != 0 || rec.timestamp == 0xFFFFFFFF) {
		return 0;  // 扇区为空或读取失败
	}
	
	// 如果第一条记录就大于目标时间戳，直接返回0
	if (rec.timestamp > target_timestamp) {
		return 0;
	}
	
	// 找到扇区内有效记录的数量（通过二分查找边界）
	// 先确定有效记录的上界
	// 注意：这里需要仔细处理，因为Flash中未写入的区域是0xFF，
	// 但如果发生异常写入，可能中间会有0xFF，或者时间戳乱序。
	// 我们的假设是：扇区内的数据是连续写入的，时间戳是单调递增的。
	
	// 简单线性扫描确认边界（比二分更稳健，防止脏数据干扰二分查找）
	// 由于每个扇区只有336条记录，线性扫描开销很小
	uint16_t valid_high = 0;
	for (uint16_t i = 0; i < W25Q64_RECORDS_PER_SECTOR; i++) {
		// 重要：使用与写入时相同的地址计算方式（页索引+页内索引），确保地址一致
		uint16_t page_index = i / W25Q64_RECORDS_PER_PAGE;
		uint16_t index_in_page = i % W25Q64_RECORDS_PER_PAGE;
		uint32_t addr = sector_addr 
			+ (uint32_t)page_index * W25Q64_PAGE_SIZE
			+ (uint32_t)index_in_page * W25Q64_RECORD_SIZE;
		ret = w25q64_read(addr, (uint8_t *)&rec, sizeof(rec));
		if (ret != 0 || rec.timestamp == 0xFFFFFFFF) {
			valid_high = i;
			break;
		}
		// 如果读到最后一条都是有效的
		if (i == W25Q64_RECORDS_PER_SECTOR - 1) {
			valid_high = W25Q64_RECORDS_PER_SECTOR;
		}
	}
	
	// 在有效范围内进行二分查找
	high = valid_high;
	
	while (low < high) {
		uint16_t mid = low + (high - low) / 2;
		// 重要：使用与写入时相同的地址计算方式（页索引+页内索引），确保地址一致
		uint16_t page_index = mid / W25Q64_RECORDS_PER_PAGE;
		uint16_t index_in_page = mid % W25Q64_RECORDS_PER_PAGE;
		uint32_t mid_addr = sector_addr 
			+ (uint32_t)page_index * W25Q64_PAGE_SIZE
			+ (uint32_t)index_in_page * W25Q64_RECORD_SIZE;
		
		ret = w25q64_read(mid_addr, (uint8_t *)&rec, sizeof(rec));
		if (ret != 0) {
			// 读取失败，保守策略：认为目标在前面
			high = mid;
			continue;
		}
		
		if (rec.timestamp <= target_timestamp) {
			// 时间戳小于等于目标，继续向后找
			low = mid + 1;
		} else {
			// 时间戳大于目标，可能是答案或答案在前面
			high = mid;
		}
	}
	
	// low现在指向第一个时间戳大于target的记录，或者指向有效记录末尾
	return low;
}

// 二分查找：在所有扇区中查找包含目标时间戳的起始扇区
// 返回：应该开始传输的扇区索引
static uint16_t binary_search_start_sector(uint32_t target_timestamp)
{
	// 特殊情况：如果没有数据或目标时间戳为0
	if (next_sector == oldest_sector || target_timestamp == 0) {
		return oldest_sector;
	}
	
	// 计算有效扇区数量
	// 重要：使用与写入时相同的扇区限制（W25Q64_MAX_SECTORS_LIMIT），确保读取顺序与写入顺序一致
	uint16_t sector_count;
	if (next_sector > oldest_sector) {
		sector_count = next_sector - oldest_sector;
	} else {
		sector_count = W25Q64_MAX_SECTORS_LIMIT - oldest_sector + next_sector;
	}
	
	if (sector_count == 0) {
		return oldest_sector;
	}
	
	// 在有效扇区范围内进行二分查找
	uint16_t low = 0;
	uint16_t high = sector_count;
	struct data_record first_rec, last_rec;
	int ret;
	
	while (low < high) {
		uint16_t mid = low + (high - low) / 2;
		// 重要：使用与写入时相同的扇区限制，确保索引计算一致
		uint16_t sector_idx = (oldest_sector + mid) % W25Q64_MAX_SECTORS_LIMIT;
		uint32_t sector_addr = W25Q64_STORAGE_BASE + sector_idx * W25Q64_SECTOR_SIZE;
		
		// 读取扇区第一条记录
		ret = w25q64_read(sector_addr, (uint8_t *)&first_rec, sizeof(first_rec));
		if (ret != 0 || first_rec.timestamp == 0xFFFFFFFF) {
			// 扇区为空，向前找
			high = mid;
			continue;
		}
		
		// 如果第一条记录的时间戳就大于目标，说明目标可能在前面的扇区
		if (first_rec.timestamp > target_timestamp) {
			high = mid;
		} else {
			// 检查扇区最后一条有效记录
			// 读取该扇区的最后一条记录（或最后一条有效记录）
			// 为简单起见，我们读取下一个扇区的第一条记录来判断当前扇区是否包含目标
			// 重要：使用与写入时相同的扇区限制
			uint16_t next_sector_idx = (oldest_sector + mid + 1) % W25Q64_MAX_SECTORS_LIMIT;
			
			// 如果是最后一个有数据的扇区，那么目标一定在这个扇区（如果存在）
			if (next_sector_idx == next_sector) {
				return sector_idx;
			}
			
			uint32_t next_sector_addr = W25Q64_STORAGE_BASE + next_sector_idx * W25Q64_SECTOR_SIZE;
			ret = w25q64_read(next_sector_addr, (uint8_t *)&last_rec, sizeof(last_rec));
			if (ret != 0 || last_rec.timestamp == 0xFFFFFFFF) {
				// 下一个扇区为空，当前扇区是最后一个
				return sector_idx;
			}
			
			// 如果下一个扇区的开始时间戳 > 目标时间戳，说明目标在当前扇区（或之前）
			// 如果下一个扇区的开始时间戳 <= 目标时间戳，说明目标在后面
			if (last_rec.timestamp > target_timestamp) {
				// 目标在当前扇区
				return sector_idx;
			} else {
				// 目标在后面的扇区
				low = mid + 1;
			}
		}
	}
	
	// 返回找到的扇区，确保在有效范围内
	// 重要：使用与写入时相同的扇区限制
	uint16_t result_sector = (oldest_sector + low) % W25Q64_MAX_SECTORS_LIMIT;
	return result_sector;
}
#endif /* 原二分查找，已用线性查找替代 */

// 历史数据通知回调（当前一包发送完成后，继续发送下一包）
static void history_notify_cb(struct bt_conn *conn, void *user_data)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(user_data);

	history_sending = false;

	// 当前包发送完成后，立即调度下一次发送
	if (history_transfer_active && bt_connected) {
		k_work_schedule(&history_send_work, K_NO_WAIT);
	}
}

// 历史数据发送工作函数（优化版：流式传输 + 二分查找 + notify_cb 流控）
static void history_send_work_handler(struct k_work *work)
{
	if (!history_transfer_active || !bt_connected) {
		// 打印最终统计信息
		printk("[历史] 传输中止: 已发送 %u/%u 条记录 (%u 包)\r\n",
		       history_total_sent_records, history_expected_records, history_total_sent_packets);
		// 重置所有传输状态
		history_transfer_active = false;
		history_transfer_sector = 0;
		history_transfer_record_idx = 0;
		history_total_sent_packets = 0;
		history_total_sent_records = 0;
		history_last_sent_timestamp = 0;
		history_binary_search_done = false;
		// 传输停止，进入睡眠（低功耗优化）
		w25q64_sleep();
		return;
	}

	// 如果上一包还在通过 notify_cb 发送中，则等待回调再次调度
	if (history_sending) {
		return;
	}

	// 历史读取与存储写入共用同一颗 W25Q64，必须串行化，避免并发导致异常
	k_mutex_lock(w25q64_get_mutex(), K_FOREVER);

	// 首次传输时唤醒 Flash（低功耗优化）
	if (!history_binary_search_done) {
		w25q64_wakeup();
	}
	
	// 首次传输时，若设置了起始时间戳则用线性查找定位“第一条 > 时间戳”的记录；若为 0 则从 oldest 开始
	if (!history_binary_search_done && history_start_timestamp > 0) {
		uint16_t start_sector, start_record;
		if (linear_find_first_after_timestamp(history_start_timestamp, &start_sector, &start_record)) {
			history_transfer_sector = start_sector;
			history_transfer_record_idx = start_record;
		} else {
			// 没有 > target 的记录，视为无数据可发，停在写入位置
			printk("[历史] 警告: 未找到时间戳 > %u 的记录，传输将结束\r\n", history_start_timestamp);
			history_transfer_sector = next_sector;
			history_transfer_record_idx = next_record_in_sector;
		}
		history_binary_search_done = true;
	} else if (!history_binary_search_done) {
		// 要全部（timestamp==0）：强制从最旧扇区开始，避免残留上次“按时间戳”的传输位置导致只传几十条
		history_transfer_sector = oldest_sector;
		history_transfer_record_idx = 0;
		history_binary_search_done = true;
	}

	// 检查是否完成传输
	// 传输顺序：从 oldest_sector 开始，依次发送到 next_sector（包含 next_sector 中 next_record_in_sector 之前的记录）
	bool transfer_complete = false;
	
	// 统一使用函数检查传输扇区是否已经超过有效范围
	// 有效范围：[oldest_sector, next_sector] 且在 next_sector 时 record_idx < next_record_in_sector
	if (next_sector == oldest_sector) {
		// 特殊情况：存储为空或所有数据在同一个扇区
		if (next_record_in_sector == 0) {
			// 存储为空
			transfer_complete = true;
		} else if (history_transfer_sector == next_sector && 
		           history_transfer_record_idx >= next_record_in_sector) {
			// 当前扇区的所有数据已发送完毕
			transfer_complete = true;
		}
		// 注意：不再判断 history_transfer_sector != next_sector，这是之前的 bug
	} else if (next_sector > oldest_sector) {
		// 正常情况：oldest_sector < next_sector，数据在 [oldest_sector, next_sector) 范围
		if (history_transfer_sector > next_sector) {
			// 已超过写入扇区（不应该发生，但作为安全检查）
			transfer_complete = true;
		} else if (history_transfer_sector == next_sector && 
		           history_transfer_record_idx >= next_record_in_sector) {
			// 已到达写入扇区的当前位置
			transfer_complete = true;
		}
	} else {
		// 环形覆盖情况：next_sector < oldest_sector
		// 数据范围：[oldest_sector, MAX) + [0, next_sector)
		// 传输完成条件：到达 next_sector 且 record_idx >= next_record_in_sector
		if (history_transfer_sector == next_sector && 
		    history_transfer_record_idx >= next_record_in_sector) {
			transfer_complete = true;
		}
		// 额外检查：如果传输扇区既不在 [oldest_sector, MAX) 也不在 [0, next_sector]，说明有问题
		// 注意：这种情况应该由二分查找正确定位，此处仅作安全检查
	}
	
	if (transfer_complete) {
		printk("[历史] 传输完成: 共发送 %u/%u 条记录 (%u 包)\r\n", 
		       history_total_sent_records, history_expected_records, history_total_sent_packets);
		if (history_skipped_invalid > 0 || history_skipped_filtered > 0) {
			printk("[历史] 跳过统计: 无效数据 %u 条, 时间戳过滤 %u 条\r\n",
			       history_skipped_invalid, history_skipped_filtered);
		}
		// 发送结束标志 (0xFF)
		uint8_t end_mark[1] = {0xFF};
		if (history_char_attr) {
			bt_gatt_notify(NULL, history_char_attr, end_mark, 1);
		}
		// 重置所有传输状态
		history_transfer_active = false;
		history_transfer_sector = 0;
		history_transfer_record_idx = 0;
		history_total_sent_packets = 0;
		history_total_sent_records = 0;
		history_last_sent_timestamp = 0;
		history_binary_search_done = false;
		// 传输完成，进入睡眠（低功耗优化）
		w25q64_sleep();
		k_mutex_unlock(w25q64_get_mutex());
		return;
	}

	// ========== 流式传输：每次只读取MTU大小的数据 ==========
	// 保存当前状态，以便发送失败时回滚
	uint16_t saved_sector = history_transfer_sector;
	uint16_t saved_idx = history_transfer_record_idx;
	uint32_t saved_last_timestamp = history_last_sent_timestamp;

	uint32_t sector_addr = W25Q64_STORAGE_BASE + history_transfer_sector * W25Q64_SECTOR_SIZE;
	
	// 流式读取缓冲区（每次最多读取8条记录 = 64字节）
	uint8_t tx_buf[STREAM_PACKET_SIZE];
	uint8_t tx_len = 0;
	int records_collected = 0;
	int records_scanned = 0;
	
	// 从Flash流式读取数据（每次只读取需要发送的量，节省RAM）
	// 确定当前扇区的有效记录数上限
	uint16_t max_records_in_sector = W25Q64_RECORDS_PER_SECTOR;
	if (history_transfer_sector == next_sector && next_sector == oldest_sector) {
		// 特殊情况：所有数据在同一个扇区
		max_records_in_sector = next_record_in_sector;
	} else if (history_transfer_sector == next_sector) {
		// 当前扇区是写入扇区
		max_records_in_sector = next_record_in_sector;
	}
	
	while (records_collected < STREAM_RECORDS_PER_PACKET && 
	       history_transfer_record_idx + records_scanned < max_records_in_sector) {
		
		// 重要：使用与写入时相同的地址计算方式（页索引+页内索引），确保地址一致
		// 写入时：page_index = record_idx / W25Q64_RECORDS_PER_PAGE, index_in_page = record_idx % W25Q64_RECORDS_PER_PAGE
		// 地址 = sector_base + page_index * W25Q64_PAGE_SIZE + index_in_page * W25Q64_RECORD_SIZE
		uint16_t record_idx = history_transfer_record_idx + records_scanned;
		uint16_t page_index = record_idx / W25Q64_RECORDS_PER_PAGE;
		uint16_t index_in_page = record_idx % W25Q64_RECORDS_PER_PAGE;
		uint32_t rec_addr = sector_addr 
			+ (uint32_t)page_index * W25Q64_PAGE_SIZE
			+ (uint32_t)index_in_page * W25Q64_RECORD_SIZE;
		struct data_record rec;
		
		int ret = w25q64_read(rec_addr, (uint8_t *)&rec, sizeof(rec));
		if (ret != 0) {
			break;
		}
		
		// 检查记录是否有效
		if (rec.timestamp == 0xFFFFFFFF) {
			// 遇到无效记录，当前扇区读取完毕
			// 如果我们还没读到预期的 next_record_in_sector，说明Flash数据可能不一致
			if (history_transfer_sector == next_sector && 
			    (history_transfer_record_idx + records_scanned) < next_record_in_sector) {
				printk("[历史] 警告: 在预期范围内读到空数据(0xFF)，提前结束扇区 (idx=%d, next=%d)\r\n", 
				       history_transfer_record_idx + records_scanned, next_record_in_sector);
			}
			records_scanned++;
			break;
		}
		
		// 数据有效性检查：过滤掉时间戳为0或异常的数据
		if (rec.timestamp == 0) {
			records_scanned++;
			history_skipped_invalid++;
			continue;
		}
		
		// 检查时间戳是否合理（1970-01-01 到 2100-01-01）
		if (rec.timestamp > 4102444800) {
			records_scanned++;
			history_skipped_invalid++;
			continue;
		}
		
		records_scanned++;
		
		// 时间戳过滤（二分查找后理论上不需要，但保留作为安全检查）
		if (history_start_timestamp > 0 && rec.timestamp <= history_start_timestamp) {
			history_skipped_filtered++;
			continue;  // 跳过不符合条件的记录
		}
		
		// 数据有效性检查：验证温度、湿度、气压范围
		bool data_valid = true;
		
		// 检查数据是否全0（可能是未初始化的数据）
		// 注意：这个检查已经在前面处理过timestamp==0的情况，这里作为额外安全检查
		if (rec.timestamp == 0 && rec.temperature == 0 && rec.humidity == 0 && rec.pressure_pa == 0) {
			// 全0数据，跳过（已在前面检查过timestamp==0，这里作为额外检查）
			// 注意：records_scanned已经在前面增加了，这里不需要再次增加
			continue;
		}
		
		// 检查温度范围 (-50.00°C ~ 150.00°C)
		if (rec.temperature < -5000 || rec.temperature > 15000) {
			data_valid = false;
		}
		
		// 检查湿度范围 (0.00% ~ 100.00%)
		if (rec.humidity > 10000) {
			data_valid = false;
		}
		
		// 检查气压范围 (100hPa ~ 2000hPa -> 10000Pa ~ 200000Pa)
		if (rec.pressure_pa < 10000 || rec.pressure_pa > 200000) {
			data_valid = false;
		}
		
		if (!data_valid) {
			// 数据异常，跳过此记录
			history_skipped_invalid++;
			continue;
		}
		
		// 将记录添加到发送缓冲区
		memcpy(&tx_buf[tx_len], &rec, sizeof(struct data_record));
		tx_len += sizeof(struct data_record);
		records_collected++;
	}
	
	// 更新记录索引（已扫描的记录数）
	history_transfer_record_idx += records_scanned;
	
	// 检查是否需要切换到下一个扇区
	if (history_transfer_record_idx >= W25Q64_RECORDS_PER_SECTOR || 
	    (records_scanned > 0 && records_collected == 0)) {
		// 当前扇区已读完或没有有效数据
		history_transfer_sector = (history_transfer_sector + 1) % W25Q64_MAX_SECTORS_LIMIT;
		history_transfer_record_idx = 0;
		
		// 切换扇区后，立即检查是否完成传输
		// 使用与前面相同的逻辑判断
		bool transfer_complete_after_switch = false;
		if (next_sector == oldest_sector) {
			// 特殊情况：存储为空或所有数据在同一个扇区
			// 如果切换到了下一个扇区，说明已经读完了当前扇区
			// 注意：切换后 history_transfer_sector = (next_sector + 1) % MAX
			// 由于 next_sector == oldest_sector，切换后的扇区不再是有效数据扇区
			transfer_complete_after_switch = true;
		} else if (next_sector > oldest_sector) {
			// 正常情况：数据在 [oldest_sector, next_sector) 范围
			if (history_transfer_sector > next_sector) {
				// 已超过写入扇区
				transfer_complete_after_switch = true;
			} else if (history_transfer_sector == next_sector && 
			           history_transfer_record_idx >= next_record_in_sector) {
				// 已到达写入扇区的当前位置
				transfer_complete_after_switch = true;
			}
		} else {
			// 环形覆盖情况：next_sector < oldest_sector
			// 数据范围：[oldest_sector, MAX) + [0, next_sector)
			if (history_transfer_sector == next_sector && 
			    history_transfer_record_idx >= next_record_in_sector) {
				transfer_complete_after_switch = true;
			}
		}
		
		if (transfer_complete_after_switch && tx_len == 0) {
			// 传输完成且没有数据要发送
			printk("[历史] 传输完成: 共发送 %u/%u 条记录 (%u 包)\r\n",
			       history_total_sent_records, history_expected_records, history_total_sent_packets);
			if (history_skipped_invalid > 0 || history_skipped_filtered > 0) {
				printk("[历史] 跳过统计: 无效数据 %u 条, 时间戳过滤 %u 条\r\n",
				       history_skipped_invalid, history_skipped_filtered);
			}
			uint8_t end_mark[1] = {0xFF};
			if (history_char_attr) {
				bt_gatt_notify(NULL, history_char_attr, end_mark, 1);
			}
			history_transfer_active = false;
			history_transfer_sector = 0;
			history_transfer_record_idx = 0;
			history_total_sent_packets = 0;
			history_total_sent_records = 0;
			history_binary_search_done = false;
			w25q64_sleep();
			k_mutex_unlock(w25q64_get_mutex());
			return;
		}
		
		if (tx_len == 0) {
			// 没有数据发送，立即处理下一个扇区
			k_work_schedule(&history_send_work, K_NO_WAIT);
			k_mutex_unlock(w25q64_get_mutex());
			return;
		}
	}
	
	// 发送数据包（使用 bt_gatt_notify_cb，实现“发完再发”流控）
	if (tx_len > 0 && history_char_attr) {
		struct data_record *last_rec = (struct data_record *)(tx_buf + tx_len - sizeof(struct data_record));
		
		// 显示发送进度（每100包打印一次，避免刷屏）
		uint32_t current_count = history_total_sent_records + records_collected;
		if ((history_total_sent_packets + 1) % 100 == 1) {
			printk("[历史] 正在发送 %u/%u (包#%u)\r\n",
			       current_count, history_expected_records, history_total_sent_packets + 1);
		}
		
		// 更新最后发送的时间戳
		history_last_sent_timestamp = last_rec->timestamp;
		
		// 将要发送的数据拷贝到全局缓冲区，确保在回调前一直有效
		memcpy(history_tx_buf, tx_buf, tx_len);

		memset(&history_notify_params, 0, sizeof(history_notify_params));
		history_notify_params.attr = history_char_attr;
		history_notify_params.data = history_tx_buf;
		history_notify_params.len  = tx_len;
		history_notify_params.func = history_notify_cb;

		history_sending = true;
		int err = bt_gatt_notify_cb(NULL, &history_notify_params);
		if (err) {
			history_sending = false;

			if (err == -ENOMEM) {
				// 发送缓冲区满，回退状态并稍后重试
				history_transfer_sector = saved_sector;
				history_transfer_record_idx = saved_idx;
				history_last_sent_timestamp = saved_last_timestamp;
				k_work_schedule(&history_send_work, K_MSEC(100));
			} else {
				printk("[历史] 发送失败 (%d)\r\n", err);
				history_transfer_active = false;
			}

			k_mutex_unlock(w25q64_get_mutex());
			return;
		}
		
		history_total_sent_packets++;
		history_total_sent_records += records_collected;
	}
	
	// 发送已成功提交，等待 notify_cb 回调再继续发送下一批
	k_mutex_unlock(w25q64_get_mutex());
}

int main(void)
{
	// UART 已通过设备树（app.overlay）自动配置和初始化
	// 配置：P0.18 (TX), P0.14 (RX), 115200 波特率
	// 无需手动操作寄存器，Zephyr 驱动会自动处理
	// printk 会自动使用配置好的 UART 控制台输出
	
	// 立即输出测试信息，验证串口是否工作
	printk("\r\n\r\n");
	printk("=== 系统启动 ===\r\n");
	printk("[固件] 版本: %s（patch=%d，来自工程根 VERSION 文件）\r\n",
	       FIRMWARE_VERSION_STRING, FIRMWARE_VERSION);

	int err;
	
	// 初始化 LED 模块（使用 led.c 封装）
	// 低功耗优化：LED默认熄灭，只有蓝牙连接时才点亮
	led_init();
	
	// 在进行系统其它初始化（尤其是蓝牙、传感器）之前，
	// 先完成 W25Q64 存储的初始化和（可选的）启动强制擦除，
	// 确保强制擦除阶段不会有其它子系统并发访问存储。
	// NVS 初始化（配置 + W25Q64 位置，存芯片 FLASH）
	(void)nvs_config_init();
	load_config();

	/* OTA 服务的 GATT 是静态定义，这里只复位状态机。
	 * 必须放在 NVS 之后：断点续传状态要读写 NVS。 */
	ble_ota_init();

#ifdef CONFIG_ADC
	/* 电池电压：SAADC 内部 VDD 通道，零外部电路。
	 * 上电先采一次，手机连上后立刻就能看到电压（否则要等一个采样周期）。
	 * 此处仍在蓝牙启动之前 —— 避开 TX 突发导致的瞬时跌落，读数更接近静态电池电压。 */
	vdd_refresh();
#endif
	
	if (max_temperature != INT16_MIN) {
		printk("[配置] 当前最高温度: %d.%02d°C (时间戳: %u)\r\n",
		       max_temperature / 100, (max_temperature >= 0 ? max_temperature : -max_temperature) % 100,
		       max_temperature_timestamp);
	} else {
		printk("[配置] 最高温度未初始化，等待首次采集更新\r\n");
	}
	if (min_temperature != INT16_MAX) {
		printk("[配置] 当前最低温度: %d.%02d°C (时间戳: %u)\r\n",
		       min_temperature / 100, (min_temperature >= 0 ? min_temperature : -min_temperature) % 100,
		       min_temperature_timestamp);
	} else {
		printk("[配置] 最低温度未初始化，等待首次采集更新\r\n");
	}
	
	// 从 NVS 读取 W25Q64 位置，再 storage_init 里进行强制擦除/写头定位
	{
		storage_position_t pos = {0, 0, 0};
		nvs_load_storage_position(&pos);
		next_sector = pos.next_sector;
		oldest_sector = pos.oldest_sector;
		next_record_in_sector = pos.next_record_in_sector;
	}
	// 从 NVS 加载时间（如果之前已同步过）
	{
		uint32_t saved_timestamp;
		bool saved_time_synced;
		nvs_load_time(&saved_timestamp, &saved_time_synced);
		if (saved_time_synced) {
			time_base_timestamp = saved_timestamp;
			time_base_uptime = k_uptime_get();
			time_synced = true;
		}
		/* 无论恢复成功与否，都**不宣称**墙钟对齐：恢复出来的是上次对时的旧值，
		 * 停机期间秒数停走，它必然偏慢。要等**本次上电**收到手机对时才算可信
		 * （届时由 time_char_write_cb 置位并重算边界）。
		 * 在此之前周期记录退回"严格等间隔 + 真实秒"，不按整分网格造"看起来对齐"的时间戳。 */
		wall_clock_aligned = false;
	}
	int storage_err = storage_init();
	if (storage_err != 0) {
		printk("[系统] 严重错误: Flash 初始化失败 (%d)，系统停机\r\n", storage_err);
		// 持续快速闪烁 LED 指示故障
		while (1) {
			led_toggle(LED_MASK);
			k_msleep(100);
		}
	}
	// 注意：不再启动时统计历史记录，避免不必要的Flash读取
	// 历史数据可通过蓝牙连接后使用历史数据特性获取
	
	if (nvs_config_is_ready()) {
		k_timer_start(&nvs_flush_timer, K_SECONDS(30), K_SECONDS(30));
		printk("[NVS] 定时 flush 已启动 (30s)\r\n");
	}
	
	// 到这里为止，W25Q64 的强制清空（若启用 STORAGE_CLEAR_ON_BOOT）
	// 已经全部完成，下面再开始系统其它模块初始化。
	
	// 初始化采集定时器和工作队列（提前初始化，避免竞争条件）
	k_timer_init(&sample_timer, sample_timer_handler, NULL);
	k_work_init(&sample_work, sample_work_handler);
	k_work_init_delayable(&sample_start_work, sample_start_work_handler);
	
	// 广播工作队列已移至 ble_adv 模块
	k_work_init_delayable(&history_send_work, history_send_work_handler);
	k_work_init_delayable(&config_save_work, config_save_work_handler);
	k_work_init_delayable(&clear_data_work, clear_data_work_handler);
	// 预擦除工作队列在storage_init()中初始化
	
	// 发送测试消息
	printk("\r\n");
	printk("========================================\r\n");
	printk("蓝牙温度计程序启动 (参考 RIOT-2020.10-RC3)\r\n");
	printk("DATA LED: P0.%d, LINK LED: P0.%d\r\n", 31, 5);
	printk("设备名称: %s (启动后添加MAC后缀)\r\n", DEVICE_NAME_BASE);
	printk("========================================\r\n");
	
	// 低功耗优化：不再启动初始化闪烁，LED保持熄灭
	// led_start_init_blink();  // 已禁用
	
	// 初始化蓝牙（已禁用绑定，无需配对）
	err = bt_enable(bt_ready_cb);
	if (err) {
		printk("蓝牙使能失败 (错误码: %d)\r\n", err);
		return 0;
	}
	
	// 获取 I2C 设备
	i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));
	if (!device_is_ready(i2c_dev)) {
		printk("I2C 设备未就绪\r\n");
	} else {
		printk("I2C 设备已就绪 (SDA: P0.7, SCL: P0.6)\r\n");
		i2c_scan_bus(i2c_dev);
	}
	
	// 初始化 AHT30 温湿度传感器
	err = aht30_init(i2c_dev);
	if (err) {
		printk("AHT30 初始化失败: %d\r\n", err);
	}
	
	// 初始化 SPL06-001 气压传感器
	err = spl06_init_ex(i2c_dev, false);
	if (err) {
		printk("SPL06-001 初始化失败: %d\r\n", err);
	}
	
	// 注意：采集任务将在蓝牙就绪后由 bt_ready_cb() 启动
	// 这样可以避免在蓝牙未初始化时访问 GATT 服务
	
	// 确保所有初始化完成后再进入主循环
	// 给系统一些时间完成所有异步初始化（如蓝牙初始化）
	k_msleep(200);
	
	printk("[系统] 初始化完成，进入主循环等待事件...\r\n");
	
	// 主循环 - 简化实现
	// 注意：CONFIG_PM=n 时不会进入深度睡眠，但功耗会略高
	// 使用 k_sleep() 让 CPU 进入空闲状态，由定时器和蓝牙中断唤醒
	int wait_count = 0;
	while (1) {
		if (!bt_ready) {
			wait_count++;
			if (wait_count % 3 == 0) { // 每30秒打印一次
				printk("[系统] 警告: 蓝牙尚未就绪，可能正在等待时钟 (RC振荡器校准)...\r\n");
			}
		}
		// 使用较长的睡眠时间，定时器或蓝牙中断会提前唤醒系统
		k_sleep(K_SECONDS(10));
	}
	
	return 0;
}
