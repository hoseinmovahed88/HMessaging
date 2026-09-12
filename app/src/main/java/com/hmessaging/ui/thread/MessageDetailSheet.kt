package com.hmessaging.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.util.EntityType
import com.hmessaging.util.TextEntities
import com.hmessaging.util.TimeFormat

/**
 * What a tap on a message opens: the text, selectable, and everything in it worth copying.
 *
 * A message bubble cannot be both selectable text and a tap target — the text selection swallows
 * the gestures the list needs — so selection lives here, in a sheet where nothing competes for the
 * same drag. The detected numbers sit above it as chips because the common case is not wanting the
 * message at all, but the one card number inside it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageDetailSheet(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onForward: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entities = remember(message.id, message.body) { TextEntities.detect(message.body) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = TimeFormat.full(message.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entities.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entities.forEach { entity ->
                        AssistChip(
                            onClick = { onCopy(entity.value) },
                            label = { Text(entity.value) },
                            leadingIcon = {
                                Text(
                                    text = stringResource(entityLabel(entity.type)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }

            SelectionContainer {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = BodyMaxHeight.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetAction(Icons.Filled.ContentCopy, R.string.copy) { onCopy(message.body) }
                SheetAction(Icons.Filled.Share, R.string.share) { onShare(message.body) }
                SheetAction(Icons.Filled.Forward, R.string.forward) { onForward(message.body) }
                SheetAction(Icons.Filled.Delete, R.string.delete, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(stringResource(labelRes))
    }
}

private fun entityLabel(type: EntityType): Int = when (type) {
    EntityType.IBAN -> R.string.entity_iban
    EntityType.CARD -> R.string.entity_card
    EntityType.PHONE -> R.string.entity_phone
    EntityType.ACCOUNT -> R.string.entity_account
    EntityType.CODE -> R.string.entity_code
    EntityType.URL -> R.string.entity_link
}

private const val BodyMaxHeight = 320
