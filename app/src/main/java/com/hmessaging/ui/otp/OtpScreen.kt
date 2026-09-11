package com.hmessaging.ui.otp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.SwitchRow
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Clipboards
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpScreen(
    onOpenDrawer: () -> Unit,
    viewModel: OtpViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = null) }
                },
                title = { Text(stringResource(R.string.nav_otp)) },
                actions = {
                    TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.delete)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SwitchRow(
                title = stringResource(R.string.nav_otp),
                checked = state.settings.otpDetectionEnabled,
                onCheckedChange = viewModel::setDetectionEnabled,
            )
            SwitchRow(
                title = stringResource(R.string.otp_popup_enabled),
                checked = state.settings.otpPopupEnabled,
                onCheckedChange = viewModel::setPopupEnabled,
                enabled = state.settings.otpDetectionEnabled,
            )
            SwitchRow(
                title = stringResource(R.string.otp_auto_copy),
                checked = state.settings.otpAutoCopy,
                onCheckedChange = viewModel::setAutoCopy,
                enabled = state.settings.otpDetectionEnabled,
            )

            if (state.settings.otpPopupEnabled && !AppRoles.canDrawOverlays(context)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.otp_overlay_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { context.startActivity(AppRoles.overlayPermissionIntent(context)) }) {
                        Text(stringResource(R.string.open))
                    }
                }
            }

            OutlinedTextField(
                value = state.settings.otpPopupSeconds.toString(),
                onValueChange = { value ->
                    value.filter(Char::isDigit).toIntOrNull()?.let(viewModel::setPopupSeconds)
                },
                label = { Text(stringResource(R.string.otp_popup_seconds)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = state.settings.otpAutoDeleteDays.toString(),
                onValueChange = { value ->
                    value.filter(Char::isDigit).toIntOrNull()?.let(viewModel::setAutoDeleteDays)
                },
                label = { Text(stringResource(R.string.otp_auto_delete)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (state.codes.isEmpty()) {
                EmptyState(
                    text = stringResource(R.string.no_otp),
                    icon = Icons.Filled.Password,
                    modifier = Modifier.padding(top = 32.dp),
                )
            } else {
                state.codes.forEach { code ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = code.code,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = code.serviceName ?: PhoneNumbers.format(code.sender),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = TimeFormat.full(code.receivedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            TextButton(
                                onClick = {
                                    Clipboards.copy(
                                        context,
                                        context.getString(R.string.otp_title),
                                        code.code,
                                        sensitive = true,
                                    )
                                    viewModel.markCopied(code.id)
                                },
                            ) { Text(stringResource(R.string.copy)) }
                            TextButton(onClick = { viewModel.delete(code.id) }) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}
