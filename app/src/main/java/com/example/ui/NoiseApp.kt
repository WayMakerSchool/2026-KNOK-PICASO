package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import android.os.Build
import android.widget.Space
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import com.example.ml.SoundAnalysis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.NoiseRecord
import com.example.auth.FirebaseAuthViewModel
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.QuietAlert
import com.example.ui.theme.QuietPrimary
import com.example.ui.theme.QuietSecondary
import com.example.ui.theme.QuietWarning
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.QuietSuccess
import com.example.ui.theme.QuietTertiary
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.CircularProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun NoiseApp(
    viewModel: NoiseViewModel,
    espRecorderViewModel: EspRecorderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasMicPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val authViewModel: FirebaseAuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    Scaffold(
        modifier = Modifier
            .fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            QuietBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .drawBehind {
                    val w = size.width
                    val h = size.height

                    // Top-Left Soft Cyan Neon Orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(QuietPrimary.copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(w * 0.15f, h * 0.2f),
                            radius = w * 0.65f
                        ),
                        radius = w * 0.65f,
                        center = Offset(w * 0.15f, h * 0.2f)
                    )

                    // Bottom-Right Soft Purple Neon Orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(QuietSecondary.copy(alpha = 0.12f), Color.Transparent),
                            center = Offset(w * 0.85f, h * 0.75f),
                            radius = w * 0.75f
                        ),
                        radius = w * 0.75f,
                        center = Offset(w * 0.85f, h * 0.75f)
                    )

                    // Middle Subtle Rose Accent
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(QuietTertiary.copy(alpha = 0.05f), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.45f),
                            radius = w * 0.5f
                        ),
                        radius = w * 0.5f,
                        center = Offset(w * 0.5f, h * 0.45f)
                    )
                }
                .statusBarsPadding()
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    espRecorderViewModel = espRecorderViewModel,
                    authViewModel = authViewModel,
                    hasMicPermission = hasMicPermission,
                    onRequestMicPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
                )
                1 -> LogScreen(viewModel = viewModel)
                2 -> RecordingScreen(viewModel = viewModel)
                3 -> EspWifiDeviceScreen(viewModel = espRecorderViewModel)
                4 -> GuideScreen(viewModel = viewModel)
                5 -> EspAlwaysRecordingScreen(viewModel = espRecorderViewModel)
            }
        }
    }
}

@Composable
fun PermissionBlocker(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardVoice,
            contentDescription = "Microphone Permission Needed",
            tint = QuietAlert,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "마이크 권한이 필요합니다",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "층간 소음 및 주변 소음을 올바르게 실시간 측정하고 기록하기 위해 오디오 녹음 권한을 허용해 주셔야 합니다.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = QuietPrimary),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(text = "마이크 권한 허용하기", color = DarkBackground, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    border: BorderStroke = BorderStroke(
        1.dp,
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.03f)
            )
        )
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f)
        ),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
