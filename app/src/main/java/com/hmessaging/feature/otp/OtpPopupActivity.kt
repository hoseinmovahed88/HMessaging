package com.hmessaging.feature.otp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hmessaging.MainActivity
import com.hmessaging.R
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.ui.theme.HmTheme
import com.hmessaging.util.Clipboards
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The verification-code pop-up.
 *
 * It exists as an activity rather than a notification because the clipboard is the point: from
 * Android 10 onward only a foreground app may write to it, so the copy happens here.
 */
class OtpPopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val otpId = intent.getLongExtra(EXTRA_OTP_ID, -1L)
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val service = intent.getStringExtra(EXTRA_SERVICE)

        if (code.isBlank()) {
            finish()
            return
        }

        val graph = AppGraph.from(this)
        graph.notifications.cancelOtp(otpId)

        lifecycleScope.launch {
            val settings = graph.prefs.settings.first()
            if (settings.otpAutoCopy) copyCode(otpId, code)
        }

        setContent {
            val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            HmTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                applySystemBarStyle = false,
            ) {
                OtpPopup(
                    code = code,
                    from = service ?: PhoneNumbers.format(sender),
                    autoDismissSeconds = settings.otpPopupSeconds,
                    onCopy = {
                        copyCode(otpId, code)
                        finish()
                    },
                    onOpen = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    private fun copyCode(otpId: Long, code: String) {
        Clipboards.copy(this, getString(R.string.otp_title), code, sensitive = true)
        if (otpId >= 0) {
            val graph = AppGraph.from(this)
            graph.applicationScope.launch { graph.otpDao.markCopied(otpId) }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        private const val EXTRA_OTP_ID = "otp_id"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_SERVICE = "service"

        fun intent(
            context: Context,
            otpId: Long,
            code: String,
            sender: String,
            serviceName: String?,
        ): Intent = Intent(context, OtpPopupActivity::class.java).apply {
            putExtra(EXTRA_OTP_ID, otpId)
            putExtra(EXTRA_CODE, code)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_SERVICE, serviceName)
        }
    }
}

@Composable
private fun OtpPopup(
    code: String,
    from: String,
    autoDismissSeconds: Int,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(autoDismissSeconds) }
    LaunchedEffect(autoDismissSeconds) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        onDismiss()
    }
    val progress by animateFloatAsState(
        targetValue = if (autoDismissSeconds > 0) remaining.toFloat() / autoDismissSeconds else 0f,
        label = "otp-countdown",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.otp_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = from,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = code,
                    fontSize = CODE_FONT_SIZE.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = CODE_LETTER_SPACING.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                if (autoDismissSeconds > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dismiss))
                    }
                    TextButton(onClick = onOpen) {
                        Text(stringResource(R.string.open))
                    }
                    TextButton(onClick = onCopy) {
                        Text(stringResource(R.string.copy))
                    }
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.55f
private const val CODE_FONT_SIZE = 40
private const val CODE_LETTER_SPACING = 6
