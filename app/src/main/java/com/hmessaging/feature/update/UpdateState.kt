package com.hmessaging.feature.update

import com.hmessaging.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where the update flow has got to. Shared: the banner and the settings card show the same one. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateStatus
    data class Ready(val info: UpdateInfo, val file: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Holds the update flow for the whole app, so the banner on the conversation list and the card in
 * settings are two views of one state rather than two checks racing each other.
 *
 * Checks are rate-limited to one every few hours and skipped entirely once an update is in hand:
 * the answer cannot change while a download is sitting there waiting to be installed.
 */
class UpdateCoordinator(
    private val checker: UpdateChecker,
    private val scope: CoroutineScope,
) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val installedVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private var lastCheckedAt = 0L
    private var job: Job? = null

    /** The quiet check on app resume; does nothing if one ran recently. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheckedAt < CHECK_INTERVAL_MS) return
        check(userAsked = false)
    }

    /**
     * @param userAsked reports "you are up to date" and any failure. The resume check stays silent
     *   about both — an app that announces its own failure to reach GitHub every time it opens is
     *   worse company than one that says nothing until it has news.
     */
    fun check(userAsked: Boolean) {
        if (job?.isActive == true) return
        if (_status.value is UpdateStatus.Downloading || _status.value is UpdateStatus.Ready) return

        job = scope.launch {
            if (userAsked) _status.value = UpdateStatus.Checking
            val info = runCatching { checker.check() }
            lastCheckedAt = System.currentTimeMillis()
            _status.value = when {
                info.isFailure -> if (userAsked) {
                    UpdateStatus.Failed(info.exceptionOrNull()?.message.orEmpty())
                } else {
                    UpdateStatus.Idle
                }

                info.getOrNull() != null -> UpdateStatus.Available(info.getOrNull()!!)
                userAsked -> UpdateStatus.UpToDate
                else -> UpdateStatus.Idle
            }
        }
    }

    fun download(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = UpdateStatus.Downloading(info, 0)
            val result = checker.download(info) { percent ->
                val current = _status.value
                if (current is UpdateStatus.Downloading) {
                    _status.value = current.copy(percent = percent)
                }
            }
            _status.value = result.fold(
                onSuccess = { UpdateStatus.Ready(info, it) },
                onFailure = { UpdateStatus.Failed(it.message.orEmpty()) },
            )
        }
    }

    /** Puts the banner away until the next check finds something. */
    fun dismiss() {
        job?.cancel()
        checker.clearDownloads()
        _status.value = UpdateStatus.Idle
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
