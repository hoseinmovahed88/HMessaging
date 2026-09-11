package com.hmessaging.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.ui.graphics.vector.ImageVector
import com.hmessaging.R

object Routes {
    const val CONVERSATIONS = "conversations"
    const val THREAD = "thread/{threadId}"
    const val NEW_MESSAGE = "new"
    const val SCHEDULED = "scheduled"
    const val BLOCKED = "blocked"
    const val AUTO_REPLY = "autoreply"
    const val FORWARDING = "forwarding"
    const val OTP = "otp"
    const val TEMPLATES = "templates"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    fun thread(threadId: Long): String = "thread/$threadId"
}

/** One entry in the navigation drawer. */
data class DrawerDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

val drawerDestinations = listOf(
    DrawerDestination(Routes.CONVERSATIONS, R.string.nav_conversations, Icons.Filled.Forum),
    DrawerDestination(Routes.SCHEDULED, R.string.nav_scheduled, Icons.Filled.Schedule),
    DrawerDestination(Routes.BLOCKED, R.string.nav_blocked, Icons.Filled.Block),
    DrawerDestination(Routes.AUTO_REPLY, R.string.nav_auto_reply, Icons.Filled.SmartToy),
    DrawerDestination(Routes.FORWARDING, R.string.nav_forwarding, Icons.Filled.Send),
    DrawerDestination(Routes.OTP, R.string.nav_otp, Icons.Filled.Password),
    DrawerDestination(Routes.TEMPLATES, R.string.nav_templates, Icons.Filled.Bookmark),
    DrawerDestination(Routes.STATS, R.string.nav_stats, Icons.Filled.Insights),
    DrawerDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)
