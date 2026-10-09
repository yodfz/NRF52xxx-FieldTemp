package com.example.pandatemperature.data.device.model

/**
 * 电池电压显示的三态归约（纯逻辑，无 Android 依赖，便于在 JVM 单测中锁定行为）。
 *
 * 必须区分两种「没有电压」，二者语义完全相反：
 * 1. [reported] = `false`：旧 6 字节帧，该固件根本没有电压能力 →
 *    保持 [previous] 不变，**不得**清掉已显示的电压（既有约定）。
 * 2. [reported] = `true` 且 [frameVoltage] = `null`：8 字节帧**上报了**电压字段，
 *    但值无效（哨兵 `0xFFFF` 或超出 nRF52810 的 1.7~3.6 V 量程）→
 *    返回 `null`，明确表示「设备当前没有有效电压」，界面显示 `--`。
 * 3. [reported] = `true` 且 [frameVoltage] 非空：返回最新有效电压。
 *
 * 注意：本函数只负责「应显示什么」，是否写库由调用方依据 [reported] 决定
 * （未携带电压字段的帧没有电压信息可写）。
 */
fun resolveDisplayedBatteryVoltage(
    previous: Float?,
    frameVoltage: Float?,
    reported: Boolean
): Float? = if (reported) frameVoltage else previous
