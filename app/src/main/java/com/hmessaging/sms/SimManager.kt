package com.hmessaging.sms

import android.Manifest
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.util.Permissions

data class SimSlot(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String?,
    val number: String?,
) {
    /**
     * What to print beside a message: the SIM's own name.
     *
     * That name is the one the phone shows everywhere else and, where the owner has set it, the
     * one they chose — "Me" and "Dad" on this phone. Only a carrier's unedited mouthful like
     * "Irancell Prepaid Line 1" falls back to the slot number, because it will not fit.
     */
    val shortLabel: String
        get() = displayName.trim().takeIf { it.isNotEmpty() && it.length <= MAX_SHORT_LABEL }
            ?: "SIM ${slotIndex + 1}"

    private companion object {
        const val MAX_SHORT_LABEL = 12
    }

}

/** Enumerates active SIMs so the composer can offer a per-message SIM choice on dual-SIM phones. */
class SimManager(private val context: Context) {

    /**
     * Active SIMs, cached.
     *
     * Reading them is a binder call into the telephony stack, and the composer asks for the list
     * on every state emission — that is once per keystroke. SIMs do not change that often;
     * [invalidate] covers the cases where they do.
     */
    fun slots(): List<SimSlot> {
        cachedSlots?.let { return it }
        if (!Permissions.has(context, Manifest.permission.READ_PHONE_STATE)) {
            return emptyList<SimSlot>().also { cachedSlots = it }
        }
        val manager = context.getSystemService(SubscriptionManager::class.java)
            ?: return emptyList<SimSlot>().also { cachedSlots = it }
        return runCatching { readSlots(manager) }.getOrDefault(emptyList()).also { cachedSlots = it }
    }

    fun invalidate() {
        cachedSlots = null
    }

    @Volatile
    private var cachedSlots: List<SimSlot>? = null

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun readSlots(manager: SubscriptionManager): List<SimSlot> =
        manager.activeSubscriptionInfoList.orEmpty().map { info ->
            SimSlot(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                displayName = info.displayName?.toString().orEmpty()
                    .ifBlank { "SIM ${info.simSlotIndex + 1}" },
                carrierName = info.carrierName?.toString(),
                // Deprecated but still the only per-subscription number; guarded by READ_PHONE_NUMBERS.
                number = @Suppress("DEPRECATION") info.number?.takeIf { it.isNotBlank() },
            )
        }.sortedBy { it.slotIndex }

    fun isMultiSim(): Boolean = slots().size > 1

    /**
     * Resolves the subscription a message should go out on: an explicit choice wins, then the
     * user's default, then the system default, then "let the platform decide".
     */
    fun resolveSubscriptionId(explicit: Int, settings: AppSettings): Int {
        if (explicit >= 0) return explicit
        if (settings.defaultSubscriptionId >= 0) return settings.defaultSubscriptionId
        val systemDefault = runCatching {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        return if (systemDefault == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            AppSettings.SUBSCRIPTION_UNSET
        } else {
            systemDefault
        }
    }

    fun displayNameFor(subscriptionId: Int): String? =
        slots().firstOrNull { it.subscriptionId == subscriptionId }?.displayName

    /** The device's own number, when the SIM exposes it. Used to keep forwards from looping. */
    fun ownNumbers(): List<String> {
        val fromSubscriptions = slots().mapNotNull { it.number }
        if (fromSubscriptions.isNotEmpty()) return fromSubscriptions
        if (!Permissions.has(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        return runCatching { telephony.line1Number }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            .orEmpty()
    }
}
