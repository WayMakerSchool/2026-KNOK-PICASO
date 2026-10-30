package com.example.data

import android.content.Context
import com.example.BuildConfig
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoiseRecord::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noiseDao(): NoiseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quiet_neighbors_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .apply {
                    if(BuildConfig.KNOK_V2)addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            // The recorder retires its RAM only after our transaction returns.
                            // NORMAL WAL can lose a committed transaction on power failure.
                            db.execSQL("PRAGMA synchronous=FULL")
                        }
                    })
                }
                .build()
                INSTANCE = instance
                instance
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceEventId TEXT")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceMetadataJson TEXT")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceRawEdges INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceDigitalEvents INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceStartSample INTEGER")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceTriggerSample INTEGER")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceSampleCount INTEGER")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN deviceClassificationPending INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE UNIQUE INDEX index_noise_records_deviceEventId ON noise_records(deviceEventId)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE noise_records ADD COLUMN soundAnalysisJson TEXT")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE noise_records ADD COLUMN recordingSource TEXT NOT NULL DEFAULT 'PHONE'"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE noise_records ADD COLUMN classificationLabel TEXT NOT NULL DEFAULT 'unclassified'"
                )
                database.execSQL(
                    "ALTER TABLE noise_records ADD COLUMN classificationConfidence REAL NOT NULL DEFAULT 0.0"
                )
                database.execSQL(
                    "ALTER TABLE noise_records ADD COLUMN isInterfloorCandidate INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE noise_records ADD COLUMN userId TEXT")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN syncId TEXT")
                database.execSQL("ALTER TABLE noise_records ADD COLUMN remoteAudioPath TEXT")
                database.execSQL(
                    "ALTER TABLE noise_records ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL'"
                )
            }
        }
    }
}
