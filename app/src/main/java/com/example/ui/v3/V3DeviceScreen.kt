package com.example.ui.v3

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.EspRecorderConnectionState
import com.example.ui.canEditDeviceEndpoint

/** Connection-first presentation; all device and analysis actions are supplied by the existing view model. */
@Composable
fun V3DeviceScreen(
    device: V3DeviceUi,
    endpoint: String,
    deviceReady: Boolean?,
    serverReady: Boolean?,
    pendingCount: Int,
    onConnect: (String) -> Unit,
    onThreshold: () -> Unit,
    onCheckServer: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onAdvanced: () -> Unit,
    onHome: () -> Unit,
    onStopDetached: () -> Unit = {},
    onReceivePending: () -> Unit = {},
    analysisSummary: String? = null
) {
    var address by rememberSaveable { mutableStateOf(endpoint) }
    LaunchedEffect(endpoint) { address=endpoint }
    val canEdit=canEditDeviceEndpoint(device.busy,device.enabled,device.connection)
    val connected=device.connection==EspRecorderConnectionState.CONNECTED ||
        device.connection==EspRecorderConnectionState.RECORDING
    val checking=device.connection==EspRecorderConnectionState.CHECKING && device.busy
    val stopping=!device.enabled && device.state in setOf(com.example.ui.EspAlwaysRecordingState.CHECKING,
        com.example.ui.EspAlwaysRecordingState.MONITORING,com.example.ui.EspAlwaysRecordingState.RECORDING,
        com.example.ui.EspAlwaysRecordingState.GRACE_PERIOD,com.example.ui.EspAlwaysRecordingState.SAVING)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=18.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("기기 연결",color=V3Blue,fontSize=28.sp,fontWeight=FontWeight.Bold)
        Text("KNOK와 앱의 연결 상태를 확인해요.",color=V3Blue,fontSize=14.sp)

        Surface(shape=RoundedCornerShape(24.dp),color=V3Surface,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Wifi,null,tint=V3Blue,modifier=Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name,color=V3Blue,fontSize=18.sp,fontWeight=FontWeight.Bold)
                        Text(if(device.connection==EspRecorderConnectionState.CHECKING && !device.busy && !device.enabled) "연결 확인 필요" else v3ConnectionText(device.connection),color=V3Blue,fontSize=13.sp)
                    }
                }
                if(connected && deviceReady==false) {
                    Text("연결은 되었지만 기기 준비 상태를 확인해 주세요.",color=V3Blue,fontSize=13.sp,lineHeight=19.sp)
                }
                if(device.deviceStorageAttention) {
                    Text("기기 저장 공간이나 대기 녹음 상태를 확인해 주세요.",color=V3Blue,fontSize=13.sp,lineHeight=19.sp)
                }
                if(device.reportedDeviceAuto && !device.enabled) {
                    Text("마지막으로 확인한 기기 감지가 켜져 있었어요. 현재 상태를 다시 확인해 주세요.",color=V3Blue,fontSize=13.sp,lineHeight=19.sp)
                    OutlinedButton(onClick=onStopDetached,enabled=connected && !device.busy && !stopping,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("기기 감지 중지 요청") }
                }
                if((device.pendingDeviceEvents>0 || device.deviceFinalizing) && !device.reportedDeviceAuto && !device.enabled) {
                    Text(if(device.deviceFinalizing) "기기에서 녹음 마감을 확인해야 해요." else "마지막 확인에서 기기에 대기 중인 녹음 ${device.pendingDeviceEvents}건이 있어요.",color=V3Blue,fontSize=13.sp,lineHeight=19.sp)
                    if(device.deviceRecording) Text("기기 녹음이 끝난 뒤 대기 녹음을 받을 수 있어요.",color=V3Blue,fontSize=12.sp)
                    OutlinedButton(onClick=onReceivePending,enabled=connected && !device.busy && !stopping && !device.deviceRecording,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("대기 녹음 받기") }
                }
                Text("KNOK와 휴대폰이 같은 Wi-Fi에 연결되어 있는지 확인해 주세요.",
                    color=V3Blue,fontSize=13.sp,lineHeight=19.sp)
                OutlinedTextField(value=address,onValueChange={address=it},label={Text("KNOK 주소")},
                    singleLine=true,enabled=canEdit,modifier=Modifier.fillMaxWidth().testTag("v3_device_address"))
                if(!canEdit && !checking) {
                    Text("주소를 바꾸려면 감지와 녹음을 먼저 중지해 주세요.",color=V3Blue,fontSize=12.sp,lineHeight=18.sp)
                }
                Button(onClick={onConnect(address.trim())},enabled=canEdit && address.isNotBlank(),
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(16.dp)) {
                    Text(if(checking)"연결 확인 중" else "연결 확인")
                }
                if(device.message.isNotBlank()) {
                    Text(device.message,color=V3Blue,fontSize=12.sp,lineHeight=18.sp)
                }
                if(connected) {
                    TextButton(onClick=onHome,modifier=Modifier.align(Alignment.End)) { Text("홈으로 가기") }
                }
            }
        }

        Surface(shape=RoundedCornerShape(24.dp),color=V3Surface,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("감지 기준",color=V3Blue,fontSize=17.sp,fontWeight=FontWeight.Bold)
                Text("${device.threshold.toInt()} · 상대값",color=V3Blue,fontSize=14.sp)
                if(!connected && !device.enabled) Text("기기에 연결한 뒤 감지를 켤 때 적용되는 값이에요.",color=V3Blue,fontSize=12.sp,lineHeight=18.sp)
                TextButton(onClick=onThreshold,modifier=Modifier.testTag("v3_threshold_settings")) { Text("감지 기준 바꾸기") }
            }
        }

        Surface(shape=RoundedCornerShape(24.dp),color=V3Surface,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("AI 분석 연결",color=V3Blue,fontSize=17.sp,fontWeight=FontWeight.Bold)
                Text("녹음은 앱에 저장되고, 소리 분석은 맥에서 진행돼요.",color=V3Blue,fontSize=13.sp,lineHeight=19.sp)
                Text("마지막 연결 확인: ${when(serverReady){true->"연결됨";false->"연결 안 됨";null->"확인 전"}}",
                    color=V3Blue,fontSize=13.sp)
                if(!analysisSummary.isNullOrBlank()) Text("최근 분석 상태: $analysisSummary",color=V3Blue,fontSize=12.sp,lineHeight=18.sp)
                TextButton(onClick=onCheckServer) { Text("맥 분석 연결 확인") }
                if(pendingCount>0) {
                    Text("분석 대기 ${pendingCount}건",color=V3Blue,fontSize=13.sp)
                    OutlinedButton(onClick=onRetryAnalysis,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text("대기 중인 분석 다시 시도")
                    }
                }
            }
        }

        OutlinedButton(onClick=onAdvanced,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text("자세한 기기 설정",modifier=Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight,null)
        }
        Spacer(Modifier.height(8.dp))
    }
}
