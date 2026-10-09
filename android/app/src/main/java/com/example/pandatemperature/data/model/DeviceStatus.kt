package com.example.pandatemperature.data.model

/**
 * 设备状态数据类
 *
 * 对应状态特征 `12340022`。新固件（1.0.6+0 起）为 **12 字节**，8 字节旧帧仍兼容。
 * 解析按绝对偏移进行，长度不足的字段取默认值（等价于旧固件）。
 */
data class DeviceStatus(
    /**
     * 历史记录间隔（秒）
     *
     * 注意语义变更：固件 1.0.6+0 起该字段表示"历史记录落盘周期"（60~3600 秒，默认 60），
     * 实时采样固定 1 秒。旧固件（v2 及更早）该字段表示"采集间隔"，采样与落盘共用同一周期。
     * 用 [supportsHistoryInterval]（能力位 bit0）区分两种语义。
     */
    val interval: Int,
    
    /**
     * 存储的记录数
     */
    val recordCount: Int,
    
    /**
     * 设备连接状态（状态位 bit0）
     */
    val isConnected: Boolean,
    
    /**
     * 时间同步状态（状态位 bit1）
     */
    val isTimeSynced: Boolean,

    /**
     * 固件版本号（状态帧偏移 6-7，uint16）。
     *
     * 新固件（1.0.6+0 起）这里是**patch 号**（例如 6 表示 1.0.6）。
     * 旧固件曾用两位整数编码主/次版本（例如 12 表示 v1.2）。
     * 因此该值**只用于展示与识别**，不再参与历史记录长度选型（见 DeviceProfileFactory）。
     */
    val firmwareVersion: Int = 0,

    /**
     * 是否正在清空数据或执行历史保留维护（状态位 bit2）
     */
    val isDataClearInProgress: Boolean = false,

    /**
     * 墙钟是否可信（状态位 bit3，固件 1.0.6+0 新增）。
     *
     * 只有**本次上电收到过手机对时**才会置 1；仅从设备 NVS 恢复旧时间时为 0。
     * 为 false 时，不应假设历史记录时间戳已对齐整分边界。
     */
    val isWallClockTrusted: Boolean = false,

    /**
     * 能力标志（固件 1.0.6+0 起，状态帧 byte5；旧固件恒为 0）
     *   bit0 支持历史记录间隔配置（interval 语义已变更）
     *   bit1 采样固定 1 秒
     *   bit2 支持事件记录
     *   bit3 历史记录为周期均值
     *   bit4 支持墙钟边界对齐（新增）
     *   bit5 对时后首个窗口可能短于一个周期（新增）
     *   bit6 支持显式请求14字节含电压历史（0x40）
     *   bit7 支持显式保留最新历史命令（0x80）
     */
    val capabilityFlags: Int = 0,

    /**
     * 实时采样间隔（秒），新固件提供，恒为 1
     */
    val sampleInterval: Int? = null,

    /**
     * 名义预计保留天数（按历史记录间隔与总容量计算），新固件提供
     */
    val retentionDays: Int? = null
) {
    companion object {
        const val HISTORY_VOLTAGE_CAPABILITY = 0x40
        const val HISTORY_RETENTION_CAPABILITY = 0x80
    }

    val supportsHistoryRetention: Boolean get() = (capabilityFlags and HISTORY_RETENTION_CAPABILITY) != 0

    val supportsHistoryVoltage: Boolean get() = (capabilityFlags and HISTORY_VOLTAGE_CAPABILITY) != 0
    /** 固件是否已采用"采样 / 历史记录"双周期语义（能力位 bit0） */
    val supportsHistoryInterval: Boolean get() = (capabilityFlags and 0x01) != 0

    /** 固件是否上报固定采样间隔（能力位 bit1） */
    val hasFixedSampleInterval: Boolean get() = (capabilityFlags and 0x02) != 0

    /** 固件是否支持事件记录（突变 / 越限 / 恢复，能力位 bit2） */
    val supportsEventRecords: Boolean get() = (capabilityFlags and 0x04) != 0

    /** 历史记录是否为周期均值（能力位 bit3） */
    val historyIsPeriodMean: Boolean get() = (capabilityFlags and 0x08) != 0

    /** 是否支持墙钟边界对齐，即周期记录落在整分/整周期边界（能力位 bit4） */
    val supportsWallclockAlign: Boolean get() = (capabilityFlags and 0x10) != 0

    /** 对时后首个窗口是否可能短于一个周期（能力位 bit5，设计保留，不应视为异常） */
    val supportsPartialWindow: Boolean get() = (capabilityFlags and 0x20) != 0

    /**
     * 固件版本显示文案。
     *
     * 版本字段的语义随固件世代变化，用能力字节区分：
     * - 能力字节非 0（新固件）：版本号是 **patch 号**，例如 6 -> `v6`（对应 1.0.6）；
     * - 能力字节为 0（旧固件）：版本号是两位主/次版本编码，例如 12 -> `v1.2`。
     */
    val firmwareVersionLabel: String
        get() = when {
            firmwareVersion <= 0 -> "--"
            capabilityFlags != 0 -> "v$firmwareVersion"
            else -> "v${firmwareVersion / 10}.${firmwareVersion % 10}"
        }
}
