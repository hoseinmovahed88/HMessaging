package com.hmessaging.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.sms.SimSlot

@Composable
fun TemplatePickerDialog(
    templates: List<TemplateEntity>,
    onDismiss: () -> Unit,
    onPick: (TemplateEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_templates)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            if (templates.isEmpty()) {
                Text(stringResource(R.string.none))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = DIALOG_MAX_HEIGHT.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    templates.forEach { template ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(template) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(template.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = template.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun SimPickerDialog(
    slots: List<SimSlot>,
    selectedSubscriptionId: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sim)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column {
                SimOption(
                    label = stringResource(R.string.theme_system),
                    selected = selectedSubscriptionId == AppSettings.SUBSCRIPTION_UNSET,
                    onClick = { onPick(AppSettings.SUBSCRIPTION_UNSET) },
                )
                slots.forEach { slot ->
                    SimOption(
                        label = listOfNotNull(slot.displayName, slot.carrierName)
                            .distinct()
                            .joinToString(" · "),
                        selected = selectedSubscriptionId == slot.subscriptionId,
                        onClick = { onPick(slot.subscriptionId) },
                    )
                }
            }
        },
    )
}

@Composable
private fun SimOption(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private const val DIALOG_MAX_HEIGHT = 360
