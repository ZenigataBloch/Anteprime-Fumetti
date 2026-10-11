package it.example.anteprime

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"), LIGHT("Chiaro"), DARK("Scuro"), AMOLED("AMOLED (nero puro)")
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3A4FC4), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5C5F72), tertiary = Color(0xFFB3261E),
    background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB8C3FF), onPrimary = Color(0xFF0A1B8F),
    primaryContainer = Color(0xFF2335AB), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD), tertiary = Color(0xFFFFB4AB),
    background = Color(0xFF121318), surface = Color(0xFF121318)
)

@Composable
fun AppTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        else -> true
    }
    val useDynamic = dynamic && Build.VERSION.SDK_INT >= 31
    var scheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(ctx)
        useDynamic -> dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    if (mode == ThemeMode.AMOLED) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF111111), surfaceContainerHigh = Color(0xFF181818),
            surfaceContainerHighest = Color(0xFF1F1F1F)
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
