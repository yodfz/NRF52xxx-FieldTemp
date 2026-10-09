package com.example.pandatemperature.data.database

import androidx.room.Room
import com.example.pandatemperature.data.model.TemperatureRecord
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real SQLite v10 file opened through Room v11; never opens the user's database. */
@RunWith(AndroidJUnit4::class)
class PhoneSampleMigrationTest {
    @Test
    fun migrationPreservesValuesAndMarksOnlyKnownLegacyPhoneRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "source-migration-${UUID.randomUUID()}"
        var room: AppDatabase? = null
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE temperature_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, temperature REAL NOT NULL, humidity REAL NOT NULL, pressure REAL, batteryVoltage REAL, createdAt INTEGER NOT NULL, deviceId TEXT NOT NULL, latitude REAL, longitude REAL)")
                        db.execSQL("CREATE TABLE devices (macAddress TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, createTime INTEGER NOT NULL, firmwareVersion INTEGER, nickname TEXT, latestBatteryVoltage REAL)")
                        db.execSQL("CREATE TABLE isolated_quarantine (id INTEGER PRIMARY KEY, note TEXT)")
                        db.execSQL("INSERT INTO isolated_quarantine VALUES (1,'synthetic-preserved')")
                        db.execSQL("INSERT INTO devices VALUES ('synthetic-device','fixture','THERMOMETER',1234,9,'fixture-name',2.9)")
                        db.execSQL("INSERT INTO temperature_records VALUES (1,7000,27.48,54.89,1010.81,2.875,1234,'synthetic-device',NULL,NULL)")
                        db.execSQL("INSERT INTO temperature_records VALUES (2,7001,26.5,51.0,1000.0,NULL,1235,'synthetic-device',1.0,NULL)")
                        db.execSQL("INSERT INTO temperature_records VALUES (3,7002,25.0,50.0,NULL,2.8,1236,'synthetic-device',NULL,2.0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Only production migration may upgrade this fixture")
                    }
                }).build()
        )
        try {
            helper.writableDatabase // Persist a genuine user_version=10 database.
            helper.close()
            room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_10_11).build()
            val dao = room.temperatureRecordDao()
            val rows = dao.getAllRecordsSync("synthetic-device").associateBy { it.id }
            assertEquals(3, rows.size)
            assertEquals(TemperatureRecord(id=1, timestamp=7000, temperature=27.48f, humidity=54.89f,
                pressure=1010.81f, batteryVoltage=2.875f, createdAt=1234, deviceId="synthetic-device"), rows.getValue(1))
            assertEquals(TemperatureRecord(id=2, timestamp=7001, temperature=26.5f, humidity=51f,
                pressure=1000f, createdAt=1235, deviceId="synthetic-device", latitude=1.0,
                isPhoneSample=true), rows.getValue(2))
            assertEquals(TemperatureRecord(id=3, timestamp=7002, temperature=25f, humidity=50f,
                batteryVoltage=2.8f, createdAt=1236, deviceId="synthetic-device", longitude=2.0,
                isPhoneSample=true), rows.getValue(3))
            val module = rows.getValue(1)
            assertFalse("Legacy no-GPS origin remains unknown, not guessed", module.isPhoneSample)
            assertEquals(7000L, module.timestamp)
            assertEquals(27.48f, module.temperature, 0f)
            assertEquals(54.89f, module.humidity, 0f)
            assertEquals(1010.81f, module.pressure!!, 0f)
            assertEquals(2.875f, module.batteryVoltage!!, 0f)
            assertEquals(1234L, module.createdAt)
            assertTrue(rows.getValue(2).isPhoneSample)
            assertTrue(rows.getValue(3).isPhoneSample)
            assertEquals(1.0, rows.getValue(2).latitude!!, 0.0)
            assertEquals(2.0, rows.getValue(3).longitude!!, 0.0)
            assertEquals(1, dao.getHistoryRecordCount("synthetic-device"))
            assertEquals(7000L, dao.getLatestRecordWithoutGps("synthetic-device")!!.timestamp)
            room.openHelper.writableDatabase.query("SELECT name,nickname,latestBatteryVoltage FROM devices").use {
                assertTrue(it.moveToFirst()); assertEquals("fixture",it.getString(0))
                assertEquals("fixture-name",it.getString(1)); assertEquals(2.9,it.getDouble(2),0.0)
            }
            room.openHelper.writableDatabase.query("SELECT note FROM isolated_quarantine").use {
                assertTrue(it.moveToFirst()); assertEquals("synthetic-preserved",it.getString(0))
            }
            room.openHelper.writableDatabase.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst()); assertEquals(11,it.getInt(0))
            }
        } finally {
            room?.close(); helper.close(); context.deleteDatabase(name)
        }
    }
}
