package com.example.ml

import org.json.JSONObject

/** Model certainty is a qualitative assessment, not a calibrated probability. */
data class SoundAnalysis(
    val sound: String,
    val certainty: String,
    val evidence: String,
    val alternative: String,
    val model: String = GeminiSoundAnalyzer.MODEL,
    val analyzedAt: Long = System.currentTimeMillis()
) {
    val certaintyLabel: String
        get() = when (certainty) {
            "clear" -> "비교적 뚜렷함"
            "uncertain" -> "불확실함"
            else -> "판단 불가"
        }

    fun toJson(): String = JSONObject()
        .put("sound", sound).put("certainty", certainty)
        .put("evidence", evidence).put("alternative", alternative)
        .put("model", model).put("analyzedAt", analyzedAt).toString()

    companion object {
        fun fromJson(json: String): SoundAnalysis {
            val value = JSONObject(json)
            fun requiredText(key: String, limit: Int): String {
                val text = value.getString(key).trim()
                require(text.isNotEmpty() && text.length <= limit) { "분석 응답 형식이 올바르지 않습니다." }
                return text
            }
            val certainty = requiredText("certainty", 20)
            require(certainty in setOf("clear", "uncertain", "unknown"))
            return SoundAnalysis(
                sound = if (certainty == "unknown") "판단 불가" else requiredText("sound", 80),
                certainty = certainty,
                evidence = requiredText("evidence", 240),
                alternative = requiredText("alternative", 160),
                model = value.optString("model", GeminiSoundAnalyzer.MODEL),
                analyzedAt = value.optLong("analyzedAt", System.currentTimeMillis())
            )
        }

        fun fromJsonOrNull(json: String?): SoundAnalysis? =
            json?.let { runCatching { fromJson(it) }.getOrNull() }
    }
}
