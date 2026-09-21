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
 *
 * The label is the SIM's own name only when the names actually tell the SIMs apart. A phone that
 * reports the same name for both — this one answers "Me" for each — would otherwise print that
 * word on every message and distinguish nothing, which is the one thing this badge exists to do.
 * Where the names do not separate them, the slot numbers do.
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
    val namesDistinguish = slots.map { it.displayName.trim().lowercase() }
        .let { names -> names.none { it.isEmpty() } && names.distinct().size == names.size }
    val label = slot.displayName.trim()
        .takeIf { namesDistinguish && it.length <= MAX_NAME_LENGTH }
        ?: slot.slotLabel
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = BADGE_TINT),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private const val BADGE_TINT = 0.14f

/** Longer than this and a carrier's name is a sentence, not a label. */
private const val MAX_NAME_LENGTH = 12
