package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.NoiseRecord
import com.example.data.NoiseRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SoundAnalysisPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun record() = NoiseRecord(timestamp = 100, maxDb = 60.0, avgDb = 45.0,
        maxVibration = 0.0, isExceeded = true, isNoiseExceeded = true,
        isVibeExceeded = false, recordingPath = "/recording.wav", durationMs = 5_000,
        note = "기존 메모", classificationLabel = "footstep", userId = "owner", syncId = "sync")

    @Test fun analysisUpdatesOnlyOwnedExistingRecordAndPreservesRecording() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.noiseDao()
            val id = dao.insertRecord(record()).toInt()
            val repo = NoiseRepository(dao)
            repo.saveSoundAnalysis(id, "different-owner", "wrong")
            assertNull(dao.findById(id)!!.soundAnalysisJson)
            repo.saveSoundAnalysis(id, "owner", "analysis")
            val saved = dao.findById(id)!!
            assertEquals("analysis", saved.soundAnalysisJson)
            assertEquals("기존 메모", saved.note)
            assertEquals("footstep", saved.classificationLabel)
            assertEquals("/recording.wav", saved.recordingPath)
            assertEquals("PENDING", saved.syncState)
            dao.mergeRemoteRecord(saved.copy(id = 0, soundAnalysisJson = null, syncState = "SYNCED"))
            assertEquals("analysis", dao.findById(id)!!.soundAnalysisJson)
            dao.markSynced(id, "remote.wav", "outdated-analysis")
            assertEquals("PENDING", dao.findById(id)!!.syncState)
            dao.markSynced(id, "remote.wav", "analysis")
            assertEquals("SYNCED", dao.findById(id)!!.syncState)
            dao.deleteRecordById(id)
            repo.saveSoundAnalysis(id, "owner", "late-result")
            assertNull(dao.findById(id))
        } finally { db.close() }
    }

    @Test fun migrationKeepsExistingVersion4Record() = runBlocking {
        val name = "migration-${UUID.randomUUID()}"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("""CREATE TABLE noise_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL, maxDb REAL NOT NULL, avgDb REAL NOT NULL,
                maxVibration REAL NOT NULL, isExceeded INTEGER NOT NULL,
                isNoiseExceeded INTEGER NOT NULL, isVibeExceeded INTEGER NOT NULL,
                recordingPath TEXT, durationMs INTEGER NOT NULL, note TEXT NOT NULL,
                recordingSource TEXT NOT NULL, classificationLabel TEXT NOT NULL,
                classificationConfidence REAL NOT NULL, isInterfloorCandidate INTEGER NOT NULL,
                userId TEXT, syncId TEXT, remoteAudioPath TEXT, syncState TEXT NOT NULL)""")
            old.execSQL("""INSERT INTO noise_records VALUES
                (1,100,60,45,0,1,1,0,'/old.m4a',5000,'메모','PHONE','footstep',0.8,1,
                'owner','sync',NULL,'LOCAL')""")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_4_5).build()
        try {
            val saved = db.noiseDao().findById(1)!!
            assertEquals("메모", saved.note)
            assertEquals("/old.m4a", saved.recordingPath)
            assertEquals("footstep", saved.classificationLabel)
            assertNull(saved.soundAnalysisJson)
            db.noiseDao().saveSoundAnalysis(1, "owner", "result")
            assertEquals("result", db.noiseDao().findById(1)!!.soundAnalysisJson)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
