package com.example.pandatemperature.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.pandatemperature.data.database.dao.DeviceDao
import com.example.pandatemperature.data.database.dao.TemperatureRecordDao
import com.example.pandatemperature.data.model.Device
import com.example.pandatemperature.data.model.TemperatureRecord

/**
 * Room 数据库实例
 */
@Database(
    entities = [TemperatureRecord::class, Device::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun temperatureRecordDao(): TemperatureRecordDao
    abstract fun deviceDao(): DeviceDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * 数据库迁移：版本 5 → 6
         * 为 devices 表添加 firmwareVersion 字段
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE devices ADD COLUMN firmwareVersion INTEGER DEFAULT NULL")
            }
        }
        
        /**
         * 数据库迁移：版本 6 → 7
         * 为 temperature_records 表添加 GPS 字段（latitude, longitude）
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE temperature_records ADD COLUMN latitude REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE temperature_records ADD COLUMN longitude REAL DEFAULT NULL")
            }
        }

        /**
         * 数据库迁移：版本 7 → 8
         * 为 devices 表添加 nickname 字段（设备昵称）
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE devices ADD COLUMN nickname TEXT DEFAULT NULL")
            }
        }

        /**
         * 数据库迁移：版本 8 → 9
         *
         * 当前 v8/v9 的实际表结构没有差异（历史文档曾计划增加海拔字段，
         * 但该字段没有进入当前实体），因此保留数据并显式注册 no-op 迁移。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // no-op: v8 and v9 schema are identical in the shipped app.
            }
        }

        /**
         * 数据库迁移：版本 9 → 10
         * 为历史记录和设备表增加可空的最新电池电压字段。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Nullable columns need no default; omitting DEFAULT keeps Room's
                // expected null default aligned with SQLite's schema metadata.
                database.execSQL("ALTER TABLE temperature_records ADD COLUMN batteryVoltage REAL")
                database.execSQL("ALTER TABLE devices ADD COLUMN latestBatteryVoltage REAL")
            }
        }
        
        /** Explicit source marker; legacy location-free rows are intentionally unknown. */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE temperature_records ADD COLUMN isPhoneSample INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE temperature_records SET isPhoneSample = 1 WHERE latitude IS NOT NULL OR longitude IS NOT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "panda_temperature_database"
                )
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11
                    )
                    // 继续兼容没有显式迁移路径的早期开发版本；v5 及以上会优先
                    // 使用上面的完整迁移链，因此正常升级到 v11 不会清空历史数据。
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
