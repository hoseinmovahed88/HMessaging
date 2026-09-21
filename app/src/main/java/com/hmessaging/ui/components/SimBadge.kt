package com.hmessaging.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hmessaging.sms.SimSlot

/**
 * Which SIM a message used.
 *
 * Drawn only when the phone has more than one, and only when the message records one it can name:
 * on a single-SIM phone the answer is never in doubt, and a badge reading "SIM 1" beside every
 * line would be a permanent reminder of nothing.
 */
@Composable
fun SimBadge(
    subscriptionId: Int,
    slots: List<SimSlot>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (slots.size < 2) return
    val slot = slots.firstOrNull { it.subscriptionId == subscriptionId } ?: return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = BADGE_TINT),
        modifier = modifier,
    ) {
        Text(
            text = slot.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private const val BADGE_TINT = 0.14f
