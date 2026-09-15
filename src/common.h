/**
 * @file common.h
 * @brief 公共数据类型定义
 */

#ifndef COMMON_H_
#define COMMON_H_

#include <zephyr/kernel.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* RAM 缓冲区大小 */
#define RAM_BUFFER_SIZE 5

/**
 * @brief 数据记录结构
 */
struct data_record {
	uint32_t timestamp;          /* Unix时间戳（秒）*/
	int16_t temperature;         /* 温度（0.01°C）*/
	uint16_t humidity;           /* 湿度（0.01%RH）*/
	uint32_t pressure_pa;          /* 气压（Pa）*/
} __packed;

/**
 * @brief 历史数据传输上下文（结构体封装优化）
 * 
 * 优化前：约8个分散的静态全局变量
 * 优化后：统一封装到一个结构体中
 */
typedef struct {
	uint16_t sector;              /* 当前传输扇区 */
	uint16_t record_idx;          /* 当前扇区内的记录索引 */
	uint32_t start_timestamp;     /* 起始时间戳（0表示发送全部数据）*/
	bool active;                  /* 传输是否进行中 */
	bool binary_search_done;      /* 二分查找是否已完成 */
	uint32_t total_sent_packets;  /* 累计发送包数 */
	uint32_t total_sent_records;  /* 累计发送记录数 */
	uint32_t last_sent_timestamp; /* 上一条发送记录的时间戳 */
	bool sending;                 /* 是否有在途的历史通知 */
	uint8_t tx_buf[60];           /* 历史数据发送缓冲区 */
} history_transfer_ctx_t;

/**
 * @brief 时间同步上下文
 */
typedef struct {
	uint32_t base_timestamp;      /* 基准时间戳（Unix 时间戳，秒）*/
	int64_t base_uptime;          /* 基准时间对应的系统运行时间（毫秒）*/
	bool synced;                  /* 时间是否已同步 */
} time_sync_ctx_t;

/**
 * @brief 清空数据上下文
 */
typedef struct {
	bool in_progress;             /* 清空数据进行中标志 */
	uint16_t progress;            /* 当前进度（扇区号）*/
	uint16_t erased_count;        /* 成功擦除扇区数 */
	uint16_t failed_count;        /* 失败扇区数 */
} clear_data_ctx_t;

/**
 * @brief 蓝牙状态上下文
 */
typedef struct {
	bool connected;               /* 是否已连接 */
	bool ready;                   /* 蓝牙是否就绪 */
} bt_state_ctx_t;

#ifdef __cplusplus
}
#endif

#endif /* COMMON_H_ */
