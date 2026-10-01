package com.example.report

import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReportField(val key: String, val label: String, val default: String = "미기록", val private: Boolean = false)
data class ReportSection(val title: String, val fields: List<ReportField>)

object ReportFields {
    val sections = listOf(
        ReportSection("1. 작성 및 제출 정보", listOf(
            ReportField("author", "작성자", "미기재", true), ReportField("contact", "연락처", "미기재", true),
            ReportField("recipient", "제출 대상"), ReportField("purpose", "작성 목적"),
            ReportField("housing", "주택 유형", "미확인"), ReportField("location", "기록 장소", "미기재", true),
            ReportField("room", "실내 위치")
        )),
        ReportSection("2. 기록 장비 및 환경", listOf(
            ReportField("hardware", "장비 상세 사양", "미확인"), ReportField("firmware", "기기 펌웨어 버전"),
            ReportField("deviceId", "기기 식별번호", "미기록", true), ReportField("placement", "마이크 설치 위치·높이"),
            ReportField("mount", "설치 방식"), ReportField("clock", "기기 시각 확인 여부", "미확인"),
            ReportField("calibration", "음압 교정 및 측정방법 검증 여부", "미확인"),
            ReportField("windows", "창문·문 상태"), ReportField("occupancy", "기록 중 재실 여부"),
            ReportField("activities", "실내에서 사용한 기기·생활 활동"), ReportField("external", "외부 공사·도로 등 다른 소음원")
        )),
        ReportSection("3. 사용자 확인 요약", listOf(
            ReportField("gaps", "녹음 누락·중단 구간", "확인되지 않음"),
            ReportField("mainSounds", "사용자가 확인한 주요 소리"), ReportField("repeatedTimes", "반복적으로 관찰한 시간대")
        )),
        ReportSection("5. 소음 유형 및 적용 범위 검토", listOf(
            ReportField("type", "검토 대상", "유형 미확인"), ReportField("basis", "검토 근거"),
            ReportField("otherCauses", "급수·배수, 기계 작동, 공사 등 다른 원인 가능성", "미확인"),
            ReportField("additional", "추가 확인이 필요한 사항")
        )),
        ReportSection("6. 별도 측정자료 (있을 때만 입력)", listOf(
            ReportField("measurer", "측정기관·측정자"), ReportField("measurementId", "측정결과서 번호·작성일"),
            ReportField("measurementEquipment", "측정 장비·교정 확인자료"), ReportField("method", "측정방법 및 기준 버전"),
            ReportField("measurementPlace", "측정 위치·연속 측정시간"), ReportField("background", "배경소음 보정·제외 구간"),
            ReportField("measurementPeriod", "측정자료의 주간·야간 구분"), ReportField("exception", "건축허가·사업승인 관련 예외 적용 여부", "미확인"),
            ReportField("exceptionBasis", "예외 적용 판단 근거자료"), ReportField("metric", "측정 지표"),
            ReportField("measuredValue", "측정값 (측정자료 그대로, 단위 포함)"), ReportField("legalValue", "적용 기준값 (확인자료 그대로, 단위 포함)"),
            ReportField("lmaxCount", "Lmax 평가 시 1시간 내 기준 초과 횟수"), ReportField("evaluation", "측정결과서에 기재된 평가")
        )),
        ReportSection("7. 기존 상담·조치 및 요청 사항", listOf(
            ReportField("priorContact", "이전에 알린 일시·대상", "없음"), ReportField("response", "답변이나 조치", "없음"),
            ReportField("change", "조치 후 관찰한 변화", "미확인"), ReportField("requestType", "요청 사항"), ReportField("request", "요청 내용")
        )),
        ReportSection("8. 첨부자료 및 작성 확인", listOf(
            ReportField("photos", "설치 위치 사진·도면 별도 제출 내역", "없음"),
            ReportField("contacts", "기존 상담·조치 내역 별도 제출 내역", "없음"),
            ReportField("measurements", "별도 측정결과서 제출 내역", "없음"),
            ReportField("editing", "녹음 파일의 편집 여부", "미확인"), ReportField("editReason", "편집 범위·이유", "미확인"),
            ReportField("originals", "원본 보관 여부", "미확인"), ReportField("privacy", "개인정보 포함 여부 및 제출본 처리 내용", "미확인"),
            ReportField("submission", "제출 대상·목적 확인", "미확인"), ReportField("signature", "작성자 확인 (이름 또는 서명 표기)", "미기재", true)
        ))
    )
    val observations = listOf(
        ReportField("features", "직접 들은 소리의 특징"), ReportField("duration", "소음이 이어졌다고 관찰한 시간", "미확인"),
        ReportField("direction", "청취 방향", "미확인"), ReportField("source", "발생 장소·세대 확인 여부와 근거", "미확인"),
        ReportField("activity", "같은 시간의 실내 활동·다른 소음"), ReportField("impact", "생활에 미친 영향")
    )
    fun observationKey(record: NoiseRecord, field: ReportField) = "observation:${record.syncId ?: record.id}:${field.key}"
}

