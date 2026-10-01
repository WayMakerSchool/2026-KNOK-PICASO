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
                text = "ESP 마이크가 90 dB 이상 감지될 때만 자동 저장합니다.",
                color = TextSecondary,
                fontSize = 12.sp
            )
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
                        String.format(Locale.getDefault(), "%.1f dB", levelDb)
                    } else {
                        "dB --"
                    },
                    color = if (levelMeterAvailable && levelDb >= EspRecorderViewModel.AUTO_TRIGGER_DB) {
                        QuietAlert
                    } else {
                        TextPrimary
                    },
                    fontSize = 36.sp,
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
                    color = if (levelMeterAvailable && levelDb >= EspRecorderViewModel.AUTO_TRIGGER_DB) {
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
                        text = "자동 시작 기준 ${EspRecorderViewModel.AUTO_TRIGGER_DB.toInt()} dB",
                        color = if (levelMeterAvailable && levelDb >= EspRecorderViewModel.AUTO_TRIGGER_DB) {
                            QuietAlert
                        } else {
                            TextSecondary
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("120 dB", color = TextSecondary, fontSize = 10.sp)
                }

                Spacer(modifier = Modifier.height(18.dp))
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
                if (!levelMeterAvailable) {
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
                AutoRuleRow("감지 기준", "${EspRecorderViewModel.AUTO_TRIGGER_DB.toInt()} dB 이상이면 시작")
                AutoRuleRow("녹음 시간", "5초 · ESP32가 자동 종료")
                AutoRuleRow("재감지 유예", "녹음 종료 후 3초 동안 추가 녹음 차단")
                AutoRuleRow("저장 위치", "자동으로 녹음 보관함에 WAV 등록")
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "현재 dB는 INMP441 원시 신호에 보정값을 적용한 값입니다. 실제 dB SPL 90 기준으로 사용하려면 기준 음압계로 보정값을 조정해야 합니다.",
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
