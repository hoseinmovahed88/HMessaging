package com.hmessaging.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hmessaging.data.model.ThemeMode

/** Colours that Material's scheme has no slot for but the HyperOS look needs. */
data class HyperColors(
    val bubbleOutgoing: Color,
    val bubbleIncoming: Color,
    val onBubbleOutgoing: Color,
    val onBubbleIncoming: Color,
    val success: Color,
    val warning: Color,
    val divider: Color,
)

val LocalHyperColors = staticCompositionLocalOf {
    HyperColors(
        bubbleOutgoing = BubbleOutgoingLight,
        bubbleIncoming = BubbleIncomingLight,
        onBubbleOutgoing = Color.White,
        onBubbleIncoming = LightOnSurface,
        success = HyperGreen,
        warning = HyperOrange,
        divider = LightOutline,
    )
}

/** Generous radii — the single most recognisable part of the HyperOS shape language. */
val HyperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = HyperBlue,
    onPrimary = Color.White,
    primaryContainer = HyperBlueContainerLight,
    onPrimaryContainer = Color(0xFF0B2A5B),
    secondary = HyperBlue,
    onSecondary = Color.White,
    secondaryContainer = HyperBlueContainerLight,
    onSecondaryContainer = Color(0xFF0B2A5B),
    tertiary = HyperOrange,
    onTertiary = Color.White,
    error = HyperRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE1E1),
    onErrorContainer = Color(0xFF5C0000),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline,
)

private val DarkColors = darkColorScheme(
    primary = HyperBlueDark,
    onPrimary = Color.White,
    primaryContainer = HyperBlueContainerDark,
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = HyperBlueDark,
    onSecondary = Color.White,
    secondaryContainer = HyperBlueContainerDark,
    onSecondaryContainer = Color(0xFFD7E5FF),
    tertiary = HyperOrange,
    onTertiary = Color.Black,
    error = HyperRedDark,
    onError = Color.Black,
    errorContainer = Color(0xFF5C1414),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurface,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = DarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
)

@Composable
fun HmTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    applySystemBarStyle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors
        else -> LightColors
    }

    val hyperColors = if (dark) {
        HyperColors(
            bubbleOutgoing = BubbleOutgoingDark,
            bubbleIncoming = BubbleIncomingDark,
            onBubbleOutgoing = Color.White,
            onBubbleIncoming = DarkOnSurface,
            success = HyperGreen,
            warning = HyperOrange,
            divider = DarkOutline,
        )
    } else {
        HyperColors(
            bubbleOutgoing = BubbleOutgoingLight,
            bubbleIncoming = BubbleIncomingLight,
            onBubbleOutgoing = Color.White,
            onBubbleIncoming = LightOnSurface,
            success = HyperGreen,
            warning = HyperOrange,
            divider = LightOutline,
        )
    }

    val view = LocalView.current
    if (applySystemBarStyle && !view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalHyperColors provides hyperColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = HmTypography,
            shapes = HyperShapes,
            content = content,
        )
    }
}
