package org.escc.ecomini

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.escc.ecomini.engine.Engine
import org.escc.ecomini.engine.LlamaNative
import org.escc.ecomini.files.Checks
import org.escc.ecomini.files.Manifest
import org.escc.ecomini.files.Store
import org.escc.ecomini.rag.Prompt
import java.io.File
import java.io.FileInputStream

enum class Screen { Setup, Chat, Settings }

sealed interface SetupState {
    data object Intro : SetupState
    data object Fetching : SetupState
    data class Offer(val manifest: Manifest, val isUpdate: Boolean) : SetupState
    data class Downloading(val done: Long, val total: Long, val waiting: String?) : SetupState
    data class Verifying(val message: String) : SetupState
    data class Error(val message: String) : SetupState
}

data class Msg(val fromUser: Boolean, val text: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val eco = app as EcoApp
    val store: Store = eco.store
    private val engine = eco.engine
    private val downloads = eco.downloads
    val engineState: StateFlow<Engine.State> = engine.state

    private val _screen = MutableStateFlow(Screen.Setup)
    val screen: StateFlow<Screen> = _screen
    private val _setup = MutableStateFlow<SetupState>(SetupState.Intro)
    val setup: StateFlow<SetupState> = _setup
    private val _messages = MutableStateFlow<List<Msg>>(emptyList())
    val messages: StateFlow<List<Msg>> = _messages
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** Bumped whenever a setting shown on screen changes, so the settings screen re-reads the store. */
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion

    private var askJob: Job? = null
    private var pollJob: Job? = null

    init {
        when {
            store.pending != null -> { _screen.value = Screen.Setup; poll() }
            store.ready() -> { _screen.value = Screen.Chat; loadEngine() }
            else -> _screen.value = Screen.Setup
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Called when the app comes to the foreground: reload if memory pressure freed the model. */
    fun onForeground() {
        if (_screen.value == Screen.Chat && engine.state.value == Engine.State.Unloaded) loadEngine()
    }

    private fun loadEngine() {
        viewModelScope.launch { engine.load() }
    }

    private fun reloadEngine() {
        viewModelScope.launch {
            engine.release()
            if (store.ready()) engine.load()
            _settingsVersion.update { it + 1 }
        }
    }

    fun retryLoad() = reloadEngine()

    fun engineBackend(): String = engine.backend

    // ---------------------------------------------------------------- setup and downloads

    fun checkDownloadSize(isUpdate: Boolean = false) {
        _setup.value = SetupState.Fetching
        viewModelScope.launch {
            _setup.value = try {
                val m = withContext(Dispatchers.IO) { store.fetchManifest() }
                SetupState.Offer(m, isUpdate)
            } catch (e: Exception) {
                SetupState.Error("Could not read the file list from ${store.baseUrl}: ${e.message}")
            }
        }
    }

    fun startDownload(manifest: Manifest) {
        downloads.start(manifest)
        poll()
    }

    fun cancelDownload() {
        pollJob?.cancel()
        downloads.cancel()
        _setup.value = SetupState.Intro
    }

    private fun poll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val p = withContext(Dispatchers.IO) { downloads.progress() }
                when {
                    p.failed != null -> {
                        downloads.cancel()
                        _setup.value = SetupState.Error("${p.failed}. Please try again.")
                        return@launch
                    }
                    p.finished -> {
                        install()
                        return@launch
                    }
                    else -> _setup.value = SetupState.Downloading(p.done, p.total, p.waiting)
                }
                delay(1000)
            }
        }
    }

    private suspend fun install() {
        _setup.value = SetupState.Verifying("Checking files…")
        val problem = withContext(Dispatchers.IO) {
            downloads.verifyAndInstall(
                onStatus = { _setup.value = SetupState.Verifying(it) },
                checkContents = { f, file ->
                    if (f.role == Manifest.ROLE_MODEL) Checks.checkModel(file.inputStream().use(Checks::readGguf))
                    else store.checkIndexFile(file)
                },
            )
        }
        if (problem != null) {
            _setup.value = SetupState.Error(problem)
            return
        }
        _setup.value = SetupState.Intro
        _screen.value = Screen.Chat
        reloadEngine()
    }

    // ---------------------------------------------------------------- chat

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _generating.value || engine.state.value != Engine.State.Ready) return
        val history = turns(_messages.value)
        _messages.update { it + Msg(true, question) + Msg(false, "") }
        _generating.value = true
        askJob = viewModelScope.launch {
            val rc = engine.ask(history, question) { piece ->
                _messages.update { list -> list.dropLast(1) + list.last().copy(text = list.last().text + piece) }
            }
            val note = when (rc) {
                LlamaNative.ERROR -> "Sorry, something went wrong while answering."
                LlamaNative.TOO_LONG -> "That question is too long for Eco to read."
                else -> null
            }
            if (note != null) {
                _messages.update { list ->
                    val last = list.last()
                    list.dropLast(1) + last.copy(text = if (last.text.isBlank()) note else last.text)
                }
            }
            _generating.value = false
        }
    }

    fun stop() = engine.stop()

    fun newChat() {
        engine.stop()
        viewModelScope.launch {
            askJob?.join()
            _messages.value = emptyList()
        }
    }

    /** Completed question/answer pairs, in order. */
    private fun turns(msgs: List<Msg>): List<Prompt.Turn> =
        msgs.chunked(2).filter { it.size == 2 && it[0].fromUser && !it[1].fromUser && it[1].text.isNotBlank() }
            .map { Prompt.Turn(it[0].text, it[1].text.trim()) }

    // ---------------------------------------------------------------- settings

    /** From the setup screen during an update, when the current files still work. */
    fun backToChat() {
        if (_setup.value is SetupState.Offer) _setup.value = SetupState.Intro
        _screen.value = Screen.Chat
        onForeground()
    }

    fun openSettings() { _notice.value = null; _screen.value = Screen.Settings }

    fun closeSettings() {
        _notice.value = null
        _screen.value = if (store.ready() && store.pending == null) Screen.Chat else Screen.Setup
        if (_screen.value == Screen.Chat) onForeground()
    }

    fun setBaseUrl(url: String) { store.baseUrl = url; _notice.value = "Download address saved."; bump() }
    fun setWifiOnly(on: Boolean) { store.wifiOnly = on; bump() }

    fun setForceCpu(on: Boolean) {
        store.forceCpu = on
        reloadEngine()
    }

    fun retryGpu() {
        store.gpuBroken = false
        _notice.value = "Close Eco completely and open it again to try the GPU."
        bump()
    }

    fun checkForUpdates() {
        _notice.value = "Checking…"
        viewModelScope.launch {
            _notice.value = try {
                val m = withContext(Dispatchers.IO) { store.fetchManifest() }
                val current = store.installed?.version
                if (m.version == current) "Your files are up to date (version $current)."
                else {
                    _setup.value = SetupState.Offer(m, isUpdate = true)
                    _screen.value = Screen.Setup
                    null
                }
            } catch (e: Exception) {
                "Could not check: ${e.message}"
            }
        }
    }

    fun deleteDownloads() {
        viewModelScope.launch {
            engine.release()
            downloads.cancel()
            withContext(Dispatchers.IO) { store.deleteDownloads() }
            _notice.value = "Downloaded files deleted."
            if (store.ready()) engine.load()
            bump()
        }
    }

    fun useDownloadedFiles() {
        store.modelSource = Store.SOURCE_DOWNLOADED
        store.indexSource = Store.SOURCE_DOWNLOADED
        _notice.value = "Using the downloaded files."
        reloadEngine()
    }

    fun pickedModel(uri: Uri?) {
        uri ?: return
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    cr.openFileDescriptor(uri, "r")!!.use { pfd ->
                        if (!OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode))
                            return@withContext "This file can't be read in place. Copy it to the phone's storage first."
                        Checks.checkModel(Checks.readGguf(FileInputStream(pfd.fileDescriptor)))
                    }
                } catch (e: Exception) {
                    "Could not read that file: ${e.message}"
                }
            }
            if (problem != null) {
                runCatching { cr.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                _notice.value = problem
                return@launch
            }
            store.chosenModelUri?.takeIf { it != uri }?.let { old ->
                runCatching { cr.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            store.chosenModelUri = uri
            store.modelSource = Store.SOURCE_CHOSEN
            _notice.value = "Model file accepted."
            reloadEngine()
        }
    }

    fun pickedIndex(uri: Uri?) {
        uri ?: return
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _notice.value = "Copying the index…"
            val problem = withContext(Dispatchers.IO) {
                val tmp = File(store.chosenIndexFile.path + ".tmp")
                try {
                    cr.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    store.checkIndexFile(tmp)?.let { return@withContext it }
                    engine.release()
                    store.chosenIndexFile.delete()
                    if (!tmp.renameTo(store.chosenIndexFile)) "Could not save the index" else null
                } catch (e: Exception) {
                    "Could not copy that file: ${e.message}"
                } finally {
                    tmp.delete()
                }
            }
            if (problem != null) {
                _notice.value = problem
                return@launch
            }
            store.indexSource = Store.SOURCE_CHOSEN
            _notice.value = "Index file accepted."
            reloadEngine()
        }
    }

    private fun bump() = _settingsVersion.update { it + 1 }
}
