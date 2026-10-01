package com.example.report

import android.content.Context
import android.media.MediaMetadataRetriever
import com.example.data.NoiseRecord
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import kotlin.coroutines.coroutineContext

object RecordingEvidenceLoader {
    suspend fun collect(records: List<NoiseRecord>, hashFiles: Boolean): Map<Int, RecordingEvidence> = withContext(Dispatchers.IO) {
        records.associate { record ->
            coroutineContext.ensureActive()
            val file = record.recordingPath?.let(::File)?.takeIf { it.isFile }
            var verified = false
            val duration = file?.let {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(it.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?.takeIf { ms -> ms > 0 }?.also { verified = true }
                } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
            }
            val hash = if (hashFiles && file != null) try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
            coroutineContext.ensureActive()
            record.id to RecordingEvidence(duration ?: record.durationMs, verified, hash, file != null)
        }
    }
}

/** Font-embedded A4 PDF. This identical renderer runs on Android and in JVM verification. */
object NoiseReportPdf {
    private const val WIDTH = 595f
    private const val HEIGHT = 842f
    private const val MARGIN = 42f
    private const val TOP = 68f
    private const val BOTTOM = 786f
    private data class Row(val text: String, val font: PDType0Font, val size: Float, val color: IntArray, val y: Float)

    @Synchronized
    fun write(context: Context, report: NoiseReport, file: File): Int {
        PDFBoxResourceLoader.init(context.applicationContext)
        file.parentFile?.mkdirs()
        try {
            PDDocument().use { document ->
                val regular = context.assets.open("report-fonts/NanumGothic-Regular.ttf").use { PDType0Font.load(document, it, true) }
                val bold = context.assets.open("report-fonts/NanumGothic-Bold.ttf").use { PDType0Font.load(document, it, true) }
                val pages = mutableListOf(mutableListOf<Row>())
                var y = TOP
                var unsupportedCharacters = false
                val encoded = mutableMapOf<Pair<Boolean, Int>, String>()
                fun supported(text: String, isBold: Boolean): String = buildString {
                    val font = if (isBold) bold else regular
                    text.codePoints().forEach { cp ->
                        if (cp == 10) append('\n') else if (cp == 9) append("    ") else append(encoded.getOrPut(isBold to cp) {
                            val token = String(Character.toChars(cp))
                            try { font.encode(token); token } catch (_: IllegalArgumentException) {
                                unsupportedCharacters = true; "[U+${cp.toString(16).uppercase()}]"
                            }
                        })
                    }
                }
                val widths = mutableMapOf<Pair<PDType0Font, String>, Float>()
                fun width(text: String, font: PDType0Font, size: Float): Float =
                    widths.getOrPut(font to text) { font.getStringWidth(text) / 1000f } * size
                fun wrap(text: String, font: PDType0Font, size: Float): List<String> {
                    val rows = mutableListOf<String>()
                    text.split('\n').forEach { paragraph ->
                        var current = ""
                        var used = 0f
                        paragraph.codePoints().forEach { cp ->
                            val token = String(Character.toChars(cp))
                            val tokenWidth = width(token, font, size)
                            if (current.isNotEmpty() && used + tokenWidth > WIDTH - MARGIN * 2) {
                                val space = current.lastIndexOf(' ')
                                if (space > 0) {
                                    rows += current.substring(0, space)
                                    current = current.substring(space + 1)
                                } else { rows += current; current = "" }
                                used = width(current, font, size)
                                if (used + tokenWidth > WIDTH - MARGIN * 2) {
                                    rows += current; current = ""; used = 0f
                                }
                            }
                            current += token; used += tokenWidth
                        }
                        rows += current
                    }
                    return rows
                }
                fun addBlock(block: ReportBlock) {
                    val isBold = block.kind in setOf(ReportBlockKind.TITLE, ReportBlockKind.SECTION, ReportBlockKind.SUBHEADING)
                    val font = if (isBold) bold else regular
                    val size = when (block.kind) {
                        ReportBlockKind.TITLE -> 20f
                        ReportBlockKind.SECTION -> 14f
                        ReportBlockKind.SUBHEADING -> 11.5f
                        ReportBlockKind.NOTE -> 9f
                        else -> 10.5f
                    }
                    val color = when (block.kind) {
                        ReportBlockKind.TITLE, ReportBlockKind.SECTION -> intArrayOf(20, 61, 90)
                        ReportBlockKind.NOTE -> intArrayOf(85, 95, 108)
                        else -> intArrayOf(24, 32, 42)
                    }
                    val gap = if (block.kind in setOf(ReportBlockKind.TITLE, ReportBlockKind.SECTION)) 14f else 7f
                    val lineHeight = size * 1.55f
                    val keepNext = when {
                        block.kind == ReportBlockKind.SUBHEADING && block.text.startsWith("기록 ") -> 65f
                        block.kind == ReportBlockKind.SECTION && block.text.startsWith("4.") -> 90f
                        block.kind in setOf(ReportBlockKind.SECTION, ReportBlockKind.SUBHEADING) -> 25f
                        else -> 0f
                    }
                    if (y > TOP && y + gap + lineHeight + keepNext > BOTTOM) {
                        pages.add(mutableListOf()); y = TOP
                    }
                    y += gap
                    wrap(supported(block.text, isBold), font, size).forEach { line ->
                        if (y + lineHeight > BOTTOM) { pages.add(mutableListOf()); y = TOP }
                        pages.last() += Row(line, font, size, color, y)
                        y += lineHeight
                    }
                }
                report.blocks.forEach(::addBlock)
                if (unsupportedCharacters) addBlock(ReportBlock("※ 글꼴에 없는 문자는 [U+코드]로 보존했습니다. 원래 입력 문자의 유니코드 표기입니다.", ReportBlockKind.NOTE))
                document.documentInformation.apply {
                    title = "KNOK 소음 발생기록 및 상담 참고보고서"
                    creator = "KNOK"
                    subject = "${report.number} / 상담 참고자료"
                    creationDate = Calendar.getInstance().apply { timeInMillis = report.createdAt }
                }
                pages.forEachIndexed { index, rows ->
                    val page = PDPage(PDRectangle(WIDTH, HEIGHT))
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        fun text(value: String, x: Float, top: Float, font: PDType0Font, size: Float, rgb: IntArray) {
                            stream.beginText(); stream.setFont(font, size)
                            stream.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
                            stream.newLineAtOffset(x, HEIGHT - top - size)
                            stream.showText(value); stream.endText()
                        }
                        val muted = intArrayOf(88, 102, 115)
                        text("KNOK | 상담 참고자료", MARGIN, 22f, regular, 8f, muted)
                        text(report.number, MARGIN, 36f, regular, 8f, muted)
                        stream.setStrokingColor(180, 195, 205); stream.setLineWidth(0.5f)
                        stream.moveTo(MARGIN, HEIGHT - 53f); stream.lineTo(WIDTH - MARGIN, HEIGHT - 53f); stream.stroke()
                        rows.forEach { text(it.text, MARGIN, it.y, it.font, it.size, it.color) }
                        stream.moveTo(MARGIN, HEIGHT - 801f); stream.lineTo(WIDTH - MARGIN, HEIGHT - 801f); stream.stroke()
                        text("KNOK 기록만으로 법정 기준 초과·발생 세대·책임을 확정하지 않습니다.", MARGIN, 809f, regular, 8f, muted)
                        val counter = "${index + 1} / ${pages.size}"
                        text(counter, WIDTH - MARGIN - width(counter, regular, 8f), 824f, regular, 8f, muted)
                    }
                }
                document.save(file)
                return pages.size
            }
        } catch (error: Exception) { file.delete(); throw error }
    }

    suspend fun create(context: Context, report: NoiseReport): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "noise-reports").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val file = File(folder, "${report.number}.pdf")
        write(context, report, file)
        coroutineContext.ensureActive()
        file
    }
}
