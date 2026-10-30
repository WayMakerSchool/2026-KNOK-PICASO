package com.example.ml

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class MacSoundResult(
    val label: String,
    val score: Float,
    val signalQuality: String,
    val modelVersion: String,
    val probabilities: Map<String, Float>,
    val inferenceMs: Long,
    val windowCount: Int,
    val windowStarts: List<Int>,
    val windowPolicy: String
)

data class MacFloorResult(
    val label: String,
    val reason: String?,
    val receiverPosition: String?,
    val modelVersion: String
)

data class MacAnalysisResult(
    val eventId: String,
    val analysisId: String,
    val audioFileSha256: String,
    val audioPcmSha256: String,
    val audioDurationSeconds: Double,
    val sound: MacSoundResult,
    val floor: MacFloorResult
) {
    fun toAudioClassificationResult() = AudioClassificationResult(
        label = sound.label,
        confidence = sound.score,
        isInterfloorCandidate = sound.label in FLOOR_SOUND_LABELS,
        probabilities = sound.probabilities,
        modelVersion = sound.modelVersion,
        inferenceMs = sound.inferenceMs,
        signalQuality = sound.signalQuality,
        windowStarts = sound.windowStarts,
        preprocessingPolicy = sound.windowPolicy
    )

    fun toStoredAnalysisJson(): String {
        val alternatives = sound.probabilities.entries
            .filter { it.key != sound.label }
            .sortedByDescending { it.value }
            .take(2)
            .joinToString(" / ") { soundLabelName(it.key) }
        val analysis = SoundAnalysis(
            sound = soundLabelName(sound.label),
            certainty = if (sound.label == "normal_or_unknown") "unknown" else "uncertain",
            evidence = "맥에서 소리 종류 모델로 분석했습니다. 모델 점수 ${(sound.score * 100).toInt()}%는 실제 정확도나 소리 발생 위치를 뜻하지 않습니다.",
            alternative = alternatives.ifEmpty { "다른 소리가 섞였을 수 있습니다." },
            model = sound.modelVersion
        )
        return JSONObject(analysis.toJson())
            .put("source", "mac_two_model")
            .put("modelScore", sound.score.toDouble())
            .put("modelScoreIsAccuracy", false)
            .put("probabilities", JSONObject(sound.probabilities))
            .put("inferenceMs", sound.inferenceMs)
            .put("analysisWindowCount", sound.windowCount)
            .put("analysisWindowStarts", JSONArray(sound.windowStarts))
            .put("preprocessingPolicy", sound.windowPolicy)
            .put("floorLabel", floor.label)
            .put("floorReason", floor.reason ?: JSONObject.NULL)
            .put("floorReceiverPosition", floor.receiverPosition ?: JSONObject.NULL)
            .put("floorModelVersion", floor.modelVersion)
            .put("eventId", eventId)
            .put("analysisId", analysisId)
            .put("audioFileSha256", audioFileSha256)
            .put("audioPcmSha256", audioPcmSha256)
            .put("audioDurationSeconds", audioDurationSeconds)
            .put("groundTruthUsed", false)
            .toString()
    }

    companion object {
        private val SOUND_LABELS = setOf(
            "dragging_furniture", "footstep", "hammering", "instant_impact",
            "normal_or_unknown", "vacuum_cleaner"
        )
        private val FLOOR_SOUND_LABELS = setOf("dragging_furniture", "hammering", "instant_impact")
        private val FLOOR_LABELS = setOf("above", "same", "below", "uncertain")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun fromJson(json: String): MacAnalysisResult {
            try {
                val root = JSONObject(json)
                require(root.getInt("schema_version") == 1) { "지원하지 않는 분석 응답 버전입니다." }
                require(!root.getBoolean("ground_truth_used")) { "정답 정보가 모델 추론 응답에 포함됐습니다." }
                val eventId = root.getString("event_id")
                require(eventId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "분석 이벤트 ID가 올바르지 않습니다." }
                val audio = root.getJSONObject("audio")
                val fileSha = audio.getString("file_sha256")
                val pcmSha = audio.getString("pcm_sha256")
                require(SHA256.matches(fileSha) && SHA256.matches(pcmSha)) { "녹음 해시 형식이 올바르지 않습니다." }
                require(audio.getInt("sample_rate") == 16000 && audio.getInt("sample_count") > 0) {
                    "분석된 녹음 형식이 KNOK와 다릅니다."
                }
                val duration = audio.getDouble("duration_seconds")
                require(duration.isFinite() && duration > 0.0) { "녹음 길이가 올바르지 않습니다." }

                val soundJson = root.getJSONObject("sound")
                val label = soundJson.getString("label")
                val score = soundJson.getDouble("score")
                require(label in SOUND_LABELS && score.isFinite() && score in 0.0..1.0) {
                    "소리 분류 결과가 올바르지 않습니다."
                }
                require(!soundJson.getBoolean("score_is_accuracy")) {
                    "모델 점수를 정확도로 표시한 응답은 사용할 수 없습니다."
                }
                val quality = soundJson.getString("signal_quality")
                require(quality in setOf("normal", "low_signal", "quantization_limited"))
                val probabilityJson = soundJson.getJSONObject("probabilities")
                val probabilities = SOUND_LABELS.associateWith { probabilityJson.getDouble(it).toFloat() }
                require(probabilities.values.all { it.isFinite() && it in 0f..1f } &&
                    kotlin.math.abs(probabilities.values.sum() - 1f) <= 0.001f) {
                    "소리 모델의 확률 분포가 올바르지 않습니다."
                }
                val windows = soundJson.optJSONArray("window_starts") ?: JSONArray()
                val starts = (0 until windows.length()).map { windows.getInt(it) }
                require(starts.all { it >= 0 }) { "분석 구간 위치가 올바르지 않습니다." }

                val floorJson = root.getJSONObject("floor")
                val floorLabel = floorJson.getString("label")
                require(floorLabel in FLOOR_LABELS) { "층 분류 결과가 올바르지 않습니다." }
                val receiver = floorJson.optString("receiver_position").takeIf { it in setOf("lower", "upper") }
                val reason = floorJson.optString("reason").takeIf { it.isNotBlank() && it != "null" }
                require(floorLabel != "uncertain" || reason != null) { "층 판단 보류 사유가 없습니다." }

                return MacAnalysisResult(
                    eventId = eventId,
                    analysisId = root.getString("analysis_id"),
                    audioFileSha256 = fileSha,
                    audioPcmSha256 = pcmSha,
                    audioDurationSeconds = duration,
                    sound = MacSoundResult(
                        label = label,
                        score = score.toFloat(),
                        signalQuality = quality,
                        modelVersion = soundJson.getString("model_version").also { require(it.isNotBlank()) },
                        probabilities = probabilities,
                        inferenceMs = soundJson.optLong("inference_ms").coerceAtLeast(0),
                        windowCount = soundJson.optInt("window_count", 1).coerceAtLeast(1),
                        windowStarts = starts,
                        windowPolicy = soundJson.optString("window_policy", "unknown")
                    ),
                    floor = MacFloorResult(
                        label = floorLabel,
                        reason = reason,
                        receiverPosition = receiver,
                        modelVersion = floorJson.getString("model_version").also { require(it.isNotBlank()) }
                    )
                )
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("맥 분석 응답을 읽지 못했습니다.", error)
            }
        }
    }
}

class MacAnalysisClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultClient()
) {
    fun analyze(wavFile: File, receiverPosition: String, eventId: String): MacAnalysisResult {
        require(wavFile.isFile && wavFile.length() in 1L..2_097_152L) { "분석할 WAV 파일을 확인해 주세요." }
        require(receiverPosition in setOf("", "lower", "upper")) { "KNOK 설치 층을 선택해 주세요." }
        require(eventId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "ESP32 이벤트 ID가 올바르지 않습니다." }
        val body = wavFile.asRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/analyze")
            .header("X-KNOK-Receiver-Position", receiverPosition)
            .header("X-KNOK-Event-ID", eventId)
            .post(body)
            .build()
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code in 500..599 || response.code == 429) {
                        throw IOException("맥 분석 서버 응답 ${response.code}")
                    }
                    if (!response.isSuccessful) throw IllegalArgumentException("맥 분석 요청이 거부됐습니다 (${response.code}).")
                    val parsed = MacAnalysisResult.fromJson(text)
                    require(parsed.eventId == eventId) { "분석 결과의 이벤트 ID가 요청과 다릅니다." }
                    require(parsed.audioFileSha256 == sha256(wavFile)) { "분석된 WAV 해시가 저장된 녹음과 다릅니다." }
                    return parsed
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt < 2) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("맥 분석 서버에 연결하지 못했습니다.")
    }

    fun isHealthy(): Boolean {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/health").get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) false
                else JSONObject(response.body?.string().orEmpty()).optBoolean("ready", false)
            }
        }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8765"

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
