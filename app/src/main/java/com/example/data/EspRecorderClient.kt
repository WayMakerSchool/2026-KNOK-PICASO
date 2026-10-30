package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import android.system.Os
import android.system.OsConstants

data class EspRecorderStatus(
    val device: String, val hostname: String, val ip: String,
    val wifiConnected: Boolean, val recording: Boolean, val durationMs: Long,
    val bytesWritten: Long, val sampleRate: Int, val channels: Int, val bitsPerSample: Int,
    val peakPcm: Int, val levelDb: Double, val maxLevelDb: Double, val averageLevelDb: Double,
    val levelMeterAvailable: Boolean, val levelSampleBlockCount: Long,
    val lastLevelPcmPeak: Int, val hasRecording: Boolean, val latestFileName: String, val message: String,
    val protocolVersion: Int = 1, val eventQueueAvailable: Boolean = false,
    val autoEnabled: Boolean = false, val finalizing: Boolean = false, val ready: Boolean = true,
    val pendingEvents: Int = 0, val queueOverflows: Long = 0, val audioOverflows: Long = 0,
    val tiltRawEdges: Long = 0, val digitalEvents: Long = 0, val thresholdDb: Double = 90.0,
    val recentPeakDb: Double = 0.0, val levelAgeMs: Long = 0,
    val sampleSequence: Long = 0, val deviceId: String = "", val bootId: String = "",
    val storageErrors: Long = 0, val quarantinedSlots: Int = 0, val waitingForSpace: Int = 0,
    val recordingLimitMs: Long = 30000
)

data class EspRecorderEvent(
    val id: String, val bytes: Long, val sampleCount: Long, val sampleRate: Int,
    val crc32: String, val startSample: Long, val triggerSample: Long,
    val rawEdges: Long, val digitalEvents: Long, val maxLevelDb: Double, val averageLevelDb: Double,
    val simulated: Boolean = false, val lastRawEdgeUs: Long = 0, val lastDigitalEventUs: Long = 0,
    val manual: Boolean = false, val truncatedAtLimit: Boolean = false, val devicePersistence: String = "flash"
) {
    val durationMs: Long get() = sampleCount * 1000 / sampleRate
    fun metadataJson(): String = JSONObject().apply {
        put("id", id);put("crc32", crc32);put("sampleRate", sampleRate);put("sampleCount", sampleCount)
        put("startSample", startSample);put("triggerSample", triggerSample)
        put("rawEdges", rawEdges);put("digitalEvents", digitalEvents)
        put("lastRawEdgeUs", lastRawEdgeUs);put("lastDigitalEventUs", lastDigitalEventUs)
        put("devicePersistenceWhenListed",devicePersistence);put("manual",manual);put("truncatedAtLimit",truncatedAtLimit);
        put("simulated", simulated);put("levelCalibrated", false);put("vibrationIntensityAvailable", false)
    }.toString()
}

