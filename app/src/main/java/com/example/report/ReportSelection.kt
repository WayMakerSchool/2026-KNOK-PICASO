package com.example.report

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object ReportSelection {
    const val MAX_RECORDS = 100
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    fun range(start: String, end: String): LongRange {
        fun parse(value: String) = LocalDateTime.parse(value.trim(), format).atZone(NoiseReportBuilder.zone).toInstant().toEpochMilli()
        val from = if (start.isBlank()) 0L else parse(start)
        val to = if (end.isBlank()) Long.MAX_VALUE else Math.addExact(parse(end), 59_999L)
        require(from <= to) { "시작일시가 종료일시보다 늦습니다." }
        return from..to
    }
}
