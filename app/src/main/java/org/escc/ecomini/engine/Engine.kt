package org.escc.ecomini.engine

import android.app.ActivityManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.escc.ecomini.chat.History
import org.escc.ecomini.files.Checks
import org.escc.ecomini.files.Store
import org.escc.ecomini.rag.Prompt
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * Owns the native models and the index. Every native call runs on one thread, in order,
 * so loading, generating and freeing never overlap.
 */
class Engine(private val ctx: Context, private val store: Store) {
    sealed interface State {
        data object Unloaded : State
        data class Loading(val step: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "eco-engine") }.asCoroutineDispatcher()
    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state

    private val am = ctx.getSystemService(ActivityManager::class.java)
    private fun memInfo() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    /** Total RAM as Android reports it (after kernel and firmware reservations). */
    val totalMem: Long = memInfo().totalMem

    /** How the current (or next) load is set up; decided from totalMem and the settings. */
    @Volatile var plan: MemoryPlan.Plan = currentPlan()
        private set

    private fun currentPlan() = MemoryPlan.plan(totalMem, store.forceCpu, store.gpuBroken)

    /** Context size of the loaded model: 4,096, or 2,048 on low-memory phones. */
    val contextSize: Int get() = plan.contextSize

    /** e.g. "GPU (OpenCL): QUALCOMM Adreno(TM) 830" or "CPU, 8 threads". */
    @Volatile var backend: String = "Not loaded"
        private set

    @Volatile private var llm = 0L
    @Volatile private var stopped = false
    private var embedder = 0L
    private var index: VectorIndex? = null
    private var db: SQLiteDatabase? = null
    private var modelFd: ParcelFileDescriptor? = null

    class NotEnoughMemory : Exception("Not enough free memory, close other apps and try again")

    companion object {
        private const val TAG = "EcoEngine"
        private var libsLoaded = false
    }

    private fun loadLibs() {
        if (libsLoaded) return
        System.loadLibrary("ecosearch")
        System.loadLibrary("eco")
        // Low-memory phones never touch the GPU drivers (their buffers would duplicate the model).
        val gpu = !store.gpuBroken && !plan.lowMemory
        // Covers GPU driver start-up too: if it crashes the app, EcoApp sees the flag next time.
        if (gpu) store.gpuAttempt = true
        LlamaNative.initBackends(ctx.applicationInfo.nativeLibraryDir, gpu)
        store.gpuAttempt = false
        libsLoaded = true
    }

    suspend fun load() {
        // Set at once (not on the engine thread) so the UI never shows "paused" while starting.
        if (_state.value != State.Ready) _state.value = State.Loading("Starting")
        loadOnThread()
    }

    private suspend fun loadOnThread() = withContext(thread) {
        if (_state.value == State.Ready) return@withContext
        try {
            // Free the old model, context, index and embedder completely before loading anything new.
            freeAll()
            doLoad()
            _state.value = State.Ready
        } catch (e: NotEnoughMemory) {
            Log.w(TAG, "not loading: not enough free memory")
            freeAll()
            _state.value = State.Failed(e.message!!)
        } catch (e: Throwable) {
            Log.e(TAG, "load failed", e)
            freeAll()
            _state.value = State.Failed(e.message ?: e.toString())
        }
    }

