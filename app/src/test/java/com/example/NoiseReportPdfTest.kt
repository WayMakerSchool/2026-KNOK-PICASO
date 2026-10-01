package com.example

import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import com.example.report.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoiseReportPdfTest {
    @Test fun multiPageKoreanReportProducesPdfForVisualInspection() {
        val now = 1790812800000L
        val records = (1..3).map { id -> NoiseRecord(id, now + id * 5000L, 70.0, 45.0, 0.0,
            false, false, false, "/sample/phone-$id.m4a", 5000L,
            soundAnalysisJson = SoundAnalysis("충격음", "uncertain", "짧고 둔탁한 소리가 반복됩니다.", "문 닫는 소리", analyzedAt = now + 30_000).toJson()) }
        val input = mapOf("recipient" to "관리사무소", "purpose" to "중재 상담 요청", "housing" to "아파트", "room" to "거실",
            "request" to "기록을 검토하고 원인 확인과 소음 저감에 관한 상담을 요청합니다.",
            ReportFields.observationKey(records.first(), ReportFields.observations.first()) to "짧은 충격음을 직접 들었습니다. ".repeat(60))
        val report = NoiseReportBuilder.build(records, input, emptyMap(), true, false, true, "KNOK-SAMPLE-20261001", now + 60_000, "1.0 (1)")
        val file = File("build/outputs/reports/knok-report-sample.pdf")
        val pages = NoiseReportPdf.write(ApplicationProvider.getApplicationContext(), report, file)
        assertTrue(pages >= 5)
        assertTrue(file.length() > 1000)
        assertEquals("%PDF-", file.inputStream().use { String(it.readNBytes(5), Charsets.US_ASCII) })
        File(file.parentFile, "knok-report-sample.json").writeText(JSONArray(report.blocks.map {
            JSONObject().put("kind", it.kind.name).put("text", it.text)
        }).toString())
    }

    @Test fun sharingGrantsOnlyTheReportFileAndRejectsPrivateFiles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // AndroidX uses '/' for FileProvider roots; Windows JVM paths use '\\'. Verify the
        // packaged provider configuration and URI grants here; device test checks actual I/O.
        val uri = android.net.Uri.parse("content://${context.packageName}.report-files/noise_reports/test.pdf")
        val intent = NoiseReportSharing.forUri(context, uri)
        assertEquals(android.content.Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".report-files", uri.authority)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val provider = context.packageManager.resolveContentProvider(uri.authority!!, android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        provider.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS").use { parser ->
            val roots = mutableListOf<String>()
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name != "paths") {
                    roots += parser.name + ":" + parser.getAttributeValue(null, "name") + ":" + parser.getAttributeValue(null, "path")
                }
            }
            assertEquals(listOf("cache-path:noise_reports:noise-reports/"), roots)
        }
        assertTrue(runCatching { NoiseReportSharing.forUri(context, android.net.Uri.parse("content://${context.packageName}.report-files/private/data")) }.isFailure)
        val outside = File(context.filesDir, "private-data.txt").apply { writeText("private") }
        assertTrue(runCatching { NoiseReportSharing.intent(context, outside) }.isFailure)
    }
}
