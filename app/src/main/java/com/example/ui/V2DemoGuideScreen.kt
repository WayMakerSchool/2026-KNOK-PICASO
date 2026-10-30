package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun V2DemoGuideScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("KNOK V2 시연 안내", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        V2GuideParagraph("1. 기기 연결", "ESP32에 전원을 연결하고, 맥과 기기가 같은 공유기를 사용하도록 합니다. Wi-Fi 탭에서 ESP32의 숫자 IP를 입력하고 연결을 확인합니다. 가상 폰의 AndroidWifi는 맥의 네트워크를 이용합니다.")
        V2GuideParagraph("2. 소리 듣고 분류하기", "상시 녹음 시작을 누르고 소리를 들려주세요. 소리가 감지되면 WAV를 저장하고 앱이 오프라인으로 6가지 소리 유형을 추정합니다. 소리가 끝난 뒤 약 4초간 녹음을 이어갑니다.")
        V2GuideParagraph("3. 기록 확인", "녹음목록에서 종류와 모델 점수, 원본 음원을 확인합니다. 다시 재생할 때는 자동 감지를 먼저 중지해주세요. 스피커 소리가 새 이벤트로 다시 녹음될 수 있습니다.")
        V2GuideParagraph("감도와 분석 범위", "감지 기준은 보정되지 않은 상대 음량입니다. 조용한 배경과 시연 소리를 비교해 조절해주세요. 표시된 모델 점수는 정답률이 아니며, 위층·아래층이나 법적 기준 초과를 확정하지 않습니다. 디지털 진동 센서는 접점 변화 횟수만 기록합니다.")
        V2GuideParagraph("기록 보존", "V2 기록은 이 앱에 별도로 저장됩니다. 자동 감지를 사용할 때 앱을 열어두세요. 저장 오류·대기열 초과·오디오 누락이 표시되면 그 상태를 확인한 뒤 시연을 계속합니다.")
    }
}

@Composable
private fun V2GuideParagraph(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(body, color = TextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
    }
}
