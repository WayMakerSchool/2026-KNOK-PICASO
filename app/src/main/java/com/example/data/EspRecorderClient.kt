package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class EspRecorderStatus(
    val device: String,
    val hostname: String,
    val ip: String,
    val wifiConnected: Boolean,
    val recording: Boolean,
    val durationMs: Long,
    val bytesWritten: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val peakPcm: Int,
    val levelDb: Double,
    val maxLevelDb: Double,
    val averageLevelDb: Double,
    val levelMeterAvailable: Boolean,
    val levelSampleBlockCount: Long,
    val lastLevelPcmPeak: Int,
    val hasRecording: Boolean,
    val latestFileName: String,
    val message: String
)

/**
 * HTTP client for the KNOK ESP32 recorder firmware.
 * All methods run on Dispatchers.IO so the UI thread never performs a network call.
 */
class EspRecorderClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun getStatus(baseUrl: String): EspRecorderStatus = withContext(Dispatchers.IO) {
        val body = executeJson(buildRequest(baseUrl, "/api/status").build())
        parseStatus(body)
    }

    suspend fun startRecording(
        baseUrl: String,
        durationMs: Long? = null
    ): EspRecorderStatus = withContext(Dispatchers.IO) {
        val path = if (durationMs != null) {
            "/api/record/start?durationMs=${durationMs.coerceAtLeast(1L)}"
        } else {
            "/api/record/start"
        }
        val body = executeJson(
            buildRequest(baseUrl, path)
                .post(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
        parseStatus(body)
    }

    suspend fun stopRecording(baseUrl: String): EspRecorderStatus = withContext(Dispatchers.IO) {
        val body = executeJson(
            buildRequest(baseUrl, "/api/record/stop")
                .post(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
        parseStatus(body)
    }

    suspend fun downloadLatest(baseUrl: String, destination: File) = withContext(Dispatchers.IO) {
        val request = buildRequest(baseUrl, "/api/recording/latest").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WAV 다운로드 실패: HTTP ${response.code}")
            }

            val responseBody = response.body ?: throw IOException("WAV 응답이 비어 있습니다.")
            destination.parentFile?.mkdirs()
            responseBody.byteStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun buildRequest(baseUrl: String, path: String): Request.Builder {
        return Request.Builder()
            .url(normalizeBaseUrl(baseUrl) + path)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
    }

    private fun executeJson(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val deviceMessage = runCatching {
                    JSONObject(body).optString("message")
                }.getOrNull().orEmpty()
                val message = deviceMessage.ifBlank { body.ifBlank { "응답 본문이 없습니다." } }
                throw IOException("ESP32 요청 실패: HTTP ${response.code}, $message")
            }
            return body
        }
    }

    private fun parseStatus(body: String): EspRecorderStatus {
        try {
            val json = JSONObject(body)
            return EspRecorderStatus(
                device = json.optString("device", "ESP32-S3-SuperMini"),
                hostname = json.optString("hostname", ""),
                ip = json.optString("ip", ""),
                wifiConnected = json.optBoolean("wifiConnected", true),
                recording = json.optBoolean("recording", false),
                durationMs = json.optLong("durationMs", 0L),
                bytesWritten = json.optLong("bytesWritten", 0L),
                sampleRate = json.optInt("sampleRate", 16000),
                channels = json.optInt("channels", 1),
                bitsPerSample = json.optInt("bitsPerSample", 16),
                peakPcm = json.optInt("peakPcm", 0),
                levelDb = json.optDouble("levelDb", 0.0),
                maxLevelDb = json.optDouble("maxLevelDb", 0.0),
                averageLevelDb = json.optDouble("averageLevelDb", 0.0),
                levelMeterAvailable = json.has("levelDb"),
                levelSampleBlockCount = json.optLong("levelSampleBlockCount", 0L),
                lastLevelPcmPeak = json.optInt("lastLevelPcmPeak", 0),
                hasRecording = json.optBoolean("hasRecording", false),
                latestFileName = json.optString("latestFileName", "latest.wav"),
                message = json.optString("message", "")
            )
        } catch (error: Exception) {
            throw IOException("ESP32 상태 응답을 해석할 수 없습니다.", error)
        }
    }

    private fun normalizeBaseUrl(rawBaseUrl: String): String {
        var baseUrl = rawBaseUrl.trim()
        if (baseUrl.isBlank()) throw IOException("ESP32 주소를 입력해 주세요.")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://$baseUrl"
        }
        return baseUrl.trimEnd('/')
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
