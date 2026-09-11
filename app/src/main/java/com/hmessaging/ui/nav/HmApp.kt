package com.hmessaging.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hmessaging.R
import com.hmessaging.ui.autoreply.AutoReplyScreen
import com.hmessaging.ui.blocked.BlockedScreen
import com.hmessaging.ui.compose.NewMessageScreen
import com.hmessaging.ui.conversations.ConversationsScreen
import com.hmessaging.ui.diagnostics.DiagnosticsScreen
import com.hmessaging.ui.forward.ForwardScreen
import com.hmessaging.ui.otp.OtpScreen
import com.hmessaging.ui.scheduled.ScheduledScreen
import com.hmessaging.ui.settings.SettingsScreen
import com.hmessaging.ui.stats.StatsScreen
import com.hmessaging.ui.templates.TemplatesScreen
import com.hmessaging.ui.thread.ThreadScreen
import com.hmessaging.ui.thread.ThreadViewModel
import kotlinx.coroutines.launch

@Composable
fun HmApp(
    initialThreadId: Long? = null,
    initialRoute: String? = null,
    onRequestDefaultSmsApp: () -> Unit = {},
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Notification taps and shortcuts arrive as an intent, not as navigation.
    LaunchedEffect(initialThreadId, initialRoute) {
        initialThreadId?.let { navController.navigate(Routes.thread(it)) }
        initialRoute?.let { navController.navigate(it) }
    }

    val navigateTo: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo(Routes.CONVERSATIONS) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    // ModalNavigationDrawer does not take back itself, so back closed the whole activity while the
    // drawer was open — and since the drawer state is saved, the app came back with it still open.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            ) {
                Spacer(Modifier.height(36.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
                drawerDestinations.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(destination.labelRes)) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        selected = currentRoute == destination.route,
                        onClick = { navigateTo(destination.route) },
                        shape = RoundedCornerShape(16.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedContainerColor = MaterialTheme.colorScheme.background,
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        NavHost(navController = navController, startDestination = Routes.CONVERSATIONS) {
            composable(Routes.CONVERSATIONS) {
                ConversationsScreen(
                    onOpenDrawer = openDrawer,
                    onOpenThread = { navController.navigate(Routes.thread(it)) },
                    onNewMessage = { navController.navigate(Routes.NEW_MESSAGE) },
                    onRequestDefaultSmsApp = onRequestDefaultSmsApp,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(
                route = Routes.THREAD,
                arguments = listOf(navArgument(ThreadViewModel.ARG_THREAD_ID) { type = NavType.StringType }),
            ) {
                ThreadScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NEW_MESSAGE) {
                NewMessageScreen(
                    onBack = { navController.popBackStack() },
                    onOpenThread = { threadId ->
                        navController.popBackStack()
                        navController.navigate(Routes.thread(threadId))
                    },
                )
            }
            composable(Routes.SCHEDULED) {
                ScheduledScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.BLOCKED) {
                BlockedScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.AUTO_REPLY) {
                AutoReplyScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.FORWARDING) {
                ForwardScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.OTP) {
                OtpScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.TEMPLATES) {
                TemplatesScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.STATS) {
                StatsScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenDrawer = openDrawer)
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onOpenDrawer = openDrawer)
            }
        }
    }
}
