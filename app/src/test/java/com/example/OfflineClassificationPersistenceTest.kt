package com.example

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineClassificationPersistenceTest {
    @Test fun resultAndModelMetadataAreCommittedWithPendingFlag() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = NoiseRepository(db.noiseDao())
            val id = repository.insert(NoiseRecord(timestamp=1, maxDb=60.0, avgDb=55.0,
                maxVibration=0.0, isExceeded=false, isNoiseExceeded=false, isVibeExceeded=false,
                recordingPath="/private/saved.wav", durationMs=4000, deviceClassificationPending=true)).toInt()
            val json = """{"model":"frozen-test-model","inferenceMs":42}"""
            repository.finishDeviceClassification(id,"footstep",.8,true,json)
            val result = requireNotNull(repository.findById(id))
            assertFalse(result.deviceClassificationPending)
            assertEquals("footstep",result.classificationLabel)
            assertEquals(json,result.soundAnalysisJson)
        } finally { db.close() }
    }

    @Test fun editedNoteAndCompletedAnalysisDoNotOverwriteEachOther() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AppDatabase::class.java).build()
        try {
            val repository=NoiseRepository(db.noiseDao())
            val id=repository.insert(NoiseRecord(timestamp=1,maxDb=60.0,avgDb=55.0,maxVibration=0.0,
                isExceeded=false,isNoiseExceeded=false,isVibeExceeded=false,recordingPath="/fixture.wav",
                durationMs=4000,note="AI 분류 대기",deviceClassificationPending=true)).toInt()
            repository.updateRecordNote(id,"AI 분류 대기 후 확인")
            val json="""{"model":"fixture"}"""
            repository.finishDeviceClassification(id,"footstep",.8,true,json)
            assertEquals("AI 분류 대기 후 확인",repository.findById(id)!!.note)
            repository.updateRecordNote(id,"분석 후 작성한 메모")
            val result=repository.findById(id)!!
            assertEquals("분석 후 작성한 메모",result.note)
            assertEquals(json,result.soundAnalysisJson)
            assertFalse(result.deviceClassificationPending)
        } finally {db.close()}
    }
}
