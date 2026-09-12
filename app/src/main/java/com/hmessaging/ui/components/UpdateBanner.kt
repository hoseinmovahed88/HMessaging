package com.hmessaging.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.feature.update.UpdateStatus

/**
 * Tells the reader a newer build exists, and gets it onto the phone.
 *
 * Nothing else will: the app is installed from a file, so there is no store to notice. It only
 * appears with actual news — a version to install or a download in progress — and never to report
 * that the check ran, or that it failed.
 */
@Composable
fun UpdateBanner(
    status: UpdateStatus,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = status is UpdateStatus.Available ||
        status is UpdateStatus.Downloading ||
        status is UpdateStatus.Ready
    if (!visible) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = when (status) {
                    is UpdateStatus.Available -> stringResource(R.string.update_available, status.info.versionName)
                    is UpdateStatus.Downloading -> stringResource(R.string.update_downloading)
                    is UpdateStatus.Ready -> stringResource(R.string.update_ready, status.info.versionName)
                    else -> ""
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            val notes = (status as? UpdateStatus.Available)?.info?.notes.orEmpty()
            if (notes.isNotBlank()) {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (status is UpdateStatus.Downloading) {
                LinearProgressIndicator(
                    progress = { status.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                when (status) {
                    is UpdateStatus.Available ->
                        TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) }

                    is UpdateStatus.Ready ->
                        TextButton(onClick = onInstall) { Text(stringResource(R.string.update_install)) }

                    else -> Unit
                }
            }
        }
    }
}
