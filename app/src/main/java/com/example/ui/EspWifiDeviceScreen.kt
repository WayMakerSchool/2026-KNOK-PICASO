package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
fun EspWifiDeviceScreen(viewModel: EspRecorderViewModel) {
    val savedBaseUrl by viewModel.baseUrl.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val message by viewModel.message.collectAsState()
    var baseUrlText by remember { mutableStateOf(savedBaseUrl) }

    val isConnected = connectionState == EspRecorderConnectionState.CONNECTED ||
        connectionState == EspRecorderConnectionState.RECORDING
    val stateColor = when (connectionState) {
        EspRecorderConnectionState.CONNECTED -> QuietSuccess
        EspRecorderConnectionState.RECORDING -> QuietAlert
        EspRecorderConnectionState.CHECKING -> QuietWarning
        EspRecorderConnectionState.DISCONNECTED -> TextSecondary
    }
    val stateText = when (connectionState) {
        EspRecorderConnectionState.CONNECTED -> "통신 가능"
        EspRecorderConnectionState.RECORDING -> "통신 가능 · 녹음 중"
        EspRecorderConnectionState.CHECKING -> "확인 중"
        EspRecorderConnectionState.DISCONNECTED -> "연결 안 됨"
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
                text = "ESP32 Wi-Fi 기기",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "ESP32와 앱 사이의 HTTP 통신만 확인합니다.",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi",
                        tint = QuietPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "통신 상태",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stateText,
                        color = stateColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "휴대폰과 ESP32가 같은 Wi-Fi에 있어야 합니다. 기본 주소는 mDNS이며, 실패하면 ESP32 시리얼 모니터의 IP를 입력하세요.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = { baseUrlText = it },
                    label = { Text("ESP32 주소") },
                    placeholder = { Text("http://knok-esp32.local 또는 http://192.168.0.123") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isBusy && connectionState != EspRecorderConnectionState.RECORDING,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuietPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedLabelColor = QuietPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledTextColor = TextSecondary,
                        disabledBorderColor = SlateBorder.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        viewModel.setBaseUrl(baseUrlText)
                        viewModel.checkConnection()
                    },
                    enabled = !isBusy && baseUrlText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuietPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (isBusy) "통신 확인 중..." else "Wi-Fi 통신 확인",
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = stateColor,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message,
                        color = if (isConnected) TextSecondary else QuietWarning,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = QuietPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ESP32 응답 정보",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                DeviceInfoRow("장치", status?.device ?: "-", TextPrimary)
                DeviceInfoRow("IP 주소", status?.ip ?: "-", TextPrimary)
                DeviceInfoRow("mDNS", status?.hostname ?: "-", TextPrimary)
                DeviceInfoRow(
                    "오디오",
                    status?.let { "${it.sampleRate}Hz · ${it.channels}ch · ${it.bitsPerSample}bit" } ?: "-",
                    TextPrimary
                )
                DeviceInfoRow(
                    "dB 감지",
                    when {
                        status == null -> "-"
                        status?.levelMeterAvailable == true -> "지원됨"
                        else -> "새 펌웨어 필요"
                    },
                    when {
                        status?.levelMeterAvailable == true -> QuietSuccess
                        status == null -> TextSecondary
                        else -> QuietWarning
                    }
                )
                DeviceInfoRow(
                    "I2S 샘플",
                    status?.let {
                        if (it.levelMeterAvailable) {
                            "${it.levelSampleBlockCount} blocks · peak ${it.lastLevelPcmPeak}"
                        } else {
                            "새 펌웨어 필요"
                        }
                    } ?: "-",
                    if (status?.levelMeterAvailable == true) TextPrimary else QuietWarning
                )
                DeviceInfoRow(
                    "녹음 상태",
                    if (status?.recording == true) "녹음 중" else "대기 중",
                    if (status?.recording == true) QuietAlert else TextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
    }
}

@Composable
private fun DeviceInfoRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
