package com.hmessaging.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onOpenDrawer: () -> Unit,
    viewModel: TemplatesViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }

    HyperScreen(
        title = stringResource(R.string.nav_templates),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = TemplateEntity(title = "", body = "") }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
            }
        },
    ) { padding ->
        if (templates.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.nav_templates),
                icon = Icons.Filled.Bookmark,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(templates, key = { it.id }) { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(template.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = template.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                TextButton(onClick = { editing = template }) {
                                    Text(stringResource(R.string.edit))
                                }
                                TextButton(onClick = { viewModel.delete(template.id) }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { template ->
        var title by remember(template.id) { mutableStateOf(template.title) }
        var body by remember(template.id) { mutableStateOf(template.body) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.nav_templates)) },
            confirmButton = {
                TextButton(
                    enabled = body.isNotBlank(),
                    onClick = {
                        viewModel.save(
                            template.copy(
                                title = title.ifBlank { body.take(TITLE_FALLBACK_LENGTH) },
                                body = body,
                            ),
                        )
                        editing = null
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.edit)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text(stringResource(R.string.type_a_message)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

private const val TITLE_FALLBACK_LENGTH = 24
