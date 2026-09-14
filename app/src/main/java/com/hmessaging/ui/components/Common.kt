package com.hmessaging.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hmessaging.di.AppGraph
import com.hmessaging.util.ContactPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** A settings row with a title, optional explanation and a trailing switch. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
    }
}

/**
 * The contact's own picture where there is one, and the monogram where there is not.
 *
 * Both the photo lookup and the decode happen off the main thread, and the monogram is drawn
 * meanwhile — so a list scrolls at full speed and the pictures appear as they are read, rather
 * than the list waiting for them.
 */
@Composable
fun ContactAvatar(
    name: String,
    address: String?,
    modifier: Modifier = Modifier,
    size: Int = 44,
) {
    val context = LocalContext.current
    val photo: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, address) {
        val number = address
        value = if (number.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val graph = AppGraph.from(context)
                ContactPhotos.load(context, graph.contacts.photoFor(number))?.asImageBitmap()
            }
        }
    }

    val bitmap = photo
    if (bitmap == null) {
        Avatar(name = name, modifier = modifier, size = size)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
        )
    }
}

/** Circular monogram used in place of a contact photo. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Int = 44) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
    Surface(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        color = avatarColor(name),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/** Stable per-contact tint so the same person always gets the same colour. */
private fun avatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF1B5E9B), Color(0xFF00696E), Color(0xFF6C5B7B), Color(0xFF9C4146),
        Color(0xFF2E7D32), Color(0xFF7B5800), Color(0xFF4A5B9B), Color(0xFF8D4E85),
    )
    return palette[Math.floorMod(seed.hashCode(), palette.size)]
}

private const val DISABLED_ALPHA = 0.38f
