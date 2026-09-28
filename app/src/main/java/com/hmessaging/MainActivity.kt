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
import androidx.lifecycle.lifecycleScope
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SmsImporter
import com.hmessaging.sms.SmsSyncService
import com.hmessaging.sms.WatcherNeed
import com.hmessaging.system.Diagnostics
import com.hmessaging.ui.nav.HmApp
import com.hmessaging.ui.nav.Routes
import com.hmessaging.ui.theme.HmTheme
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Permissions
import com.hmessaging.util.SmsIntents
import kotlinx.coroutines.delay
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
    ) { result ->
        // The result code alone says whether the dialog was shown and accepted; the app-op
        // afterwards says whether the platform did what a grant is supposed to do. Both go in
        // the log, because a dialog that closes at once (the role was already held) changes
        // nothing, and the reader needs to be able to see that.
        val graph = AppGraph.from(this)
        graph.applicationScope.launch {
            graph.diagnostics.record(
                Diagnostics.KIND_SYNC,
                "SMS role request returned ${result.resultCode}; write permission is now " +
                    AppRoles.smsWriteOp(this@MainActivity).name.lowercase(),
            )
        }
        importSystemSmsOnce()
    }

    private var unlocked by mutableStateOf(false)
    private var openThreadId by mutableStateOf<Long?>(null)
    private var openRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = AppGraph.from(this)

        requestMissingPermissions()
        handleIncomingIntent(intent)

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
                            initialThreadId = openThreadId,
                            initialRoute = openRoute,
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
        handleIncomingIntent(intent)
    }

    /**
     * Routes a notification tap, or a "message this person" request from the dialer or contacts,
     * to the right conversation.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == ACTION_OPEN_SCHEDULED) {
            openRoute = Routes.SCHEDULED
            return
        }

        if (intent.action == ACTION_OPEN_THREAD) {
            intent.getLongExtra(EXTRA_THREAD_ID, -1L).takeIf { it >= 0 }?.let { openThreadId = it }
            return
        }

        val target = SmsIntents.parse(intent) ?: return
        val address = target.address
        if (address == null) {
            // A share with text but no recipient: hand the text to the compose screen and let
            // the user pick who it goes to.
            AppGraph.from(this).pendingShareBody = target.body
            openRoute = Routes.NEW_MESSAGE
            return
        }

        val graph = AppGraph.from(this)
        lifecycleScope.launch {
            val threadId = graph.messageRepository.threadIdFor(address)
            // The composer restores a thread's draft on open, so prefilling it is all that is
            // needed to carry the shared text across.
            target.body?.let { graph.messageRepository.setDraft(threadId, it) }
            openThreadId = threadId
        }
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
            // Let the conversation list finish its own queries before adding provider reads to
            // the same dispatcher; otherwise the screen stays blank while this work runs.
            delay(RESUME_WORK_DELAY_MS)
            val settings = graph.prefs.settings.first()
            val progress = graph.smsImporter.syncNew(deliverThroughPipeline = true)
            if (progress.imported > 0) {
                graph.diagnostics.record(Diagnostics.KIND_SYNC, "on open — picked up ${progress.imported}")
            }
            healGaps(graph)
            graph.messageRepository.refreshContactNames(onlyMissing = true)
            // The one-time sweep for duplicated messages that used to run here is gone. It cleaned up
            // after a bug fixed long ago, and on a fresh install — where its flag starts unset — it
            // deleted genuine repeats instead: the same text from the same sender twice within ten
            // minutes. Phones that needed it ran it already.
            // Keeps going until nothing is left without a provider row: the platform store is the
            // only copy every other app on the phone can see, and this app is the only one allowed
            // to write it. Capped per pass, so a long history fills over several opens rather than
            // holding one up.
            // Before writing anything, check that what the app believes is already there really
            // is. A message remembers the platform row it was written to and is never written
            // again — so a store that has been emptied underneath the app would otherwise stay
            // empty forever, with every message convinced it was already handled.
            val forgotten = graph.messageRepository.reconcileSystemProvider()
            if (forgotten > 0) {
                graph.diagnostics.record(
                    Diagnostics.KIND_SYNC,
                    "$forgotten message(s) were missing from the system SMS store — queued again",
                )
            }
            val backfill = graph.messageRepository.backfillSystemProvider()
            if (backfill.written > 0) {
                graph.diagnostics.record(
                    Diagnostics.KIND_SYNC,
                    "wrote ${backfill.written} message(s) into the system SMS store",
                )
            }
            // Rows left without a thread id by earlier versions belong to no conversation as far
            // as every other app on the phone is concerned.
            val rethreaded = graph.messageRepository.repairProviderThreadIds()
            if (rethreaded > 0) {
                graph.diagnostics.record(
                    Diagnostics.KIND_SYNC,
                    "gave $rethreaded system SMS row(s) a thread id",
                )
            }
            val merged = graph.messageRepository.mergeDuplicateThreads()
            if (merged > 0) {
                graph.diagnostics.record(
                    Diagnostics.KIND_SYNC,
                    "merged $merged duplicate conversation(s) split by country code",
                )
            }
            // Started only while this phone has shown it needs it. The service cannot run
            // without a permanent notification, so leaving it running on a phone that delivers
            // its own broadcasts charges the user a line in the shade forever for nothing.
            val stats = graph.prefs.deliveryStats.first()
            if (WatcherNeed.shouldRun(this@MainActivity, settings, stats)) {
                SmsSyncService.start(this@MainActivity)
            } else {
                SmsSyncService.stop(this@MainActivity)
            }
            // Quiet unless it finds something; see UpdateCoordinator.
            graph.updates.checkIfDue()
        }
    }

    /**
     * Finds messages the phone holds and this app does not, wherever in the history they are.
     *
     * The sync above only reads rows newer than the newest one held, which is the right cheap
     * question every open — and the wrong one once, when the app's own history has a hole in the
     * middle: a database restored from an older copy, an import that stopped short, anything that
     * left the phone knowing more than the app while the app's newest message stayed newer than
     * the hole. Nothing this app did then would ever look back. So when the phone's store holds
     * noticeably more rows than this app has recorded from it, the whole store is walked once,
     * and that walk is not repeated until the row count changes.
     */
    private suspend fun healGaps(graph: AppGraph) {
        val providerRows = graph.systemSmsWriter.rowCount() ?: return
        val held = graph.messageDao.countWithSystemId()
        val settings = graph.prefs.settings.first()
        if (providerRows.toLong() == settings.gapCheckedAtRows) return
        if (providerRows - held <= GAP_TOLERANCE) {
            graph.prefs.setGapCheckedAtRows(providerRows.toLong())
            return
        }
        val walk = graph.smsImporter.importAll(SmsImporter.NO_LIMIT)
        graph.diagnostics.record(
            Diagnostics.KIND_IMPORT,
            "the phone holds $providerRows rows and this app had $held of them — " +
                if (walk.succeeded) {
                    "walked the whole store, picked up ${walk.imported}, skipped ${walk.skipped}"
                } else {
                    "walk FAILED: ${walk.error}"
                },
        )
        if (walk.succeeded) graph.prefs.setGapCheckedAtRows(providerRows.toLong())
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
            // Two passes. The first is capped so the list fills within seconds of the first
            // open; the second takes everything else, however long the history is. It used to
            // stop after the first and mark the import done — and on a phone holding a hundred
            // thousand messages that left all but the newest few thousand on the phone and out
            // of the app, which looked exactly like losing them.
            val first = graph.smsImporter.importAll()
            graph.diagnostics.record(
                Diagnostics.KIND_IMPORT,
                if (first.succeeded) {
                    "imported ${first.imported}, skipped ${first.skipped}"
                } else {
                    "FAILED — ${first.error}; will retry"
                },
            )
            if (!first.succeeded) return@launch
            val rest = if (first.imported >= SmsImporter.DEFAULT_LIMIT) {
                graph.smsImporter.importAll(SmsImporter.NO_LIMIT).also {
                    graph.diagnostics.record(
                        Diagnostics.KIND_IMPORT,
                        if (it.succeeded) {
                            "rest of the history: imported ${it.imported}, skipped ${it.skipped}"
                        } else {
                            "rest of the history FAILED — ${it.error}; will retry"
                        },
                    )
                }
            } else {
                null
            }
            // Only latch the flag once the whole store has been read. Latching it after a failed
            // query is what left the app with an empty list and no second attempt; latching it
            // after a capped pass is what left it with a short one.
            if (rest == null || rest.succeeded) graph.prefs.setSystemSmsImported(true)
        }
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.hmessaging.action.OPEN_THREAD"
        const val ACTION_OPEN_SCHEDULED = "com.hmessaging.action.OPEN_SCHEDULED"
        const val EXTRA_THREAD_ID = "thread_id"

        private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        private const val RESUME_WORK_DELAY_MS = 700L

        /**
         * Rows the phone may hold beyond what this app recorded without that meaning a hole:
         * duplicates the store itself carries, and rows other apps wrote before this one was
         * default. Anything past this is worth a full walk.
         */
        private const val GAP_TOLERANCE = 20
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
