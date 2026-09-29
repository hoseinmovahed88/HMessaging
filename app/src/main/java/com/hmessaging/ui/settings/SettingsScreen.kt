package com.hmessaging.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.backup.BackupManager
import com.hmessaging.data.model.ThemeMode
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperGroupTitle
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperRow
import com.hmessaging.ui.components.HyperRowDivider
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.components.HyperSwitchRow
import com.hmessaging.ui.rememberIsDefaultSmsApp
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.update.UpdateStatus
import com.hmessaging.util.AppRoles
import com.hmessaging.util.TimeFormat

@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.statusMessage.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.from(context) }
    val updateStatus by graph.updates.status.collectAsStateWithLifecycle()
    var updateUrl by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    val snackbarHost = remember { SnackbarHostState() }
    val isDefaultSmsApp = rememberIsDefaultSmsApp()

    var signature by remember(settings.signature) { mutableStateOf(settings.signature) }
    // Hoisted out of the LazyColumn body: that lambda is a LazyListScope, not a composable scope.
    val slots = remember { viewModel.simSlots() }
    var includeMessagesInBackup by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
    ) { uri -> uri?.let { viewModel.exportBackup(it, includeMessagesInBackup) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it, replaceExisting = false) } }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(status) {
        status?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    HyperScreen(
        title = stringResource(R.string.nav_settings),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        snackbarHostState = snackbarHost,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item("appearance-title") { HyperGroupTitle(stringResource(R.string.settings_appearance)) }
            item("appearance") {
                HyperCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(themeLabel(mode)) },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    border = null,
                                )
                            }
                        }
                    }
                    HyperRowDivider()
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        checked = settings.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                    HyperRowDivider()
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_persian_calendar),
                        subtitle = TimeFormat.full(System.currentTimeMillis()),
                        checked = settings.persianCalendar,
                        onCheckedChange = viewModel::setPersianCalendar,
                    )
                }
            }

            item("permissions-title") { HyperGroupTitle(stringResource(R.string.settings_permissions)) }
            item("permissions") {
                HyperCard {
                    PermissionRow(
                        label = stringResource(R.string.default_app_title),
                        granted = isDefaultSmsApp,
                        onRequest = { roleLauncher.launch(AppRoles.defaultSmsRequestIntent(context)) },
                    )
                    HyperRowDivider()
                    PermissionRow(
                        label = stringResource(R.string.otp_popup_enabled),
                        granted = AppRoles.canDrawOverlays(context),
                        onRequest = { roleLauncher.launch(AppRoles.overlayPermissionIntent(context)) },
                    )
                    AppRoles.exactAlarmSettingsIntent(context)?.let { intent ->
                        HyperRowDivider()
                        PermissionRow(
                            label = stringResource(R.string.settings_exact_alarms),
                            granted = AppRoles.canScheduleExactAlarms(context),
                            onRequest = { roleLauncher.launch(intent) },
                        )
                    }
                    if (AppRoles.canRequestCallScreeningRole()) {
                        HyperRowDivider()
                        PermissionRow(
                            label = stringResource(R.string.block_calls_too),
                            granted = AppRoles.isCallScreeningApp(context),
                            onRequest = {
                                AppRoles.callScreeningRequestIntent(context)?.let(roleLauncher::launch)
                            },
                        )
                    }
                }
            }

            item("sending-title") { HyperGroupTitle(stringResource(R.string.settings_sending)) }
            item("sending") {
                HyperCard {
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_delivery_reports),
                        checked = settings.deliveryReports,
                        onCheckedChange = viewModel::setDeliveryReports,
                    )
                    HyperRowDivider()
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_split_counter),
                        subtitle = stringResource(R.string.settings_split_counter_desc),
                        checked = settings.numberLongMessages,
                        onCheckedChange = viewModel::setNumberLongMessages,
                    )
                    if (settings.numberLongMessages) {
                        HyperRowDivider()
                        NumberField(
                            label = stringResource(R.string.settings_chunk_chars),
                            value = settings.chunkChars,
                            onValueChange = viewModel::setChunkChars,
                        )
                    }
                    HyperRowDivider()
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_signature),
                        checked = settings.signatureEnabled,
                        onCheckedChange = viewModel::setSignatureEnabled,
                    )
                    if (settings.signatureEnabled) {
                        HyperRowDivider()
                        OutlinedTextField(
                            value = signature,
                            onValueChange = { signature = it },
                            label = { Text(stringResource(R.string.settings_signature)) },
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = {
                                TextButton(onClick = { viewModel.setSignature(signature) }) {
                                    Text(stringResource(R.string.save))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
            }

            if (slots.size > 1) {
                item("sim-title") { HyperGroupTitle(stringResource(R.string.settings_sim)) }
                item("sim") {
                    HyperCard {
                        HyperRow(
                            title = stringResource(R.string.theme_system),
                            onClick = { viewModel.setDefaultSubscriptionId(AppSettings.SUBSCRIPTION_UNSET) },
                            value = if (settings.defaultSubscriptionId == AppSettings.SUBSCRIPTION_UNSET) "✓" else null,
                        )
                        slots.forEach { slot ->
                            HyperRowDivider()
                            HyperRow(
                                title = slot.displayName,
                                subtitle = slot.carrierName,
                                onClick = { viewModel.setDefaultSubscriptionId(slot.subscriptionId) },
                                value = if (settings.defaultSubscriptionId == slot.subscriptionId) "✓" else null,
                            )
                        }
                    }
                }
            }

            item("delivery-title") { HyperGroupTitle(stringResource(R.string.settings_live_sync)) }
            item("delivery") {
                HyperCard {
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_live_sync),
                        subtitle = stringResource(R.string.settings_live_sync_desc),
                        checked = settings.liveSyncEnabled,
                        onCheckedChange = viewModel::setLiveSyncEnabled,
                    )
                }
            }

            item("notifications-title") { HyperGroupTitle(stringResource(R.string.channel_messages)) }
            item("notifications") {
                HyperCard {
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_notification_preview),
                        checked = settings.notificationPreview,
                        onCheckedChange = viewModel::setNotificationPreview,
                    )
                    HyperRowDivider()
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_quick_reply),
                        subtitle = stringResource(R.string.settings_quick_reply_desc),
                        checked = settings.quickReplyPopupEnabled,
                        onCheckedChange = viewModel::setQuickReplyPopupEnabled,
                    )
                    // Without the overlay permission the switch is on and nothing ever appears,
                    // which reads as a broken feature rather than a missing permission.
                    if (settings.quickReplyPopupEnabled && !AppRoles.canDrawOverlays(context)) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.otp_overlay_needed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = {
                                    context.startActivity(AppRoles.overlayPermissionIntent(context))
                                },
                            ) {
                                Text(stringResource(R.string.open))
                            }
                        }
                    }
                }
            }

            item("update-title") { HyperGroupTitle(stringResource(R.string.update_check)) }
            item("update") {
                HyperCard {
                    val status = updateStatus
                    HyperRow(
                        title = stringResource(R.string.update_check),
                        subtitle = when (status) {
                            is UpdateStatus.Checking -> stringResource(R.string.bank_scanning)
                            is UpdateStatus.UpToDate -> stringResource(R.string.update_up_to_date)
                            is UpdateStatus.Failed -> stringResource(R.string.update_failed)
                            is UpdateStatus.Available ->
                                stringResource(R.string.update_available, status.info.versionName)

                            is UpdateStatus.Downloading -> stringResource(R.string.update_downloading)
                            is UpdateStatus.Ready ->
                                stringResource(R.string.update_ready, status.info.versionName)

                            else -> stringResource(
                                R.string.update_installed_version,
                                graph.updates.installedVersion,
                            )
                        },
                        onClick = { graph.updates.check(userAsked = true) },
                    )
                    when (status) {
                        is UpdateStatus.Available -> {
                            HyperRowDivider()
                            HyperRow(
                                title = stringResource(R.string.update_download),
                                onClick = { graph.updates.download(status.info) },
                            )
                        }

                        is UpdateStatus.Ready -> {
                            HyperRowDivider()
                            HyperRow(
                                title = stringResource(R.string.update_install),
                                onClick = { context.startActivity(graph.updateChecker.installIntent(status.file)) },
                            )
                        }

                        else -> Unit
                    }
                    HyperRowDivider()
                    OutlinedTextField(
                        value = updateUrl,
                        onValueChange = { updateUrl = it },
                        label = { Text(stringResource(R.string.settings_update_url)) },
                        supportingText = { Text(stringResource(R.string.settings_update_url_desc)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        trailingIcon = {
                            TextButton(onClick = { viewModel.setUpdateManifestUrl(updateUrl) }) {
                                Text(stringResource(R.string.save))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }

            item("security-title") { HyperGroupTitle(stringResource(R.string.settings_security)) }
            item("security") {
                HyperCard {
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_app_lock),
                        subtitle = stringResource(R.string.settings_app_lock_desc),
                        checked = settings.appLockEnabled,
                        onCheckedChange = viewModel::setAppLockEnabled,
                    )
                }
            }

            item("backup-title") { HyperGroupTitle(stringResource(R.string.settings_backup)) }
            item("backup") {
                HyperCard {
                    val running = importProgress
                    HyperRow(
                        title = stringResource(R.string.settings_import_system),
                        subtitle = if (running == null) {
                            stringResource(R.string.settings_import_system_desc)
                        } else {
                            stringResource(
                                R.string.settings_import_progress,
                                running.imported,
                                running.scanned,
                                running.total,
                            )
                        },
                        showChevron = running == null,
                        enabled = running == null,
                        onClick = viewModel::importSystemSms,
                    )
                    HyperRowDivider()
                    HyperSwitchRow(
                        title = stringResource(R.string.settings_backup_include_messages),
                        checked = includeMessagesInBackup,
                        onCheckedChange = { includeMessagesInBackup = it },
                    )
                    HyperRowDivider()
                    HyperRow(
                        title = stringResource(R.string.settings_export),
                        showChevron = true,
                        onClick = { exportLauncher.launch(BackupManager.suggestedFileName()) },
                    )
                    HyperRowDivider()
                    HyperRow(
                        title = stringResource(R.string.settings_import),
                        showChevron = true,
                        onClick = { importLauncher.launch(arrayOf(BackupManager.MIME_TYPE)) },
                    )
                }
            }

            item("about-title") { HyperGroupTitle(stringResource(R.string.settings_about)) }
            item("about") {
                HyperCard {
                    Text(
                        text = stringResource(R.string.about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter(Char::isDigit).toIntOrNull()?.let(onValueChange) },
        label = { Text(label) },
        shape = RoundedCornerShape(16.dp),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    HyperRow(
        title = label,
        onClick = if (granted) null else onRequest,
        showChevron = !granted,
        trailing = {
            Text(
                text = stringResource(if (granted) R.string.enabled else R.string.disabled),
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)
