package com.example.ui.v3

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.NoiseRecord
import com.example.ui.EspAlwaysRecordingState
import com.example.ui.EspRecorderConnectionState

/** Read-only rendering model; it does not start a poller, recorder, or inference job. */
data class V3DeviceUi(
    val name: String="내 KNOK",
    val connection: EspRecorderConnectionState=EspRecorderConnectionState.DISCONNECTED,
    val state: EspAlwaysRecordingState=EspAlwaysRecordingState.OFF,
    val enabled: Boolean=false,
    val busy: Boolean=false,
    val level: Double=0.0,
    val levelAvailable: Boolean=false,
    val threshold: Double=70.0,
    val message: String="",
    val reportedDeviceAuto: Boolean=false,
    val pendingDeviceEvents: Int=0,
    val deviceFinalizing: Boolean=false,
    val deviceStorageAttention: Boolean=false,
    val deviceRecording: Boolean=false
)

@Composable
fun V3HomeScreen(device: V3DeviceUi, latest: NoiseRecord?, onRename: () -> Unit,
    onToggle: () -> Unit, onRecords: () -> Unit, onPlay: (NoiseRecord) -> Unit, onDevice: () -> Unit,
    latestPlaying: Boolean=false) {
    val stopping=!device.enabled && device.state in setOf(EspAlwaysRecordingState.CHECKING,
        EspAlwaysRecordingState.MONITORING,EspAlwaysRecordingState.RECORDING,
        EspAlwaysRecordingState.GRACE_PERIOD,EspAlwaysRecordingState.SAVING)
    val manualRecording=!device.enabled && !stopping && !device.reportedDeviceAuto && device.connection==EspRecorderConnectionState.RECORDING
    val unconfirmedDeviceAuto=!device.enabled && !stopping && device.reportedDeviceAuto
    val displayState=if(manualRecording)EspAlwaysRecordingState.RECORDING else device.state
    val active=device.enabled || displayState !in setOf(EspAlwaysRecordingState.OFF,EspAlwaysRecordingState.ERROR)
    val checkingConnection=!active && device.connection==EspRecorderConnectionState.CHECKING && device.busy
    val needsConnection=!active && (device.connection==EspRecorderConnectionState.DISCONNECTED ||
        (device.connection==EspRecorderConnectionState.CHECKING && !device.busy))
    val presentation=v3MonitorPresentation(displayState)
    val hasLiveLevel=device.levelAvailable && device.level.isFinite() && device.connection in setOf(
        EspRecorderConnectionState.CONNECTED,EspRecorderConnectionState.RECORDING)
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val heroHeight=(maxHeight-275.dp).coerceIn(96.dp,390.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if(!active) {
                    Row(Modifier.fillMaxWidth().padding(start=24.dp,end=14.dp,top=15.dp),
                        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                        Text(device.name,color=V3Blue,fontWeight=FontWeight.Bold,fontSize=28.sp,lineHeight=36.sp,maxLines=2,
                            overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false),textAlign=TextAlign.Center)
                        IconButton(onClick=onRename) { Icon(Icons.Outlined.Edit,"기기 이름 변경",Modifier.size(22.dp)) }
                    }
                    TextButton(onClick=onDevice,modifier=Modifier.align(Alignment.CenterHorizontally)) {
                        Text("●  ${if(device.connection==EspRecorderConnectionState.CHECKING && !device.busy) "연결 확인 필요" else v3ConnectionText(device.connection)}",fontSize=14.sp,color=V3Blue)
                    }
                    Box(Modifier.fillMaxWidth().height(heroHeight).clip(RoundedCornerShape(1.dp)),contentAlignment=Alignment.Center) {
                        Text("KNOK",fontSize=144.sp,fontWeight=FontWeight.Black,color=V3Blue.copy(alpha=.065f),
                            maxLines=1,softWrap=false,modifier=Modifier.rotate(-13f))
                        Image(painterResource(R.drawable.knok_v3_product),"웜화이트 KNOK 제품",
                            modifier=Modifier.fillMaxSize().padding(horizontal=32.dp,vertical=7.dp),contentScale=ContentScale.Fit)
                    }
                    Surface(shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp),color=V3Surface) {
                        Column(Modifier.padding(horizontal=21.dp,vertical=17.dp)) {
                            Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp).background(V3Line,CircleShape))
                            Spacer(Modifier.height(12.dp))
                            V3SectionTitle("최근 기록","전체 보기",onRecords)
                            if(latest==null) {
                                Text("아직 저장된 녹음이 없어요",color=V3Blue,fontSize=15.sp,modifier=Modifier.padding(vertical=15.dp))
                            } else {
                                val record=v3RecordPresentation(latest)
                                Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.GraphicEq,null,Modifier.size(33.dp),tint=V3Blue)
                                    Spacer(Modifier.width(15.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(record.title,color=V3Blue,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=2)
                                        Text("${record.badge} · ${java.text.SimpleDateFormat("HH:mm",java.util.Locale.KOREA).format(java.util.Date(latest.timestamp))} · ${latest.durationMs/1000}초",fontSize=12.sp,color=V3Blue)
                                    }
                                    OutlinedIconButton(onClick={onPlay(latest)},modifier=Modifier.size(48.dp),border=androidx.compose.foundation.BorderStroke(1.dp,V3Blue)) {
                                        Icon(if(latestPlaying)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                            if(latestPlaying)"최근 녹음 일시정지" else "최근 녹음 재생",Modifier.size(27.dp))
                                    }
                                }
                            }
                            if(device.message.isNotBlank()) Text(device.message,color=if(device.state==EspAlwaysRecordingState.ERROR)MaterialTheme.colorScheme.error else V3Blue,
                                fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(top=10.dp))
                            if(unconfirmedDeviceAuto) Text("마지막으로 확인한 기기 감지가 켜져 있었어요. 현재 상태를 다시 확인해 주세요.",
                                fontSize=12.sp,lineHeight=18.sp,color=V3Blue,modifier=Modifier.padding(top=10.dp))
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(presentation.title,fontSize=32.sp,lineHeight=40.sp,fontWeight=FontWeight.Bold,color=V3Blue,textAlign=TextAlign.Center,modifier=Modifier.testTag("v3_monitor_title"))
                        Spacer(Modifier.height(6.dp))
                        Text(device.name,fontSize=17.sp,color=V3Blue,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
                        Text(v3ConnectionText(device.connection),fontSize=12.sp,color=V3Blue,modifier=Modifier.padding(top=6.dp))
                        Box(Modifier.fillMaxWidth().height((heroHeight*.65f).coerceIn(150.dp,235.dp)),contentAlignment=Alignment.Center) {
                            LevelDecoration(device.level,hasLiveLevel,Modifier.fillMaxWidth().height(100.dp))
                            Surface(shape=CircleShape,color=V3Pale.copy(alpha=.8f),modifier=Modifier.size(160.dp)) {
                                Box(Modifier.padding(20.dp).background(V3Blue,CircleShape),contentAlignment=Alignment.Center) {
                                    Icon(Icons.Outlined.Mic,null,Modifier.size(56.dp),tint=V3Cream)
                                }
                            }
                        }
                        Text(presentation.description,fontSize=13.sp,color=V3Blue,textAlign=TextAlign.Center)
                        Spacer(Modifier.height(19.dp))
                        Surface(shape=RoundedCornerShape(25.dp),color=V3Surface,modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text("현재 감지 음량",fontSize=15.sp,fontWeight=FontWeight.Bold,color=V3Blue)
                                Row(verticalAlignment=Alignment.Bottom) {
                                    Text(v3RelativeLevel(device.level,hasLiveLevel),fontSize=53.sp,fontWeight=FontWeight.Bold,color=V3Blue)
                                    Text("dB · 상대값",fontSize=14.sp,color=V3Blue,modifier=Modifier.padding(start=8.dp,bottom=13.dp))
                                }
                                LinearProgressIndicator(progress=if(hasLiveLevel)(device.level/120.0).toFloat().coerceIn(0f,1f) else 0f,
                                    modifier=Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),color=V3Blue,trackColor=V3Line)
                                Text("감지 기준 ${device.threshold.toInt()} · 상대값",fontSize=12.sp,color=V3Blue,modifier=Modifier.padding(top=12.dp))
                            }
                        }
                        Spacer(Modifier.height(15.dp))
                        V3InfoNote("녹음과 AI 분석은 별도로 진행돼요.\n앱을 닫거나 연결이 끊기면 감지 상태를 다시 확인해 주세요.")
                        if(device.message.isNotBlank())
                            Text(device.message,color=if(device.state==EspAlwaysRecordingState.ERROR)MaterialTheme.colorScheme.error else V3Blue,
                                fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                        if(device.state==EspAlwaysRecordingState.ERROR || device.connection==EspRecorderConnectionState.DISCONNECTED) {
                            TextButton(onClick=onDevice) { Text("기기 상태 확인") }
                        }
                    }
                }
            }
        }
        Surface(color=if(active)V3Cream else V3Surface) {
            Column(Modifier.padding(start=18.dp,end=18.dp,top=8.dp,bottom=10.dp)) {
                V3Action(if(stopping)"감지 종료 중" else if(manualRecording)"녹음 중지" else if(active)if(presentation.recording)"녹음·감지 중지" else "소음 감지 중지"
                    else if(checkingConnection)"연결 확인 중" else if(unconfirmedDeviceAuto)"기기 상태 확인" else if(needsConnection)"기기 연결하기" else "소음 감지 켜기",
                    if(needsConnection || unconfirmedDeviceAuto)onDevice else onToggle,
                    enabled=!stopping && !checkingConnection && (if(manualRecording)!device.busy else device.enabled || !device.busy),stop=active)
            }
        }
    }
}

@Composable
private fun LevelDecoration(level: Double, available: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        if(!available || !level.isFinite())return@Canvas
        val strength=(level/120.0).toFloat().coerceIn(0f,1f)
        for(i in 0..14) {
            val x=size.width*i/14f
            val factor=(1f-kotlin.math.abs(i-7)/8f).coerceAtLeast(.1f)
            val h=size.height*strength*factor
            drawLine(V3Blue.copy(alpha=.35f),Offset(x,size.height/2-h/2),Offset(x,size.height/2+h/2),4.dp.toPx(),StrokeCap.Round)
        }
    }
}