/** Control requests have a short budget; WAV transfers have their own larger budget. */
class EspRecorderClient(private val syncDirectory: (File)->Unit = EspWavFiles::syncDirectory) {
    private val controlClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
    private val fileClient = controlClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun getStatus(baseUrl: String): EspRecorderStatus = withContext(Dispatchers.IO) {
        parseStatus(executeJson(buildRequest(baseUrl,"/api/status").build()))
    }
    suspend fun startRecording(baseUrl: String,durationMs: Long? = null): EspRecorderStatus = withContext(Dispatchers.IO) {
        val path="/api/record/start"+(durationMs?.let{"?durationMs=${it.coerceIn(1,30000)}"}?:"")
        parseStatus(executeJson(post(baseUrl,path)))
    }
    suspend fun stopRecording(baseUrl: String): EspRecorderStatus = withContext(Dispatchers.IO) {
        parseStatus(executeJson(post(baseUrl,"/api/record/stop")))
    }
    suspend fun setAuto(baseUrl: String,enabled: Boolean,thresholdDb: Double): EspRecorderStatus = withContext(Dispatchers.IO) {
        require(thresholdDb.isFinite() && thresholdDb in 30.0..120.0)
        parseStatus(executeJson(post(baseUrl,"/api/auto?enabled=${if(enabled)1 else 0}&thresholdDb=$thresholdDb")))
    }
    suspend fun listEvents(baseUrl: String): List<EspRecorderEvent> = withContext(Dispatchers.IO) {
        try {
            val events=JSONObject(executeJson(buildRequest(baseUrl,"/api/events").build())).getJSONArray("events")
            require(events.length()<=2) {"이벤트 대기열 크기가 올바르지 않습니다."}
            (0 until events.length()).map {index->
                val j=events.getJSONObject(index)
                require(j.getBoolean("completed")) {"미완성 이벤트입니다."}
                val e=EspRecorderEvent(j.getString("id"),j.getLong("bytes"),j.getLong("sampleCount"),
                    j.getInt("sampleRate"),j.getString("crc32"),j.getLong("startSample"),j.getLong("triggerSample"),
                    j.getLong("rawEdges"),j.getLong("digitalEvents"),j.getDouble("maxLevelDb"),j.getDouble("averageLevelDb"),j.optBoolean("simulated"),j.optLong("lastRawEdgeUs"),j.optLong("lastDigitalEventUs"),j.optBoolean("manual"),j.optBoolean("truncatedAtLimit"),j.optString("devicePersistence","flash"))
                require(EVENT_ID.matches(e.id) && CRC.matches(e.crc32)) {"이벤트 식별자/체크섬 오류"}
                require(e.sampleRate==16000 && e.sampleCount in 1..480000 && e.bytes==44+e.sampleCount*2) {"이벤트 길이 오류"}
                require(e.startSample>=0 && e.triggerSample in e.startSample..(e.startSample+e.sampleCount)) {"이벤트 시간 오류"}
                require(e.rawEdges>=0 && e.digitalEvents in 0..e.rawEdges) {"디지털 센서 횟수 오류"}
                require(e.maxLevelDb.isFinite() && e.averageLevelDb.isFinite() && e.maxLevelDb in 0.0..120.0 && e.averageLevelDb in 0.0..120.0)
                e
            }.also { require(it.map{e->e.id}.distinct().size==it.size) {"중복 이벤트 응답"} }
        } catch(error: Exception) {throw IOException("ESP32 이벤트 응답 오류: ${error.message}",error)}
    }
    suspend fun acknowledge(baseUrl: String,id: String) = withContext(Dispatchers.IO) {
        require(EVENT_ID.matches(id));executeJson(post(baseUrl,"/api/events/ack?id=$id"));Unit
    }
    suspend fun downloadLatest(baseUrl: String,destination: File) = withContext(Dispatchers.IO) {
        downloadFile(baseUrl,"/api/recording/latest",destination,null)
    }
    suspend fun downloadEvent(baseUrl: String,event: EspRecorderEvent,destination: File) = withContext(Dispatchers.IO) {
        downloadFile(baseUrl,"/api/events/file?id=${event.id}",destination,event)
    }
    private fun downloadFile(baseUrl: String,path: String,destination: File,event: EspRecorderEvent?) {
        destination.parentFile?.mkdirs()
        val partial=File(destination.path+".part")
        try {
            fileClient.newCall(buildRequest(baseUrl,path).header("Accept","audio/wav").build()).execute().use {response->
                if(!response.isSuccessful)throw IOException("WAV 다운로드 실패: HTTP ${response.code}")
                if(event!=null && response.header("X-KNOK-Event-ID")!=event.id)throw IOException("이벤트 ID가 일치하지 않습니다.")
                val body=response.body?:throw IOException("빈 WAV 응답")
                if(event!=null && body.contentLength()>=0 && body.contentLength()!=event.bytes)throw IOException("WAV 전송 길이 오류")
                body.byteStream().use {input->FileOutputStream(partial).use {output->
                    val buffer=ByteArray(8192);var total=0L
                    while(true){val n=input.read(buffer);if(n<0)break;total+=n
                        if(total>960044)throw IOException("WAV 최대 길이 초과")
                        output.write(buffer,0,n)
                    }
                    output.fd.sync()
                }}
            }
            EspWavFiles.validate(partial,event)
            if(!partial.renameTo(destination))throw IOException("WAV 파일 확정 실패")
            syncDirectory(requireNotNull(destination.absoluteFile.parentFile))
        } finally {partial.delete()}
    }
    private fun post(baseUrl: String,path: String)=buildRequest(baseUrl,path)
        .post(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)).build()
    private fun buildRequest(baseUrl: String,path: String)=Request.Builder()
        .url(normalizeBaseUrl(baseUrl)+path).header("Accept","application/json").header("Cache-Control","no-cache")
    private fun executeJson(request: Request): String {
        controlClient.newCall(request).execute().use {response->
            val body=response.body?.string().orEmpty()
            if(!response.isSuccessful){
                val message=runCatching{JSONObject(body).optString("message")}.getOrNull().orEmpty()
                throw IOException("ESP32 요청 실패: HTTP ${response.code}, ${message.ifBlank{body}}")
            }
            return body
        }
    }
    private fun parseStatus(body: String): EspRecorderStatus {
        try {
            val j=JSONObject(body)
            return EspRecorderStatus(
                device=j.optString("device","ESP32-S3-SuperMini"),hostname=j.optString("hostname"),ip=j.optString("ip"),
                wifiConnected=j.optBoolean("wifiConnected",true),recording=j.optBoolean("recording"),durationMs=j.optLong("durationMs"),
                bytesWritten=j.optLong("bytesWritten"),sampleRate=j.optInt("sampleRate",16000),channels=j.optInt("channels",1),bitsPerSample=j.optInt("bitsPerSample",16),
                peakPcm=j.optInt("peakPcm"),levelDb=j.optDouble("levelDb",0.0),maxLevelDb=j.optDouble("maxLevelDb",0.0),averageLevelDb=j.optDouble("averageLevelDb",0.0),
                levelMeterAvailable=j.has("levelDb"),levelSampleBlockCount=j.optLong("levelSampleBlockCount"),lastLevelPcmPeak=j.optInt("lastLevelPcmPeak"),
                hasRecording=j.optBoolean("hasRecording"),latestFileName=j.optString("latestFileName","latest.wav"),message=j.optString("message"),
                protocolVersion=j.optInt("protocolVersion",1),eventQueueAvailable=j.optBoolean("eventQueueAvailable"),autoEnabled=j.optBoolean("autoEnabled"),
                finalizing=j.optBoolean("finalizing"),ready=j.optBoolean("ready",true),pendingEvents=j.optInt("pendingEvents"),
                queueOverflows=j.optLong("queueOverflows"),audioOverflows=j.optLong("audioOverflows"),tiltRawEdges=j.optLong("tiltRawEdges"),
                digitalEvents=j.optLong("digitalEvents"),thresholdDb=j.optDouble("thresholdDb",90.0),recentPeakDb=j.optDouble("recentPeakDb",0.0),
                levelAgeMs=j.optLong("levelAgeMs"),sampleSequence=j.optLong("sampleSequence"),deviceId=j.optString("deviceId"),bootId=j.optString("bootId"),
                storageErrors=j.optLong("storageErrors"),quarantinedSlots=j.optInt("quarantinedSlots"),waitingForSpace=j.optInt("waitingForSpace"),recordingLimitMs=j.optLong("recordingLimitMs",30000)
            )
        }catch(error: Exception){throw IOException("ESP32 상태 응답을 해석할 수 없습니다.",error)}
    }
    private fun normalizeBaseUrl(raw: String): String {
        var base=raw.trim();if(base.isEmpty())throw IOException("ESP32 주소를 입력해 주세요.")
        if(!base.startsWith("http://")&&!base.startsWith("https://"))base="http://$base"
        return base.trimEnd('/')
    }
    private companion object {
        val JSON_MEDIA_TYPE="application/json".toMediaType()
        val EVENT_ID=Regex("[0-9a-f]{12}-[0-9a-f]{16}-[0-9a-f]{8}")
        val CRC=Regex("[0-9a-fA-F]{8}")
    }
}

