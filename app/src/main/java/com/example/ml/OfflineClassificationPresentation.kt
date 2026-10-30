package com.example.ml

import org.json.JSONObject
import java.util.Locale

fun AudioClassificationResult.classificationSummary(): String = if(signalQuality!="normal")
    "판단 보류 · 녹음 신호가 너무 약합니다."
    else "${soundLabelName(label)} · 모델 점수 ${(confidence*100).toInt()}% · ${inferenceMs}ms"

fun storedClassificationSummary(label: String,confidence: Double,json: String?): String {
    if(label=="analysis_failed")return "분석 실패 · 원본 음원을 보존했습니다."
    val limited=runCatching{JSONObject(requireNotNull(json)).optString("signalQuality")=="quantization_limited"}.getOrDefault(false)
    return if(limited)"판단 보류 · 녹음 신호가 너무 약합니다."
      else "AI 분류: ${soundLabelName(label)} · 모델 점수 ${String.format(Locale.ROOT,"%.0f",confidence*100)}%"
}

fun analysisWindowDescription(json: String?): String? = runCatching {
    val value=JSONObject(requireNotNull(json))
    if(value.optInt("analysisWindowCount",1)>1) "녹음 전체를 겹치는 4초 구간 ${value.getInt("analysisWindowCount")}개로 분석했습니다."
    else if(!value.optBoolean("analysisWindowCropped"))null
    else {
        val start=value.getInt("analysisWindowStartSample").coerceAtLeast(0)/16000.0
        String.format(Locale.ROOT,"전체 녹음 중 %.1f–%.1f초의 대표 4초를 분석했습니다.",start,start+4)
    }
}.getOrNull()

/** Floor output is a research estimate; a classifier score is never rendered as accuracy. */
fun floorDirectionDescription(json: String?): String? = runCatching {
    val value = JSONObject(requireNotNull(json))
    val label = value.optString("floorLabel")
    when (label) {
        "above" -> "층 방향 연구 결과: 위층"
        "same" -> "층 방향 연구 결과: 같은 층"
        "below" -> "층 방향 연구 결과: 아래층"
        "uncertain" -> "층 방향 판단 보류 · ${floorHoldReason(value.optString("floorReason"))}"
        else -> null
    }
}.getOrNull()

fun analysisEventIdDescription(json: String?): String? = runCatching {
    val eventId = JSONObject(requireNotNull(json)).optString("eventId")
    eventId.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }?.let { "기기 이벤트 ID: $it" }
}.getOrNull()

private fun floorHoldReason(reason: String): String = when (reason) {
    "low_signal_quality" -> "녹음 신호 품질이 낮습니다."
    "unsupported_sound" -> "층 방향 연구 대상 소리가 아닙니다."
    "sound_score_below_threshold" -> "소리 종류 분류 점수가 기준보다 낮습니다."
    "audio_too_short" -> "녹음이 너무 짧습니다."
    "receiver_position_unregistered" -> "설치 위치를 등록하지 않았습니다."
    "floor_model_unavailable" -> "층 연구 모델을 사용할 수 없습니다."
    "invalid_floor_result" -> "층 모델 응답을 확인할 수 없습니다."
    else -> "추가 자료가 필요합니다."
}

fun soundLabelName(label: String): String = when (label) {
    "footstep" -> "발걸음"
    "dragging_furniture" -> "가구 끌기"
    "instant_impact" -> "물건 떨어짐·단발 충격"
    "hammering" -> "반복 망치질·타격"
    "vacuum_cleaner" -> "청소기"
    "normal_or_unknown" -> "일반·미지 소음"
    "analysis_failed" -> "분석 실패"
    else -> "분석 대기"
}

/** Describe the actual classifier output; no invented physical/acoustic explanation. */
fun AudioClassificationResult.offlineAnalysisJson(): String {
    val limited=signalQuality!="normal"
    val alternatives = probabilities.entries.filter { it.key != label }
        .sortedByDescending { it.value }.take(2).joinToString(" / ") { soundLabelName(it.key) }
    val analysis = SoundAnalysis(
        sound = soundLabelName(label),
        certainty = if (label == "normal_or_unknown") "unknown" else "uncertain",
        evidence = if(limited)"녹음 신호가 PCM16 해상도 한계에 가까워 소리 종류 판단을 보류했습니다. 주변의 모든 작은 소음을 걸러내는 기준은 아닙니다."
          else "기기에 녹음된 음원을 오프라인 AI로 분류했습니다. 모델 점수 ${(confidence * 100).toInt()}%는 실제 정확도나 발생 위치를 뜻하지 않습니다.",
        alternative = alternatives.ifEmpty { "다른 소리가 섞였을 가능성이 있습니다." },
        model = modelVersion
    )
    return JSONObject(analysis.toJson())
        .put("source", if(limited)"signal_quality_gate" else "offline_audio")
        .put("signalQuality",signalQuality)
        .put("modelScore", if(limited)JSONObject.NULL else confidence.toDouble())
        .put("probabilities", JSONObject(probabilities))
        .put("inferenceMs", inferenceMs)
        .put("analysisWindowStartSample", windowStartSample)
        .put("analysisWindowCropped", windowCropped)
        .put("analysisWindowCount", maxOf(1,windowStarts.size))
        .put("analysisWindowStarts", org.json.JSONArray(windowStarts))
        .put("auxiliaryAnalysisWindowStarts", org.json.JSONArray(auxiliaryWindowStarts))
        .put("preprocessingPolicy", preprocessingPolicy)
        .toString()
}
