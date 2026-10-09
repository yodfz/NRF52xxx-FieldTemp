package com.example.pandatemperature.utils

import kotlin.math.pow

/**
 * 将观测气压换算为海平面气压 (MSLP)
 * 与 [calculateAltitude] 使用的公式一致（可逆）
 * @param pressureHpa 观测点气压（hPa）
 * @param altitudeM 观测点海拔（米）
 * @param temperatureCelsius 温度（°C），可选
 * @return 海平面气压（hPa）
 */
fun pressureToSeaLevel(pressureHpa: Float, altitudeM: Double, temperatureCelsius: Float? = null): Float {
    val h = altitudeM.toFloat()
    val p0 = 1013.25f
    return if (temperatureCelsius != null) {
        // 由 h = ((p0/p)^(1/5.257) - 1) * (T+273.15) / 0.0065 反推
        // p0 = p * (1 + 0.0065*h/(T+273.15))^5.257
        val tempK = temperatureCelsius + 273.15f
        val factor = 1.0 + 0.0065 * h / tempK
        (pressureHpa * factor.pow(5.257)).toFloat()
    } else {
        // 标准大气：h = 44330 * (1 - (p/p0)^(1/5.255)) => p/p0 = (1 - h/44330)^5.255 => p0 = p / (1 - h/44330)^5.255
        val ratio = 1.0 - h / 44330.0
        if (ratio <= 0) return pressureHpa
        (pressureHpa / ratio.toDouble().pow(5.255)).toFloat()
    }
}

/**
 * 计算海拔高度（仅气压）
 * @param pressurehPa 气压（hPa）
 * @param temperatureCelsius 温度（°C），可选。如果提供，将使用带温度补偿的公式提高精度。
 * @return 海拔高度（米）
 */
fun calculateAltitude(pressurehPa: Float, temperatureCelsius: Float? = null): Int {
    return calculateAltitudeDouble(pressurehPa, temperatureCelsius).toInt()
}

/**
 * 气压推算海拔（内部用 Double，供混合计算复用）
 */
private fun calculateAltitudeDouble(pressurehPa: Float, temperatureCelsius: Float? = null): Double {
    val p0 = 1013.25
    return if (temperatureCelsius != null) {
        val pRatio = p0 / pressurehPa
        val term1 = pRatio.pow(1.0 / 5.257) - 1.0
        val tempK = temperatureCelsius + 273.15
        (term1 * tempK) / 0.0065
    } else {
        44330.0 * (1.0 - (pressurehPa / p0).pow(1.0 / 5.255))
    }
}

/**
 * 混合计算海拔：有 GPS 时用 GPS + 气压加权混合，无 GPS 时退化为仅气压
 * @param pressurehPa 气压（hPa）
 * @param temperatureCelsius 温度（°C），可选
 * @param gpsAltitudeMeters GPS 海拔（米），null 时仅用气压计算
 * @return 海拔高度（米），用于显示时取整即可
 */
fun calculateAltitudeHybrid(
    pressurehPa: Float,
    temperatureCelsius: Float? = null,
    gpsAltitudeMeters: Double? = null
): Double {
    val pressureAlt = calculateAltitudeDouble(pressurehPa, temperatureCelsius)
    if (gpsAltitudeMeters == null) return pressureAlt
    // 有 GPS：加权混合（GPS 权重略高，气压用于平滑与短期变化）
    val gpsWeight = 0.6
    return gpsWeight * gpsAltitudeMeters + (1.0 - gpsWeight) * pressureAlt
}

/**
 * 根据气压变化趋势或绝对值推测天气活动（简化版）
 * @param pressurehPa 气压（hPa）
 * @return 天气描述
 */
fun getWeatherDescription(pressurehPa: Float): String {
    return when {
        pressurehPa > 1020 -> "高压区 (晴朗)"
        pressurehPa < 1000 -> "低压区 (可能降雨)"
        else -> "气压正常 (多云)"
    }
}
