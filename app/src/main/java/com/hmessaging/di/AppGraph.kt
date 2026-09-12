package com.hmessaging.di

import android.content.Context
import com.hmessaging.backup.BackupManager
import com.hmessaging.data.db.HmDatabase
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.feature.autoreply.AutoReplyEngine
import com.hmessaging.feature.block.BlockEngine
import com.hmessaging.feature.forward.ForwardEngine
import com.hmessaging.feature.otp.OtpPresenter
import com.hmessaging.feature.quickreply.QuickReplyPresenter
import com.hmessaging.feature.schedule.ScheduleManager
import com.hmessaging.notify.Notifications
import com.hmessaging.sms.IncomingMessagePipeline
import com.hmessaging.sms.SimManager
import com.hmessaging.sms.SmsImporter
import com.hmessaging.sms.SmsSender
import com.hmessaging.sms.SystemSmsWriter
import com.hmessaging.system.Diagnostics
import com.hmessaging.system.ForegroundTracker
import com.hmessaging.util.ContactsLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph.
 *
 * Broadcast receivers and services are constructed by the platform, not by an injector, so every
 * entry point reaches the same singletons through [from]. Everything is lazy: a receiver that only
 * needs the schedule manager never pays for the SMS importer.
 */
class AppGraph private constructor(val appContext: Context) {

    /** Outlives any one receiver's `goAsync` window; work is never tied to a component's life. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: HmDatabase by lazy { HmDatabase.build(appContext) }
    val prefs: AppPrefs by lazy { AppPrefs(appContext) }

    val threadDao by lazy { database.threadDao() }
    val messageDao by lazy { database.messageDao() }
    val blockDao by lazy { database.blockDao() }
    val scheduleDao by lazy { database.scheduleDao() }
    val autoReplyDao by lazy { database.autoReplyDao() }
    val forwardDao by lazy { database.forwardDao() }
    val otpDao by lazy { database.otpDao() }
    val templateDao by lazy { database.templateDao() }
    val diagDao by lazy { database.diagDao() }

    val diagnostics: Diagnostics by lazy { Diagnostics(appContext, diagDao, messageDao, threadDao, prefs, systemSmsWriter) }

    /** Registered by the Application; see [ForegroundTracker]. */
    val foreground = ForegroundTracker()

    val contacts: ContactsLookup by lazy { ContactsLookup(appContext) }
    val simManager: SimManager by lazy { SimManager(appContext) }
    val systemSmsWriter: SystemSmsWriter by lazy { SystemSmsWriter(appContext) }
    val notifications: Notifications by lazy { Notifications(appContext) }

    val messageRepository: MessageRepository by lazy {
        MessageRepository(threadDao, messageDao, contacts, systemSmsWriter)
    }

    val smsSender: SmsSender by lazy {
        SmsSender(appContext, messageRepository, prefs, simManager)
    }

    val blockEngine: BlockEngine by lazy { BlockEngine(blockDao, prefs, contacts) }

    val otpPresenter: OtpPresenter by lazy { OtpPresenter(appContext, otpDao, prefs, notifications) }

    val quickReplyPresenter: QuickReplyPresenter by lazy {
        QuickReplyPresenter(appContext, prefs, foreground)
    }

    val autoReplyEngine: AutoReplyEngine by lazy {
        AutoReplyEngine(autoReplyDao, prefs, smsSender, contacts)
    }

    val forwardEngine: ForwardEngine by lazy {
        ForwardEngine(forwardDao, prefs, smsSender, contacts)
    }

    val incomingPipeline: IncomingMessagePipeline by lazy {
        IncomingMessagePipeline(
            repository = messageRepository,
            diagnostics = diagnostics,
            blockEngine = blockEngine,
            otpPresenter = otpPresenter,
            autoReplyEngine = autoReplyEngine,
            forwardEngine = forwardEngine,
            notifications = notifications,
            quickReplyPresenter = quickReplyPresenter,
            prefs = prefs,
        )
    }

    val scheduleManager: ScheduleManager by lazy {
        ScheduleManager(appContext, scheduleDao, smsSender, notifications)
    }

    val smsImporter: SmsImporter by lazy {
        SmsImporter(appContext, threadDao, messageDao, messageRepository) { incomingPipeline }
    }

    val backupManager: BackupManager by lazy {
        BackupManager(appContext, database, prefs, messageRepository, scheduleManager)
    }

    /**
     * Text shared into the app without a recipient, waiting for the compose screen to claim it.
     * One-shot: reading it clears it, so rotating the screen does not resurrect an old share.
     */
    @Volatile
    var pendingShareBody: String? = null

    fun consumePendingShareBody(): String? {
        val body = pendingShareBody
        pendingShareBody = null
        return body
    }

    /** Drops memoised contact names and compiled rule patterns after the data behind them changes. */
    fun invalidateCaches() {
        contacts.invalidate()
        simManager.invalidate()
        blockEngine.invalidateCaches()
        autoReplyEngine.invalidateCaches()
        forwardEngine.invalidateCaches()
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
