package com.hmessaging.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.system.Diagnostics
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperGroupTitle
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperRow
import com.hmessaging.ui.components.HyperRowDivider
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.theme.LocalHyperColors
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Clipboards
import com.hmessaging.util.TimeFormat

/**
 * Shows, one line at a time, every precondition for receiving a message — plus a log of what the
 * telephony stack has actually handed the app.
 */
@Composable
fun DiagnosticsScreen(
    onOpenDrawer: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val report by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    HyperScreen(
        title = stringResource(R.string.nav_diagnostics),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        actions = {
            HyperIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.diag_refresh),
                onClick = viewModel::refresh,
            )
        },
    ) { padding ->
        val current = report
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            if (current == null) {
                item("loading") {
                    Text(
                        text = stringResource(if (refreshing) R.string.diag_running else R.string.diag_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@LazyColumn
            }

            item("summary") {
                HyperCard {
                    Text(
                        text = stringResource(
                            if (current.allOk) R.string.diag_all_ok else R.string.diag_problems,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (current.allOk) {
                            LocalHyperColors.current.success
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item("checks-title") { HyperGroupTitle(stringResource(R.string.diag_checks)) }
            item("checks") {
                HyperCard {
                    current.checks.forEachIndexed { index, check ->
                        HyperRow(
                            title = check.label,
                            subtitle = check.detail,
                            leading = { StatusDot(check.ok) },
                        )
                        if (index != current.checks.lastIndex) HyperRowDivider(startInset = 42)
                    }
                }
            }

            item("actions-title") { HyperGroupTitle(stringResource(R.string.diag_actions)) }
            item("actions") {
                HyperCard {
                    HyperRow(
                        title = stringResource(R.string.diag_open_default_apps),
                        showChevron = true,
                        onClick = {
                            runCatching { context.startActivity(AppRoles.defaultAppsSettingsIntent()) }
                        },
                    )
                    HyperRowDivider()
                    HyperRow(
                        title = stringResource(R.string.diag_open_app_settings),
                        showChevron = true,
                        onClick = {
                            runCatching { context.startActivity(AppRoles.appDetailsSettingsIntent(context)) }
                        },
                    )
                    AppRoles.autostartSettingsIntent()?.let { intent ->
                        HyperRowDivider()
                        HyperRow(
                            title = stringResource(R.string.diag_autostart),
                            showChevron = true,
                            onClick = { runCatching { context.startActivity(intent) } },
                        )
                    }
                    AppRoles.batteryOptimizationSettingsIntent()?.let { intent ->
                        HyperRowDivider()
                        HyperRow(
                            title = stringResource(R.string.diag_battery),
                            showChevron = true,
                            onClick = { runCatching { context.startActivity(intent) } },
                        )
                    }
                    HyperRowDivider()
                    HyperRow(
                        title = stringResource(R.string.diag_import_now),
                        showChevron = true,
                        onClick = viewModel::importNow,
                    )
                    HyperRowDivider()
                    HyperRow(
                        title = stringResource(R.string.diag_copy_report),
                        showChevron = true,
                        onClick = {
                            Clipboards.copy(
                                context,
                                context.getString(R.string.nav_diagnostics),
                                current.asText(),
                            )
                        },
                    )
                    HyperRowDivider()
                    HyperRow(
                        title = stringResource(R.string.diag_clear_log),
                        showChevron = true,
                        onClick = viewModel::clearLog,
                    )
                }
            }

            item("events-title") { HyperGroupTitle(stringResource(R.string.diag_events)) }
            item("events") {
                HyperCard {
                    if (current.events.isEmpty()) {
                        Text(
                            text = stringResource(R.string.diag_no_events),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        current.events.forEachIndexed { index, event ->
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = "${event.kind} · ${TimeFormat.full(event.at)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = kindColor(event.kind),
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = event.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (index != current.events.lastIndex) HyperRowDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    val hyper = LocalHyperColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = if (ok) hyper.success else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(10.dp),
            content = {},
        )
    }
}

@Composable
private fun kindColor(kind: String) = when (kind) {
    Diagnostics.KIND_BLOCKED -> MaterialTheme.colorScheme.tertiary
    Diagnostics.KIND_ERROR -> MaterialTheme.colorScheme.error
    Diagnostics.KIND_STORED -> LocalHyperColors.current.success
    else -> MaterialTheme.colorScheme.primary
}