enum class ReportBlockKind { TITLE, SECTION, SUBHEADING, BODY, NOTE }
data class ReportBlock(val text: String, val kind: ReportBlockKind = ReportBlockKind.BODY)
data class RecordingEvidence(val durationMs: Long, val fileDurationVerified: Boolean = false, val hash: String? = null,
    val localFileAvailable: Boolean = false)
data class NoiseReport(val number: String, val createdAt: Long, val blocks: List<ReportBlock>) {
    fun plainText() = blocks.joinToString("\n\n") { it.text }
}

object NoiseReportBuilder {
    const val FORM_VERSION = "1.0"
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.KOREA).withZone(zone)
    fun time(ms: Long): String = if (ms > 0) formatter.format(Instant.ofEpochMilli(ms)) else "미기록"
    fun seconds(ms: Long): String = if (ms > 0) String.format(Locale.US, "%.3f초", ms / 1000.0) else "미기록"
    enum class Period(val label: String) { DAY("주간"), NIGHT("야간"), BOUNDARY("경계 시각을 걸침"), UNKNOWN("미확인") }
    // End is exclusive: a clip ending exactly at 22:00 belongs to the preceding interval.
    fun period(end: Long, duration: Long): Period {
        if (end <= 0 || duration <= 0 || duration >= end) return Period.UNKNOWN
        val start = Instant.ofEpochMilli(end - duration).atZone(zone)
        val boundary = if (start.hour < 6) start.toLocalDate().atTime(6, 0).atZone(zone)
            else if (start.hour < 22) start.toLocalDate().atTime(22, 0).atZone(zone)
            else start.toLocalDate().plusDays(1).atTime(6, 0).atZone(zone)
        if (end > boundary.toInstant().toEpochMilli()) return Period.BOUNDARY
        return if (start.hour in 6..21) Period.DAY else Period.NIGHT
    }
    fun filename(record: NoiseRecord): String = (record.recordingPath ?: record.remoteAudioPath)
        ?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "미기록"

    fun build(records: List<NoiseRecord>, input: Map<String, String>, evidence: Map<Int, RecordingEvidence>,
        masked: Boolean, measurementProvided: Boolean, reviewed: Boolean, number: String, createdAt: Long, appVersion: String): NoiseReport {
        require(records.isNotEmpty()) { "녹음 기록을 선택해 주세요." }
        val sorted = records.sortedWith(compareBy<NoiseRecord> { it.timestamp }.thenBy { it.id })
        val out = mutableListOf<ReportBlock>()
        fun text(t: String, kind: ReportBlockKind = ReportBlockKind.BODY) { out += ReportBlock(t, kind) }
        fun note(t: String) = text("※ $t", ReportBlockKind.NOTE)
        fun value(f: ReportField, key: String = f.key): String {
            val v = input[key]?.trim().orEmpty()
            return if (masked && f.private && v.isNotEmpty()) "[가림]" else v.ifBlank { f.default }
        }
        fun fields(index: Int) { ReportFields.sections[index].fields.forEach { text("${it.label}: ${value(it)}") } }
        fun length(r: NoiseRecord) = evidence[r.id]?.durationMs ?: r.durationMs
        text("KNOK 소음 발생기록 및 상담 참고보고서", ReportBlockKind.TITLE)
        text("보고서 번호: $number\n작성일시: ${time(createdAt)} (한국시간)\n기록 대상 기간: ${time(sorted.first().timestamp)} ~ ${time(sorted.last().timestamp)}\n출력 앱 버전: $appVersion / 기기 펌웨어 버전: ${input["firmware"].orEmpty().ifBlank { "미기록" }}\n보고서 양식 버전: $FORM_VERSION")
        note("기록 대상 기간과 선택 기준은 앱 저장일시입니다. 녹음 당시 앱 버전은 미기록입니다.")
        text("이 보고서는 KNOK가 저장한 기록과 사용자의 관찰 내용을 정리한 상담 참고자료입니다. 전문기관의 측정결과서와 구분되며, KNOK 기록만으로 법정 기준 초과 여부, 소음 발생 세대 또는 법적 책임을 확정하지 않습니다.")
        text("1. 작성 및 제출 정보", ReportBlockKind.SECTION); fields(0)
        note("공개하거나 시연하는 사본에서는 이름, 연락처, 상세 주소 등 개인 식별정보를 가립니다.")
        text("2. 기록 장비 및 환경", ReportBlockKind.SECTION)
        text("녹음 장비: ${sorted.map { if (it.recordingSource == "ESP32") "ESP32 외장 마이크 (상세 사양 미확인)" else if (it.recordingSource == "PHONE") "휴대폰 마이크" else "기타 (${it.recordingSource})" }.distinct().joinToString(", ")}")
        fields(1); note("기기 위치, 재실 상태, 실내 활동 등이 기록에 영향을 줄 수 있으므로 확인한 사실만 작성합니다.")
        text("3. 기록 범위 및 요약", ReportBlockKind.SECTION)
        val total = sorted.sumOf { length(it).coerceAtLeast(0) }
        val periods = sorted.groupingBy { period(it.timestamp, length(it)) }.eachCount()
        text("저장된 녹음 파일 참조 수: ${sorted.size}개\n녹음 구간 길이 합계: ${total / 60000}분 ${String.format(Locale.US, "%.3f", total % 60000 / 1000.0)}초\n주간 녹음 파일 수 (추정): ${periods[Period.DAY] ?: 0}개\n야간 녹음 파일 수 (추정): ${periods[Period.NIGHT] ?: 0}개\n경계 시각을 걸친 파일 수 (추정): ${periods[Period.BOUNDARY] ?: 0}개\n시간 구분 미확인: ${periods[Period.UNKNOWN] ?: 0}개")
        text("파일 길이 확인: ${sorted.count { evidence[it.id]?.fileDurationVerified == true }}개 / 로컬 파일 접근 가능: ${sorted.count { evidence[it.id]?.localFileAvailable == true }}개")
        note("길이는 접근 가능한 로컬 파일의 메타데이터를 우선 사용하며, 나머지는 앱 저장 길이를 사용합니다. 미기록 길이는 합계에서 제외하고 중복·겹친 구간은 합산합니다.")
        note("주간 06:00~22:00, 야간 22:00~06:00 (한국시간). 저장일시를 종료 시각으로 가정한 추정 구분이며, ESP32 다운로드 지연·기기 시각 오차로 실제 녹음 시각과 다를 수 있습니다.")
        fields(2)
        note("녹음 파일 수는 소음 발생 횟수와 같지 않을 수 있습니다. 녹음 길이를 실제 소음의 전체 지속시간으로 간주하지 않습니다. 녹음하지 않은 시간의 소음 유무는 이 보고서로 확인할 수 없습니다.")
        text("4. 소음 발생일지", ReportBlockKind.SECTION)
        sorted.forEachIndexed { index, r ->
            val e = evidence[r.id]
            val ms = length(r)
            text("기록 ${String.format(Locale.US, "%03d", index + 1)}", ReportBlockKind.SUBHEADING)
            text("가. 자동 저장 정보", ReportBlockKind.SUBHEADING)
            text("녹음 시작일시: 미기록\n녹음 종료일시: 미기록\n앱 저장일시: ${time(r.timestamp)} (한국시간)\n참고 구간 (저장일시·길이로 계산): ${if (ms > 0 && r.timestamp > ms) time(r.timestamp - ms) else "미확인"} ~ ${time(r.timestamp)}\n주간·야간 구분 (추정): ${period(r.timestamp, ms).label}\n녹음 파일명: ${if (masked) "[가림 · 기록 ${index + 1}]" else filename(r)}\n녹음 길이: ${seconds(ms)} (${if (e?.fileDurationVerified == true) "로컬 파일 메타데이터 확인" else "앱 저장 길이; 파일 길이 미검증"})\n녹음 방식: 미기록\n기기 상대 음량값: 평균 ${relative(r.avgDb)}, 최댓값 ${relative(r.maxDb)}")
            text("음량값 측정 방식·단위: ${if (r.recordingSource == "ESP32") "ESP32 PCM 진폭 기반 상대 레벨; 음압 교정 여부 미확인" else "휴대폰 마이크 진폭의 로그 변환값 (앱 표시 범위 30~95); 음압 교정 여부 미확인"}. 검증된 dB(A), Leq, Lmax와 구분합니다.")
            note("참고 구간은 실제 시작·종료 시각을 증명하지 않습니다.")
            text("나. AI 분석 정보", ReportBlockKind.SUBHEADING)
            val ai = SoundAnalysis.fromJsonOrNull(r.soundAnalysisJson)
            if (ai != null) {
                val raw = JSONObject(r.soundAnalysisJson!!)
                text("추정 소리 종류 (저장된 분석): ${ai.sound}\n모델 출력 점수: 미기록 (수치 점수 없음)\n모델의 정성 평가: ${ai.certaintyLabel}\n분석 근거: ${ai.evidence}\n다른 가능성: ${ai.alternative}\n분석 구간: 전체 제출 파일 (저장된 구간 타임코드 없음)\n모델 버전: ${raw.optString("model").ifBlank { "미기록" }}\n분석일시: ${time(raw.optLong("analyzedAt", 0))} (한국시간)")
            } else text("추정 소리 종류: 미분석 또는 저장 결과 읽기 불가\n모델 출력 점수: 미기록\n분석한 녹음 구간: 미기록\n모델 버전: 미기록")
            if (r.classificationLabel != "unclassified") {
                text("별도 저장된 기기 내 분류: ${r.classificationLabel}\n기기 내 모델 출력 점수: ${if (r.classificationConfidence.isFinite() && r.classificationConfidence in 0.0..1.0) String.format(Locale.US, "%.4f", r.classificationConfidence) else "미기록"} (검증된 확률 아님)\n기기 내 모델 버전·분석 구간: 미기록")
            }
            text("다. 사용자 관찰", ReportBlockKind.SUBHEADING)
            ReportFields.observations.forEach { text("${it.label}: ${value(it, ReportFields.observationKey(r, it))}") }
            text("기존 앱 메모 (직접 관찰 여부 미확인): ${if (masked && r.note.isNotBlank()) "[가림]" else r.note.ifBlank { "미기록" }}")
            note("AI 분류와 점수는 모델의 추정 결과입니다. 법률상 소음 유형, 발생 세대, 고의 또는 피해를 확정하지 않습니다.")
        }
        text("5. 소음 유형 및 적용 범위 검토", ReportBlockKind.SECTION); fields(3)
        note("AI가 소리 종류를 분류했다는 이유만으로 법령상 층간소음에 해당한다고 확정하지 않습니다. 기계 작동음과 기기 사용 중 발생하는 충격·마찰음 등은 원인과 상황을 구분하여 상담기관에 확인합니다.")
        text("6. 법정 기준 관련 측정자료", ReportBlockKind.SECTION)
        text("측정자료 유무: ${if (measurementProvided) "있음 (사용자 입력; KNOK의 검증 아님)" else "없음"}")
        if (measurementProvided) fields(4)
        else text("법정 기준 초과 여부: 판정 불가. 공정시험기준에 따른 별도 측정자료가 확보되지 않았음.")
        note("짧은 녹음의 음량값, 화면의 평균·최댓값 또는 녹음 파일 개수를 법정 평가 지표로 대신하지 않습니다.")
        text("7. 기존 상담·조치 및 요청 사항", ReportBlockKind.SECTION); fields(5)
        text("8. 첨부자료 및 작성 확인", ReportBlockKind.SECTION)
        text("첨부 1. 소음 발생일지: ${sorted.size}건 (이 보고서의 4항)\n첨부 2. 녹음 파일 목록: ${sorted.size}개 (아래 목록; 오디오 파일 자체는 PDF에 포함되지 않음)")
        fields(6)
        text("파일 해시 (SHA-256): 아래 목록에 실제 생성한 값만 기재합니다. 파일 해시는 생성 시점의 내용 식별값이며 원본성·발생 사실을 인증하지 않습니다.")
        text("출력본 개인정보 처리: ${if (masked) "작성자·연락처·장소·식별번호·확인 이름·파일명·기존 앱 메모 가림. 자유 입력 내용은 작성자의 별도 검토 필요." else "개인정보 가림 꺼짐. 제출 전 입력 내용 검토 필요."}")
        text(if (reviewed) "작성자는 자동 기록과 직접 작성한 관찰 내용을 검토하였으며, 확인되지 않은 사항은 미확인으로 표시하였습니다."
            else "작성자 검토: 아직 확인하지 않음 (미리보기)")
        text("확인일: ${time(createdAt).substringBefore(' ')} (한국시간)")
        text("첨부 2. 녹음 파일 목록", ReportBlockKind.SECTION)
        sorted.forEachIndexed { index, r ->
            val e = evidence[r.id]
            text("기록 ${index + 1}: ${if (masked) "[파일명 가림]" else filename(r)}\n저장일시: ${time(r.timestamp)} / 길이: ${seconds(length(r))}\n파일 접근 상태: ${if (e?.localFileAvailable == true) "로컬 파일 확인" else "로컬 파일 미확인; 원격 참조 여부만 저장됨"}\nSHA-256: ${e?.hash ?: "미생성"}")
        }
        note("사진·상담내역·별도 측정자료와 오디오 파일은 실제 제출한 자료만 별도로 첨부하세요.")
        note("시간대 구분 참고: 공동주택 층간소음의 범위와 기준에 관한 규칙 별표. https://law.go.kr/LSW/flDownload.do?bylClsCd=110201&flSeq=123540013 (2026-10-01 확인). 기준값 비교는 수행하지 않습니다.")
        return NoiseReport(number, createdAt, out)
    }
    private fun relative(value: Double) = if (value.isFinite()) String.format(Locale.US, "%.1f (기기 상대값)", value) else "미기록"
}
