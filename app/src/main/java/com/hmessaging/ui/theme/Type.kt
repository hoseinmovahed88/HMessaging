package com.hmessaging.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The platform default family is used deliberately: it is the only family guaranteed to carry a
 * complete Persian/Arabic shaping set on every device this app targets.
 *
 * Weights lean heavier than Material's defaults — HyperOS leads with a large, bold page title and
 * keeps body text quiet underneath it.
 *
 * Every style asks for [TextDirection.Content], which is what makes Persian read correctly. Left to
 * itself a paragraph takes its direction from the layout, so under an English locale a Persian
 * message was laid out left-to-right: colons and brackets jumped to the wrong end of the line and
 * text hugged the wrong margin. `Content` takes the direction from the first strong character
 * instead, so each piece of text — a message, a template, a contact name — is laid out in its own
 * language's direction regardless of which language the app's own labels are in. Material reads
 * `bodyLarge` through `LocalTextStyle`, so text fields inherit it too and typing behaves the same
 * way as reading.
 */
val HmTypography = Typography(
    displaySmall = hmStyle(FontWeight.Bold, 34.sp, 42.sp, (-0.5).sp),
    headlineLarge = hmStyle(FontWeight.Bold, 30.sp, 38.sp, (-0.4).sp),
    headlineMedium = hmStyle(FontWeight.Bold, 26.sp, 34.sp),
    headlineSmall = hmStyle(FontWeight.SemiBold, 22.sp, 30.sp),
    titleLarge = hmStyle(FontWeight.SemiBold, 18.sp, 26.sp),
    titleMedium = hmStyle(FontWeight.Medium, 16.sp, 22.sp),
    titleSmall = hmStyle(FontWeight.Medium, 14.sp, 20.sp),
    bodyLarge = hmStyle(FontWeight.Normal, 16.sp, 23.sp),
    bodyMedium = hmStyle(FontWeight.Normal, 14.sp, 20.sp),
    bodySmall = hmStyle(FontWeight.Normal, 12.sp, 17.sp),
    labelLarge = hmStyle(FontWeight.Medium, 14.sp, 19.sp),
    labelMedium = hmStyle(FontWeight.Medium, 12.sp, 16.sp),
    labelSmall = hmStyle(FontWeight.Medium, 11.sp, 15.sp),
)

private fun hmStyle(
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    textDirection = TextDirection.Content,
)
