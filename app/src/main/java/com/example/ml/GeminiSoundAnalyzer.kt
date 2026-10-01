package com.example.ml

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.example.auth.FirebaseClientProvider
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Authenticated server call: the paid Gemini key is never included in the APK. */
class GeminiSoundAnalyzer(private val context: Context) {
    companion object {
        const val MODEL = "gemini-3.5-flash-lite"
        const val MAX_BYTES = 10 * 1024 * 1024L
        const val MAX_DURATION_MS = 5 * 60 * 1000L

        fun mimeType(extension: String): String = when (extension.lowercase()) {
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            else -> throw IllegalArgumentException("WAV 또는 M4A 녹음 파일을 선택해 주세요.")
        }

        fun validateInput(size: Long, durationMs: Long) {
            require(size > 44) { "녹음 파일이 비어 있거나 너무 짧습니다." }
            require(size <= MAX_BYTES) { "10MB 이하의 녹음 파일을 분석할 수 있습니다." }
            require(durationMs in 1..MAX_DURATION_MS) { "비용을 줄이기 위해 5분 이하의 녹음만 분석합니다. 짧게 다시 녹음해 주세요." }
        }
    }

    suspend fun analyze(file: File): SoundAnalysis = withContext(Dispatchers.IO) {
        require(file.isFile) { "녹음 파일을 찾을 수 없습니다." }
        require(file.length() <= MAX_BYTES) { "10MB 이하의 녹음 파일을 분석할 수 있습니다." }
        val mime = mimeType(file.extension)
        val metadata = MediaMetadataRetriever()
        val duration = try {
            metadata.setDataSource(file.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { metadata.release() }
        validateInput(file.length(), duration)
        val app = FirebaseClientProvider.appOrNull(context)
            ?: error("Firebase 설정이 필요합니다. google-services.json을 확인해 주세요.")
        check(FirebaseClientProvider.authOrNull(context)?.currentUser != null) { "로그인 후 소리 분석을 사용할 수 있습니다." }
        val callable = FirebaseFunctions.getInstance(app, "asia-northeast3")
            .getHttpsCallable("analyzeRecordingSound").apply { setTimeout(120, TimeUnit.SECONDS) }
        try {
            val response = withTimeout(130_000L) {
                callable.call(mapOf("mime" to mime, "audio" to Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))).await()
            }
            val result = response.data as? Map<*, *> ?: error("분석 결과를 받지 못했습니다.")
            SoundAnalysis.fromJson(JSONObject(result).toString())
        } catch (error: FirebaseFunctionsException) {
            val message = when (error.code) {
                FirebaseFunctionsException.Code.NOT_FOUND -> "소리 분석 서버 배포가 필요합니다."
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> "로그인과 앱 인증 설정을 확인해 주세요."
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> error.message ?: "분석 횟수 또는 Gemini 크레딧을 확인해 주세요."
                else -> "소리 분석 연결을 확인하고 다시 시도해 주세요."
            }
            throw IllegalStateException(message)
        }
    }
}
