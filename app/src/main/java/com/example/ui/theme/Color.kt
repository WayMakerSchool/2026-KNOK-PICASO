package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.BuildConfig

// Light Theme / Default colors
val PrimaryBlue = Color(0xFF0F172A)
val AccentTeal = Color(0xFF0D9488)
val LightSlate = Color(0xFFF8FAFC)
val DarkNavy = Color(0xFF0F172A)

// Dark Theme / Premium High Contrast Theme
val DarkBackground = if (BuildConfig.KNOK_V3) Color(0xFFF7F2EB) else Color(0xFF0B0F19)
val DarkSurface = if (BuildConfig.KNOK_V3) Color(0xFFFFFCF8) else Color(0xFF151F32)
val DarkSurfaceVariant = if (BuildConfig.KNOK_V3) Color(0xFFEAF2F7) else Color(0xFF1E293B)

val QuietPrimary = if (BuildConfig.KNOK_V3) Color(0xFF006FBA) else Color(0xFF38BDF8)
val QuietSecondary = if (BuildConfig.KNOK_V3) Color(0xFF006FBA) else Color(0xFF818CF8)
val QuietTertiary = if (BuildConfig.KNOK_V3) Color(0xFF006FBA) else Color(0xFFF43F5E)

val QuietSuccess = if (BuildConfig.KNOK_V3) Color(0xFF006FBA) else Color(0xFF10B981)
val QuietWarning = if (BuildConfig.KNOK_V3) Color(0xFF956223) else Color(0xFFF59E0B)
val QuietAlert = if (BuildConfig.KNOK_V3) Color(0xFFB43E38) else Color(0xFFEF4444)

val SlateBorder = if (BuildConfig.KNOK_V3) Color(0xFFD4E2EA) else Color(0xFF334155)
val TextPrimary = if (BuildConfig.KNOK_V3) Color(0xFF006FBA) else Color(0xFFF8FAFC)
val TextSecondary = if (BuildConfig.KNOK_V3) Color(0xFF006FBA) else Color(0xFF94A3B8)

