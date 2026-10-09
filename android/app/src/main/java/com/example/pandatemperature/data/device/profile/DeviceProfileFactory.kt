package com.example.pandatemperature.data.device.profile

import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.device.model.DeviceTypes
import com.example.pandatemperature.data.device.model.HistoryRecord
import com.example.pandatemperature.data.device.model.SensorData
import com.example.pandatemperature.data.device.profile.thermometer.ThermometerProfile
import com.example.pandatemperature.data.device.profile.thermometer.ThermometerV1Profile
import com.example.pandatemperature.data.device.profile.thermometer.ThermometerV2Profile
import com.example.pandatemperature.data.device.profile.thermometer.ThermometerV3Profile
import com.example.pandatemperature.data.model.DeviceStatus

/**
 * 设备配置工厂
 * 根据设备类型、固件版本、可用服务创建对应的设备配置
 */
object DeviceProfileFactory {
    
    /**
     * 创建设备配置（通用方法）
     * @param deviceType 设备类型（字符串常量，参见 DeviceTypes）
     * @param firmwareVersion 固件版本号，0 或 null 表示老固件
     * @param availableServiceUuids 设备支持的服务 UUID 集合
     * @return 对应的设备配置
     */
    fun <T : SensorData, H : HistoryRecord> createProfile(
        deviceType: String,
        firmwareVersion: Int?,
        availableServiceUuids: Set<String> = emptySet(),
        capabilityFlags: Int = 0
    ): DeviceProfile<T, H>? {
        @Suppress("UNCHECKED_CAST")
        return when (deviceType) {
            DeviceTypes.THERMOMETER -> createThermometerProfile(firmwareVersion, availableServiceUuids, capabilityFlags) as? DeviceProfile<T, H>
            DeviceTypes.UNKNOWN -> createThermometerProfile(firmwareVersion, availableServiceUuids, capabilityFlags) as? DeviceProfile<T, H>
            else -> null
        }
    }
    
    /**
     * 创建温度计配置（专用方法）
     *
     * 历史长度按显式能力选择：0x40 请求 V3（14B），否则合并实时固件仍是 V2（12B）。
     * patch 号与旧版版本编码都不能用于推断 V3，通知包长度也不用于猜格式。
     * 无能力标志的旧 ESS 固件保留 V1（8B）。
     *
     * @param firmwareVersion 固件版本号（新固件为 patch 号）
     * @param availableServiceUuids 可用服务 UUID
     * @param capabilityFlags 状态帧 byte5；0x40 显式选择含电压历史
     * @return 温度计配置
     */
    fun createThermometerProfile(
        firmwareVersion: Int?,
        availableServiceUuids: Set<String> = emptySet(),
        capabilityFlags: Int = 0
    ): ThermometerProfile {
        // 合并实时数据固件：有有效版本号，或设备直接暴露了实时数据服务。
        val hasRealtimeDataService = availableServiceUuids.contains(BleConstants.REALTIME_DATA_SERVICE)
        val hasValidVersion = firmwareVersion != null && firmwareVersion > 0
        val isCombinedRealtimeFirmware = hasValidVersion || hasRealtimeDataService

        return if ((capabilityFlags and DeviceStatus.HISTORY_VOLTAGE_CAPABILITY) != 0) {
            ThermometerV3Profile(firmwareVersion ?: 0)
        } else if (isCombinedRealtimeFirmware) {
            // 12 字节历史（V2），含气压、不含电压。旧固件与 1.0.6+0 新固件同属此路径。
            ThermometerV2Profile(firmwareVersion ?: 0)
        } else {
            // 老 ESS 固件：8 字节历史（V1），分开读取温度/湿度。
            ThermometerV1Profile()
        }
    }
    
    /**
     * 根据固件版本快速判断是否为新固件
     * 用于在读取设备状态后快速判断
     */
    fun isNewFirmware(firmwareVersion: Int?): Boolean {
        return firmwareVersion != null && firmwareVersion > 0
    }
}
