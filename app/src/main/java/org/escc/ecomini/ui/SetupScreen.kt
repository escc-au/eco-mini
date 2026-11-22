package org.escc.ecomini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.escc.ecomini.MainViewModel
import org.escc.ecomini.SetupState
import org.escc.ecomini.files.Manifest
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1e3)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: MainViewModel) {
    val state by vm.setup.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up Eco Mini") },
                actions = {
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val update = (state as? SetupState.Offer)?.isUpdate == true
            Text(if (update) "Updated files are available" else "Welcome to Eco Mini", style = MaterialTheme.typography.headlineSmall)
            if (!update) {
                Text(
                    "Eco Mini runs entirely on your phone. Nothing you type leaves it. Before the first chat, " +
                        "it needs two files: the Eco language model and its knowledge index. They are " +
                        "downloaded once from Hugging Face (huggingface.co), and after that Eco Mini works offline.",
                )
                Text(
                    "Phones with less than 12 GB of memory may fail to run Eco Mini, or may run it slowly. " +
                        "Using Eco Mini on a phone with less than 8 GB of memory is not recommended.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when (val s = state) {
                SetupState.Intro -> {
                    Text(
                        "To show you the download size, Eco Mini will read a small file list from huggingface.co.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { vm.checkDownloadSize() }) { Text("Check download size") }
                }
                SetupState.Fetching -> Busy("Reading the file list…")
                is SetupState.Offer -> Offer(vm, s.manifest)
                is SetupState.Downloading -> {
                    val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Text("${formatSize(s.done)} of ${formatSize(s.total)}")
                    s.waiting?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    Text(
                        "You can leave the app: the download continues in the background.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel download") }
                }
                is SetupState.Verifying -> Busy(s.message)
                is SetupState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.checkDownloadSize() }) { Text("Try again") }
                }
            }
            // During an update the current files still work.
            if (vm.store.ready() && state !is SetupState.Verifying) {
                TextButton(onClick = vm::backToChat) { Text(if (state is SetupState.Offer) "Not now" else "Back to chat") }
            }
        }
    }
}

@Composable
private fun Offer(vm: MainViewModel, m: Manifest) {
    var wifiOnly by remember { mutableStateOf(vm.store.wifiOnly) }
    Text("Download size: ${formatSize(m.totalSize)}", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("• Eco model: ${formatSize(m.model.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("• Knowledge index: ${formatSize(m.index.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Version ${m.version}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("We recommend Wi-Fi: this is a large download.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wi-Fi only", Modifier.weight(1f))
        Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; vm.setWifiOnly(it) })
    }
    Button(onClick = { vm.startDownload(m) }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.padding(end = 16.dp))
        Text(text)
    }
    Spacer(Modifier.height(8.dp))
}
