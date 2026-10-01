package com.example

import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import com.example.report.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoiseReportTest {
    private fun time(value: String) = LocalDateTime.parse(value).atZone(NoiseReportBuilder.zone).toInstant().toEpochMilli()
    private fun record(id: Int, end: String, duration: Long = 5_000) = NoiseRecord(id, time(end), 116.0, 70.0, 0.0,
        true, true, false, "/private/clip-$id.wav", duration, note = "개인정보 메모", recordingSource = "ESP32")
    private fun report(records: List<NoiseRecord>, input: Map<String, String> = emptyMap(), masked: Boolean = true,
        measured: Boolean = false) = NoiseReportBuilder.build(records, input, emptyMap(), masked, measured, true,
        "KNOK-TEST", time("2026-10-01T12:00:00"), "1.0").plainText()

    @Test fun boundariesUseKoreanTimeAndDoNotDoubleCountCrossingClips() {
        assertEquals(NoiseReportBuilder.Period.NIGHT, NoiseReportBuilder.period(time("2026-10-01T06:00:00"), 5_000))
        assertEquals(NoiseReportBuilder.Period.DAY, NoiseReportBuilder.period(time("2026-10-01T06:00:05"), 5_000))
        assertEquals(NoiseReportBuilder.Period.DAY, NoiseReportBuilder.period(time("2026-10-01T22:00:00"), 5_000))
        assertEquals(NoiseReportBuilder.Period.BOUNDARY, NoiseReportBuilder.period(time("2026-10-01T22:00:02"), 5_000))
        assertEquals(NoiseReportBuilder.Period.NIGHT, NoiseReportBuilder.period(time("2026-10-02T00:00:02"), 5_000))
        assertEquals(NoiseReportBuilder.Period.BOUNDARY, NoiseReportBuilder.period(time("2026-10-02T12:00:00"), 86_400_000))
        assertEquals(NoiseReportBuilder.Period.UNKNOWN, NoiseReportBuilder.period(time("2026-10-01T12:00:00"), 0))
        val output = report(listOf(record(3, "2026-10-01T22:00:10"), record(1, "2026-10-01T12:00:00"), record(2, "2026-10-01T22:00:02")))
        assertTrue(output.contains("주간 녹음 파일 수 (추정): 1개"))
        assertTrue(output.contains("야간 녹음 파일 수 (추정): 1개"))
        assertTrue(output.contains("경계 시각을 걸친 파일 수 (추정): 1개"))
    }

    @Test fun legacyRecordsNeverBecomeLegalMeasurementsOrVerifiedTimestamps() {
        val output = report(listOf(record(1, "2026-10-01T12:00:00")))
        assertTrue(output.contains("녹음 시작일시: 미기록"))
        assertTrue(output.contains("녹음 종료일시: 미기록"))
        assertTrue(output.contains("녹음 방식: 미기록"))
        assertTrue(output.contains("법정 기준 초과 여부: 판정 불가"))
        assertFalse(output.contains("법정 기준 초과!"))
        assertFalse(output.contains("116.0 dB(A)"))
        assertTrue(output.contains("SHA-256: 미생성"))
        assertTrue(output.contains("사용자가 확인한 주요 소리: 미기록"))
    }

    @Test fun structuredPrivacyMaskAndObservationStaySeparateFromAutomaticNotes() {
        val r = record(1, "2026-10-01T12:00:00")
        val field = ReportFields.observations.first()
        val input = mapOf("author" to "홍길동", "contact" to "010-1234-5678", "location" to "개인 주소",
            "signature" to "홍길동", ReportFields.observationKey(r, field) to "직접 들은 짧은 충격음")
        val masked = report(listOf(r), input)
        assertFalse(masked.contains("홍길동")); assertFalse(masked.contains("010-1234-5678"))
        assertFalse(masked.contains("개인 주소")); assertFalse(masked.contains("개인정보 메모"))
        assertFalse(masked.contains("clip-1.wav"))
        assertTrue(masked.contains("직접 들은 짧은 충격음"))
        assertTrue(report(listOf(r), input, false).contains("홍길동"))
    }

    @Test fun aiQualitativeOutputIsNotInventedProbabilityAndInvalidJsonDoesNotCrash() {
        val r = record(1, "2026-10-01T12:00:00")
        val ai = SoundAnalysis("충격음", "uncertain", "짧은 충격음", "문 닫는 소리", analyzedAt = r.timestamp)
        val output = report(listOf(r.copy(soundAnalysisJson = ai.toJson())))
        assertTrue(output.contains("모델 출력 점수: 미기록 (수치 점수 없음)"))
        assertTrue(output.contains("모델의 정성 평가: 불확실함"))
        assertFalse(output.contains("90%"))
        assertTrue(report(listOf(r.copy(soundAnalysisJson = "broken"))).contains("미분석 또는 저장 결과 읽기 불가"))
        val older = org.json.JSONObject(ai.toJson()).apply { remove("model"); remove("analyzedAt") }.toString()
        assertTrue(report(listOf(r.copy(soundAnalysisJson = older))).contains("모델 버전: 미기록"))
        assertTrue(report(listOf(r.copy(soundAnalysisJson = older))).contains("분석일시: 미기록"))
    }

    @Test fun separatelyEnteredMeasurementsAreIncludedOnlyWithExplicitDeclaration() {
        val r = record(1, "2026-10-01T12:00:00")
        val values = mapOf("measuredValue" to "사용자 제공 결과 42 dB(A)")
        assertFalse(report(listOf(r), values).contains("사용자 제공 결과 42"))
        assertTrue(report(listOf(r), values, measured = true).contains("사용자 제공 결과 42 dB(A)"))
        assertTrue(report(listOf(r), values, measured = true).contains("KNOK의 검증 아님"))
    }

    @Test fun selectionDatesAreStrictAndEndMinuteIsInclusive() {
        val range = ReportSelection.range("2026-10-01 06:00", "2026-10-01 22:00")
        assertTrue(time("2026-10-01T22:00:59") in range)
        assertFalse(time("2026-10-01T22:01:00") in range)
        assertTrue(runCatching { ReportSelection.range("2026-02-30 00:00", "") }.isFailure)
        assertTrue(runCatching { ReportSelection.range("2026-10-02 00:00", "2026-10-01 00:00") }.isFailure)
    }

    @Test fun hashComesFromActualLocalBytesAndMissingAudioIsNotInvented() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.filesDir, "report-hash-test.m4a").apply { writeText("abc") }
        try {
            val r = record(1, "2026-10-01T12:00:00").copy(recordingPath = file.absolutePath)
            val remote = r.copy(id = 2, recordingPath = null, remoteAudioPath = "not-downloaded/clip.wav")
            val values = RecordingEvidenceLoader.collect(listOf(r, remote), true)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", values[1]!!.hash)
            assertTrue(values[1]!!.localFileAvailable)
            assertFalse(values[1]!!.fileDurationVerified)
            assertEquals(5_000L, values[1]!!.durationMs)
            assertNull(values[2]!!.hash)
            assertFalse(values[2]!!.localFileAvailable)
        } finally { file.delete() }
    }
}
