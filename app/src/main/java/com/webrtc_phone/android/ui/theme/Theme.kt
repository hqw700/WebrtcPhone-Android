package com.webrtc_phone.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webrtc_phone.android.data.settings.ThemeMode

object AppColors {
    val Brand = Color(0xFF3D7BFF)
    val BrandDark = Color(0xFF7AA5FF)
    val Online = Color(0xFF22C55E)
    val Offline = Color(0xFF9CA3AF)
    val Busy = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B)
}

private val Light = lightColorScheme(
    primary = AppColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF00225E),
    secondary = Color(0xFF5B6478),
    secondaryContainer = Color(0xFFE2E7F2),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F7),
    surfaceContainer = Color(0xFFF1F3F8),
    surfaceContainerHigh = Color(0xFFEBEEF5),
    outline = Color(0xFFC7CCD8),
    outlineVariant = Color(0xFFE3E6EE),
    error = AppColors.Danger,
)

private val Dark = darkColorScheme(
    primary = AppColors.BrandDark,
    onPrimary = Color(0xFF00296F),
    primaryContainer = Color(0xFF1D3F87),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB9C2D6),
    secondaryContainer = Color(0xFF2B3242),
    background = Color(0xFF0E1116),
    surface = Color(0xFF151922),
    surfaceVariant = Color(0xFF1E2430),
    surfaceContainer = Color(0xFF191E28),
    surfaceContainerHigh = Color(0xFF222835),
    outline = Color(0xFF3A4252),
    outlineVariant = Color(0xFF2A3140),
    error = Color(0xFFFF6B6B),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WebrtcPhoneTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
