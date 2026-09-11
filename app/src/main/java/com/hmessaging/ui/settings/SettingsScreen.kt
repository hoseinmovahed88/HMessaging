package com.hmessaging.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.hmessaging.ui.components.SectionHeader
import com.hmessaging.ui.components.SwitchRow
import com.hmessaging.util.AppRoles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.statusMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    var signature by remember(settings.signature) { mutableStateOf(settings.signature) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = null) }
                },
                title = { Text(stringResource(R.string.nav_settings)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(themeLabel(mode)) },
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_sending))
            SwitchRow(
                title = stringResource(R.string.settings_delivery_reports),
                checked = settings.deliveryReports,
                onCheckedChange = viewModel::setDeliveryReports,
            )
            SwitchRow(
                title = stringResource(R.string.settings_split_counter),
                subtitle = stringResource(R.string.settings_split_counter_desc),
                checked = settings.numberLongMessages,
                onCheckedChange = viewModel::setNumberLongMessages,
            )
            if (settings.numberLongMessages) {
                OutlinedTextField(
                    value = settings.chunkChars.toString(),
                    onValueChange = { value ->
                        value.filter(Char::isDigit).toIntOrNull()?.let(viewModel::setChunkChars)
                    },
                    label = { Text(stringResource(R.string.settings_chunk_chars)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SwitchRow(
                title = stringResource(R.string.settings_signature),
                checked = settings.signatureEnabled,
                onCheckedChange = viewModel::setSignatureEnabled,
            )
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it },
                label = { Text(stringResource(R.string.settings_signature)) },
                enabled = settings.signatureEnabled,
                trailingIcon = {
                    TextButton(onClick = { viewModel.setSignature(signature) }) {
                        Text(stringResource(R.string.save))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            val slots = remember { viewModel.simSlots() }
            if (slots.size > 1) {
                Text(
                    text = stringResource(R.string.settings_sim),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.defaultSubscriptionId == AppSettings.SUBSCRIPTION_UNSET,
                        onClick = { viewModel.setDefaultSubscriptionId(AppSettings.SUBSCRIPTION_UNSET) },
                        label = { Text(stringResource(R.string.theme_system)) },
                    )
                    slots.forEach { slot ->
                        FilterChip(
                            selected = settings.defaultSubscriptionId == slot.subscriptionId,
                            onClick = { viewModel.setDefaultSubscriptionId(slot.subscriptionId) },
                            label = { Text(slot.displayName) },
                        )
                    }
                }
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.channel_messages))
            SwitchRow(
                title = stringResource(R.string.settings_notification_preview),
                checked = settings.notificationPreview,
                onCheckedChange = viewModel::setNotificationPreview,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_security))
            SwitchRow(
                title = stringResource(R.string.settings_app_lock),
                subtitle = stringResource(R.string.settings_app_lock_desc),
                checked = settings.appLockEnabled,
                onCheckedChange = viewModel::setAppLockEnabled,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_permissions))
            PermissionRow(
                label = stringResource(R.string.default_app_title),
                granted = AppRoles.isDefaultSmsApp(context),
                onRequest = { roleLauncher.launch(AppRoles.defaultSmsRequestIntent(context)) },
            )
            PermissionRow(
                label = stringResource(R.string.otp_popup_enabled),
                granted = AppRoles.canDrawOverlays(context),
                onRequest = { roleLauncher.launch(AppRoles.overlayPermissionIntent(context)) },
            )
            AppRoles.exactAlarmSettingsIntent(context)?.let { intent ->
                PermissionRow(
                    label = stringResource(R.string.settings_exact_alarms),
                    granted = AppRoles.canScheduleExactAlarms(context),
                    onRequest = { roleLauncher.launch(intent) },
                )
            }
            if (AppRoles.canRequestCallScreeningRole()) {
                PermissionRow(
                    label = stringResource(R.string.block_calls_too),
                    granted = AppRoles.isCallScreeningApp(context),
                    onRequest = {
                        AppRoles.callScreeningRequestIntent(context)?.let(roleLauncher::launch)
                    },
                )
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_backup))
            TextButton(
                onClick = viewModel::importSystemSms,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.settings_import_system)) }
            SwitchRow(
                title = stringResource(R.string.settings_backup_include_messages),
                checked = includeMessagesInBackup,
                onCheckedChange = { includeMessagesInBackup = it },
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { exportLauncher.launch(BackupManager.suggestedFileName()) }) {
                    Text(stringResource(R.string.settings_export))
                }
                TextButton(onClick = { importLauncher.launch(arrayOf(BackupManager.MIME_TYPE)) }) {
                    Text(stringResource(R.string.settings_import))
                }
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (granted) {
            Text(
                text = stringResource(R.string.enabled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onRequest) { Text(stringResource(R.string.open)) }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)
