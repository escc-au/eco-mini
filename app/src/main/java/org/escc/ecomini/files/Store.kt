package org.escc.ecomini.files

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Settings, file locations and which source (downloaded or chosen) each file comes from. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("eco", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co/escc/eco-4b-gguf/resolve/main/"
        const val EMBEDDER_ASSET = "bge-small-en-v1.5-q8_0.gguf"
        const val SOURCE_DOWNLOADED = "downloaded"
        const val SOURCE_CHOSEN = "chosen"
    }

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL)!!
        set(v) = prefs.edit { putString("base_url", v.trim().let { if (it.endsWith("/")) it else "$it/" }) }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit { putBoolean("wifi_only", v) }
    var forceCpu: Boolean
        get() = prefs.getBoolean("force_cpu", false)
        set(v) = prefs.edit { putBoolean("force_cpu", v) }

    /** Set while a GPU load is running; still set on the next start means the GPU crashed the app. */
    var gpuAttempt: Boolean
        get() = prefs.getBoolean("gpu_attempt", false)
        set(v) = prefs.edit(commit = true) { putBoolean("gpu_attempt", v) }
    var gpuBroken: Boolean
        get() = prefs.getBoolean("gpu_broken", false)
        set(v) = prefs.edit { putBoolean("gpu_broken", v) }

    /** The manifest of the files currently installed from download (null if none). */
    var installed: Manifest?
        get() = prefs.getString("installed_manifest", null)?.let { runCatching { Manifest.parse(it) }.getOrNull() }
        set(v) = prefs.edit { if (v == null) remove("installed_manifest") else putString("installed_manifest", v.toJson()) }

    /** The manifest of a download in progress, and its DownloadManager ids by file name. */
    var pending: Manifest?
        get() = prefs.getString("pending_manifest", null)?.let { runCatching { Manifest.parse(it) }.getOrNull() }
        set(v) = prefs.edit { if (v == null) remove("pending_manifest") else putString("pending_manifest", v.toJson()) }
    var downloadIds: Map<String, Long>
        get() = prefs.getStringSet("download_ids", emptySet())!!.mapNotNull {
            val (n, id) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            n to (id.toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
        set(v) = prefs.edit { putStringSet("download_ids", v.map { "${it.key}=${it.value}" }.toSet()) }

    var modelSource: String
        get() = prefs.getString("model_source", SOURCE_DOWNLOADED)!!
        set(v) = prefs.edit { putString("model_source", v) }
    var indexSource: String
        get() = prefs.getString("index_source", SOURCE_DOWNLOADED)!!
        set(v) = prefs.edit { putString("index_source", v) }
    var chosenModelUri: Uri?
        get() = prefs.getString("chosen_model_uri", null)?.let(Uri::parse)
        set(v) = prefs.edit { putString("chosen_model_uri", v?.toString()) }

    val downloadDir: File get() = ctx.getExternalFilesDir(null) ?: throw IOException("Shared storage is not available")
    fun downloadedFile(name: String) = File(downloadDir, name)
    fun partFile(name: String) = File(downloadDir, "$name.part")
    val chosenIndexFile: File get() = File(ctx.filesDir, "chosen-index.sqlite")
    val embedderFile: File get() = File(ctx.filesDir, EMBEDDER_ASSET)

    /** Model location: a path for downloaded files, or a content Uri for a chosen one. */
    sealed interface ModelLocation {
        data class Path(val file: File) : ModelLocation
        data class Content(val uri: Uri) : ModelLocation
    }

    fun modelLocation(): ModelLocation? = when (modelSource) {
        SOURCE_CHOSEN -> chosenModelUri?.takeIf { uri ->
            ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }?.let { ModelLocation.Content(it) }
        else -> installed?.model?.let { downloadedFile(it.name) }?.takeIf { it.isFile }?.let { ModelLocation.Path(it) }
    }

    fun indexFile(): File? = when (indexSource) {
        SOURCE_CHOSEN -> chosenIndexFile
        else -> installed?.index?.let { downloadedFile(it.name) }
    }?.takeIf { it.isFile }

    fun ready(): Boolean = runCatching { modelLocation() != null && indexFile() != null }.getOrDefault(false)

    /** Copies the bundled embedder out of the APK once (assets are stored uncompressed). */
    fun ensureEmbedder(): File {
        val out = embedderFile
        val assetSize = ctx.assets.openFd(EMBEDDER_ASSET).use { it.length }
        if (out.isFile && out.length() == assetSize) return out
        val tmp = File(out.path + ".tmp")
        ctx.assets.open(EMBEDDER_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (!tmp.renameTo(out)) throw IOException("cannot install embedder")
        return out
    }

    /** Fetches manifest.json. Only called after the user asks for it. */
    fun fetchManifest(): Manifest {
        val conn = URL(baseUrl + "manifest.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) throw IOException("Server replied ${conn.responseCode} for manifest.json")
            val text = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 1 shl 20) throw IOException("manifest.json is too large")
                }
                out.toString(Charsets.UTF_8.name())
            }
            return Manifest.parse(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Opens an index read-only and checks its meta table. Returns null if fine, else the problem. */
    fun checkIndexFile(file: File): String? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val meta = HashMap<String, String>()
            db.rawQuery("SELECT key, value FROM meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: ""
            }
            val cols = HashSet<String>()
            db.rawQuery("PRAGMA table_info(chunks)", null).use { c ->
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
            }
            Checks.checkIndex(meta, cols)
        }
    } catch (e: Exception) {
        "This is not an Eco index (${e.message})."
    }

    fun deleteDownloads() {
        installed?.files?.forEach { downloadedFile(it.name).delete() }
        downloadDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        installed = null
        pending = null
        downloadIds = emptyMap()
    }
}

private fun Manifest.toJson(): String = org.json.JSONObject().apply {
    put("version", version)
    put("files", org.json.JSONArray().apply {
        files.forEach {
            put(org.json.JSONObject().put("name", it.name).put("role", it.role).put("size", it.size).put("sha256", it.sha256))
        }
    })
}.toString()