    private fun doLoad() {
        _state.value = State.Loading("Starting")
        plan = currentPlan()
        loadLibs()
        val threads = Threads.count()

        _state.value = State.Loading("Loading the search model")
        val embFile = store.ensureEmbedder()
        Checks.checkEmbedder(embFile.inputStream().use(Checks::readGguf))?.let { error(it) }
        embedder = LlamaNative.loadEmbedder(embFile.path, threads)
        if (embedder == 0L) error("Could not load the search model")

        _state.value = State.Loading("Loading the knowledge index")
        val indexFile = store.indexFile() ?: error("The index file is missing")
        store.checkIndexFile(indexFile)?.let { error(it) }
        loadIndex(indexFile)

        _state.value = State.Loading("Loading Eco")
        val (modelPath, modelBytes) = openModel()
        val p = plan
        val info = memInfo()
        val needed = MemoryPlan.bytesNeeded(p, modelBytes)
        Log.i(TAG, "memory: total ${MemoryPlan.gib(info.totalMem)}, available ${MemoryPlan.gib(info.availMem)}, " +
            "model needs about ${MemoryPlan.gib(needed)} more, low-memory phone: ${p.lowMemory}")
        if (info.availMem < needed) throw NotEnoughMemory()

        val pick = if (p.allowGpu) pickGpu() else null
        if (pick != null) {
            Log.i(TAG, "loading: backend=GPU ${pick.second}, n_ctx=${p.contextSize}, repack=${p.repack}")
            store.gpuAttempt = true
            llm = LlamaNative.loadModel(modelPath, pick.first, threads, p.contextSize, p.repack)
            store.gpuAttempt = false
            if (llm != 0L) backend = "GPU (${pick.second})"
            else Log.w(TAG, "GPU load failed (already freed by the native side), using CPU")
        }
        if (llm == 0L) {
            Log.i(TAG, "loading: backend=CPU, threads=$threads, n_ctx=${p.contextSize}, repack=${p.repack}")
            llm = LlamaNative.loadModel(modelPath, "", threads, p.contextSize, p.repack)
            backend = "CPU, $threads threads"
        }
        if (llm != 0L) {
            backend += " · ${"%,d".format(p.contextSize)}-token context" +
                if (p.lowMemory) " · weights read from file (low-memory phone)" else ""
            Log.i(TAG, "loaded: $backend (n_ctx=${p.contextSize}, repack=${p.repack})")
        }
        if (llm == 0L) error("Could not load the Eco model")
    }

    /** Returns (device name, label) of the GPU to try, preferring OpenCL (Adreno) over Vulkan. */
    private fun pickGpu(): Pair<String, String>? {
        val gpus = LlamaNative.listDevices().map { it.split('\t') }
            .filter { it.size == 3 && (it[2] == "gpu" || it[2] == "igpu") }
        Log.i(TAG, "devices: ${LlamaNative.listDevices().joinToString()}")
        val d = gpus.firstOrNull { it[0].contains("OpenCL", true) } ?: gpus.firstOrNull { it[0].startsWith("Vulkan") }
        return d?.let { it[0] to "${if (it[0].contains("OpenCL", true)) "OpenCL" else "Vulkan"}: ${it[1]}" }
    }

    /** Returns the path llama.cpp should open and the model's size in bytes. */
    private fun openModel(): Pair<String, Long> = when (val loc = store.modelLocation() ?: error("The model file is missing")) {
        is Store.ModelLocation.Path -> {
            Checks.checkModel(loc.file.inputStream().use(Checks::readGguf))?.let { error(it) }
            loc.file.path to loc.file.length()
        }
        is Store.ModelLocation.Content -> {
            val pfd = ctx.contentResolver.openFileDescriptor(loc.uri, "r") ?: error("Cannot open the chosen model")
            modelFd = pfd
            Checks.checkModel(FileInputStream(pfd.fileDescriptor).let(Checks::readGguf))?.let { error(it) }
            // llama.cpp reopens and memory-maps the file in place through this path.
            "/proc/self/fd/${pfd.fd}" to pfd.statSize
        }
    }

