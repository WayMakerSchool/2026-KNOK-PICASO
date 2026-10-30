package com.example.ui.v3

import android.content.Context
import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import com.example.ml.soundLabelName
import com.example.ui.EspAlwaysRecordingState
import com.example.ui.EspRecorderConnectionState
import com.example.ui.canEditDeviceEndpoint
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class V3MonitorPresentation(val title: String, val description: String, val recording: Boolean)

fun v3MonitorPresentation(state: EspAlwaysRecordingState): V3MonitorPresentation = when (state) {
    EspAlwaysRecordingState.OFF -> V3MonitorPresentation("감지 꺼짐", "감지를 켜면 기준을 넘는 소리를 기록해요.", false)
    EspAlwaysRecordingState.CHECKING -> V3MonitorPresentation("연결 확인 중", "KNOK의 상태를 확인하고 있어요.", false)
    EspAlwaysRecordingState.MONITORING -> V3MonitorPresentation("감지 중", "소음을 기다리고 있어요.", false)
    EspAlwaysRecordingState.RECORDING -> V3MonitorPresentation("녹음 중", "KNOK에서 소리를 기록하고 있어요.", true)
    EspAlwaysRecordingState.GRACE_PERIOD -> V3MonitorPresentation("녹음 마무리 중", "소리가 잦아드는지 확인하고 있어요.", true)
    EspAlwaysRecordingState.SAVING -> V3MonitorPresentation("저장 중", "원본 녹음을 안전하게 보관하고 있어요.", false)
    EspAlwaysRecordingState.ERROR -> V3MonitorPresentation("확인 필요", "기기 상태와 연결을 확인해 주세요.", false)
}

fun v3ConnectionText(connection: EspRecorderConnectionState): String = when (connection) {
    EspRecorderConnectionState.DISCONNECTED -> "연결 안 됨"
    EspRecorderConnectionState.CHECKING -> "연결 확인 중"
    else -> "연결됨"
}

fun v3RelativeLevel(level: Double, available: Boolean): String =
    if (available && level.isFinite()) String.format(Locale.KOREA, "%.1f", level) else "—"

fun v3CanEditThreshold(busy: Boolean,enabled: Boolean,connection: EspRecorderConnectionState,
    state: EspAlwaysRecordingState,reportedDeviceAuto: Boolean): Boolean =
    canEditDeviceEndpoint(busy,enabled,connection) && !reportedDeviceAuto &&
        (state==EspAlwaysRecordingState.OFF || (state==EspAlwaysRecordingState.ERROR && connection==EspRecorderConnectionState.CONNECTED))

data class V3RecordPresentation(val title: String, val badge: String, val alternative: String?)

fun v3RecordPresentation(record: NoiseRecord): V3RecordPresentation {
    if (record.classificationLabel == "analysis_failed") return V3RecordPresentation("분석 실패", "원본 보존됨", null)
    val analysis = SoundAnalysis.fromJsonOrNull(record.soundAnalysisJson)
    if (analysis == null) return V3RecordPresentation("분석 대기", "원본 녹음", null)
    val title = if (record.classificationLabel in setOf("footstep", "hammering", "instant_impact", "dragging_furniture", "vacuum_cleaner", "normal_or_unknown"))
        soundLabelName(record.classificationLabel) else analysis.sound
    val score = runCatching {
        (JSONObject(record.soundAnalysisJson).opt("modelScore") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
    }.getOrNull()
    val badge = if (score == null) "AI 추정 · ${analysis.certaintyLabel}" else {
        val percent = String.format(Locale.KOREA, "%.1f", score * 100)
        val prefix = if (analysis.certainty == "unknown" || record.classificationLabel == "normal_or_unknown")
            "AI 판단 보류" else "AI 추정"
        "$prefix · 모델 점수 $percent%"
    }
    return V3RecordPresentation(title, badge, analysis.alternative.takeIf { it.isNotBlank() })
}

/** UI-only aliases. Recorder endpoint/settings and the recording database are never rewritten. */
class V3DeviceAliasStore(context: Context) {
    private val prefs = context.getSharedPreferences("knok_v3_display_names", Context.MODE_PRIVATE)
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun read(deviceId: String, endpoint: String): String =
        deviceId.takeIf { it.isNotBlank() }?.let { prefs.getString("device_${key(it)}", null) }
            ?: prefs.getString("endpoint_${key(endpoint.trim().trimEnd('/'))}", null) ?: "내 KNOK"

    fun save(deviceId: String, endpoint: String, value: String): Boolean {
        val name = value.trim()
        if (name.isBlank()) return false
        prefs.edit().apply {
            putString("endpoint_${key(endpoint.trim().trimEnd('/'))}", name)
            if (deviceId.isNotBlank()) putString("device_${key(deviceId)}", name)
        }.apply()
        return true
    }
}
