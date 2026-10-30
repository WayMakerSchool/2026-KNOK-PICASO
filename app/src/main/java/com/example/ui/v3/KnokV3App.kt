package com.example.ui.v3

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.auth.FirebaseAuthViewModel
import com.example.ui.*

private fun v3TabIndex(route: String): Int? = when (route) {
    "home" -> 0
    "records", "detail" -> 1
    "report" -> 2
    "device", "device_advanced" -> 3
    else -> null
}

@Composable
fun KnokV3App(viewModel:NoiseViewModel,esp:EspRecorderViewModel) {
    val context=LocalContext.current
    val records by viewModel.allRecords.collectAsState()
    val playingId by viewModel.playingRecordId.collectAsState()
    val playing by viewModel.isPlaying.collectAsState()
    val progress by viewModel.playbackProgress.collectAsState()
    val analyzingId by viewModel.analyzingRecordId.collectAsState()
    val errors by viewModel.analysisErrors.collectAsState()
    val endpoint by esp.baseUrl.collectAsState()
    val status by esp.status.collectAsState()
    val connection by esp.connectionState.collectAsState()
    val macServerReady by esp.macServerReady.collectAsState()
    val analysisSummary by esp.analysisSummary.collectAsState()
    val enabled by esp.alwaysRecordingEnabled.collectAsState()
    val state by esp.alwaysRecordingState.collectAsState()
    val busy by esp.isBusy.collectAsState()
    val threshold by esp.detectionThreshold.collectAsState()
    val level by esp.currentLevelDb.collectAsState()
    val message by esp.message.collectAsState()
    val aliasStore=remember { V3DeviceAliasStore(context) }
    val deviceId=status?.deviceId.orEmpty()
    var alias by remember(deviceId,endpoint) { mutableStateOf(aliasStore.read(deviceId,endpoint)) }
    var route by rememberSaveable { mutableStateOf("home") }
    var recordId by rememberSaveable { mutableStateOf<Int?>(null) }
    var dialog by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }
    val audioRecords=records.filter { it.recordingPath!=null || it.remoteAudioPath!=null }
    val reportedDeviceAuto=status?.let { it.protocolVersion>=2 && it.eventQueueAvailable && it.autoEnabled }==true
    val selected=audioRecords.firstOrNull { it.id==recordId }
    val latest=audioRecords.maxByOrNull { it.timestamp }
    val thresholdEditable=v3CanEditThreshold(busy,enabled,connection,state,reportedDeviceAuto)
    val displayedThreshold=if(enabled || reportedDeviceAuto)status?.thresholdDb?:threshold else threshold
    var micPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) }
    val micLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micPermission=it }
    fun play(record:com.example.data.NoiseRecord) {
        if(playingId==record.id)viewModel.togglePlayPause() else viewModel.playAudio(record)
    }
    fun retryPendingAnalysis() {
        esp.retryDeferredMacAnalyses()
        Toast.makeText(context,"대기 중인 분석을 다시 확인해요.",Toast.LENGTH_SHORT).show()
    }
    BackHandler(enabled=route!="home") { route=when(route){"detail"->"records";"device_advanced"->"device";else->"home"} }
    V3DetectionStartGuard(playing,{esp.startAlwaysRecording()},{viewModel.stopAudio();esp.startAlwaysRecording()}) { requestDetection ->
    V3PlaybackGuard(enabled || connection!=EspRecorderConnectionState.CONNECTED || reportedDeviceAuto || state==EspAlwaysRecordingState.ERROR,audioRecords,playingId,playing,{play(it)}) { requestPlayback ->
    Scaffold(containerColor=V3Cream,
        topBar={ Column(Modifier.statusBarsPadding()) { V3Header({dialog="device"},{dialog="profile"}) } },
        bottomBar={V3BottomNavigation(route) { route=it }}) { padding ->
        AnimatedContent(targetState = route, modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                val from = v3TabIndex(initialState)
                val to = v3TabIndex(targetState)
                val direction = if (from != null && to != null && from != to) {
                    if (to > from) 1 else -1
                } else 0
                val motion = tween<IntOffset>(durationMillis = 240, easing = FastOutSlowInEasing)
                (fadeIn(tween(180)) + slideInHorizontally(motion) { width -> direction * width / 18 })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(motion) { width -> -direction * width / 18 })
            }, label = "screen transition") { screenRoute ->
            // The outgoing screen remains visible briefly but must not receive taps or accessibility focus.
            val outgoingGuard = if (screenRoute != route) Modifier.clearAndSetSemantics { }
                .pointerInput(route) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                } else Modifier
            Box(Modifier.fillMaxSize().testTag("v3_page_$screenRoute").then(outgoingGuard)) {
            when(screenRoute) {
                "home" -> V3HomeScreen(V3DeviceUi(alias,connection,state,enabled,busy,
                    status?.levelDb?:level,status?.levelMeterAvailable==true,
                    displayedThreshold,message,reportedDeviceAuto,status?.pendingEvents?:0,
                    status?.finalizing==true,status?.let {!it.ready || it.quarantinedSlots>0 || it.waitingForSpace>0}==true,status?.recording==true),latest,
                    onRename={aliasInput=alias;dialog="rename"},
                    onToggle={when {
                        enabled -> esp.stopAlwaysRecording()
                        connection==EspRecorderConnectionState.RECORDING -> esp.stopRecording()
                        else -> requestDetection()
                    }},
                    onRecords={route="records"},onPlay={requestPlayback(it)},onDevice={route="device"},
                    latestPlaying=latest!=null&&playingId==latest.id&&playing)
                "records" -> V3RecordsScreen(audioRecords,playingId,playing,
                    onOpen={recordId=it.id;route="detail"},onPlay={requestPlayback(it)},onReport={route="report"},onPrepare={route="home"})
                "detail" -> if(selected!=null) V3RecordDetail(selected,playingId==selected.id&&playing,
                    if(playingId==selected.id)progress else 0f,analyzingId==selected.id,errors[selected.id],
                    onBack={route="records"},onPlay={requestPlayback(selected)},onAnalyze={
                        if(selected.deviceClassificationPending && selected.recordingSource=="ESP32" && selected.deviceEventId!=null)
                            retryPendingAnalysis()
                        else viewModel.analyzeSound(selected)
                    },
                    onNote={noteInput=selected.note;dialog="note"},onDelete={dialog="delete"},analysisSummary=analysisSummary)
                    else Text("녹음을 불러오는 중이에요.",modifier=Modifier.padding(24.dp),color=V3Blue)
                "report" -> NoiseReportScreen(records) { route="home" }
                "device" -> V3DeviceScreen(V3DeviceUi(alias,connection,state,enabled,busy,
                    status?.levelDb?:level,status?.levelMeterAvailable==true,displayedThreshold,message,reportedDeviceAuto,status?.pendingEvents?:0,
                    status?.finalizing==true,status?.let {!it.ready || it.quarantinedSlots>0 || it.waitingForSpace>0}==true,status?.recording==true),endpoint,status?.ready,
                    macServerReady,audioRecords.count { it.deviceClassificationPending && it.recordingSource=="ESP32" && it.deviceEventId!=null },
                    onConnect={address->esp.setBaseUrl(address);esp.checkConnection()},onThreshold={dialog="threshold"},
                    onCheckServer={esp.checkMacAnalysisServer()},onRetryAnalysis={retryPendingAnalysis()},
                    onAdvanced={route="device_advanced"},onHome={route="home"},onStopDetached={esp.stopDetachedDeviceAuto(true)},
                    onReceivePending={esp.stopDetachedDeviceAuto(false)},analysisSummary=analysisSummary)
                "device_advanced" -> Column(Modifier.fillMaxSize()) {
                    TextButton(onClick={route="device"}) { Text("← 기기 연결") }
                    TextButton(onClick={dialog="threshold"},modifier=Modifier.fillMaxWidth().testTag("v3_threshold_settings")) {
                        Text("감지 기준 ${displayedThreshold.toInt()} · 조절",fontSize=14.sp)
                    }
                    Box(Modifier.weight(1f)) { EspWifiDeviceScreen(esp) }
                }
                "log" -> LogScreen(viewModel)
                "guide" -> GuideScreen(viewModel)
                "demoGuide" -> V2DemoGuideScreen()
                "phone" -> {
                    val auth:FirebaseAuthViewModel=androidx.lifecycle.viewmodel.compose.viewModel()
                    DashboardScreen(viewModel,esp,auth,micPermission) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                }
            }
            }
        }
    }
    }
    }
    if(dialog=="profile") AlertDialog(onDismissRequest={dialog=""},containerColor=V3Cream,
        title={Text("KNOK V3",fontWeight=FontWeight.Bold)},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("로그인 없이 사용하는 로컬 검증 앱이에요.\n녹음은 이 V3 앱에 따로 저장돼요.",fontSize=14.sp,lineHeight=22.sp)
            Spacer(Modifier.height(14.dp))
            listOf("log" to "소음대장", "guide" to "공간 가이드", "demoGuide" to "기기 시연 안내", "phone" to "휴대폰 임시 녹음").forEach { (id,text) ->
                TextButton(onClick={dialog="";route=id},modifier=Modifier.fillMaxWidth()) { Text(text) }
            }
        }},confirmButton={TextButton(onClick={dialog=""}) {Text("닫기")}})
    if(dialog=="device") AlertDialog(onDismissRequest={dialog=""},containerColor=V3Cream,
        title={Text("현재 KNOK")},text={Column {
            Text(alias,fontSize=20.sp,fontWeight=FontWeight.Bold)
            Text(v3ConnectionText(connection),modifier=Modifier.padding(top=8.dp))
            Text("현재 앱은 한 번에 기기 한 대를 연결해요. 기기 연결은 기기 화면에서 확인할 수 있어요.",fontSize=13.sp,modifier=Modifier.padding(top=16.dp))
        }},confirmButton={TextButton(onClick={dialog="";route="device"}) {Text("기기 설정")}},
        dismissButton={TextButton(onClick={dialog=""}) {Text("닫기")}})
    if(dialog=="rename") AlertDialog(onDismissRequest={dialog=""},containerColor=V3Cream,
        title={Text("기기 이름 변경")},text={OutlinedTextField(value=aliasInput,onValueChange={aliasInput=it},
            label={Text("설치한 공간의 이름")},singleLine=true,modifier=Modifier.fillMaxWidth())},
        confirmButton={TextButton(enabled=aliasInput.isNotBlank(),onClick={
            if(aliasStore.save(deviceId,endpoint,aliasInput)){alias=aliasStore.read(deviceId,endpoint);dialog=""}
        }) {Text("이름 저장")}},dismissButton={TextButton(onClick={dialog=""}) {Text("취소")}})
    if(dialog=="threshold") AlertDialog(onDismissRequest={dialog=""},containerColor=V3Cream,
        title={Text("감지 기준 조절")},text={Column {
            Text("${displayedThreshold.toInt()} · 상대값",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Slider(value=displayedThreshold.toFloat(),onValueChange={esp.setDetectionThreshold(it.toDouble())},
                valueRange=30f..120f,steps=89,enabled=thresholdEditable,
                modifier=Modifier.fillMaxWidth().testTag("v3_detection_slider"))
            Text(if(thresholdEditable)"작은 소리를 놓치면 기준을 낮추세요. 변경한 값은 자동 저장돼요."
                else "감지·녹음을 중지한 뒤 조절할 수 있어요.",fontSize=13.sp)
            if(thresholdEditable && connection!=EspRecorderConnectionState.CONNECTED)
                Text("이 값은 다음 감지 시작에 적용돼요.",fontSize=12.sp)
        }},confirmButton={TextButton(onClick={dialog=""}){Text("닫기")}})
    if(dialog=="note"&&selected!=null) AlertDialog(onDismissRequest={dialog=""},containerColor=V3Cream,
        title={Text("녹음 메모")},text={OutlinedTextField(value=noteInput,onValueChange={noteInput=it},modifier=Modifier.fillMaxWidth(),minLines=3)},
        confirmButton={TextButton(onClick={viewModel.updateRecordNote(selected,noteInput);dialog=""}){Text("저장")}},
        dismissButton={TextButton(onClick={dialog=""}){Text("취소")}})
    if(dialog=="delete"&&selected!=null) AlertDialog(onDismissRequest={dialog=""},containerColor=V3Cream,
        title={Text("이 녹음을 삭제할까요?")},text={Text("이 앱에 저장된 기록과 원본 녹음이 삭제돼요. 삭제 후에는 되돌릴 수 없어요.")},
        confirmButton={TextButton(onClick={viewModel.deleteRecord(selected);dialog="";route="records"}) {Text("삭제")}},
        dismissButton={TextButton(onClick={dialog=""}){Text("취소")}})
}