    private fun loadIndex(file: File) {
        val d = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        db = d
        val dim = Checks.EMBED_DIM
        val count = d.rawQuery("SELECT COUNT(*) FROM chunks", null).use { it.moveToFirst(); it.getInt(0) }
        val ix = VectorIndex(dim, count)
        index = ix
        val page = 2048
        val embs = ByteArray(page * dim)
        val scales = FloatArray(page)
        val ids = LongArray(page)
        var last = Long.MIN_VALUE
        while (true) {
            var n = 0
            d.rawQuery("SELECT id, emb, scale FROM chunks WHERE id > ? ORDER BY id LIMIT $page", arrayOf(last.toString())).use { c ->
                while (c.moveToNext()) {
                    val e = c.getBlob(1)
                    if (e == null || e.size != dim) error("Index chunk ${c.getLong(0)} has a bad embedding")
                    System.arraycopy(e, 0, embs, n * dim, dim)
                    ids[n] = c.getLong(0)
                    scales[n] = c.getFloat(2)
                    n++
                }
            }
            if (n == 0) break
            ix.add(embs, scales, ids, n)
            last = ids[n - 1]
            if (n < page) break
        }
        if (ix.size == 0) error("The index is empty")
    }

    private fun chunkText(id: Long): String =
        db!!.rawQuery("SELECT text FROM chunks WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        }

    private fun tokens(text: String) = LlamaNative.countTokens(llm, text.toByteArray())

    private fun generateText(prompt: String, max: Int, onText: (String) -> Unit): Int =
        LlamaNative.generate(llm, prompt.toByteArray(), max) { onText(String(it, Charsets.UTF_8)) }

    /** Leaves out the oldest turns of the prompt (never of the saved chat) until it fits the context. */
    private fun fit(history: List<Prompt.Turn>, reserve: Int, build: (List<Prompt.Turn>) -> String): String {
        val (prompt, dropped) = History.fit(history, reserve, ::tokens, contextSize, build)
        if (dropped > 0) Log.i(TAG, "left the oldest $dropped of ${history.size} turns out of the prompt")
        return prompt
    }

    /** Answers a question, streaming text pieces through onText. Returns a LlamaNative status. */
    suspend fun ask(history: List<Prompt.Turn>, question: String, onText: (String) -> Unit): Int = withContext(thread) {
        if (_state.value != State.Ready || llm == 0L) return@withContext LlamaNative.ERROR
        LlamaNative.resetStop(llm)
        stopped = false
        // Follow-ups are rewritten into a standalone query by Eco itself; the first question is used as typed.
        val query = if (history.isEmpty()) question else {
            val p = fit(history, Prompt.MAX_REWRITE_TOKENS) { Prompt.rewrite(it, question) }
            val out = StringBuilder()
            val rc = generateText(p, Prompt.MAX_REWRITE_TOKENS) { out.append(it) }
            if (rc == LlamaNative.STOPPED) return@withContext rc
            Prompt.cleanQuery(out.toString(), question).also { Log.i(TAG, "search query: $it") }
        }
        if (stopped) return@withContext LlamaNative.STOPPED
        val qv = LlamaNative.embed(embedder, query.toByteArray()) ?: return@withContext LlamaNative.ERROR
        val chunks = index!!.search(qv, Prompt.TOP_K).map(::chunkText)
        val prompt = fit(history, Prompt.MAX_ANSWER_TOKENS) { Prompt.answer(it, chunks, question) }
        generateText(prompt, Prompt.MAX_ANSWER_TOKENS, onText)
    }

    /** Safe from any thread. */
    fun stop() {
        stopped = true
        val h = llm
        if (h != 0L) LlamaNative.requestStop(h)
    }

    /** Frees everything (e.g. when memory runs low). The next load() starts again. */
    suspend fun release() {
        stop()
        withContext(thread) {
            freeAll()
            _state.value = State.Unloaded
        }
    }

    private fun freeAll() {
        // Reverse order of loading. LlamaNative.free releases sampler, context (KV cache) and model.
        if (llm != 0L) {
            LlamaNative.free(llm)
            Log.i(TAG, "freed the previous model and context")
        }
        llm = 0L
        modelFd?.close()
        modelFd = null
        index?.close()
        index = null
        db?.close()
        db = null
        if (embedder != 0L) LlamaNative.free(embedder)
        embedder = 0L
        backend = "Not loaded"
    }
}
