package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class EspEventImportTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val id="2884856f4a18-0123456789abcdef-00000001"
    private fun wav(): ByteArray = ByteBuffer.allocate(160044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(160036);put("WAVEfmt ".toByteArray());putInt(16)
        putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16)
        put("data".toByteArray());putInt(160000)
        repeat(80000){putShort((it%100).toShort())}
    }.array()
    private inner class Fixture : AutoCloseable {
        val dir=File(context.cacheDir,"esp-${UUID.randomUUID()}").apply{mkdirs()}
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val repo=NoiseRepository(db.noiseDao())
        val server=MockWebServer()
        var bytes=wav();var checksumOverride:String?=null;var badId=false;var ackFails=false
        var ackRequests=0
        var statusRequests=0
        var simulated=false
        var startAutoFails=false
        var stopAutoFails=false
        var waitsForAckToFinalize=false
        var autoResetsAfterSecondStatus=false
        var statusFailuresRemaining=0
        var detachedFinalizingOnFirstStatus=false
        val autoRequests=mutableListOf<Boolean>()
        var status="""{"levelDb":71,"tiltRawEdges":12}"""
        val base:String get()=server.url("/").toString().trimEnd('/')
        // Robolectric ShadowLinux cannot open directories. Android instrumentation
        // checks the real syscall; these cases test ordering/protocol failures.
        fun importer()=EspEventImporter(EspRecorderClient(syncDirectory={}),repo,dir)
        init {
            server.dispatcher=object:Dispatcher(){
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.path.orEmpty()
                    if(path.startsWith("/api/status")){
                        statusRequests++
                        if(statusFailuresRemaining>0){statusFailuresRemaining--;return MockResponse().setResponseCode(503).setBody("{}")}
                        if(waitsForAckToFinalize && ackRequests==0 && statusRequests>=3)
                            return MockResponse().setResponseCode(500).setBody("{\"message\":\"ACK was needed to release storage\"}")
                        return MockResponse().setBody(effectiveStatus())
                    }
                    if(path.startsWith("/api/auto")){
                        val enabling=path.contains("enabled=1")
                        autoRequests.add(enabling)
                        if ((enabling&&startAutoFails)||(!enabling&&stopAutoFails))return MockResponse().setResponseCode(500).setBody("{}")
                        return MockResponse().setResponseCode(202).setBody(effectiveStatus())
                    }
                    if(path.startsWith("/api/record/stop"))return MockResponse().setResponseCode(202).setBody(effectiveStatus())
                    if(path.startsWith("/api/events/file"))return MockResponse()
                        .addHeader("X-KNOK-Event-ID",if(badId)"wrong" else id).setBody(Buffer().write(bytes))
                    if(path.startsWith("/api/events/ack")){
                        ackRequests++;return MockResponse().setResponseCode(if(ackFails)500 else 200).setBody("{}")
                    }
                    if(path.startsWith("/api/events")){
                        val crc=checksumOverride?:"%08x".format(CRC32().apply{update(bytes)}.value)
                        return MockResponse().setBody("""{"events":[{"id":"$id","completed":true,"simulated":$simulated,"bytes":${bytes.size},"sampleRate":16000,"sampleCount":80000,"crc32":"$crc","durationMs":5000,"startSample":32000,"triggerSample":48000,"rawEdges":17,"digitalEvents":3,"lastRawEdgeUs":123456,"lastDigitalEventUs":123000,"maxLevelDb":94,"averageLevelDb":75}]}""")
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        fun effectiveStatus():String = if(detachedFinalizingOnFirstStatus)
            """{"protocolVersion":2,"eventQueueAvailable":true,"ready":true,"autoEnabled":false,"finalizing":${statusRequests==1},"recording":false}"""
            else if(autoResetsAfterSecondStatus)
            """{"protocolVersion":2,"eventQueueAvailable":true,"ready":true,"autoEnabled":${statusRequests<2 || autoRequests.count{it}>1},"recording":false}"""
            else if(waitsForAckToFinalize)
            """{"protocolVersion":2,"eventQueueAvailable":true,"ready":true,"finalizing":${ackRequests==0},"recording":false}"""
            else status
        override fun close(){server.shutdown();db.close();dir.deleteRecursively()}
    }
    @Test fun directorySyncFailureNeverAcknowledgesOrCommitsRecord()=runBlocking {
        Fixture().use { f ->
            val client=EspRecorderClient(syncDirectory={throw java.io.IOException("directory sync failed")})
            assertTrue(runCatching{EspEventImporter(client,f.repo,f.dir).importPending(f.base,null)}.isFailure)
            assertEquals(0,f.ackRequests);assertNull(f.db.noiseDao().findByDeviceEventId(id))
        }
    }
    @Test fun detachedDeviceAutoCanStopAndSavePendingEvents()=runBlocking {
        Fixture().use { f ->
            f.autoResetsAfterSecondStatus=true
            val session=EspDeviceAutoSession(EspRecorderClient(syncDirectory={}),f.importer())
            val stopped=session.stopDetachedDeviceAuto(f.base,null)
            assertFalse(stopped.autoEnabled)
            assertEquals(listOf(false),f.autoRequests)
            assertTrue(f.ackRequests>0)
            assertTrue(File(f.repo.findDeviceEvent(id)!!.recordingPath!!).isFile)
        }
    }
    @Test fun detachedStopFailureDoesNotAcknowledgeOrErasePendingEvents()=runBlocking {
        Fixture().use { f ->
            f.autoResetsAfterSecondStatus=true
            f.stopAutoFails=true
            val session=EspDeviceAutoSession(EspRecorderClient(syncDirectory={}),f.importer())
            assertTrue(runCatching { session.stopDetachedDeviceAuto(f.base,null) }.isFailure)
            assertEquals(0,f.ackRequests)
            assertNull(f.repo.findDeviceEvent(id))
        }
    }
    @Test fun detachedStopDoesNotStopAnUnrelatedManualRecording()=runBlocking {
        Fixture().use { f ->
            f.status="""{"protocolVersion":2,"eventQueueAvailable":true,"ready":true,"autoEnabled":false,"recording":true}"""
            val session=EspDeviceAutoSession(EspRecorderClient(syncDirectory={}),f.importer())
            assertTrue(runCatching { session.stopDetachedDeviceAuto(f.base,null) }.isFailure)
            assertTrue(f.autoRequests.isEmpty())
            assertEquals(0,f.ackRequests)
        }
    }
    @Test fun receivingPendingEventsDoesNotStopNewlyEnabledDetection()=runBlocking {
        Fixture().use { f ->
            f.autoResetsAfterSecondStatus=true
            val session=EspDeviceAutoSession(EspRecorderClient(syncDirectory={}),f.importer())
            assertTrue(runCatching { session.stopDetachedDeviceAuto(f.base,null,stopIfRunning=false) }.isFailure)
            assertTrue(f.autoRequests.isEmpty())
            assertEquals(0,f.ackRequests)
        }
    }
    @Test fun detachedFinalizingEventCanFinishAndBeImportedWithoutStoppingManualAudio()=runBlocking {
        Fixture().use { f ->
            f.detachedFinalizingOnFirstStatus=true
            val session=EspDeviceAutoSession(EspRecorderClient(syncDirectory={}),f.importer())
            val status=session.stopDetachedDeviceAuto(f.base,null,stopIfRunning=false)
            assertFalse(status.finalizing)
            assertTrue(f.autoRequests.isEmpty())
            assertTrue(f.ackRequests>0)
        }
    }
    @Test fun deviceStorageFailureIsNotReportedAsSuccessfulRecovery()=runBlocking {
        Fixture().use { f ->
            f.status="""{"protocolVersion":2,"eventQueueAvailable":true,"ready":false,"autoEnabled":false,"recording":false,"quarantinedSlots":1}"""
            val session=EspDeviceAutoSession(EspRecorderClient(syncDirectory={}),f.importer())
            assertTrue(runCatching { session.stopDetachedDeviceAuto(f.base,null,stopIfRunning=false) }.isFailure)
            assertNotNull(f.repo.findDeviceEvent(id))
        }
    }
    @Test fun finalWavNameIsSyncedBeforeDatabaseAndAck()=runBlocking {
        Fixture().use { f ->
            var syncs=0
            val client=EspRecorderClient(syncDirectory={ directory ->
                syncs++;assertEquals(f.dir.canonicalFile,directory.canonicalFile)
                assertTrue(File(directory,"esp32_event_${id}.wav").readBytes().contentEquals(f.bytes))
                assertEquals(0,f.ackRequests);assertNull(runBlocking {f.db.noiseDao().findByDeviceEventId(id)})
            })
            EspEventImporter(client,f.repo,f.dir).importPending(f.base,null)
            assertEquals(1,syncs);assertEquals(1,f.ackRequests)
        }
    }
    @Test fun importCommitsAudioAndDigitalCountsBeforeAck()=runBlocking {
        Fixture().use {f->
            f.importer().importPending(f.base,"owner-a")
            val row=f.db.noiseDao().findByDeviceEventId(id)!!
            assertEquals(1,f.ackRequests);assertTrue(File(row.recordingPath!!).readBytes().contentEquals(f.bytes))
            assertEquals(17L,row.deviceRawEdges);assertEquals(3L,row.deviceDigitalEvents)
            assertEquals(32000L,row.deviceStartSample);assertEquals(48000L,row.deviceTriggerSample)
            assertEquals(123456L,org.json.JSONObject(row.deviceMetadataJson!!).getLong("lastRawEdgeUs"))
            assertEquals(80000L,row.deviceSampleCount);assertEquals(0.0,row.maxVibration,0.0)
            assertTrue(row.deviceClassificationPending);assertEquals("owner-a",row.userId)
            assertEquals("unclassified",row.classificationLabel)
        }
    }
    @Test fun lostAckAndRestartDoNotCreateDuplicateRecords()=runBlocking {
        Fixture().use {f->
            f.ackFails=true
            assertTrue(runCatching{f.importer().importPending(f.base,"owner-a")}.isFailure)
            val first=f.db.noiseDao().findByDeviceEventId(id)!!
            f.ackFails=false;f.importer().importPending(f.base,"owner-b")
            val saved=f.db.noiseDao().findByDeviceEventId(id)!!
            assertEquals(first.id,saved.id);assertEquals("owner-a",saved.userId)
            assertEquals(1,f.db.noiseDao().getRecordsForUserOnce("owner-a").size)
            assertTrue(f.db.noiseDao().getRecordsForUserOnce("owner-b").isEmpty())
            assertEquals(2,f.ackRequests)
        }
    }
    @Test fun checksumFailureNeverAcknowledgesOrPersists()=runBlocking {
        Fixture().use {f->
            f.checksumOverride="00000000"
            assertTrue(runCatching{f.importer().importPending(f.base,null)}.isFailure)
            assertNull(f.db.noiseDao().findByDeviceEventId(id));assertEquals(0,f.ackRequests)
            assertTrue(f.dir.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun invalidWavIsRejectedEvenWithMatchingChecksum()=runBlocking {
        Fixture().use {f->
            f.bytes[20]=3 // IEEE float is not the promised PCM16 contract.
            assertTrue(runCatching{f.importer().importPending(f.base,null)}.isFailure)
            assertEquals(0,f.ackRequests);assertNull(f.db.noiseDao().findByDeviceEventId(id))
        }
    }
    @Test fun wrongResponseEventIdentityCannotReplaceAudio()=runBlocking {
        Fixture().use {f->
            f.badId=true
            assertTrue(runCatching{f.importer().importPending(f.base,null)}.isFailure)
            assertEquals(0,f.ackRequests)
        }
    }
    @Test fun legacyStatusRemainsCompatibleAndNewCountersAreParsed()=runBlocking {
        Fixture().use {f->
            val legacy=EspRecorderClient().getStatus(f.base)
            assertEquals(1,legacy.protocolVersion);assertFalse(legacy.eventQueueAvailable)
            assertEquals(12L,legacy.tiltRawEdges);assertEquals(71.0,legacy.levelDb,0.0)
            f.status="""{"protocolVersion":2,"eventQueueAvailable":true,"autoEnabled":true,"ready":true,"finalizing":true,"pendingEvents":2,"queueOverflows":3}"""
            val v2=EspRecorderClient().getStatus(f.base)
            assertTrue(v2.eventQueueAvailable&&v2.autoEnabled&&v2.finalizing);assertEquals(3L,v2.queueOverflows)
        }
    }
    @Test fun storedEventCanResumeClassificationAfterRestart()=runBlocking {
        Fixture().use {f->
            f.importer().importPending(f.base,"owner-a")
            val row=f.repo.pendingDeviceClassifications().single()
            f.repo.finishDeviceClassification(row.id,"impact",0.8,true)
            val result=f.repo.findById(row.id)!!
            assertEquals("impact",result.classificationLabel);assertFalse(result.deviceClassificationPending)
            assertEquals("owner-a",result.userId);assertEquals(id,result.deviceEventId)
        }
    }
    @Test fun deviceAutoSessionControlsDeviceAndImportsWithoutLegacyStart()=runBlocking {
        Fixture().use {f->
            f.status="""{"protocolVersion":2,"eventQueueAvailable":true,"ready":true,"autoEnabled":true,"wifiConnected":true}"""
            val seen=mutableListOf<EspRecorderStatus>()
            EspDeviceAutoSession(EspRecorderClient(),f.importer()).run(
                f.base,90.0,{null},{f.statusRequests<4},{seen.add(it)},{})
            assertEquals(listOf(true,false),f.autoRequests)
            assertTrue(seen.isNotEmpty());assertNotNull(f.db.noiseDao().findByDeviceEventId(id))
        }
    }
    @Test fun activeStorageFailureCannotLookLikeHealthyMonitoring()=runBlocking {
        Fixture().use { f ->
            f.status="""{"protocolVersion":2,"ready":true,"autoEnabled":true,"storageErrors":3,"quarantinedSlots":2}"""
            val damaged=EspRecorderClient().getStatus(f.base)
            assertEquals(com.example.ui.EspAlwaysRecordingState.ERROR,com.example.ui.deviceAutoState(damaged))
            f.status="""{"protocolVersion":2,"ready":true,"autoEnabled":true,"storageErrors":3,"quarantinedSlots":0,"waitingForSpace":1}"""
            assertEquals(com.example.ui.EspAlwaysRecordingState.ERROR,com.example.ui.deviceAutoState(EspRecorderClient().getStatus(f.base)))
            f.status="""{"protocolVersion":2,"ready":true,"autoEnabled":true,"storageErrors":3,"quarantinedSlots":0}"""
            assertEquals(com.example.ui.EspAlwaysRecordingState.MONITORING,com.example.ui.deviceAutoState(EspRecorderClient().getStatus(f.base)))
        }
    }
    @Test fun lostStartResponseStillAttemptsToDisarmDevice()=runBlocking {
        Fixture().use {f->
            f.startAutoFails=true
            assertTrue(runCatching {EspDeviceAutoSession(EspRecorderClient(),f.importer()).run(
                f.base,90.0,{null},{false},{},{})}.isFailure)
            assertEquals(listOf(true,false),f.autoRequests)
        }
    }
    @Test fun failedStopMustNotReportSuccessfulShutdown()=runBlocking {
        Fixture().use {f->
            f.stopAutoFails=true
            assertTrue(runCatching {EspDeviceAutoSession(EspRecorderClient(),f.importer()).run(
                f.base,90.0,{null},{false},{},{})}.isFailure)
        }
    }
    @Test fun simulatedEventsAreLabelledAsSimulationInRecord()=runBlocking {
        Fixture().use {f->
            f.simulated=true;f.importer().importPending(f.base,null)
            assertTrue(f.db.noiseDao().findByDeviceEventId(id)!!.note.contains("모의"))
        }
    }
    @Test fun cancellationImmediatelyAfterRealCommitKeepsDurableAudio()=runBlocking {
        Fixture().use {f->
            val real=f.db.noiseDao()
            lateinit var operation: Job
            val boundary=object:NoiseDao by real {
                override suspend fun persistDeviceEvent(record:NoiseRecord):NoiseRecord {
                    val saved=real.persistDeviceEvent(record)
                    operation.cancel()
                    yield() // Exercise cancellable return after the actual Room commit.
                    return saved
                }
            }
            operation=launch(start=CoroutineStart.LAZY) {
                EspEventImporter(EspRecorderClient(syncDirectory={}),NoiseRepository(boundary),f.dir).importPending(f.base,"owner-a")
            }
            operation.start();operation.join()
            val row=real.findByDeviceEventId(id)!!
            assertTrue("Committed row must retain its WAV when the caller is cancelled",File(row.recordingPath!!).exists())
            assertEquals(0,f.ackRequests)
        }
    }
    @Test fun cloudDuplicateCannotReplaceLocalAudioOrIdentity()=runBlocking {
        Fixture().use {f->
            f.importer().importPending(f.base,"owner-a")
            val dao=f.db.noiseDao();val original=dao.findByDeviceEventId(id)!!
            dao.updateRecord(original.copy(syncState="SYNCED"))
            dao.mergeRemoteRecord(original.copy(id=0,syncId="another-client",recordingPath=null,syncState="SYNCED"))
            val saved=dao.findByDeviceEventId(id)!!
            assertEquals(original.id,saved.id);assertEquals(original.recordingPath,saved.recordingPath)
            assertEquals(original.syncId,saved.syncId)
            dao.mergeRemoteRecord(original.copy(id=0,userId="owner-b",syncId="other-owner",recordingPath=null,syncState="SYNCED"))
            assertEquals("owner-a",dao.findByDeviceEventId(id)!!.userId)
        }
    }
    @Test fun stoppingImportsCompletedEventsBeforeWaitingForStorageFinalization()=runBlocking {
        Fixture().use {f->
            f.waitsForAckToFinalize=true
            EspDeviceAutoSession(EspRecorderClient(),f.importer()).run(
                f.base,90.0,{"owner-a"},{false},{},{})
            assertNotNull(f.db.noiseDao().findByDeviceEventId(id))
            assertTrue(f.ackRequests>0)
        }
    }
    @Test fun deviceRebootRestoresRequestedAutomaticDetection()=runBlocking {
        Fixture().use {f->
            f.autoResetsAfterSecondStatus=true
            EspDeviceAutoSession(EspRecorderClient(),f.importer()).run(
                f.base,90.0,{null},{f.statusRequests<4},{},{})
            assertEquals(listOf(true,true,false),f.autoRequests)
        }
    }
    @Test fun temporaryOutageLongerThanTenPollsKeepsSessionAndRecovers()=runTest {
        Fixture().use {f->
            f.statusFailuresRemaining=12
            f.status="""{"protocolVersion":2,"eventQueueAvailable":true,"ready":true,"autoEnabled":true}"""
            val seen=mutableListOf<EspRecorderStatus>()
            EspDeviceAutoSession(EspRecorderClient(),f.importer()).run(
                f.base,90.0,{null},{f.statusRequests<14},{seen.add(it)},{})
            assertTrue(f.statusRequests>=14)
            assertTrue(seen.any{it.ready&&it.autoEnabled})
        }
    }
    @Test fun migrationFromVersion5PreservesExistingRecording()=runBlocking {
        val name="esp-migration-${UUID.randomUUID()}"
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use {old->
            old.execSQL("""CREATE TABLE noise_records(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              timestamp INTEGER NOT NULL,maxDb REAL NOT NULL,avgDb REAL NOT NULL,maxVibration REAL NOT NULL,
              isExceeded INTEGER NOT NULL,isNoiseExceeded INTEGER NOT NULL,isVibeExceeded INTEGER NOT NULL,
              recordingPath TEXT,durationMs INTEGER NOT NULL,note TEXT NOT NULL,recordingSource TEXT NOT NULL,
              classificationLabel TEXT NOT NULL,classificationConfidence REAL NOT NULL,isInterfloorCandidate INTEGER NOT NULL,
              userId TEXT,syncId TEXT,remoteAudioPath TEXT,syncState TEXT NOT NULL,soundAnalysisJson TEXT)""")
            old.execSQL("""INSERT INTO noise_records VALUES(7,100,60,40,0,0,0,0,'/preserved.wav',5000,'기존 메모','ESP32','footstep',0.8,1,'owner','sync',NULL,'LOCAL','report')""")
            old.version=5
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_5_6).build()
        try {
            val row=db.noiseDao().findById(7)!!
            assertEquals("/preserved.wav",row.recordingPath);assertEquals("기존 메모",row.note)
            assertEquals("report",row.soundAnalysisJson);assertEquals("owner",row.userId)
            assertNull(row.deviceEventId);assertEquals(0L,row.deviceRawEdges)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
