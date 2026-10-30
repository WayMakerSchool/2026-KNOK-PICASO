package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Device detection keeps running independently of import and inference work. */
class EspDeviceAutoSession(
    private val client: EspRecorderClient,
    private val importer: EspEventImporter
) {
    /** Reconnect to a detached device and reuse the normal stop → save → ACK sequence. */
    suspend fun stopDetachedDeviceAuto(baseUrl: String,owner: String?,stopIfRunning: Boolean=true,onStatus: (EspRecorderStatus)->Unit = {}): EspRecorderStatus {
        val current=client.getStatus(baseUrl)
        onStatus(current)
        if(current.protocolVersion<2 || !current.eventQueueAvailable)
            throw IOException("기기에서 자동 감지 중지 상태를 확인할 수 없습니다.")
        val stopped=if(current.autoEnabled) {
            if(!stopIfRunning) throw IOException("기기 감지가 켜져 있어요. 중지하려면 기기 감지 중지 요청을 눌러 주세요.")
            client.setAuto(baseUrl,false,current.thresholdDb)
            client.stopRecording(baseUrl)
        } else {
            if(current.recording)
                throw IOException("자동 감지는 꺼져 있지만 기기 녹음이 진행 중이에요. 기기 상태를 다시 확인해 주세요.")
            current
        }
        importer.finishPending(baseUrl,owner,stopped)
        val confirmed=client.getStatus(baseUrl)
        onStatus(confirmed)
        if(confirmed.autoEnabled || confirmed.recording || confirmed.finalizing)
            throw IOException("기기 감지·녹음 종료를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.")
        if(!confirmed.ready || confirmed.quarantinedSlots>0 || confirmed.waitingForSpace>0 || confirmed.pendingEvents>0)
            throw IOException("기기에 대기 녹음이나 저장 문제가 남아 있어요. 기기 상태를 확인해 주세요.")
        return confirmed
    }

    suspend fun run(
        baseUrl: String,thresholdDb: Double,owner: ()->String?,enabled: ()->Boolean,
        onStatus: (EspRecorderStatus)->Unit,onMessage: (String)->Unit,
        onUnavailable: ()->Unit = {}
    ) = coroutineScope {
        var imports: Job? = null
        try {
          client.setAuto(baseUrl,true,thresholdDb)
          imports=launch {
            while(isActive&&enabled()) {
                try {
                    val count=importer.importPending(baseUrl,owner())
                    if(count>0)onMessage("${count}개 WAV 저장 완료 · AI 분류는 별도 진행")
                } catch(cancel: CancellationException){throw cancel}
                  catch(error: Exception){onMessage("이벤트 수신 재시도: ${error.message}")}
                delay(250)
            }
        }
            var failures=0
            while(isActive&&enabled()) {
                try {
                    val status=client.getStatus(baseUrl)
                    if(!status.ready)throw IOException("ESP32 수집 준비 안 됨: ${status.message}")
                    if(!status.autoEnabled && enabled()) {
                        client.setAuto(baseUrl,true,thresholdDb)
                        onMessage("기기 재연결 확인 · 자동 감지 다시 시작 요청")
                    }
                    onStatus(status);failures=0
                } catch(cancel: CancellationException){throw cancel}
                  catch(error: Exception){
                    onUnavailable()
                    failures++;onMessage("상태 재확인 중: ${error.message}")
                    // Wi-Fi recovery may take longer than ten 200 ms polls. Keep
                    // the user's requested session, with bounded backoff and a
                    // visible unavailable state, until recovery or explicit stop.
                    if(failures>=3)delay(minOf(2000L,failures*200L))
                }
                delay(200)
            }
        } finally {
            // A lost stop response is NOT reported as a successful device stop.
            withContext(NonCancellable) {
                val stopped=runCatching {
                    client.setAuto(baseUrl,false,thresholdDb)
                    client.stopRecording(baseUrl)
                }
                if(stopped.isFailure)onMessage("기기 중지 응답 없음 · 기기가 계속 감지할 수 있어 재연결 확인 필요")
                imports?.cancelAndJoin()
                if(stopped.isFailure)throw IOException("기기 중지 응답 없음 · 재연결 후 중지 확인 필요",stopped.exceptionOrNull())
                if(stopped.isSuccess) {
                    val last=importer.finishPending(baseUrl,owner(),stopped.getOrThrow())
                    onStatus(last)
                }
            }
        }
    }
}
