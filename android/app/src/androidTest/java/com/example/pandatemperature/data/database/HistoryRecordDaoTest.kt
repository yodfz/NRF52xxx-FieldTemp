package com.example.pandatemperature.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pandatemperature.data.database.dao.TemperatureRecordDao
import com.example.pandatemperature.data.model.TemperatureRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises generated Room SQL against an isolated in-memory database.
 * Never uses AppDatabase.getDatabase or the user's persistent database.
 */
@RunWith(AndroidJUnit4::class)
class HistoryRecordDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: TemperatureRecordDao

    @Before
    fun createIsolatedDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        dao = database.temperatureRecordDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun record(
        timestamp: Long,
        device: String = "test-device-a",
        temperature: Float = 25f
    ) = TemperatureRecord(
        timestamp = timestamp,
        temperature = temperature,
        humidity = 50f,
        pressure = 1000f,
        deviceId = device,
        createdAt = 1_791_475_000_000L
    )

    @Test
    fun repeatedFullDownloadOf33000RecordsPreservesCountValuesAndIds() = runBlocking {
        val records = (0 until 33_000).map { index ->
            record(1_791_400_000L + index, temperature = (index % 100) / 10f)
        }
        records.chunked(100).forEach { dao.upsertHistoryRecords(it) }
        val initial = dao.getAllRecordsSync("test-device-a").associateBy { it.timestamp }
        assertEquals(33_000, initial.size)

        // A retry can restart at the beginning and deliver packets in a different order.
        records.asReversed().chunked(100).forEach { dao.upsertHistoryRecords(it) }
        val repeated = dao.getAllRecordsSync("test-device-a").associateBy { it.timestamp }
        assertEquals(33_000, dao.getRecordCount("test-device-a"))
        assertEquals(initial, repeated)
    }

    @Test
    fun duplicateAndOutOfOrderBatchesKeepOneRowPerTimestamp() = runBlocking {
        val first = record(1000, temperature = 20f)
        val second = record(2000, temperature = 21f)
        val third = record(3000, temperature = 22f)
        val latestFirst = first.copy(temperature = 24f)

        dao.upsertHistoryRecords(listOf(third, first, second, latestFirst, third))
        assertEquals(3, dao.getRecordCount("test-device-a"))
        assertEquals(24f, dao.getRecordByTimestamp("test-device-a", 1000)!!.temperature, 0f)

        dao.upsertHistoryRecords(listOf(second, third, latestFirst, second))
        val persisted = dao.getAllRecordsSync("test-device-a")
        assertEquals(listOf(3000L, 2000L, 1000L), persisted.map { it.timestamp })
        assertEquals(listOf(22f, 21f, 24f), persisted.map { it.temperature })
    }

    @Test
    fun largeSingleBatchCrossesSQLiteBindLimitWithoutDuplicates() = runBlocking {
        val records = (0 until 1_201).map { record(10_000L + it) }
        dao.upsertHistoryRecords(records + records.take(30))
        dao.upsertHistoryRecords(records.asReversed())
        assertEquals(1_201, dao.getRecordCount("test-device-a"))
    }

    @Test
    fun phoneGpsRowsRemainIndependentAtSameDeviceAndTimestamp() = runBlocking {
        val module = record(4000)
        // Synthetic coordinates, unrelated to the user's location.
        val phone = module.copy(latitude = 1.0, longitude = 2.0, batteryVoltage = 3.0f)
        val phoneId = dao.insert(phone)
        dao.upsertHistoryRecords(listOf(module))
        val moduleBefore = dao.getHistoryRecordsByTimestamps("test-device-a", listOf(4000)).single()

        dao.upsertHistoryRecords(listOf(module.copy(temperature = 29f)))
        val rows = dao.getAllRecordsSync("test-device-a")
        assertEquals(2, rows.size)
        assertEquals(1, dao.getHistoryRecordCount("test-device-a"))
        assertEquals(phone.copy(id = phoneId), rows.single { it.latitude != null })
        assertEquals(moduleBefore.id, rows.single { it.latitude == null }.id)
        assertEquals(29f, rows.single { it.latitude == null }.temperature, 0f)
        assertNotEquals(phoneId, moduleBefore.id)
    }

    @Test
    fun differentDevicesAtSameTimestampRemainIndependent() = runBlocking {
        val first = record(5000, "test-device-a", 20f)
        val second = record(5000, "test-device-b", 30f)
        dao.upsertHistoryRecords(listOf(first, second))
        val firstBefore = dao.getAllRecordsSync("test-device-a").single()
        val secondBefore = dao.getAllRecordsSync("test-device-b").single()

        dao.upsertHistoryRecords(listOf(second.copy(temperature = 31f), first.copy(temperature = 21f)))
        assertEquals(first.copy(id = firstBefore.id, temperature = 21f), dao.getAllRecordsSync("test-device-a").single())
        assertEquals(second.copy(id = secondBefore.id, temperature = 31f), dao.getAllRecordsSync("test-device-b").single())
        assertNotEquals(firstBefore.id, secondBefore.id)
    }

    @Test
    fun existingModuleRecordIsUpdatedWithoutChangingItsId() = runBlocking {
        val original = record(6000)
        dao.upsertHistoryRecords(listOf(original))
        val originalId = dao.getAllRecordsSync("test-device-a").single().id
        val updated = original.copy(
            temperature = 27f,
            humidity = 60f,
            pressure = 1010f,
            batteryVoltage = 2.99f,
            createdAt = original.createdAt + 1000
        )

        dao.upsertHistoryRecords(listOf(updated))
        assertEquals(1, dao.getRecordCount("test-device-a"))
        assertEquals(updated.copy(id = originalId), dao.getAllRecordsSync("test-device-a").single())
    }
    @Test
    fun phoneSamplesWithoutGpsNeverBecomeModuleHistory() = runBlocking {
        val module = record(7000)
        val phone = module.copy(temperature = 31f, isPhoneSample = true)
        val phoneId = dao.insert(phone)
        dao.insert(record(9000).copy(isPhoneSample = true))
        dao.upsertHistoryRecords(listOf(module))
        dao.upsertHistoryRecords(listOf(module.copy(temperature = 28f)))
        val rows = dao.getAllRecordsSync("test-device-a")
        assertEquals(phone.copy(id = phoneId), rows.single { it.id == phoneId })
        assertEquals(3, rows.size)
        assertEquals(1, dao.getHistoryRecordCount("test-device-a"))
        assertEquals(7000L, dao.getLatestRecordWithoutGps("test-device-a")!!.timestamp)
        assertEquals(28f, dao.getHistoryRecordsByTimestamps("test-device-a", listOf(7000)).single().temperature, 0f)
        var rejected = false
        try { dao.upsertHistoryRecords(listOf(phone)) } catch (_: IllegalArgumentException) { rejected = true }
        org.junit.Assert.assertTrue("Phone source must be rejected as history input", rejected)
    }
    @Test
    fun authoritativeWireSetRemovesCachedExtrasButPreservesAllOtherSources() = runBlocking {
        val wire = (10_000L until 13_000L).toSet()
        dao.upsertHistoryRecords(wire.map { record(it) })
        // These 164 cache rows are newer by timestamp: sorting take3000 would keep the wrong set.
        dao.upsertHistoryRecords((90_000L until 90_164L).map { record(it) })
        val phone = record(10_001).copy(isPhoneSample = true)
        val gps = record(10_002).copy(latitude = 1.0) // Legacy GPS stays protected even without marker.
        val phoneId=dao.insert(phone); val gpsId=dao.insert(gps)
        dao.upsertHistoryRecords(listOf(record(99_000,"test-device-b")))
        database.openHelper.writableDatabase.execSQL("CREATE TABLE isolated_metadata (id INTEGER PRIMARY KEY, value TEXT)")
        database.openHelper.writableDatabase.execSQL("INSERT INTO isolated_metadata VALUES (1,'quarantine-preserved')")
        assertEquals(164,dao.trimHistoryToWireSet("test-device-a",wire))
        assertEquals(3000,dao.getHistoryRecordCount("test-device-a"))
        val after=dao.getAllRecordsSync("test-device-a")
        assertEquals(wire,after.filter { !it.isPhoneSample && it.latitude==null && it.longitude==null }.map { it.timestamp }.toSet())
        assertEquals(phone.copy(id=phoneId),after.single { it.id==phoneId })
        assertEquals(gps.copy(id=gpsId),after.single { it.id==gpsId })
        assertEquals(1,dao.getHistoryRecordCount("test-device-b"))
        database.openHelper.writableDatabase.query("SELECT value FROM isolated_metadata").use {
            org.junit.Assert.assertTrue(it.moveToFirst());assertEquals("quarantine-preserved",it.getString(0))
        }
    }

    @Test
    fun failureInSecondDeleteChunkRollsBackTheEntireReconciliation() = runBlocking {
        val wire=(10_000L until 13_000L).toSet()
        dao.upsertHistoryRecords(wire.map { record(it) })
        dao.upsertHistoryRecords((90_000L until 91_201L).map { record(it) })
        val before=dao.getAllRecordsSync("test-device-a")
        val failId=before.filter { it.timestamp !in wire }[700].id
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER abort_middle BEFORE DELETE ON temperature_records WHEN OLD.id = $failId BEGIN SELECT RAISE(ABORT, 'injected second-chunk failure'); END")
        var failed=false
        try { dao.trimHistoryToWireSet("test-device-a",wire) } catch (_: Exception) { failed=true }
        org.junit.Assert.assertTrue("Trigger must abort reconciliation",failed)
        assertEquals(before,dao.getAllRecordsSync("test-device-a"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_middle")
        var identityChecks=0
        failed=false
        try {
            dao.trimHistoryToWireSet("test-device-a",wire) { ++identityChecks < 3 }
        } catch (_: IllegalStateException) { failed=true }
        org.junit.Assert.assertTrue("Lost session identity after first chunk must roll back",failed)
        assertEquals(before,dao.getAllRecordsSync("test-device-a"))
        assertEquals(1201,dao.trimHistoryToWireSet("test-device-a",wire))
        assertEquals(3000,dao.getHistoryRecordCount("test-device-a"))
    }
}

