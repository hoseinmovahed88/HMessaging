package com.hmessaging.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette modelled on HyperOS: a true-black dark theme with lifted grey cards, a near-white light
 * theme with white cards, and a single saturated blue accent. Contrast is carried by the card
 * surfaces rather than by borders or elevation.
 */

// Accent
val HyperBlue = Color(0xFF3482FF)
val HyperBlueDark = Color(0xFF4C8DFF)
val HyperBlueContainerLight = Color(0xFFE3EDFF)
val HyperBlueContainerDark = Color(0xFF16325C)

val HyperGreen = Color(0xFF34C759)
val HyperOrange = Color(0xFFFF9500)
val HyperRed = Color(0xFFE54545)
val HyperRedDark = Color(0xFFFF6B6B)

// Light
val LightBackground = Color(0xFFF4F4F6)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDEDF0)
val LightOnSurface = Color(0xFF0B0B0C)
val LightOnSurfaceVariant = Color(0xFF8A8A8E)
val LightOutline = Color(0xFFD9D9DE)

// Dark
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1A1A1C)
val DarkSurfaceVariant = Color(0xFF242426)
val DarkOnSurface = Color(0xFFF2F2F5)
val DarkOnSurfaceVariant = Color(0xFF98989D)
val DarkOutline = Color(0xFF2E2E31)

// Message bubbles: outgoing rides the accent, incoming sits on the card colour.
val BubbleOutgoingLight = HyperBlue
val BubbleOutgoingDark = Color(0xFF2F6FE0)
val BubbleIncomingLight = Color(0xFFFFFFFF)
val BubbleIncomingDark = Color(0xFF242426)