internal object EspWavFiles {
    fun syncDirectory(directory: File) {
        try {
            val fd=Os.open(directory.absolutePath,OsConstants.O_RDONLY,0)
            try {
                if(!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))throw IOException("WAV 저장 폴더가 아닙니다.")
                Os.fsync(fd)
            } finally { Os.close(fd) }
        } catch(error: android.system.ErrnoException) {
            throw IOException("WAV 폴더 저장 확정 실패 · 기기 이벤트를 보존합니다.",error)
        }
    }
    fun validate(file: File,event: EspRecorderEvent?=null) {
        val length=file.length()
        if(length !in 46..960044 || (length-44)%2!=0L)throw IOException("미완성 WAV 길이")
        val header=ByteArray(44)
        file.inputStream().use {input->if(input.read(header)!=44)throw IOException("WAV 헤더 누락")}
        val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at:Int,text:String)=header.copyOfRange(at,at+text.length).contentEquals(text.toByteArray())
        if(!tag(0,"RIFF")||!tag(8,"WAVEfmt ")||!tag(36,"data")||b.getInt(4).toLong()!=length-8||
            b.getInt(16)!=16||b.getShort(20).toInt()!=1||b.getShort(22).toInt()!=1||b.getInt(24)!=16000||
            b.getInt(28)!=32000||b.getShort(32).toInt()!=2||b.getShort(34).toInt()!=16||b.getInt(40).toLong()!=length-44)
            throw IOException("PCM16 mono 16kHz WAV 검증 실패")
        if(event!=null){
            if(length!=event.bytes || (length-44)/2!=event.sampleCount)throw IOException("이벤트 PCM 길이 불일치")
            val crc=CRC32();file.inputStream().use {input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;crc.update(buffer,0,n)}}
            if(crc.value.toString(16).padStart(8,'0')!=event.crc32.lowercase())throw IOException("WAV 체크섬 불일치")
        }
    }
}