fun AccountCard(viewModel: FirebaseAuthViewModel) {
    val context = LocalContext.current
    val authState by viewModel.uiState.collectAsState()

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            1.dp,
            if (authState.isSignedIn) QuietPrimary.copy(alpha = 0.45f)
            else Color.White.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (authState.isSignedIn) "Google 계정 연결됨" else "내 기록 계정",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = authState.email ?: "로그인하면 기기 변경 후에도 내 기록을 이어서 볼 수 있어.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (authState.isSignedIn) {
                    OutlinedButton(
                        onClick = { viewModel.signOut(context) },
                        enabled = !authState.isLoading,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("로그아웃", fontSize = 11.sp)
                    }
                } else {
                    Button(
                        onClick = { viewModel.signInWithGoogle(context) },
                        enabled = authState.isConfigured && !authState.isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = QuietPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (authState.isConfigured) "Google 로그인" else "설정 필요",
                            color = DarkBackground,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            authState.message?.let { message ->
                Text(
                    text = message,
                    color = if (authState.isSignedIn) QuietSuccess else QuietWarning,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            if (authState.isSignedIn) {
                OutlinedButton(
                    onClick = viewModel::syncNow,
                    enabled = !authState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Text("지금 동기화", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: NoiseViewModel,
    espRecorderViewModel: EspRecorderViewModel? = null,
    authViewModel: FirebaseAuthViewModel? = null,
    hasMicPermission: Boolean = true,
    onRequestMicPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val isMeasuring by viewModel.isMeasuring.collectAsState()
    val currentDb by viewModel.currentDb.collectAsState()
    val currentVibe by viewModel.currentVibe.collectAsState()
    val maxDb by viewModel.maxSessionDb.collectAsState()
    val maxVibe by viewModel.maxSessionVibe.collectAsState()
    val durationS by viewModel.sessionDurationS.collectAsState()
    val isExceededNow by viewModel.isLimitExceededNow.collectAsState()
    val waveHistory by viewModel.waveHistory.collectAsState()

    var noteText by remember { mutableStateOf("") }

    // Pulsing threshold ring multiplier (Alert state design)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        // App top title
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "KNOK",
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "실시간 소음 & 진동 안심 감시단",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Exceeded Alert badge
                AnimatedVisibility(visible = isExceededNow && isMeasuring) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .scale(pulseScale)
                            .background(QuietAlert.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .border(1.dp, QuietAlert, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(QuietAlert)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "기준치 초과!",
                            color = QuietAlert,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            authViewModel?.let { AccountCard(viewModel = it) }
        }

        // New Descriptive Home Safety Index Card (Glassmorphic)
        item {
            val safetyIndex = if (isMeasuring) {
                (100.0 - (currentDb - 30.0).coerceAtLeast(0.0) * 1.3 - (currentVibe * 350.0)).coerceIn(0.0, 100.0).toInt()
            } else {
                100
            }
            
            val statusText: String
            val statusDesc: String
            val statusColor: Color
            val statusEmoji: String
            
            if (!isMeasuring) {
                statusText = "대시보드 대기 중"
                statusDesc = "모니터링 시작을 누르면 실시간 층간소음 분석을 가동합니다."
                statusColor = QuietSecondary
                statusEmoji = "💡"
            } else if (safetyIndex >= 90) {
                statusText = "최상 (매우 고요함)"
                statusDesc = "우리 집 소음안심 지수가 ${safetyIndex}% 로 매우 조용하며 최상의 상태입니다."
                statusColor = QuietSuccess
                statusEmoji = "🌿"
            } else if (safetyIndex >= 70) {
                statusText = "양호 (아늑함 편안함)"
                statusDesc = "이웃 배려와 일상 평화가 완벽히 균형 잡힌 상태입니다. (안심지수 ${safetyIndex}%)"
                statusColor = QuietPrimary
                statusEmoji = "☕"
            } else if (safetyIndex >= 50) {
                statusText = "보통 (활동 수역 발생)"
                statusDesc = "가벼운 미동이나 실시간 발소리가 유입될 조짐이 있습니다. (안심지수 ${safetyIndex}%)"
                statusColor = QuietWarning
                statusEmoji = "🏡"
            } else if (safetyIndex >= 35) {
                statusText = "주의 (소음 경계 상태)"
                statusDesc = "층간 소음 권장 위험선에 거의 도달했습니다. 주의를 권장드립니다. (안심지수 ${safetyIndex}%)"
                statusColor = QuietWarning
                statusEmoji = "⚠️"
            } else {
                statusText = "경고 (한계 데시벨 초과!)"
                statusDesc = "기준 규격을 관통하는 소리가 발생 중입니다! KNOK 안심 대장이 녹화/기록을 수행합니다."
                statusColor = QuietAlert
                statusEmoji = "🚨"
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(statusColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = statusEmoji, fontSize = 20.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "현재 공간 상태",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = statusText,
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        
                        // Circular percentage meter
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { safetyIndex / 100f },
                                modifier = Modifier.size(44.dp),
                                color = statusColor,
                                strokeWidth = 5.dp,
                                trackColor = Color.White.copy(alpha = 0.1f)
                            )
                            Text(
                                text = "${safetyIndex}%",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = statusDesc,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Environment information tip banner (Non empty space design)
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Assessment,
                        contentDescription = "Stat Info",
                        tint = QuietPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "대한민국 공동주택 층간소음 기준",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "✔️ 최고소음 한계: 주간 57dB / 야간 52dB\n✔️ 진동 기준치: 체감 가능한 수준인 0.05m/s² 초과 시 경고",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Dual Telemetry Gauge Meters Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Noise dB Gauge
                Box(modifier = Modifier.weight(1f)) {
                    TelemetryDial(
                        label = "소음 레벨",
                        value = currentDb,
                        maxValue = maxDb,
                        unit = "dB",
                        threshold = viewModel.noiseThreshold,
                        isAlertActive = isMeasuring,
                        colors = Pair(QuietPrimary, QuietAlert)
                    )
                }

                // Vibration m/s2 Gauge
                Box(modifier = Modifier.weight(1f)) {
                    TelemetryDial(
                        label = "바닥 진동",
                        value = currentVibe,
                        maxValue = maxVibe,
                        unit = "m/s²",
                        threshold = viewModel.vibeThreshold,
                        isAlertActive = isMeasuring,
                        colors = Pair(QuietSecondary, QuietAlert)
                    )
                }
            }
        }

        // Premium Programmatic Scrolling Custom Waveform
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "실시간 파동 모니터링",
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        if (isMeasuring) {
                            Text(
                                text = "실시간 기록 중",
                                color = QuietPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        } else {
                            Text(
                                text = "대기 중",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) {
                        SoundWaveCanvas(history = waveHistory, threshold = viewModel.noiseThreshold)
                    }
                }
            }
        }

        // Remote recording control for the external microphone connected to ESP32.
        item {
            espRecorderViewModel?.let { recorderViewModel ->
                Esp32RecordingCard(viewModel = recorderViewModel)
            }
        }

        // Control Panel Deck (Pulsating Start Button)
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isMeasuring) {
                        Text(
                            text = "측정 진행 시간",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatSeconds(durationS),
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Custom note text input (Quick tags support)
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text("측정 비고 / 소음 원인") },
                            placeholder = { Text("예: 위층 망치질, 악기 연주 소리") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.02f),
                                focusedBorderColor = QuietPrimary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                                focusedLabelColor = QuietPrimary,
                                unfocusedLabelColor = TextSecondary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Quick Chips row
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tags = listOf("발걸음", "의자 끎", "망치질", "문 쾅", "가구 이동", "음악 소리")
                            items(tags) { tag ->
                                AssistChip(
                                    onClick = { noteText = tag },
                                    label = { Text(text = tag, color = TextPrimary, fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.stopMeasurementWithoutSaving()
                                    noteText = ""
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(text = "취소", color = TextPrimary)
                            }

                            Button(
                                onClick = {
                                    viewModel.stopMeasurementAndSave(noteText)
                                    noteText = ""
                                },
                                modifier = Modifier.weight(1.5f),
                                colors = ButtonDefaults.buttonColors(containerColor = QuietPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = DarkBackground
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "측정 정지 및 저장",
                                    color = DarkBackground,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // Standing by layout
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Mic standing",
                            tint = QuietPrimary.copy(alpha = 0.5f),
                            modifier = Modifier
                                .size(56.dp)
                                .padding(bottom = 12.dp)
                        )
                        Text(
                            text = "소음 및 진동 모니터링 준비 완료",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "측정 시작 시 오디오 녹음 및 바닥 진동 수치를 실시간 분석하여 데이터베이스에 완벽 보관합니다.",
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                if (hasMicPermission) {
                                    viewModel.startMeasurement(context)
                                } else {
                                    onRequestMicPermission()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = QuietPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start Measurement",
                                tint = DarkBackground
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "실시간 측정 모니터링 시작",
                                color = DarkBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Esp32RecordingCard(viewModel: EspRecorderViewModel) {
    val savedBaseUrl by viewModel.baseUrl.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val message by viewModel.message.collectAsState()
    var baseUrlText by remember { mutableStateOf(savedBaseUrl) }

    val statusText = when (connectionState) {
        EspRecorderConnectionState.DISCONNECTED -> "연결 안 됨"
        EspRecorderConnectionState.CHECKING -> "확인 중"
        EspRecorderConnectionState.CONNECTED -> "연결됨"
        EspRecorderConnectionState.RECORDING -> "녹음 중"
    }
    val statusColor = when (connectionState) {
        EspRecorderConnectionState.DISCONNECTED -> TextSecondary
        EspRecorderConnectionState.CHECKING -> QuietWarning
        EspRecorderConnectionState.CONNECTED -> QuietSuccess
        EspRecorderConnectionState.RECORDING -> QuietAlert
    }
    val durationSeconds = (status?.durationMs ?: 0L) / 1000L
    val recordingProgress = ((status?.durationMs ?: 0L).toFloat() / 30_000f).coerceIn(0f, 1f)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        border = BorderStroke(
            1.dp,
            if (connectionState == EspRecorderConnectionState.RECORDING) {
                QuietAlert.copy(alpha = 0.5f)
            } else {
                Color.White.copy(alpha = 0.12f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(QuietPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "ESP32 Wi-Fi",
                            tint = QuietPrimary,
                            modifier = Modifier.size(21.dp)
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
                            text = "Wi-Fi로 녹음하고 WAV 데이터로 저장",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "휴대폰과 ESP32를 같은 Wi-Fi에 연결하세요. 기본 주소는 mDNS이며, 연결되지 않으면 시리얼 모니터의 IP를 입력하세요.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = baseUrlText,
                onValueChange = { baseUrlText = it },
                label = { Text("ESP32 주소") },
                placeholder = { Text("http://192.168.0.123 또는 http://knok-esp32.local") },
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.setBaseUrl(baseUrlText)
                        viewModel.checkConnection()
                    },
                    enabled = !isBusy && baseUrlText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text("연결 확인", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        viewModel.setBaseUrl(baseUrlText)
                        if (connectionState == EspRecorderConnectionState.RECORDING) {
                            viewModel.stopRecording()
                        } else {
                            viewModel.startRecording()
                        }
                    },
                    enabled = !isBusy && (connectionState == EspRecorderConnectionState.RECORDING || baseUrlText.isNotBlank()),
                    modifier = Modifier.weight(1.35f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connectionState == EspRecorderConnectionState.RECORDING) QuietAlert else QuietPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (connectionState == EspRecorderConnectionState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = DarkBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (connectionState == EspRecorderConnectionState.RECORDING) "중지 및 저장" else "마이크 녹음 시작",
                        color = DarkBackground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (connectionState == EspRecorderConnectionState.RECORDING) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { recordingProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = QuietAlert,
                    trackColor = QuietAlert.copy(alpha = 0.15f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "녹음 시간 ${formatSeconds(durationSeconds)} · 최대 30초 · ${status?.bytesWritten ?: 0L} bytes",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = if (connectionState == EspRecorderConnectionState.DISCONNECTED) QuietWarning else TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun TelemetryDial(
    label: String,
    value: Double,
    maxValue: Double,
    unit: String,
    threshold: Double,
    isAlertActive: Boolean,
    colors: Pair<Color, Color>
) {
    val baseColor = colors.first
    val alertColor = colors.second

    val isLimitExceeded = value >= threshold
    val currentIndicatorColor by animateColorAsState(
        targetValue = if (isLimitExceeded && isAlertActive) alertColor else baseColor,
        animationSpec = tween(300),
        label = "indicator_color"
    )

    // Animated pointer sweep progress
    val targetSweep = if (unit == "dB") {
        // Map 30dB - 100dB to 0.0 - 1.0 progress
        ((value - 30.0) / (95.0 - 30.0)).coerceIn(0.0, 1.0)
    } else {
        // Map 0.0 - 0.2 m/s2 to 0.0 - 1.0 progress
        (value / 0.15).coerceIn(0.0, 1.0)
    }

    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep.toFloat(),
        animationSpec = tween(150),
        label = "sweep"
    )

    GlassCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Gauge painter Box
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(100.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 8.dp.toPx()
                    val centerFactor = size.width / 2

                    // Back Track
                    drawArc(
                        color = Color(0xFF1E293B),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Threshold limit divider dot
                    val threshProgress = if (unit == "dB") {
                        ((threshold - 30.0) / (95.0 - 30.0)).coerceIn(0.0, 1.0)
                    } else {
                        (threshold / 0.15).coerceIn(0.0, 1.0)
                    }
                    val threshAngle = 135f + (threshProgress * 270).toFloat()

                    // Active Sweep Indicator
                    drawArc(
                        color = currentIndicatorColor,
                        startAngle = 135f,
                        sweepAngle = animatedSweep * 270f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                // Inner numerical labels
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (unit == "dB") String.format(Locale.getDefault(), "%.1f", value) else String.format(Locale.getDefault(), "%.3f", value),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = unit,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Max peak session tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(currentIndicatorColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (unit == "dB") "최대 ${String.format(Locale.getDefault(), "%.1f", maxValue)}dB" else "최대 ${String.format(Locale.getDefault(), "%.3f", maxValue)} m/s²",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun SoundWaveCanvas(history: List<Float>, threshold: Double) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (history.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val step = width / 50f // Max History points
        val wavePath = Path()
        val fillPath = Path()

        // Normalize DB values 30-95 in the Canvas Height
        val minDb = 30f
        val maxDb = 95f
        val dbRange = maxDb - minDb

        val points = history.mapIndexed { index, db ->
            val x = index * step
            // Flip height coordinate since y=0 is at the top
            val normDb = (db - minDb) / dbRange
            val y = height - (normDb * height).coerceIn(4f, height - 4f)
            Offset(x, y)
        }

        wavePath.moveTo(points[0].x, points[0].y)
        fillPath.moveTo(points[0].x, points[0].y)

        for (i in 1 until points.size) {
            val p0 = points[i - 1]
            val p1 = points[i]
            // Cubic curve interpolation for smooth wave aesthetics
            val controlX = (p0.x + p1.x) / 2
            wavePath.quadraticTo(p0.x, p0.y, controlX, (p0.y + p1.y) / 2)
            fillPath.quadraticTo(p0.x, p0.y, controlX, (p0.y + p1.y) / 2)
        }

        // Draw last point
        wavePath.lineTo(points.last().x, points.last().y)
        fillPath.lineTo(points.last().x, points.last().y)

        // Close filling path boundary
        fillPath.lineTo(points.last().x, height)
        fillPath.lineTo(0f, height)
        fillPath.close()

        // Draw gradient fill (translucent wave area)
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(QuietPrimary.copy(alpha = 0.25f), Color.Transparent)
            )
        )

        // Outline glow wave
        drawPath(
            path = wavePath,
            color = QuietPrimary,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw standard threshold alert indicator line (horizontal marker at 57dB)
        val limitPercent = (threshold.toFloat() - minDb) / dbRange
        val limitY = height - (limitPercent * height)

        drawLine(
            color = QuietAlert.copy(alpha = 0.6f),
            start = Offset(0f, limitY),
            end = Offset(width, limitY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Square
        )
    }
}

@Composable
fun LogScreen(viewModel: NoiseViewModel) {
    val allRecords by viewModel.allRecords.collectAsState()
    val exceededRecords by viewModel.exceededRecords.collectAsState()

    var showOnlyExceeded by remember { mutableStateOf(false) }
    val recordList = if (showOnlyExceeded) exceededRecords else allRecords

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Log Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "층간소음 기록대장",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "법적 분쟁 시 객관적 증명 자료로 활용해보세요.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            IconButton(onClick = { viewModel.clearAllData() }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear All Records",
                    tint = TextSecondary
                )
            }
        }

        // Exceeded filter selector row (Non empty design)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, RoundedCornerShape(12.dp))
                .clickable { showOnlyExceeded = !showOnlyExceeded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filter Icon",
                    tint = if (showOnlyExceeded) QuietAlert else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "층간소음 기준 초과 로그만 보기",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (showOnlyExceeded) QuietAlert else SlateBorder),
                contentAlignment = Alignment.Center
            ) {
                if (showOnlyExceeded) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(DarkBackground)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Empty Status Handling
        if (recordList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Empty Log Icon",
                        tint = SlateBorder,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (showOnlyExceeded) "기준 초과된 층간소음 기록이 없습니다." else "측정된 소음/진동 기록이 없습니다.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "대시보드에서 '실시간 측정'을 시작해보세요.",
                        color = SlateBorder,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(recordList, key = { it.id }) { record ->
                    LogRecordCard(record = record, onDelete = { viewModel.deleteRecord(record) })
                }
            }
        }
    }
}

@Composable
fun LogRecordCard(record: NoiseRecord, onDelete: () -> Unit) {
    GlassCard(
        border = BorderStroke(1.dp, if (record.isExceeded) QuietAlert.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Status indicator + delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.isExceeded) {
                        Box(
                            modifier = Modifier
                                .background(QuietAlert.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, QuietAlert, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "층간소음 기준 초과!",
                                color = QuietAlert,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(SlateBorder.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (record.recordingSource == "ESP32") "ESP32 외장 마이크" else "실내 기본 범주",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatDate(record.timestamp),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete record",
                        tint = TextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body values details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "최대 소음", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.1f", record.maxDb)} dB",
                        color = if (record.isNoiseExceeded) QuietAlert else TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(text = "평균 소음", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.1f", record.avgDb)} dB",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(text = "바닥 진동", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.3f", record.maxVibration)} m/s²",
                        color = if (record.isVibeExceeded) QuietAlert else TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(text = "세션 기간", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatSeconds(record.durationMs / 1000L),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (record.classificationLabel != "unclassified") {
                val classificationName = when (record.classificationLabel) {
                    "footstep" -> "발걸음"
                    "instant_impact" -> "물건 떨어짐/충격"
                    "dragging_furniture" -> "가구 끌기"
                    "hammering" -> "망치/타격"
                    "vacuum_cleaner" -> "청소기"
                    "normal_or_unknown" -> "일반/미분류 소리"
                    else -> record.classificationLabel
                }
                val confidence = String.format(
                    Locale.getDefault(),
                    "%.0f",
                    record.classificationConfidence * 100.0
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            (if (record.isInterfloorCandidate) QuietAlert else QuietPrimary).copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (record.isInterfloorCandidate) {
                            "AI 감지: $classificationName · 층간소음 후보 · 신뢰도 ${confidence}%"
                        } else {
                            "AI 감지: $classificationName · 신뢰도 ${confidence}%"
                        },
                        color = if (record.isInterfloorCandidate) QuietAlert else QuietPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Optional note field
            if (record.note.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkBackground.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Assessment,
                            contentDescription = "Note mark",
                            modifier = Modifier.size(14.dp),
                            tint = QuietPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = record.note,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingScreen(viewModel: NoiseViewModel) {
    val allRecords by viewModel.allRecords.collectAsState()
    val recordingsWithFiles = allRecords.filter {
        it.recordingPath != null || it.remoteAudioPath != null
    }

    val playingRecordId by viewModel.playingRecordId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val analyzingRecordId by viewModel.analyzingRecordId.collectAsState()
    val analysisErrors by viewModel.analysisErrors.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = "녹음 보관함",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "녹음을 재생하거나 어떤 소리인지 분석해보세요.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "소리 분석을 누르면 녹음이 Google 서버로 전송됩니다. AI 결과는 오인식될 수 있습니다. 5분·10MB 이하 지원.",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        if (recordingsWithFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = "Empty audio files",
                        tint = SlateBorder,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "저장된 녹음 파일이 없습니다.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(recordingsWithFiles, key = { it.id }) { record ->
                    val isCurrentPlaying = playingRecordId == record.id
                    GlassCard(
                        border = BorderStroke(1.dp, if (isCurrentPlaying) QuietPrimary else Color.White.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.playAudio(record) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isCurrentPlaying) QuietPrimary else SlateBorder),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isCurrentPlaying && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play Control",
                                            tint = if (isCurrentPlaying) DarkBackground else TextPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = when {
                                                record.recordingSource == "ESP32" -> "ESP32 외장 마이크"
                                                record.note.isNotEmpty() -> record.note
                                                else -> "일반 소음 측정 세션"
                                            },
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatDate(record.timestamp),
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "최고 ${String.format(Locale.getDefault(), "%.1f", record.maxDb)} dB",
                                        color = if (record.isNoiseExceeded ||
                                            (record.recordingSource == "ESP32" &&
                                                record.maxDb >= EspRecorderViewModel.AUTO_TRIGGER_DB)
                                        ) {
                                            QuietAlert
                                        } else {
                                            QuietPrimary
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (record.isExceeded) {
                                        Box(
                                            modifier = Modifier
                                                .background(QuietAlert.copy(alpha = 0.15f), RoundedCornerShape(5.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "기준 초과",
                                                color = QuietAlert,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            SoundAnalysisPanel(
                                record = record,
                                isAnalyzing = analyzingRecordId == record.id,
                                canAnalyze = analyzingRecordId == null,
                                error = analysisErrors[record.id],
                                onAnalyze = { viewModel.analyzeSound(record) }
                            )

                            // Dynamic seeker bar inside active card
                            if (isCurrentPlaying) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatSeconds((playbackProgress * (record.durationMs / 1000f)).toLong()),
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Slider(
                                        value = playbackProgress,
                                        onValueChange = {},
                                        valueRange = 0f..1f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = QuietPrimary,
                                            activeTrackColor = QuietPrimary,
                                            inactiveTrackColor = SlateBorder
                                        )
                                    )
                                    Text(
                                        text = formatSeconds(record.durationMs / 1000L),
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SoundAnalysisPanel(
    record: NoiseRecord,
    isAnalyzing: Boolean,
    canAnalyze: Boolean,
    error: String?,
    onAnalyze: () -> Unit
) {
    val analysis = SoundAnalysis.fromJsonOrNull(record.soundAnalysisJson)
    Column(
        modifier = Modifier.testTag("sound-analysis-${record.id}").fillMaxWidth()
            .background(QuietPrimary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        if (analysis != null) {
            Text("추정 소리: ${analysis.sound}", color = QuietPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("${analysis.certaintyLabel} · ${analysis.evidence}", color = TextPrimary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("다른 가능성: ${analysis.alternative}", color = TextSecondary, fontSize = 11.sp)
            Text("소리의 종류에 대한 추정이며 발생 위치·원인을 확정하지 않습니다.", color = TextSecondary, fontSize = 10.sp)
        } else {
            OutlinedButton(
                onClick = onAnalyze,
                enabled = canAnalyze && !isAnalyzing && record.soundAnalysisJson == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isAnalyzing) "소리 분석 중…" else if (error != null) "소리 분석 다시 시도" else "소리 분석")
            }
            if (isAnalyzing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = QuietPrimary)
            }
            if (record.soundAnalysisJson != null) {
                Text("저장된 분석 결과를 읽을 수 없습니다.", color = QuietAlert, fontSize = 11.sp)
            }
            error?.let { Text(it, color = QuietAlert, fontSize = 11.sp) }
        }
    }
}

@Composable
fun GuideScreen(viewModel: NoiseViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Guide Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "KNOK 안심 공간가이드",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "법적 층간소음 기준 수치 및 안심 생활 수칙",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Decibel Chart explanation (Legal standard)
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(QuietPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Standard",
                                    tint = QuietPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "대한민국 법적 층간소음 기준",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "환경부 공동주택 층간소음 기준 규칙 및 범위",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // High contrast grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "주간 (06시~22시)",
                                    color = QuietPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "직접 소음 (1분간): 39 dB",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "최고 소음 (순간): 57 dB",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "야간 (22시~06시)",
                                    color = QuietSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "직접 소음 (1분간): 34 dB",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "최고 소음 (순간): 52 dB",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Real-world Decibel Levels Reference Card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "일상 소음 수준 가이드",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        data class GuidelineLevel(val desc: String, val value: String, val ratio: Float, val color: Color)

                        // Progress representation of other sounds
                        listOf(
                            GuidelineLevel("조용한 도서관/속삭임", "30 dB", 0.30f, QuietSuccess),
                            GuidelineLevel("일반 주택 거실 평온함", "40 dB", 0.40f, QuietSuccess),
                            GuidelineLevel("공동주택 직접소음 한계 (야간)", "34 dB", 0.34f, QuietPrimary),
                            GuidelineLevel("공동주택 직접소음 한계 (주간)", "39 dB", 0.39f, QuietPrimary),
                            GuidelineLevel("가정 내 청소기 가동 소음", "60 dB", 0.60f, QuietWarning),
                            GuidelineLevel("공동주택 순간소음 한계 (주간)", "57 dB", 0.57f, QuietWarning),
                            GuidelineLevel("아이들 뛰는 소리 (쿵쿵)", "65 dB", 0.65f, QuietAlert)
                        ).forEach { (desc, value, progressVal, color) ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = desc, color = TextSecondary, fontSize = 11.sp)
                                    Text(text = value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { progressVal },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = color,
                                    trackColor = Color.White.copy(alpha = 0.05f)
                                )
                            }
                        }
                    }
                }
            }

            // Comfortable Living Habits (안심 생활 수칙)
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "층간소음 예방 생활 수칙",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val rules = listOf(
                            "실내 슬리퍼 착용으로 발소리를 쿵쿵 내지 않기" to "슬리퍼는 약 10~15 dB 이상의 소음 감쇄 효과를 줍니다.",
                            "늦은 밤이나 이른 아침 세탁기와 청소기 가동 금지" to "밤 10시 이후 진도와 마찰음은 주택 구조를 타고 훨씬 멀리 도달합니다.",
                            "의자 다리와 가구 밑에 안심 소음 방지 패드 적용" to "끌리는 가구 기계 마찰음은 위아래 세대에 강렬한 쐐기음을 전달합니다.",
                            "문이나 샷시를 세게 닫지 않고 충격 완화 범퍼 적용" to "순간 최강 충격음은 대한민국 아파트 층간 분쟁 원인의 큰 요인입니다."
                        )

                        rules.forEachIndexed { idx, (rule, desc) ->
                            Row(
                                modifier = Modifier.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(QuietPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        color = QuietPrimary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = rule, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(text = desc, color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuietBottomNavigation(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF030712).copy(alpha = 0.82f)) // Translucent iOS style background
            .navigationBarsPadding()
    ) {
        // Transparent border brush
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Bottom
        ) {
            BottomNavItem(
                icon = Icons.Default.History,
                label = "소음대장",
                isSelected = selectedTab == 1,
                modifier = Modifier.weight(1f),
                onClick = { onTabSelected(1) }
            )
            BottomNavItem(
                icon = Icons.Default.Folder,
                label = "녹음목록",
                isSelected = selectedTab == 2,
                modifier = Modifier.weight(1f),
                onClick = { onTabSelected(2) }
            )
            BottomNavItem(
                icon = Icons.Default.Home,
                label = "홈",
                isSelected = selectedTab == 0,
                isCenterCircle = true,
                modifier = Modifier.weight(1f),
                onClick = { onTabSelected(0) }
            )
            BottomNavItem(
                icon = Icons.Default.Wifi,
                label = "Wi-Fi",
                isSelected = selectedTab == 3,
                modifier = Modifier.weight(1f),
                onClick = { onTabSelected(3) }
            )
            BottomNavItem(
                icon = Icons.Default.Info,
                label = "공간가이드",
                isSelected = selectedTab == 4,
                modifier = Modifier.weight(1f),
                onClick = { onTabSelected(4) }
            )
            BottomNavItem(
                icon = Icons.Default.Mic,
                label = "상시녹음",
                isSelected = selectedTab == 5,
                modifier = Modifier.weight(1f),
                onClick = { onTabSelected(5) }
            )
        }
    }
}

@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    isCenterCircle: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val activeColor = QuietPrimary
    val inactiveColor = TextSecondary

    if (isCenterCircle) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val breathAlpha by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(modifier)
                .padding(bottom = 6.dp)
        ) {
            // Pulse glow aura circle (Backlight)
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (isSelected) QuietPrimary else QuietSecondary).copy(alpha = breathAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.04f)
                            )
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.12f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .clickable(onClick = onClick),
                content = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (isSelected) QuietPrimary else TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        }
    } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = modifier
                    .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = if (isSelected) activeColor else inactiveColor,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

// Helpers for formatters
fun formatSeconds(seconds: Long): String {
    val s = seconds % 60
    val m = (seconds / 60) % 60
    val h = seconds / 3600
    return if (h > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREAN)
    return sdf.format(Date(timestamp))
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
fun DashboardPreview() {
    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        QuietBottomNavigation(selectedTab = 0) {}
    }
}
