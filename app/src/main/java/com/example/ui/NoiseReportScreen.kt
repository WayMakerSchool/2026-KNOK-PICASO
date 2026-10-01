package com.example.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.auth.AccountSession
import com.example.data.NoiseRecord
import com.example.report.*
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

private data class ReportSnapshot(
    val records: List<NoiseRecord>, val input: Map<String, String>, val evidence: Map<Int, RecordingEvidence>,
    val masked: Boolean, val measured: Boolean, val number: String, val createdAt: Long
) {
    fun report(reviewed: Boolean) = NoiseReportBuilder.build(records, input, evidence, masked, measured, reviewed,
        number, createdAt, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
}

@Composable
fun NoiseReportScreen(records: List<NoiseRecord>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner by AccountSession.userId.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var inputJson by rememberSaveable { mutableStateOf("{}") }
    var masked by rememberSaveable { mutableStateOf(true) }
    var hashFiles by rememberSaveable { mutableStateOf(false) }
    var measured by rememberSaveable { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<ReportSnapshot?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val recordings = records.filter { it.userId == owner && (it.recordingPath != null || it.remoteAudioPath != null) }
    LaunchedEffect(recordings) {
        if (!initialized && recordings.isNotEmpty()) {
            selectedIds = recordings.take(ReportSelection.MAX_RECORDS).map { it.id }
            initialized = true
        }
    }
    val range = remember(start, end) { runCatching { ReportSelection.range(start, end) } }
    val available = recordings.filter { range.getOrNull()?.contains(it.timestamp) == true }
    val chosen = available.filter { it.id in selectedIds }
    val input = remember(inputJson) {
        val json = JSONObject(inputJson)
        json.keys().asSequence().associateWith { json.getString(it) }
    }
    fun update(key: String, value: String) {
        val next = JSONObject(inputJson).put(key, value.take(2000)).toString()
        if (next.length <= 150_000) inputJson = next
        else status = "입력 내용이 너무 많습니다. 보고서를 나누어 작성해 주세요."
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val path = pendingPath
        if (uri != null && path != null && pendingOwner == AccountSession.userId.value) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("저장 위치를 열 수 없습니다.")
                        output.use { stream -> File(path).inputStream().use { it.copyTo(stream) } }
                    }
                    status = "PDF를 저장했습니다."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "PDF를 저장하지 못했습니다. 저장 위치를 확인해 주세요." }
                finally { busy = false }
            }
        }
    }
    fun export(share: Boolean) {
        val current = snapshot ?: return
        val exportOwner = owner
        scope.launch {
            busy = true; status = null
            try {
                check(reviewed) { "보고서 내용을 검토해 주세요." }
                val file = NoiseReportPdf.create(context, current.report(true))
                check(exportOwner == AccountSession.userId.value) { "계정이 변경되었습니다. 다시 작성해 주세요." }
                if (share) {
                    context.startActivity(Intent.createChooser(NoiseReportSharing.intent(context, file), "KNOK 보고서 공유"))
                } else {
                    pendingPath = file.absolutePath; pendingOwner = exportOwner
                    savePdf.launch(file.name)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "보고서를 만들지 못했습니다. 다시 시도해 주세요." }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy) { if (step > 0) { step--; status = null } else onBack() } }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (step > 0) { step--; status = null } else onBack() }, enabled = !busy) { Text("뒤로") }
            Text("상담 참고보고서", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(listOf("1. 녹음 선택", "2. 작성·관찰", "3. 미리보기")[step], color = QuietPrimary, fontSize = 13.sp)
        status?.let { Text(it, color = QuietPrimary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> {
                    item {
                        Text("저장일시 기준으로 기간과 녹음을 선택하세요. 한 보고서에 최대 ${ReportSelection.MAX_RECORDS}개를 포함할 수 있습니다.", color = TextSecondary, fontSize = 12.sp)
                        ReportInput("시작일시 (선택)", start, "예: 2026-10-01 00:00", !busy) { start = it }
                        ReportInput("종료일시 (선택)", end, "예: 2026-10-01 23:59", !busy) { end = it }
                        if (range.isFailure) Text("날짜 형식과 순서를 확인해 주세요. YYYY-MM-DD HH:mm", color = QuietAlert, fontSize = 12.sp)
                        Text("${chosen.size}개 선택 / 기간 내 ${available.size}개", color = TextPrimary)
                        TextButton(onClick = {
                            selectedIds = if (chosen.isNotEmpty()) emptyList() else available.take(ReportSelection.MAX_RECORDS).map { it.id }
                        }, enabled = !busy && available.isNotEmpty()) { Text(if (chosen.isNotEmpty()) "선택 해제" else "기간 내 선택") }
                    }
                    items(available, key = { "record-${it.id}" }) { record ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            selectedIds = if (record.id in selectedIds) selectedIds - record.id
                                else if (chosen.size < ReportSelection.MAX_RECORDS) selectedIds + record.id else selectedIds
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(record.id in selectedIds, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(NoiseReportBuilder.time(record.timestamp), color = TextPrimary, fontSize = 13.sp)
                                Text("${if (record.recordingSource == "ESP32") "ESP32" else "휴대폰"} · ${NoiseReportBuilder.seconds(record.durationMs)} · ${if (record.soundAnalysisJson != null) "분석 결과 저장됨" else "저장된 분석 없음"}", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                    if (available.isEmpty()) item { Text("선택 가능한 녹음이 없습니다.", color = TextSecondary) }
                }
                1 -> {
                    item {
                        Text("확인한 사실만 입력하세요. 빈 항목은 미기록·미확인으로 출력됩니다. AI 분석은 저장된 결과만 사용합니다.", color = TextSecondary, fontSize = 12.sp)
                        ReportToggle("공개·시연용 개인정보 가림", masked, !busy) { masked = it }
                        Text("이름·연락처·장소·식별번호·확인 이름·파일명·기존 메모를 가립니다. 아래 자유 입력에 포함된 개인정보는 직접 검토하세요.", color = TextSecondary, fontSize = 11.sp)
                        ReportToggle("로컬 녹음 파일 SHA-256 생성", hashFiles, !busy) { hashFiles = it }
                        Text("휴대폰에 없는 파일은 해시를 생성하지 않습니다. 오디오와 별도 첨부자료 자체는 PDF에 포함되지 않습니다.", color = TextSecondary, fontSize = 11.sp)
                    }
                    items(ReportFields.sections, key = { it.title }) { section ->
                        Column {
                            TextButton(onClick = { expanded = if (expanded == section.title) "" else section.title }, enabled = !busy) {
                                Text("${if (expanded == section.title) "▾" else "▸"} ${section.title}")
                            }
                            if (expanded == section.title) {
                                val isMeasurement = section.title.startsWith("6.")
                                if (isMeasurement) ReportToggle("별도의 측정자료가 있음", measured, !busy) { measured = it }
                                if (!isMeasurement || measured) section.fields.forEach { field ->
                                    ReportInput(field.label, input[field.key].orEmpty(), field.default, !busy) { update(field.key, it) }
                                } else Text("KNOK 기록만으로 법정 기준 초과 여부는 판정하지 않습니다.", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    item { Text("4. 기록별 사용자 관찰", color = TextPrimary, fontWeight = FontWeight.Bold) }
                    items(chosen, key = { "observe-${it.id}" }) { record ->
                        val key = "observe-${record.id}"
                        Column {
                            TextButton(onClick = { expanded = if (expanded == key) "" else key }, enabled = !busy) {
                                Text("${if (expanded == key) "▾" else "▸"} ${NoiseReportBuilder.time(record.timestamp)}")
                            }
                            if (expanded == key) ReportFields.observations.forEach { field ->
                                val fieldKey = ReportFields.observationKey(record, field)
                                ReportInput(field.label, input[fieldKey].orEmpty(), field.default, !busy) { update(fieldKey, it) }
                            }
                        }
                    }
                }
                2 -> {
                    val preview = snapshot?.report(reviewed)
                    if (preview != null) {
                        item {
                            Text("선택한 기록과 입력 내용의 고정 사본입니다. 변경하려면 뒤로 이동해 미리보기를 다시 만드세요.", color = TextSecondary, fontSize = 11.sp)
                            ReportToggle("자동 기록·관찰 내용과 개인정보를 검토했습니다", reviewed, !busy) { reviewed = it }
                        }
                        items(preview.blocks) { block ->
                            Text(block.text, color = if (block.kind == ReportBlockKind.NOTE) TextSecondary else TextPrimary,
                                fontSize = when (block.kind) {
                                    ReportBlockKind.TITLE -> 19.sp
                                    ReportBlockKind.SECTION -> 16.sp
                                    ReportBlockKind.NOTE -> 11.sp
                                    else -> 12.sp
                                }, fontWeight = if (block.kind in setOf(ReportBlockKind.TITLE, ReportBlockKind.SECTION, ReportBlockKind.SUBHEADING)) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        if (step < 2) {
            Button(onClick = {
                if (step == 0) step = 1 else {
                    val frozenRecords = chosen.toList()
                    val frozenInput = input.toMap()
                    val frozenMasked = masked; val frozenMeasured = measured; val frozenHash = hashFiles
                    scope.launch {
                        busy = true; status = null
                        try {
                            val evidence = RecordingEvidenceLoader.collect(frozenRecords, frozenHash)
                            val now = System.currentTimeMillis()
                            snapshot = ReportSnapshot(frozenRecords, frozenInput, evidence, frozenMasked, frozenMeasured,
                                "KNOK-${NoiseReportBuilder.time(now).filter(Char::isDigit)}-${UUID.randomUUID().toString().take(8)}", now)
                            reviewed = false; step = 2
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { status = "녹음 정보를 확인하지 못했습니다. 다시 시도해 주세요." }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy && range.isSuccess && chosen.isNotEmpty() && chosen.size <= ReportSelection.MAX_RECORDS,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Text(if (step == 0) "작성하기" else "미리보기 만들기") }
        } else Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { export(false) }, enabled = !busy && reviewed && snapshot != null, modifier = Modifier.weight(1f)) { Text("PDF 저장") }
            OutlinedButton(onClick = { export(true) }, enabled = !busy && reviewed && snapshot != null, modifier = Modifier.weight(1f)) { Text("PDF 공유") }
        }
    }
}

@Composable
private fun ReportInput(label: String, value: String, placeholder: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(placeholder) },
        enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), minLines = 1, maxLines = 5,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedLabelColor = QuietPrimary, unfocusedLabelColor = TextSecondary, unfocusedPlaceholderColor = TextSecondary))
}

@Composable
private fun ReportToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}
