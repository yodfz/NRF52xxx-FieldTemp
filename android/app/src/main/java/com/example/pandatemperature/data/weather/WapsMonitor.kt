package com.example.pandatemperature.data.weather

import android.location.Location
import com.example.pandatemperature.utils.computeEhr
import com.example.pandatemperature.utils.decideAlertByEhr
import com.example.pandatemperature.utils.isInStationaryZoneByDisplacement
import com.example.pandatemperature.utils.medianFilterPressure
import com.example.pandatemperature.utils.pressureToSeaLevel
import com.example.pandatemperature.utils.shouldExitStationary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WAPS（气压趋势天气预警）采样循环。
 *
 * 只负责一件事：每秒采气压、每 10 秒采 GPS、每分钟做中值滤波与静止/移动判定，
 * 产出 [WapsResult]。实时值、开关与 GPS 获取全部由 [Inputs]/[Outputs] 注入，
 * 因此这个类不依赖 ViewModel，可以单独替换与复用。
 *
 * 从 `MainViewModel` 中按原逻辑搬移，行为未做任何修改。
 */
class WapsMonitor(
    private val inputs: Inputs,
    private val outputs: Outputs
) {

    /** 采样循环需要的外部输入。 */
    data class Inputs(
        /** 天气预警开关。 */
        val alertEnabled: () -> Boolean,
        /** 设备是否处于"已连接且服务发现完成"。 */
        val deviceReady: () -> Boolean,
        /** 当前气压（hPa）。 */
        val pressureHpa: () -> Float?,
        /** 当前温度（℃），用于海平面气压换算。 */
        val temperatureC: () -> Float?,
        /** 当前位置；无权限或未定位时返回 null。 */
        val location: suspend () -> Location?
    )

    /** 采样循环的产出。 */
    data class Outputs(
        val onResult: (WapsResult) -> Unit,
        val onGpsAltitudeMeters: (Double) -> Unit,
        val onStormAlert: (String) -> Unit
    )

    private val buffer = WapsBuffer()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 断开连接时清理锚点与缓冲。 */
    fun clear() {
        buffer.clear()
    }

    private suspend fun loop() {
        val intervalMs = 1000L
        var lastGpsTime = 0L
        var lastMinuteTime = 0L
        val minuteMs = 60 * 1000L
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            if (!inputs.alertEnabled()) break
            if (!inputs.deviceReady()) break
            val press = inputs.pressureHpa()
            if (press == null || press <= 0f) continue  // 无气压或气压为 0（故障）不参与 WAPS 计算
            val temp = inputs.temperatureC()
            val now = System.currentTimeMillis()
            // 每 1 秒：气压入缓冲
            buffer.addPressure(PressureSample(now, press, temp))
            // 每 10 秒：GPS 入缓冲（GPS 丢失时保持上一状态，不清锚点）
            if (now - lastGpsTime >= GPS_SAMPLE_INTERVAL_MS) {
                lastGpsTime = now
                val loc = inputs.location()
                loc?.let {
                    val altM = it.altitude.toDouble()
                    outputs.onGpsAltitudeMeters(altM)
                    buffer.addGps(
                        GpsSample(
                            it.latitude,
                            it.longitude,
                            altM,
                            now
                        )
                    )
                }
            }
            // 每 1 分钟：中值滤波、静止/移动（10m 进入 20m 退出）、锚点预热、EHR 计算
            if (now - lastMinuteTime >= minuteMs) {
                lastMinuteTime = now
                val samples = buffer.getPressureSamplesForMedian()
                medianFilterPressure(samples)?.let { smoothed ->
                    buffer.setSmoothedPressure(smoothed)
                }
                val gpsList = buffer.getGpsSamplesLast5Min()
                val smoothedP = buffer.lastSmoothedPressure?.pressureHpa
                val lastGps = gpsList.lastOrNull()
                // 退出静止：相对参考点位移 ≥ 20m 则清锚点
                val refLat = buffer.stationaryRefLat
                val refLon = buffer.stationaryRefLon
                if (refLat != null && refLon != null && lastGps != null &&
                    shouldExitStationary(refLat, refLon, lastGps)
                ) {
                    buffer.clearStationaryAnchor()
                    buffer.lockedAltitudeM = null
                }
                val inStationaryZone = isInStationaryZoneByDisplacement(gpsList)
                val state: WapsState
                val ehr: Float?
                val durationMin: Float?
                val alert: AlertLevel
                val message: String
                if (inStationaryZone && gpsList.isNotEmpty()) {
                    state = WapsState.Stationary
                    val first = gpsList.first()
                    if (buffer.stationaryRefLat == null) {
                        buffer.setStationaryReference(first.latitude, first.longitude, first.timestampMs)
                    }
                    smoothedP?.let { buffer.addWarmupPressure(it) }
                    buffer.tryLockAnchor(now)
                    if (lastGps != null) buffer.lockedAltitudeM = lastGps.altitudeMeters
                    if (buffer.isAnchorLocked()) {
                        val anchorP = buffer.stationaryAnchorP!!
                        val anchorTime = buffer.stationaryAnchorTimeMs!!
                        durationMin = (now - anchorTime) / 60_000f
                        ehr = computeEhr(anchorP, smoothedP ?: anchorP, durationMin)
                        val pair = decideAlertByEhr(durationMin, ehr)
                        alert = pair.first
                        message = pair.second
                        if (alert == AlertLevel.FlashStorm) {
                            outputs.onStormAlert(message)
                        }
                    } else {
                        ehr = null
                        durationMin = (buffer.stationaryRefTimeMs?.let { (now - it) / 60_000f })
                        alert = AlertLevel.None
                        message = "监测中（静止 1–2 分钟后显示趋势）"
                    }
                } else {
                    state = WapsState.Active
                    if (!inStationaryZone) buffer.clearStationaryAnchor()
                    buffer.lockedAltitudeM = null
                    ehr = null
                    durationMin = null
                    alert = AlertLevel.None
                    message = "移动中，使用高度计模式"
                }
                val alt = if (state == WapsState.Stationary) buffer.lockedAltitudeM
                else lastGps?.altitudeMeters
                val mslp = if (smoothedP != null && alt != null) {
                    pressureToSeaLevel(smoothedP, alt, temp)
                } else null
                outputs.onResult(
                    WapsResult(
                        state = state,
                        mslpHpa = mslp,
                        deltaP3hHpa = null,
                        alert = alert,
                        message = message,
                        isDataCollecting = state == WapsState.Stationary && !buffer.isAnchorLocked(),
                        ehrHpaPerHour = ehr,
                        stationaryDurationMin = durationMin
                    )
                )
            }
        }
    }
}
