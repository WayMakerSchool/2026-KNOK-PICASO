package com.example.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** File + DB commit precedes ACK. Re-delivery uses the persistent unique event ID. */
class EspEventImporter(
    private val client: EspRecorderClient,
    private val repository: NoiseRepository,
    private val audioDirectory: File
) {
    suspend fun importPending(baseUrl: String,ownerUserId: String?): Int = importMutex.withLock {
        var imported=0
        for(event in client.listEvents(baseUrl)) {
            currentCoroutineContext().ensureActive()
            val existing=repository.findDeviceEvent(event.id)
            val existingFile=existing?.recordingPath?.let(::File)
            val validExisting=existingFile!=null&&runCatching{EspWavFiles.validate(existingFile,event)}.isSuccess
            val destination=if(validExisting)existingFile!! else File(audioDirectory,"esp32_event_${event.id}.wav")
            var committed=existing!=null
            try {
                if(!validExisting)client.downloadEvent(baseUrl,event,destination)
                currentCoroutineContext().ensureActive()
                if(existing==null) {
                    withContext(NonCancellable) {
                      repository.insertDeviceEvent(NoiseRecord(
                        // Import time; source sample clocks are retained separately (not claimed UTC).
                        timestamp=System.currentTimeMillis(),maxDb=event.maxLevelDb,avgDb=event.averageLevelDb,
                        maxVibration=0.0,isExceeded=false,isNoiseExceeded=false,isVibeExceeded=false,
                        recordingPath=destination.absolutePath,durationMs=event.durationMs,
                        note=(if(event.simulated)"[API 모의 시험 · 실물 측정 아님] " else "")+"ESP32 자동/수동 이벤트 · 수신 시각 기준 · 디지털 진동 ${event.digitalEvents}회"+
                          (if(event.truncatedAtLimit)" · 기기 녹음 상한에서 종료" else "")+" · AI 분류 대기",
                        recordingSource="ESP32",userId=ownerUserId,deviceEventId=event.id,deviceMetadataJson=event.metadataJson(),
                        deviceRawEdges=event.rawEdges,deviceDigitalEvents=event.digitalEvents,
                        deviceStartSample=event.startSample,deviceTriggerSample=event.triggerSample,
                        deviceSampleCount=event.sampleCount,deviceClassificationPending=true
                    ))
                      committed=true;imported++
                    }
                } else if(!validExisting)repository.repairDeviceAudioPath(existing.id,destination.absolutePath)
                client.acknowledge(baseUrl,event.id)
            } finally {
                // Never remove the durable audio when only the acknowledgement failed.
                if(!committed)destination.delete()
            }
        }
        imported
    }
    suspend fun finishPending(baseUrl: String,ownerUserId: String?,initial: EspRecorderStatus): EspRecorderStatus {
        var status=initial
        val deadline=System.nanoTime()+15_000_000_000L
        while (true) {
            // ACK complete older events even while another RAM event waits for disk space.
            importPending(baseUrl,ownerUserId)
            status=client.getStatus(baseUrl)
            if (!status.recording && !status.finalizing) {
                importPending(baseUrl,ownerUserId)
                return status
            }
            if (System.nanoTime()>=deadline) throw IOException("기기 WAV 마감 확인이 필요합니다.")
            delay(200)
        }
    }
    private companion object {val importMutex=Mutex()}
}
