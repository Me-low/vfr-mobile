package org.openvfr.efb.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class CockpitDisplayMode(val label: String) {
    MATERIAL_YOU("Material You"),
    SUNLIGHT("Plein Soleil"),
    NIGHT_RED("Nuit OLED")
}

private val SunlightColorScheme = lightColorScheme(
    primary = Color(0xFF004D40),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE0F2F1),
    onSecondaryContainer = Color(0xFF00251A),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFF000000),
    error = Color(0xFFB00020)
)

private val NightRedColorScheme = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2B0000),
    onSecondaryContainer = Color(0xFFFF6961),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFF453A),
    surfaceContainerHigh = Color(0xFF140000),
    outline = Color(0xFF8E0000),
    error = Color(0xFFFF1744)
)

@Composable
fun CockpitTheme(
    mode: CockpitDisplayMode,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val colorScheme = when (mode) {
        CockpitDisplayMode.SUNLIGHT -> SunlightColorScheme
        CockpitDisplayMode.NIGHT_RED -> NightRedColorScheme
        CockpitDisplayMode.MATERIAL_YOU -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (isDark) darkColorScheme() else lightColorScheme()
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
