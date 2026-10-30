package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.NoiseRecord
import com.example.data.NoiseRepository
import com.example.ui.DeferredAnalysisGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class PendingAnalysisQueueTest {
    @Test fun laterPendingRecordingsRemainReachableBeyondTheFirstPage()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val repo=NoiseRepository(db.noiseDao())
            repeat(20) { n ->
                repo.insertDeviceEvent(NoiseRecord(timestamp=n.toLong(),maxDb=0.0,avgDb=0.0,maxVibration=0.0,
                    isExceeded=false,isNoiseExceeded=false,isVibeExceeded=false,recordingPath="/fixture-$n.wav",
                    durationMs=4000,deviceEventId="fixture-$n",deviceClassificationPending=true))
            }
            val first=repo.pendingDeviceClassificationsAfter(0)
            val second=repo.pendingDeviceClassificationsAfter(first.last().id)
            assertEquals(16,first.size)
            assertEquals(4,second.size)
            assertTrue(second.all { it.id>first.last().id })
            assertTrue(repo.pendingDeviceClassificationsAfter(second.last().id).isEmpty())
        } finally { db.close() }
    }

    @Test fun retryRequestedDuringAnAttemptIsNotLostWhenThatAttemptFails() {
        val gate=DeferredAnalysisGate()
        val oldAttempt=gate.generation
        gate.retryAll()
        gate.deferAfterFailure(42,oldAttempt)
        assertFalse(gate.isDeferred(42))
        gate.deferAfterFailure(42,gate.generation)
        assertTrue(gate.isDeferred(42))
        gate.retryAll()
        assertFalse(gate.isDeferred(42))
    }
}
