package com.example.pandatemperature.utils

import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.data.weather.ChartWeatherEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherChartEventsTest {

    private fun createRecord(ts: Long, t: Float, rh: Float, p: Float?): TemperatureRecord {
        return TemperatureRecord(
            id = 0,
            timestamp = ts,
            temperature = t,
            humidity = rh,
            pressure = p,
            createdAt = System.currentTimeMillis(),
            deviceId = "test"
        )
    }

    @Test
    fun testIndoorWarmDry_WithStrongChange_AndSlightPressureChange() {
        // User Scenario:
        // Temp: 10 -> 21 (+11)
        // Hum: 80 -> 50 (-30)
        // Pressure: 1016 -> 1017.2 (+1.2)
        // This should be INDOOR_WARM_DRY because the T/H change is massive, 
        // and P change (1.2) is below "Clearing" threshold (2.0) but above strict "Stable" (0.6).
        
        val startTs = 1000000L
        val endTs = startTs + 3600L

        val records = listOf(
            createRecord(startTs, 10f, 80f, 1016.0f),
            createRecord(endTs, 21f, 50f, 1017.2f) // deltaP = 1.2
        )

        val events = WeatherChartEvents.compute(records)
        // Currently this fails (returns empty) because 1.2 > 0.6
        // We want it to return INDOOR_WARM_DRY
        
        assertEquals(1, events.size)
        assertEquals(ChartWeatherEventType.INDOOR_WARM_DRY, events[0].type)
    }

    @Test
    fun testIndoorWarmDry_NoPressure() {
        // Test "Indoor Warm/Dry" logic when pressure is MISSING (null)
        // Rule: 1h deltaT >= 1.5, deltaRH <= -8
        // Start: T=20, RH=50
        // End: T=22, RH=40 (deltaT=2.0, deltaRH=-10) -> Should trigger
        val startTs = 1000000L
        val endTs = startTs + 3600L

        val records = listOf(
            createRecord(startTs, 20f, 50f, null),
            createRecord(endTs, 22f, 40f, null)
        )

        val events = WeatherChartEvents.compute(records)
        
        println("Events count: ${events.size}")
        events.forEach { println("Event: ${it.type} at ${it.timestampSec}") }

        assertEquals(1, events.size)
        assertEquals(ChartWeatherEventType.INDOOR_WARM_DRY, events[0].type)
    }

    @Test
    fun testIndoorWarmDry_WithPressure_Stable() {
        // Test "Indoor Warm/Dry" logic when pressure is PRESENT and STABLE
        // Rule: deltaT >= 1.5, deltaRH <= -8, |deltaP| <= 0.6
        val startTs = 1000000L
        val endTs = startTs + 3600L

        val records = listOf(
            createRecord(startTs, 20f, 50f, 1013.0f),
            createRecord(endTs, 22f, 40f, 1013.2f) // deltaP = 0.2
        )

        val events = WeatherChartEvents.compute(records)
        assertEquals(1, events.size)
        assertEquals(ChartWeatherEventType.INDOOR_WARM_DRY, events[0].type)
    }

    @Test
    fun testIndoorWarmDry_WithPressure_Unstable() {
        // Test "Indoor Warm/Dry" logic when pressure is PRESENT but UNSTABLE
        // Rule: deltaT >= 1.5, deltaRH <= -8, |deltaP| > 0.6 -> Should NOT trigger
        val startTs = 1000000L
        val endTs = startTs + 3600L

        val records = listOf(
            createRecord(startTs, 20f, 50f, 1013.0f),
            createRecord(endTs, 22f, 40f, 1015.0f) // deltaP = 2.0
        )

        val events = WeatherChartEvents.compute(records)
        // Should trigger PRESSURE_RISE_CLEARING (deltaP >= 2) but NOT INDOOR_WARM_DRY
        
        val indoorEvents = events.filter { it.type == ChartWeatherEventType.INDOOR_WARM_DRY }
        assertEquals(0, indoorEvents.size)
        
        val pressureEvents = events.filter { it.type == ChartWeatherEventType.PRESSURE_RISE_CLEARING }
        assertEquals(1, pressureEvents.size)
    }

    @Test
    fun testAllEventTypes() {
        val now = 1000000L
        val records = mutableListOf<TemperatureRecord>()
        
        // 1. Pressure Drop (Rain)
        // t=0, P=1000
        // t=3600, P=997 (delta -3)
        records.add(createRecord(now, 20f, 50f, 1000f))
        records.add(createRecord(now + 3600, 20f, 50f, 997f))
        
        // 2. Pressure Rise (Clearing)
        // t=7200, P=1000 (delta +3 from 997? No, 1h window)
        // 3600->997. 7200->1000. delta = 3. Correct.
        records.add(createRecord(now + 7200, 20f, 50f, 1000f))
        
        // 3. Cold Front (6h)
        // t=10000, T=20, RH=50
        // t=10000+6h (31600), T=10 (delta -10), RH=70 (delta +20)
        records.add(createRecord(now + 10000, 20f, 50f, 1000f))
        records.add(createRecord(now + 31600, 10f, 70f, 1000f))
        
        // 4. Fog/Dew
        // RH >= 90, Spread <= 2
        // T=10, RH=95. DewPoint ~9.2. Spread ~0.8.
        records.add(createRecord(now + 40000, 10f, 95f, 1000f))
        
        val events = WeatherChartEvents.compute(records)
        println("Events count: ${events.size}")
        events.forEach { println("Event: ${it.type} at ${it.timestampSec}") }
        
        val types = events.map { it.type }.toSet()
        assert(types.contains(ChartWeatherEventType.PRESSURE_DROP_RAIN))
        assert(types.contains(ChartWeatherEventType.PRESSURE_RISE_CLEARING))
        assert(types.contains(ChartWeatherEventType.COLD_FRONT))
        assert(types.contains(ChartWeatherEventType.FOG_DEW))
    }

    @Test
    fun testIndoorWarmDry_StalePressure() {
        // Test "Indoor Warm/Dry" logic when pressure is present but STALE (too old)
        // Rule: deltaT >= 1.5, deltaRH <= -8.
        
        val now = 1000000L
        val records = mutableListOf<TemperatureRecord>()
        
        // P1 at T-2h = 1000.
        // P2 at T+2h = 1020.
        // Event at T (Indoor).
        // Start (T-1h). Nearest P1 (1h dist).
        // End (T). Nearest P1 (2h) or P2 (2h).
        // If old logic picked P1 and P2 -> deltaP = 20 -> Suppress.
        // New logic (limit 30m) -> Ignores both -> Trigger.
        
        records.add(createRecord(now - 2*3600, 20f, 50f, 1000f)) // P1
        records.add(createRecord(now + 2*3600, 20f, 50f, 1020f)) // P2
        
        // Event
        records.add(createRecord(now - 3600, 20f, 50f, null)) // Start
        records.add(createRecord(now, 22f, 40f, null)) // End
        
        val events = WeatherChartEvents.compute(records)
        assertEquals(1, events.size)
        assertEquals(ChartWeatherEventType.INDOOR_WARM_DRY, events[0].type)
    }
}
