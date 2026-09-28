package com.hmessaging.backup

import android.content.Context
import android.net.Uri
import com.hmessaging.data.db.HmDatabase
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MatchTarget
import com.hmessaging.data.model.MatchType
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.model.ScheduleStatus
import com.hmessaging.data.model.SourceMatch
import com.hmessaging.data.model.ThemeMode
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.feature.schedule.ScheduleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Exports and restores everything the user configured, as a single human-readable JSON file. */
class BackupManager(
    private val context: Context,
    private val database: HmDatabase,
    private val prefs: AppPrefs,
    private val repository: MessageRepository,
    private val scheduleManager: ScheduleManager,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class ImportResult(
        val blockRules: Int = 0,
        val autoReplyRules: Int = 0,
        val forwardRules: Int = 0,
        val scheduled: Int = 0,
        val templates: Int = 0,
        val messages: Int = 0,
    )

    suspend fun export(target: Uri, includeMessages: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
                exportTo(stream, includeMessages)
            } ?: error("Could not open the selected file for writing")
        }
    }

    /** Writes the backup document to an already-open stream; returns its length in characters. */
    suspend fun exportTo(stream: OutputStream, includeMessages: Boolean): Int {
        val payload = buildBackup(includeMessages)
        val text = json.encodeToString(BackupFile.serializer(), payload)
        stream.write(text.toByteArray(Charsets.UTF_8))
        return text.length
    }

    suspend fun import(source: Uri, replaceExisting: Boolean): Result<ImportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(source)?.use { stream ->
                    importFrom(stream, replaceExisting)
                } ?: error("Could not open the selected file for reading")
            }
        }

    suspend fun importFrom(stream: InputStream, replaceExisting: Boolean): ImportResult {
        val text = stream.readBytes().toString(Charsets.UTF_8)
        val payload = json.decodeFromString(BackupFile.serializer(), text)
        return applyBackup(payload, replaceExisting)
    }

    private suspend fun buildBackup(includeMessages: Boolean): BackupFile {
        val settings = prefs.settings.first()
        return BackupFile(
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                themeMode = settings.themeMode.name,
                dynamicColor = settings.dynamicColor,
                deliveryReports = settings.deliveryReports,
                numberLongMessages = settings.numberLongMessages,
                chunkChars = settings.chunkChars,
                signature = settings.signature,
                signatureEnabled = settings.signatureEnabled,
                otpDetectionEnabled = settings.otpDetectionEnabled,
                otpPopupEnabled = settings.otpPopupEnabled,
                otpAutoCopy = settings.otpAutoCopy,
                otpAutoDeleteDays = settings.otpAutoDeleteDays,
                otpPopupSeconds = settings.otpPopupSeconds,
                blockPrivateNumbers = settings.blockPrivateNumbers,
                blockNonContacts = settings.blockNonContacts,
                screenCalls = settings.screenCalls,
                keepBlockedMessages = settings.keepBlockedMessages,
                autoReplyEnabled = settings.autoReplyEnabled,
                forwardingEnabled = settings.forwardingEnabled,
                forwardMaxPerHour = settings.forwardMaxPerHour,
                notificationPreview = settings.notificationPreview,
                appLockEnabled = settings.appLockEnabled,
            ),
            blockRules = database.blockDao().allRules().map {
                BackupBlockRule(it.pattern, it.matchType.name, it.target.name, it.enabled, it.note)
            },
            autoReplyRules = database.autoReplyDao().allRules().map {
                BackupAutoReplyRule(
                    name = it.name,
                    enabled = it.enabled,
                    matchType = it.matchType.name,
                    pattern = it.pattern,
                    replyText = it.replyText,
                    daysMask = it.daysMask,
                    startMinuteOfDay = it.startMinuteOfDay,
                    endMinuteOfDay = it.endMinuteOfDay,
                    cooldownMinutes = it.cooldownMinutes,
                    maxPerDay = it.maxPerDay,
                    priority = it.priority,
                )
            },
            forwardRules = database.forwardDao().allRules().map {
                BackupForwardRule(
                    name = it.name,
                    enabled = it.enabled,
                    matchType = it.matchType.name,
                    pattern = it.pattern,
                    targets = it.targets,
                    includeSender = it.includeSender,
                    includeTimestamp = it.includeTimestamp,
                    subscriptionId = it.subscriptionId,
                    template = it.template,
                )
            },
            scheduled = database.scheduleDao().pending().map {
                BackupScheduled(
                    recipients = it.recipients,
                    body = it.body,
                    scheduledAt = it.scheduledAt,
                    repeat = it.repeat.name,
                    repeatUntil = it.repeatUntil,
                    subscriptionId = it.subscriptionId,
                    status = it.status.name,
                )
            },
            templates = database.templateDao().all().map {
                BackupTemplate(it.title, it.body, it.sortOrder)
            },
            messages = if (includeMessages) {
                database.messageDao().all().map {
                    BackupMessage(it.address, it.body, it.date, it.type.name, it.read, it.isOtp, it.subscriptionId)
                }
            } else {
                emptyList()
            },
        )
    }

    private suspend fun applyBackup(payload: BackupFile, replaceExisting: Boolean): ImportResult {
        if (replaceExisting) {
            database.blockDao().allRules().forEach { database.blockDao().deleteRuleById(it.id) }
            database.autoReplyDao().allRules().forEach { database.autoReplyDao().deleteById(it.id) }
            database.forwardDao().allRules().forEach { database.forwardDao().deleteById(it.id) }
            database.templateDao().all().forEach { database.templateDao().deleteById(it.id) }
        }

        payload.settings?.let { restoreSettings(it) }

        payload.blockRules.forEach {
            database.blockDao().upsertRule(
                BlockRuleEntity(
                    pattern = it.pattern,
                    matchType = enumOr(it.matchType, MatchType.EXACT),
                    target = enumOr(it.target, MatchTarget.SENDER),
                    enabled = it.enabled,
                    note = it.note,
                ),
            )
        }
        payload.autoReplyRules.forEach {
            database.autoReplyDao().upsert(
                AutoReplyRuleEntity(
                    name = it.name,
                    enabled = it.enabled,
                    matchType = enumOr(it.matchType, SourceMatch.ALL),
                    pattern = it.pattern,
                    replyText = it.replyText,
                    daysMask = it.daysMask,
                    startMinuteOfDay = it.startMinuteOfDay,
                    endMinuteOfDay = it.endMinuteOfDay,
                    cooldownMinutes = it.cooldownMinutes,
                    maxPerDay = it.maxPerDay,
                    priority = it.priority,
                ),
            )
        }
        payload.forwardRules.forEach {
            database.forwardDao().upsert(
                ForwardRuleEntity(
                    name = it.name,
                    enabled = it.enabled,
                    matchType = enumOr(it.matchType, SourceMatch.ALL),
                    pattern = it.pattern,
                    targets = it.targets,
                    includeSender = it.includeSender,
                    includeTimestamp = it.includeTimestamp,
                    subscriptionId = it.subscriptionId,
                    template = it.template,
                ),
            )
        }
        payload.templates.forEach {
            database.templateDao().upsert(TemplateEntity(title = it.title, body = it.body, sortOrder = it.sortOrder))
        }

        // Scheduled messages need their alarms re-armed, and anything already past is dropped.
        var restoredSchedules = 0
        payload.scheduled
            .filter { it.scheduledAt > System.currentTimeMillis() }
            .forEach {
                scheduleManager.save(
                    ScheduledMessageEntity(
                        recipients = it.recipients,
                        body = it.body,
                        scheduledAt = it.scheduledAt,
                        repeat = enumOr(it.repeat, RepeatMode.NONE),
                        repeatUntil = it.repeatUntil,
                        subscriptionId = it.subscriptionId,
                        status = enumOr(it.status, ScheduleStatus.PENDING),
                    ),
                )
                restoredSchedules++
            }

        var restoredMessages = 0
        if (payload.messages.isNotEmpty()) {
            val known = HashSet(database.messageDao().fingerprints())
            payload.messages.forEach { message ->
                if (!known.add("${message.address}|${message.date}")) return@forEach
                val threadId = repository.threadIdFor(message.address)
                val type = enumOr(message.type, MessageType.INBOX)
                database.messageDao().insert(
                    MessageEntity(
                        threadId = threadId,
                        address = message.address,
                        body = message.body,
                        date = message.date,
                        type = type,
                        read = message.read,
                        status = if (type == MessageType.SENT) DeliveryStatus.SENT else DeliveryStatus.NONE,
                        subscriptionId = message.subscriptionId,
                        isOtp = message.isOtp,
                    ),
                )
                restoredMessages++
            }
        }

        return ImportResult(
            blockRules = payload.blockRules.size,
            autoReplyRules = payload.autoReplyRules.size,
            forwardRules = payload.forwardRules.size,
            scheduled = restoredSchedules,
            templates = payload.templates.size,
            messages = restoredMessages,
        )
    }

    private suspend fun restoreSettings(settings: BackupSettings) {
        prefs.setThemeMode(enumOr(settings.themeMode, ThemeMode.SYSTEM))
        prefs.setDynamicColor(settings.dynamicColor)
        prefs.setDeliveryReports(settings.deliveryReports)
        prefs.setNumberLongMessages(settings.numberLongMessages)
        prefs.setChunkChars(settings.chunkChars)
        prefs.setSignature(settings.signature)
        prefs.setSignatureEnabled(settings.signatureEnabled)
        prefs.setOtpDetectionEnabled(settings.otpDetectionEnabled)
        prefs.setOtpPopupEnabled(settings.otpPopupEnabled)
        prefs.setOtpAutoCopy(settings.otpAutoCopy)
        prefs.setOtpAutoDeleteDays(settings.otpAutoDeleteDays)
        prefs.setOtpPopupSeconds(settings.otpPopupSeconds)
        prefs.setBlockPrivateNumbers(settings.blockPrivateNumbers)
        prefs.setBlockNonContacts(settings.blockNonContacts)
        prefs.setScreenCalls(settings.screenCalls)
        prefs.setKeepBlockedMessages(settings.keepBlockedMessages)
        prefs.setAutoReplyEnabled(settings.autoReplyEnabled)
        prefs.setForwardingEnabled(settings.forwardingEnabled)
        prefs.setForwardMaxPerHour(settings.forwardMaxPerHour)
        prefs.setNotificationPreview(settings.notificationPreview)
        prefs.setAppLockEnabled(settings.appLockEnabled)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    companion object {
        const val MIME_TYPE = "application/json"

        fun suggestedFileName(): String =
            "hmessaging-backup-${System.currentTimeMillis()}.json"
    }
}
