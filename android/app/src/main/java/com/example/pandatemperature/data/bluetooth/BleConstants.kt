package com.example.pandatemperature.data.bluetooth

/**
 * 蓝牙 GATT 服务和特征 UUID 常量定义
 * 与 Web 端保持一致
 */
object BleConstants {
    // ESS (Environmental Sensing Service) 服务（已废弃，保留用于兼容性检查）
    const val ESS_SERVICE = "0000181a-0000-1000-8000-00805f9b34fb"
    
    // 温度特征（已废弃，保留用于兼容性检查）
    const val TEMP_CHAR = "00002a6e-0000-1000-8000-00805f9b34fb"
    
    // 湿度特征（已废弃，保留用于兼容性检查）
    const val HUMIDITY_CHAR = "00002a6f-0000-1000-8000-00805f9b34fb"
    
    // 实时数据服务（v1.1 新增：合并温度+湿度+气压）
    const val REALTIME_DATA_SERVICE = "12340030-1234-5678-1234-56789abcdef0"
    
    // 实时数据特征（6字节：temperature int16 + humidity uint16 + pressure_dhpa uint16）
    const val REALTIME_DATA_CHAR = "12340031-1234-5678-1234-56789abcdef0"
    
    // 配置服务
    const val CONFIG_SERVICE = "12340020-1234-5678-1234-56789abcdef0"
    
    // 时间同步特征
    const val TIME_SYNC_CHAR = "12340011-1234-5678-1234-56789abcdef0"
    
    // 历史记录间隔特征（读写，uint16，单位秒）
    //
    // 语义变更（固件 v3）：该特征表示"历史记录落盘周期"，实时采样固定 1 秒。
    // 旧固件（v2 及更早）表示"采集间隔"，采样与落盘共用同一周期。
    // 通过状态特征 byte5 的能力标志 bit0（supportsHistoryInterval）区分。
    const val INTERVAL_CHAR = "12340021-1234-5678-1234-56789abcdef0"

    /** 设备历史记录上限（条），与固件 W25Q64_MAX_RECORDS 保持一致；用于估算可保留时长 */
    const val HISTORY_MAX_RECORDS = 65000

    /** 历史记录间隔下限（秒）。这是固件的容量红线：65000×60s = 45.1 天，低于它无法兑现 30 天保留承诺 */
    const val HISTORY_INTERVAL_MIN = 60

    /** 历史记录间隔上限（秒），与固件 MAX_HISTORY_INTERVAL 一致（1 小时） */
    const val HISTORY_INTERVAL_MAX = 3600

    /** 按历史记录间隔估算可保留天数（名义速率，不含事件记录） */
    fun estimateRetentionDays(intervalSeconds: Int): Int =
        (intervalSeconds.toLong() * HISTORY_MAX_RECORDS / 86400L).toInt()
    
    // 设备状态特征
    const val STATUS_CHAR = "12340022-1234-5678-1234-56789abcdef0"
    
    // 历史数据特征
    const val HISTORY_CHAR = "12340023-1234-5678-1234-56789abcdef0"

    // 最高最低温度特征（12字节：最高温度2 + 最高时间4 + 最低温度2 + 最低时间4，可读/可通知）
    const val MAX_MIN_TEMP_CHAR = "12340024-1234-5678-1234-56789abcdef0"

    // 重置最高最低温度特征（可写：写入任意值即可重置，并通知手机）
    const val RESET_MAX_MIN_TEMP_CHAR = "12340025-1234-5678-1234-56789abcdef0"
    
    // 历史记录信息特征（8字节：总记录数 + 起始扇区 + 起始记录索引）
    // 注意：该 UUID 在早期设计中曾用于电池电量特征，当前固件已将其用于历史信息特征
    const val HISTORY_INFO_CHAR = "12340026-1234-5678-1234-56789abcdef0"
    
    // Client Characteristic Configuration Descriptor
    const val CCCD_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"
    
    // 设备名称过滤器（支持多种设备名称，兼容新旧版本）
    const val DEVICE_NAME_PANDA_EX = "PandaTemperatureEX"  // v1.1 默认名称
    const val DEVICE_NAME_PANDA = "PandaTemperature"       // 旧版本
    const val DEVICE_NAME_PREFIX_PANDA = "Panda"            // 前缀匹配
    const val DEVICE_NAME_YODFZ = "yodfz-temp"              // 备用名称
    const val DEVICE_NAME_PREFIX_YODFZ = "yodfz"            // 前缀匹配
    
    // 清空数据服务（v1.2 新增）
    const val CLEAR_DATA_SERVICE = "12340040-1234-5678-1234-56789abcdef0"
    
    // 清空数据特征（可写：写入任意值触发清空所有历史数据，异步执行）
    const val CLEAR_DATA_CHAR = "12340041-1234-5678-1234-56789abcdef0"

    // ========== BLE OTA（固件 1.0.6+0 新增的自定义升级服务）==========
    // 详见 OTA协议说明.md / 固件 ble_ota.h。协议常量与流程在 data/ota 包。

    /** OTA 服务 */
    const val OTA_SERVICE = "12340050-1234-5678-1234-56789abcdef0"

    /** OTA Control（Write，带响应） */
    const val OTA_CONTROL_CHAR = "12340051-1234-5678-1234-56789abcdef0"

    /** OTA Data（Write Without Response） */
    const val OTA_DATA_CHAR = "12340052-1234-5678-1234-56789abcdef0"

    /** OTA Status（Notify，12 字节） */
    const val OTA_STATUS_CHAR = "12340053-1234-5678-1234-56789abcdef0"

    // 历史数据结束标志
    const val HISTORY_END_FLAG: Byte = 0xFF.toByte()
}
