/**
 * @file nvs_config.h
 * @brief NVS 配置管理头文件
 */

#ifndef STORAGE_NVS_CONFIG_H_
#define STORAGE_NVS_CONFIG_H_

#include <zephyr/kernel.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/app_version.h>
#include <limits.h>

#ifdef __cplusplus
extern "C" {
#endif

/* NVS 分区：offset/size 由 PM 生成的 nvs_storage 分区提供。 */
#define NVS_SECTOR_SIZE       4096

/* NVS key 定义 */
#define NVS_ID_NEXT_SECTOR    1
#define NVS_ID_OLDEST_SECTOR  2
#define NVS_ID_NEXT_RECORD    3
#define NVS_ID_CONFIG         4
#define NVS_ID_TIME_BASE      5
#define NVS_ID_OTA            6   /* OTA 断点续传状态（offset / total / ih_ver / valid） */

/* ================== 版本号：唯一来源是工程根的 VERSION 文件 ==================
 *
 * Zephyr 在 `${APPLICATION_SOURCE_DIR}/VERSION` 存在时会：
 *   ① 生成 `app_version.h`（APP_VERSION_MAJOR / APP_PATCHLEVEL / APP_VERSION_STRING …）
 *   ② 把 `CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION` 的默认值设为 `APP_VERSION_TWEAK_STRING`
 *      （即 "1.0.4+0"），从而交给 imgtool `--version` 去签镜像。
 * 所以：**改版本只改 VERSION 文件**，不要再手改这里的常量、也不要手改 prj.conf 里的
 * CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION —— 那正是"镜像版本号恒为 0.0.0+0"事故的根源。
 *
 * FIRMWARE_VERSION 沿用历史约定：NVS 里存的是**补丁号**（v4=1.0.3 → 3，v5=1.0.4 → 4）。
 * 只跟到 PATCHLEVEL 是刻意的：MAJOR/MINOR 变更属于跨代不兼容，届时需另行走 NVS 布局迁移，
 * 不应悄悄改掉这个字段的语义。
 */
#ifndef APP_PATCHLEVEL
#error "缺少 app_version.h（由工程根 VERSION 文件生成）。请确认 VERSION 文件存在且已重新构建。"
#endif
#define FIRMWARE_VERSION          APP_PATCHLEVEL
#define FIRMWARE_VERSION_STRING   APP_VERSION_STRING

/*
 * 采样周期与历史记录周期已解耦（D-2，2026-09-11）
 * ---------------------------------------------------------------------------
 * sample_interval  —— 传感器采样 / 实时 BLE 显示 / 最高最低温度跟踪。**固定 1 秒，不可配置**。
 * history_interval —— 历史记录落盘周期：每积累 history_interval 个采样点，取算术平均写入
 *                     W25Q64 一条 12 字节记录。默认 60 秒，可由 BLE 特性 12340021 配置。
 *
 * 容量口径（每条记录 12 B，每扇区 336 条，上限 W25Q64_MAX_RECORDS = 65000 条）：
 *   60 秒  -> 65,000 × 60  / 86400 = 45.1 天   （产品承诺 30 天，余量 33%）
 *   30 秒  -> 22.6 天（**不足 30 天，故下限锁死在 60 秒**）
 *   300 秒 -> 225.7 天
 * MIN_HISTORY_INTERVAL 取 60 是**容量红线**，不是性能取舍：低于它就无法兑现 30 天保留承诺。
 */
#define SAMPLE_INTERVAL_FIXED    1     /* 采样固定 1 秒 */
#define DEFAULT_HISTORY_INTERVAL 60    /* 历史记录默认 60 秒 */
#define MIN_HISTORY_INTERVAL     60    /* 下限：守住 30 天保留（见上方容量口径）*/
#define MAX_HISTORY_INTERVAL     3600  /* 上限 1 小时 */

/* 以下三个宏仅用于解析**旧格式** NVS 配置（v2 及更早，采样/历史共用一个周期），
 * 新格式不再使用 sample_interval 作为可配置项。 */
#define DEFAULT_SAMPLE_INTERVAL  1
#define MIN_SAMPLE_INTERVAL      1
#define MAX_SAMPLE_INTERVAL      3600

/* 配置数据结构（存储在Flash）—— 当前格式：18 字节 */
typedef struct {
	uint16_t sample_interval;            /* 采样间隔（秒），新固件恒为 SAMPLE_INTERVAL_FIXED */
	uint16_t history_interval;           /* 历史记录落盘间隔（秒），60~3600 */
	int16_t  max_temperature;            /* 最高温度（0.01°C）*/
	int16_t  min_temperature;            /* 最低温度（0.01°C）*/
	uint32_t max_temperature_timestamp;  /* 最高温度发生时间 */
	uint32_t min_temperature_timestamp;  /* 最低温度发生时间 */
	uint16_t firmware_version;           /* 固件版本号 */
} __packed device_config_t;

/* 旧格式（固件 v2，16 字节）：无 history_interval。
 * 必须显式建模，否则升级后 nvs_load_config() 会因长度不匹配而丢弃历史最高/最低温度。 */
typedef struct {
	uint16_t sample_interval;
	int16_t  max_temperature;
	int16_t  min_temperature;
	uint32_t max_temperature_timestamp;
	uint32_t min_temperature_timestamp;
	uint16_t firmware_version;
} __packed device_config_v2_t;

/* 设备配置上下文 */
typedef struct {
	uint16_t sample_interval;
	uint16_t history_interval;
	int16_t  max_temperature;
	int16_t  min_temperature;
	uint32_t max_temperature_timestamp;
	uint32_t min_temperature_timestamp;
	uint16_t firmware_version;
	uint16_t record_count;
	bool     config_dirty;
	bool     position_dirty;
} device_config_ctx_t;

/* 存储位置上下文 */
typedef struct {
	uint16_t next_sector;
	uint16_t oldest_sector;
	uint16_t next_record_in_sector;
} storage_position_t;

/**
 * @brief 初始化 NVS
 * @return 0 成功，负数错误码
 */
int nvs_config_init(void);

/**
 * @brief 检查 NVS 是否就绪
 * @return true 已就绪，false 未就绪
 */
bool nvs_config_is_ready(void);

/**
 * @brief 从 NVS 加载存储位置
 * @param pos 存储位置输出
 */
void nvs_load_storage_position(storage_position_t *pos);

/**
 * @brief 保存存储位置到 NVS
 * @param pos 存储位置
 */
void nvs_save_storage_position(const storage_position_t *pos);

/**
 * @brief 清空存储位置信息
 */
void nvs_clear_storage_position(void);

/**
 * @brief 从 NVS 加载时间基准
 * @param timestamp 时间戳输出
 * @param time_synced 时间同步状态输出
 */
void nvs_load_time(uint32_t *timestamp, bool *time_synced);

/**
 * @brief 保存时间到 NVS
 * @param timestamp 时间戳
 * @return 0 成功，负数错误码
 */
int nvs_save_time(uint32_t timestamp);

/**
 * @brief 从 NVS 加载设备配置
 * @param config 配置输出
 */
void nvs_load_config(device_config_ctx_t *config);

/**
 * @brief 保存设备配置到 NVS
 * @param config 配置
 * @param immediate true 立即保存，false 延迟保存
 */
void nvs_save_config(device_config_ctx_t *config, bool immediate);

/**
 * @brief 刷新 NVS（保存脏数据）
 * @param config 配置上下文
 * @param pos 存储位置
 */
void nvs_flush(device_config_ctx_t *config, const storage_position_t *pos);

/**
 * @brief 获取 NVS 文件系统指针
 * @return NVS 文件系统指针
 */
struct nvs_fs *nvs_get_fs(void);

#ifdef __cplusplus
}
#endif

#endif /* STORAGE_NVS_CONFIG_H_ */
