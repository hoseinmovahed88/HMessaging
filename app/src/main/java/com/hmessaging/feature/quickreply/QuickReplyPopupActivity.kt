package com.hmessaging.feature.quickreply

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hmessaging.MainActivity
import com.hmessaging.R
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.ui.theme.HmTheme
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * The floating reply window for a message that lands while the phone is in use.
 *
 * Shaped like a heads-up banner and pinned to the top so it covers as little of whatever the reader
 * was doing as possible, but unlike a banner it stays until dismissed and carries a real text
 * field — the whole point is answering without leaving the app you were in. Tapping outside closes
 * it; the message is already stored and its notification is already posted, so closing loses
 * nothing.
 */
class QuickReplyPopupActivity : ComponentActivity() {

    private var message by mutableStateOf(QuickReplyMessage.EMPTY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!adopt(intent)) {
            finish()
            return
        }

        val graph = AppGraph.from(this)

        setContent {
            val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            HmTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                applySystemBarStyle = false,
            ) {
                QuickReplyPopup(
                    message = message,
                    onSend = ::send,
                    onOpen = ::openConversation,
                    onDismiss = ::finish,
                )
            }
        }
    }

    /** A second message replaces the first rather than stacking another window on top of it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!adopt(intent)) finish()
    }

    private fun adopt(intent: Intent): Boolean {
        val parsed = parse(intent) ?: return false
        message = parsed
        return true
    }

    private fun send(text: String) {
        val target = message
        if (text.isBlank() || target.threadId <= 0) return
        val graph = AppGraph.from(this)
        // The application scope, not this activity's: finishing must not cancel the send.
        graph.applicationScope.launch {
            graph.smsSender.send(recipients = listOf(target.address), body = text)
            graph.messageRepository.markThreadRead(target.threadId)
            graph.notifications.cancelThread(target.threadId)
        }
        finish()
    }

    private fun openConversation() {
        val target = message
        val graph = AppGraph.from(this)
        lifecycleScope.launch { graph.messageRepository.markThreadRead(target.threadId) }
        graph.notifications.cancelThread(target.threadId)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_THREAD
                data = Uri.parse("hmessaging://thread/${target.threadId}")
                putExtra(MainActivity.EXTRA_THREAD_ID, target.threadId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finish()
    }

    companion object {
        private const val EXTRA_THREAD_ID = "thread_id"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_AT = "at"

        fun intent(
            context: Context,
            threadId: Long,
            address: String,
            contactName: String?,
            body: String,
            receivedAt: Long,
        ): Intent = Intent(context, QuickReplyPopupActivity::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra(EXTRA_ADDRESS, address)
            putExtra(EXTRA_NAME, contactName)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_AT, receivedAt)
        }

        private fun parse(intent: Intent): QuickReplyMessage? {
            val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
            val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
            val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
            if (threadId <= 0 || address.isBlank() || body.isBlank()) return null
            return QuickReplyMessage(
                threadId = threadId,
                address = address,
                contactName = intent.getStringExtra(EXTRA_NAME),
                body = body,
                receivedAt = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis()),
            )
        }
    }
}

internal data class QuickReplyMessage(
    val threadId: Long,
    val address: String,
    val contactName: String?,
    val body: String,
    val receivedAt: Long,
) {
    val title: String get() = contactName ?: PhoneNumbers.format(address)

    companion object {
        val EMPTY = QuickReplyMessage(0, "", null, "", 0)
    }
}

@Composable
private fun QuickReplyPopup(
    message: QuickReplyMessage,
    onSend: (String) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the message, so a second one arriving does not inherit a half-typed reply meant
    // for the first.
    var reply by remember(message.threadId, message.receivedAt) { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // No ripple and no indication: this is the "tap outside to close" area, not a button.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .statusBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(CARD_CORNER.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                // Swallows taps on the card so they do not reach the dismiss area behind it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = TimeFormat.clock(message.receivedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A long message scrolls inside the card instead of pushing the reply box off-screen.
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = BODY_MAX_HEIGHT.dp)
                        .verticalScroll(rememberScrollState()),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextField(
                        value = reply,
                        onValueChange = { reply = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.quick_reply_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        maxLines = REPLY_MAX_LINES,
                        shape = RoundedCornerShape(22.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { onSend(reply) },
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        // The keyboard is not raised on arrival: most of these are read and
                        // dismissed, and stealing the keyboard mid-task would be worse than the
                        // banner this replaces. One tap on the field opens it.
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (reply.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier
                            .padding(start = 6.dp, bottom = 4.dp)
                            .size(44.dp),
                    ) {
                        IconButton(
                            onClick = { onSend(reply) },
                            enabled = reply.isNotBlank(),
                        ) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = stringResource(R.string.send),
                                tint = if (reply.isNotBlank()) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.open)) }
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.35f
private const val CARD_CORNER = 26
private const val BODY_MAX_HEIGHT = 200
private const val REPLY_MAX_LINES = 4
