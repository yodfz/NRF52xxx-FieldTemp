/**
 * @file ble_services.h
 * @brief 蓝牙 GATT 服务定义头文件
 */

#ifndef BLE_BLE_SERVICES_H_
#define BLE_BLE_SERVICES_H_

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/gatt.h>
#include "../common.h"

#ifdef __cplusplus
extern "C" {
#endif

/* 时间同步特征属于配置服务（12340020），没有独立的 12340010 服务。 */
#define BT_UUID_TIME_SYNC_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340011, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)

/* 配置服务 UUID 定义 */
#define BT_UUID_CONFIG_SERVICE_VAL \
	BT_UUID_128_ENCODE(0x12340020, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_INTERVAL_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340021, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_STATUS_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340022, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_HISTORY_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340023, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_TEMP_RANGE_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340024, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_TEMP_RANGE_RESET_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340025, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_HISTORY_INFO_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340026, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)

/* 实时数据服务 UUID 定义 */
#define BT_UUID_REALTIME_SERVICE_VAL \
	BT_UUID_128_ENCODE(0x12340030, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_REALTIME_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340031, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)

/* 清空数据服务 UUID 定义 */
#define BT_UUID_CLEAR_DATA_SERVICE_VAL \
	BT_UUID_128_ENCODE(0x12340040, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_CLEAR_DATA_CHAR_VAL \
	BT_UUID_128_ENCODE(0x12340041, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)

/* 流式传输参数 */
#define STREAM_RECORDS_PER_PACKET 5
#define STREAM_PACKET_SIZE (STREAM_RECORDS_PER_PACKET * 12)  /* 60字节 */

/**
 * @brief 获取历史传输上下文
 * @return 历史传输上下文指针
 */
history_transfer_ctx_t *ble_get_history_ctx(void);

/**
 * @brief 重置历史传输上下文
 */
void ble_reset_history_ctx(void);

/**
 * @brief 启动历史数据传输
 * @param start_timestamp 起始时间戳（0表示全部）
 */
void ble_start_history_transfer(uint32_t start_timestamp);

/**
 * @brief 停止历史数据传输
 */
void ble_stop_history_transfer(void);

#ifdef __cplusplus
}
#endif

#endif /* BLE_BLE_SERVICES_H_ */
