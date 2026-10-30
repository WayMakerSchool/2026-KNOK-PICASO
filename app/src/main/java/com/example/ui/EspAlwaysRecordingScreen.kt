package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.QuietAlert
import com.example.ui.theme.QuietPrimary
import com.example.ui.theme.QuietSuccess
import com.example.ui.theme.QuietWarning
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun EspAlwaysRecordingScreen(viewModel: EspRecorderViewModel) {
    val baseUrl by viewModel.baseUrl.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val alwaysEnabled by viewModel.alwaysRecordingEnabled.collectAsState()
    val alwaysState by viewModel.alwaysRecordingState.collectAsState()
    val currentLevelDb by viewModel.currentLevelDb.collectAsState()
    val message by viewModel.message.collectAsState()
    val detectionThreshold by viewModel.detectionThreshold.collectAsState()
    val analysisSummary by viewModel.analysisSummary.collectAsState()

    val deviceAuto = status?.eventQueueAvailable == true
    val threshold = if (alwaysEnabled) status?.thresholdDb ?: detectionThreshold else detectionThreshold
    val levelDb = status?.levelDb ?: currentLevelDb
    val levelMeterAvailable = status?.levelMeterAvailable == true
    val levelProgress = (levelDb / 120.0).toFloat().coerceIn(0f, 1f)
    val stateColor = when (alwaysState) {
        EspAlwaysRecordingState.OFF -> TextSecondary
        EspAlwaysRecordingState.CHECKING -> QuietWarning
        EspAlwaysRecordingState.MONITORING -> QuietSuccess
        EspAlwaysRecordingState.RECORDING -> QuietAlert
        EspAlwaysRecordingState.GRACE_PERIOD -> QuietWarning
        EspAlwaysRecordingState.SAVING -> QuietPrimary
        EspAlwaysRecordingState.ERROR -> QuietAlert
    }
    val stateText = when (alwaysState) {
        EspAlwaysRecordingState.OFF -> "꺼짐"
        EspAlwaysRecordingState.CHECKING -> "연결 확인 중"
        EspAlwaysRecordingState.MONITORING -> "감지 중"
        EspAlwaysRecordingState.RECORDING -> "자동 녹음 중"
        EspAlwaysRecordingState.GRACE_PERIOD -> "3초 유예 중"
        EspAlwaysRecordingState.SAVING -> "WAV 저장 중"
        EspAlwaysRecordingState.ERROR -> "확인 필요"
    }
    val connectionText = when (connectionState) {
        EspRecorderConnectionState.CONNECTED -> "Wi-Fi 통신 가능"
        EspRecorderConnectionState.RECORDING -> "ESP32 녹음 중"
        EspRecorderConnectionState.CHECKING -> "Wi-Fi 확인 중"
        EspRecorderConnectionState.DISCONNECTED -> "ESP32 연결 안 됨"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(
                text = "상시 녹음",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = if (status == null) "KNOK 연결 후 자동 녹음과 소리 분류를 시작할 수 있습니다."
                    else if (deviceAuto) "기기에서 연속 감지하고, 저장과 AI 분류를 별도로 진행합니다."
                    else "구형 펌웨어 · 앱의 음량 조회로 자동 녹음합니다.",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("오프라인 AI 소리 분석", color = QuietPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(analysisSummary, color = TextPrimary, fontSize = 13.sp)
                Text("발걸음 · 가구 끌기 · 단발 충격 · 반복 타격 · 청소기 · 일반/미지 소음", color = TextSecondary, fontSize = 10.sp)
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                stateColor.copy(alpha = if (alwaysEnabled) 0.55f else 0.14f)
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(stateColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (alwaysEnabled) Icons.Default.Mic else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = stateColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ESP32 외장 마이크",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = connectionText,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stateText,
                        color = stateColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = if (levelMeterAvailable) {
                        String.format(Locale.getDefault(), "%.1f dB (상대값)", levelDb)
                    } else {
                        "dB --"
                    },
                    color = if (levelMeterAvailable && levelDb >= threshold) {
                        QuietAlert
                    } else {
                        TextPrimary
                    },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "현재 감지 음량",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { levelProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (levelMeterAvailable && levelDb >= threshold) {
                        QuietAlert
                    } else {
                        QuietPrimary
                    },
                    trackColor = SlateBorder.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0 dB", color = TextSecondary, fontSize = 10.sp)
                    Text(
                        text = "자동 시작 기준 ${threshold.toInt()} dB",
                        color = if (levelMeterAvailable && levelDb >= threshold) {
                            QuietAlert
                        } else {
                            TextSecondary
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("120 dB", color = TextSecondary, fontSize = 10.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("감지 기준 ${detectionThreshold.toInt()} · 상대값", color = TextPrimary, fontSize = 12.sp)
                Slider(value = detectionThreshold.toFloat(),
                    onValueChange = { viewModel.setDetectionThreshold(it.toDouble()) },
                    valueRange = 30f..120f, steps = 89, enabled = !alwaysEnabled && !isBusy)
                Text("작은 소리를 놓치면 기준을 낮추세요. 감지를 중지한 상태에서 조절할 수 있습니다.", color = TextSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (alwaysEnabled) {
                            viewModel.stopAlwaysRecording()
                        } else {
                            viewModel.startAlwaysRecording()
                        }
                    },
                    enabled = !isBusy && (alwaysEnabled || baseUrl.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (alwaysEnabled) QuietAlert else QuietPrimary,
                        disabledContainerColor = SlateBorder
                    )
                ) {
                    Icon(
                        imageVector = if (alwaysEnabled) Icons.Default.Mic else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = DarkBackground,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isBusy -> "처리 중..."
                            alwaysEnabled -> "상시 녹음 중지"
                            else -> "상시 녹음 시작"
                        },
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    color = if (alwaysState == EspAlwaysRecordingState.ERROR) QuietWarning else TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                if (status != null && !levelMeterAvailable) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "현재 ESP32 응답에 levelDb가 없습니다. 새 펌웨어를 다시 업로드하세요.",
                        color = QuietWarning,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "자동 녹음 규칙",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                AutoRuleRow("감지 기준", "${threshold.toInt()} dB 이상이면 시작")
                AutoRuleRow("녹음 시간", if (deviceAuto) "감지 전 1초 + 이후 약 4초 · 최대 ${(status?.recordingLimitMs ?: 15000) / 1000}초" else "5초 · ESP32가 자동 종료")
                AutoRuleRow("재감지", if (deviceAuto) "기기 감지 유지 · 연속 소음은 합쳐 기록" else "녹음 종료 후 3초 유예")
                if (deviceAuto) {
                    AutoRuleRow("수신 대기", "${status?.pendingEvents ?: 0} / 2개")
                    AutoRuleRow("디지털 진동", "${status?.digitalEvents ?: 0}회 · 세기 측정 안 함")
                    AutoRuleRow("대기열 초과 / 오디오 누락", "${status?.queueOverflows ?: 0} / ${status?.audioOverflows ?: 0}")
                    if ((status?.quarantinedSlots ?: 0) > 0 || (status?.waitingForSpace ?: 0) > 0 || status?.ready == false) {
                        Text("기기 저장 오류 확인 필요 · ${status?.message}", color = QuietWarning, fontSize = 11.sp)
                    }
                }
                AutoRuleRow("저장 위치", "자동으로 녹음 보관함에 WAV 등록")
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "음량은 보정되지 않은 상대값이며 법정 dBA 측정값이 아닙니다. 기기 감지는 WAV 전송 중에도 계속되지만, 상태 화면 갱신은 전송 중 늦어질 수 있습니다.",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
    }
}

@Composable
private fun AutoRuleRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
