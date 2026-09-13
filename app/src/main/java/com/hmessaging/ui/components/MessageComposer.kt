@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hmessaging.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hmessaging.R

/**
 * The bar a message is written in.
 *
 * Shared between the conversation and the new-message screen because they are the same act: the
 * new-message screen used to be an unrelated form with a big outlined box and a send icon in the
 * title bar, and arriving in a conversation after sending from it felt like arriving in a different
 * app.
 *
 * Scheduling is on a long press of send rather than a button, for the reason it is everywhere else:
 * it is used once in a hundred messages and a permanent button charges all hundred for it.
 */
@Composable
fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    modifier: Modifier = Modifier,
    onSchedule: (() -> Unit)? = null,
    placeholder: String = stringResource(R.string.type_a_message),
    maxLines: Int = ComposerMaxLines,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    above: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            above?.invoke()
            Row(verticalAlignment = Alignment.Bottom) {
                leading?.invoke(this)
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = {
                        Text(
                            text = placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge),
                    maxLines = maxLines,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke(this)
                Surface(
                    shape = CircleShape,
                    color = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .padding(start = 4.dp, bottom = 4.dp)
                        .size(SendButtonSize.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                enabled = canSend,
                                onClick = onSend,
                                onLongClick = onSchedule,
                                onLongClickLabel = stringResource(R.string.schedule_send),
                            ),
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val ComposerMaxLines = 6
private const val SendButtonSize = 44
