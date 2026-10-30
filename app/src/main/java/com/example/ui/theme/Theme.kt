package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.BuildConfig

private val V3ColorScheme = lightColorScheme(
    primary = Color(0xFF006FBA), onPrimary = Color(0xFFF7F2EB),
    secondary = Color(0xFF006FBA), onSecondary = Color(0xFFF7F2EB),
    background = Color(0xFFF7F2EB), onBackground = Color(0xFF006FBA),
    surface = Color(0xFFFFFCF8), onSurface = Color(0xFF006FBA),
    surfaceVariant = Color(0xFFEAF2F7), onSurfaceVariant = Color(0xFF006FBA),
    outline = Color(0xFF7697AC), error = Color(0xFFB43E38)
)

private val DarkColorScheme =
  darkColorScheme(
    primary = QuietPrimary,
    secondary = QuietSecondary,
    tertiary = QuietTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onTertiary = TextPrimary
  )

private val LightColorScheme =
  lightColorScheme(
    primary = AccentTeal,
    secondary = PrimaryBlue,
    tertiary = QuietTertiary,
    background = LightSlate,
    surface = Color.White,
    onBackground = DarkNavy,
    onSurface = DarkNavy,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      BuildConfig.KNOK_V3 -> V3ColorScheme
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
