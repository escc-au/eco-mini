package org.escc.ecomini.files

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Background, resumable downloads of the manifest's files through Android's DownloadManager. */
class Downloads(ctx: Context, private val store: Store) {
    private val app = ctx.applicationContext
    private val dm = app.getSystemService(DownloadManager::class.java)

    data class Progress(
        val done: Long,
        val total: Long,
        val finished: Boolean,
        val failed: String?,
        val waiting: String?,
    )

    /** Starts downloading every file not already installed with the same checksum. */
    fun start(manifest: Manifest) {
        cancel()
        val ids = HashMap<String, Long>()
        val have = store.installed?.files.orEmpty()
        for (f in manifest.files) {
            val already = have.any { it.name == f.name && it.sha256 == f.sha256 } && store.downloadedFile(f.name).length() == f.size
            if (already) continue
            store.partFile(f.name).delete()
            val req = DownloadManager.Request(Uri.parse(store.baseUrl + f.name))
                .setTitle("Eco: ${f.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(app, null, "${f.name}.part")
            if (store.wifiOnly) {
                req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                req.setAllowedOverMetered(false)
            }
            ids[f.name] = dm.enqueue(req)
        }
        store.pending = manifest
        store.downloadIds = ids
    }

    fun cancel() {
        val ids = store.downloadIds.values
        if (ids.isNotEmpty()) dm.remove(*ids.toLongArray())
        store.downloadIds = emptyMap()
        store.pending = null
    }

    fun progress(): Progress {
        val manifest = store.pending ?: return Progress(0, 0, true, null, null)
        val ids = store.downloadIds
        var done = 0L
        var finished = true
        var failed: String? = null
        var waiting: String? = null
        val byId = HashMap<Long, Pair<Int, Long>>()
        if (ids.isNotEmpty()) {
            dm.query(DownloadManager.Query().setFilterById(*ids.values.toLongArray()))?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    byId[id] = status to bytes
                    if (status == DownloadManager.STATUS_PAUSED) {
                        waiting = when (reason) {
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection"
                            else -> "Paused, will resume"
                        }
                    }
                    if (status == DownloadManager.STATUS_FAILED) failed = "Download failed (code $reason)"
                }
            }
        }
        for (f in manifest.files) {
            val id = ids[f.name]
            if (id == null) {
                done += f.size // already installed
                continue
            }
            val (status, bytes) = byId[id] ?: (DownloadManager.STATUS_FAILED to 0L)
            if (byId[id] == null) failed = failed ?: "Download of ${f.name} was cancelled"
            done += if (status == DownloadManager.STATUS_SUCCESSFUL) f.size else bytes
            if (status != DownloadManager.STATUS_SUCCESSFUL) finished = false
        }
        return Progress(done, manifest.totalSize, finished && failed == null, failed, waiting)
    }

    /**
     * After all downloads finish: checks each new file's SHA-256 and contents, then moves it into place.
     * Returns null on success, otherwise a message. Damaged files are deleted.
     */
    fun verifyAndInstall(onStatus: (String) -> Unit, checkContents: (ManifestFile, File) -> String?): String? {
        val manifest = store.pending ?: return "Nothing to install"
        for (f in manifest.files) {
            if (store.downloadIds[f.name] == null) continue
            val part = store.partFile(f.name)
            onStatus("Checking ${f.name}…")
            if (!Checks.matches(part, f) { done -> onStatus("Checking ${f.name}… ${done * 100 / f.size}%") }) {
                part.delete()
                cancel()
                return "${f.name} did not match its checksum and was deleted. Please try again."
            }
            checkContents(f, part)?.let { problem ->
                part.delete()
                cancel()
                return problem
            }
        }
        for (f in manifest.files) {
            if (store.downloadIds[f.name] == null) continue
            val dest = store.downloadedFile(f.name)
            dest.delete()
            if (!store.partFile(f.name).renameTo(dest)) return "Could not move ${f.name} into place"
        }
        // Old files the new manifest no longer lists.
        store.installed?.files?.filter { old -> manifest.files.none { it.name == old.name } }
            ?.forEach { store.downloadedFile(it.name).delete() }
        store.installed = manifest
        store.modelSource = Store.SOURCE_DOWNLOADED
        store.indexSource = Store.SOURCE_DOWNLOADED
        // Tidy DownloadManager's list; the .part files it would delete are already renamed.
        store.downloadIds.values.takeIf { it.isNotEmpty() }?.let { dm.remove(*it.toLongArray()) }
        store.downloadIds = emptyMap()
        store.pending = null
        return null
    }
}
