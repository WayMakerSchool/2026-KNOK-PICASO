package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoiseRecord::class], version = 5, exportSchema = false)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
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
