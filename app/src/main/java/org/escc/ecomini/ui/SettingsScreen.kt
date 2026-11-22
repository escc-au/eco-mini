package org.escc.ecomini.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.escc.ecomini.BuildConfig
import org.escc.ecomini.MainViewModel
import org.escc.ecomini.engine.Engine
import org.escc.ecomini.files.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val version by vm.settingsVersion.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val store = vm.store
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pickedModel(it) }
    val pickIndex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pickedIndex(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = vm::closeSettings) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        key(version) {
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

                Section("Engine")
                val inUse = when (engineState) {
                    Engine.State.Ready -> vm.engineBackend()
                    is Engine.State.Loading -> "Loading…"
                    is Engine.State.Failed -> "Not running (failed to load)"
                    Engine.State.Unloaded -> "Not loaded"
                }
                Text("In use: $inUse")
                Text(
                    "Memory: ${formatSize(vm.totalMemory)}" + if (vm.lowMemoryPhone) " (low-memory mode)" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (vm.lowMemoryPhone) {
                    Text(
                        "This phone has under 12 GB of memory, so Eco Mini always uses the CPU, reads the model " +
                            "straight from its file and uses a shorter context (2,048 tokens). " +
                            "Long chats are trimmed sooner in what is sent to the model; your saved chats are not.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Toggle("Force CPU", store.forceCpu) { vm.setForceCpu(it) }
                }
                if (store.gpuBroken && !store.forceCpu && !vm.lowMemoryPhone) {
                    Text(
                        "The GPU stopped the app last time, so Eco Mini uses the CPU.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = vm::retryGpu) { Text("Try the GPU again") }
                }

                Section("Files")
                val installed = store.installed
                Text("Model: ${sourceLabel(store.modelSource, installed?.version)}")
                Text("Index: ${sourceLabel(store.indexSource, installed?.version)}")
                OutlinedButton(onClick = vm::checkForUpdates) { Text("Check for updated files") }
                var confirm by remember { mutableStateOf(false) }
                if (installed != null || store.pending != null) {
                    if (!confirm) {
                        OutlinedButton(onClick = { confirm = true }) { Text("Delete downloaded files") }
                    } else {
                        Text("Delete the downloaded model and index? You would need to download them again.")
                        Row {
                            Button(onClick = { confirm = false; vm.deleteDownloads() }) { Text("Delete") }
                            TextButton(onClick = { confirm = false }) { Text("Cancel") }
                        }
                    }
                }

                Section("Use files on this phone")
                Text(
                    "If you downloaded the files on a computer and copied them to this phone, choose them here. " +
                        "The model is read in place; the index is copied into the app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text("Choose model file (.gguf)…") }
                OutlinedButton(onClick = { pickIndex.launch(arrayOf("*/*")) }) { Text("Choose index file (.sqlite)…") }
                val anyChosen = store.modelSource == Store.SOURCE_CHOSEN || store.indexSource == Store.SOURCE_CHOSEN
                if (anyChosen && installed != null) {
                    TextButton(onClick = vm::useDownloadedFiles) { Text("Use the downloaded files instead") }
                }

                Section("Download")
                var url by remember { mutableStateOf(store.baseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Download address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setBaseUrl(url) }, enabled = url.startsWith("https://")) { Text("Save") }
                    TextButton(onClick = { url = Store.DEFAULT_BASE_URL; vm.setBaseUrl(url) }) { Text("Reset") }
                }
                Toggle("Wi-Fi only", store.wifiOnly) { vm.setWifiOnly(it) }

                Section("Chats")
                Text(
                    "Chats are saved only on this phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var confirmChats by remember { mutableStateOf(false) }
                if (!confirmChats) {
                    OutlinedButton(onClick = { confirmChats = true }) { Text("Delete all chats") }
                } else {
                    Text("Delete every saved chat? This can't be undone.")
                    Row {
                        Button(onClick = { confirmChats = false; vm.deleteAllChats() }) { Text("Delete all") }
                        TextButton(onClick = { confirmChats = false }) { Text("Cancel") }
                    }
                }

                Section("About")
                Text("Eco Mini", style = MaterialTheme.typography.titleSmall)
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(
                    "Free software under the Apache 2.0 licence. Runs on llama.cpp. " +
                        "The app only goes online to download its files, when you ask it to.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceLabel(source: String, version: String?) =
    if (source == Store.SOURCE_CHOSEN) "file chosen on this phone"
    else if (version != null) "downloaded (version $version)" else "not installed"

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}
