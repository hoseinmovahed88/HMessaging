package com.hmessaging.feature.block

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.launch

/**
 * Applies the same block list to incoming calls.
 *
 * Only active when the user grants the call-screening role *and* turns the setting on; otherwise
 * every call is explicitly allowed through, which is what Android requires of a screening service.
 */
@RequiresApi(Build.VERSION_CODES.N)
class HmCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        val graph = AppGraph.from(applicationContext)
        graph.applicationScope.launch {
            val blocked = runCatching {
                graph.blockEngine.evaluateCaller(number) is BlockDecision.Blocked
            }.getOrDefault(false)
            respondWith(callDetails, reject = blocked)
        }
    }

    private fun respondWith(callDetails: Call.Details, reject: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(reject)
            .setRejectCall(reject)
            .setSkipCallLog(false)
            .setSkipNotification(reject)
            .build()
        runCatching { respondToCall(callDetails, response) }
    }
}
