package com.hmessaging

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SmsSyncService
import com.hmessaging.system.Diagnostics
import com.hmessaging.ui.nav.HmApp
import com.hmessaging.ui.nav.Routes
import com.hmessaging.ui.theme.HmTheme
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Permissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-activity host.
 *
 * [AppCompatActivity] rather than `ComponentActivity` because the biometric prompt needs a
 * `FragmentActivity` to attach to.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // A newly granted contacts permission has to invalidate the memoised "no name" answers
        // and re-resolve the names already stored against each thread.
        val graph = AppGraph.from(this)
        graph.invalidateCaches()
        graph.applicationScope.launch { graph.messageRepository.refreshContactNames() }
    }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { importSystemSmsOnce() }

    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = AppGraph.from(this)
        val threadId = intent?.takeIf { it.action == ACTION_OPEN_THREAD }
            ?.getLongExtra(EXTRA_THREAD_ID, -1L)
            ?.takeIf { it >= 0 }
        val startRoute = if (intent?.action == ACTION_OPEN_SCHEDULED) Routes.SCHEDULED else null

        requestMissingPermissions()

        setContent {
            val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            HmTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        settings.appLockEnabled && !unlocked -> LockScreen(onUnlock = ::promptUnlock)

                        // The default-SMS banner used to live here, above the navigation host,
                        // which pushed it under the status bar and gave it no insets of its own.
                        // It is now part of the conversations screen, inside that screen's
                        // Scaffold, so the window insets apply to it like any other content.
                        else -> HmApp(
                            initialThreadId = threadId,
                            initialRoute = startRoute,
                            onRequestDefaultSmsApp = ::requestDefaultSmsRole,
                        )
                    }
                }
            }
        }

        if (deviceCannotAuthenticate()) unlocked = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The role can be granted from system Settings as well as from our own prompt, so the
        // one-time history import is driven by observing the role, not by a dialog result.
        importSystemSmsOnce()
        catchUpOnMessages()
    }

    /**
     * Pulls anything the phone's SMS store has that we do not.
     *
     * Without this the app depends entirely on a broadcast that some ROMs never deliver, and the
     * only way to see a new message is to trigger an import by hand.
     */
    private fun catchUpOnMessages() {
        val graph = AppGraph.from(this)
        graph.applicationScope.launch {
            val settings = graph.prefs.settings.first()
            val progress = graph.smsImporter.syncNew(deliverThroughPipeline = true)
            if (progress.imported > 0) {
                graph.diagnostics.record(Diagnostics.KIND_SYNC, "on open — picked up ${progress.imported}")
            }
            graph.messageRepository.refreshContactNames()
            if (settings.liveSyncEnabled) SmsSyncService.start(this@MainActivity)
        }
    }

    /** App lock is only meaningful when the device can actually authenticate. */
    private fun deviceCannotAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS

    private fun promptUnlock() {
        if (deviceCannotAuthenticate()) {
            unlocked = true
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_locked))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    private fun requestMissingPermissions() {
        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestDefaultSmsRole() {
        roleLauncher.launch(AppRoles.defaultSmsRequestIntent(this))
    }

    /**
     * Pulls the phone's existing SMS history in once.
     *
     * Reading the SMS provider needs READ_SMS, not the default-SMS role — gating this on the role
     * meant a user who had granted the permission but not the role saw a permanently empty list
     * with no explanation.
     */
    private fun importSystemSmsOnce() {
        if (!Permissions.has(this, android.Manifest.permission.READ_SMS)) return
        val graph = AppGraph.from(this)
        graph.applicationScope.launch {
            if (graph.prefs.settings.first().systemSmsImported) return@launch
            val progress = graph.smsImporter.importAll()
            // Only latch the flag on a successful read. Latching it after a failed query is what
            // left the app with an empty list and no second attempt.
            if (progress.succeeded) graph.prefs.setSystemSmsImported(true)
            graph.diagnostics.record(
                Diagnostics.KIND_IMPORT,
                if (progress.succeeded) {
                    "imported ${progress.imported}, skipped ${progress.skipped}"
                } else {
                    "FAILED — ${progress.error}; will retry"
                },
            )
        }
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.hmessaging.action.OPEN_THREAD"
        const val ACTION_OPEN_SCHEDULED = "com.hmessaging.action.OPEN_SCHEDULED"
        const val EXTRA_THREAD_ID = "thread_id"

        private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_locked),
            style = MaterialTheme.typography.titleLarge,
        )
        Button(onClick = onUnlock, modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.unlock))
        }
    }
}
