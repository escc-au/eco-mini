package org.escc.ecomini.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class Msg(val fromUser: Boolean, val text: String)

data class Chat(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val messages: List<Msg>,
)

data class ChatSummary(val id: String, val title: String, val created: Long, val updated: Long)

/**
 * Saved chats, one JSON file per chat in the app's private storage. Nothing leaves the phone.
 * Writes go to a temporary file first and are then renamed, so a crash never leaves half a chat.
 * Not thread-safe: call it from one thread (the ViewModel uses a single-threaded dispatcher).
 */
class ChatStore(private val dir: File) {
    companion object {
        const val TITLE_LENGTH = 40
        private val ID = Regex("^[A-Za-z0-9-]+$")

        fun newId(): String = UUID.randomUUID().toString()

        /** The first question, on one line, cut to about 40 characters at a word boundary. */
        fun titleFrom(question: String): String {
            val q = question.trim().replace(Regex("\\s+"), " ")
            if (q.length <= TITLE_LENGTH) return q
            val cut = q.lastIndexOf(' ', TITLE_LENGTH).takeIf { it >= TITLE_LENGTH / 2 } ?: TITLE_LENGTH
            return q.substring(0, cut).trimEnd() + "…"
        }

        fun toJson(c: Chat): String = JSONObject().apply {
            put("id", c.id)
            put("title", c.title)
            put("created", c.created)
            put("updated", c.updated)
            put("messages", JSONArray().apply {
                c.messages.forEach { put(JSONObject().put("user", it.fromUser).put("text", it.text)) }
            })
        }.toString()

        fun fromJson(s: String): Chat {
            val o = JSONObject(s)
            val arr = o.getJSONArray("messages")
            return Chat(
                id = o.getString("id"),
                title = o.getString("title"),
                created = o.getLong("created"),
                updated = o.getLong("updated"),
                messages = (0 until arr.length()).map {
                    val m = arr.getJSONObject(it)
                    Msg(m.getBoolean("user"), m.getString("text"))
                },
            )
        }
    }

    private fun file(id: String): File {
        require(ID.matches(id)) { "bad chat id" }
        return File(dir, "$id.json")
    }

    fun save(chat: Chat) {
        dir.mkdirs()
        val dest = file(chat.id)
        val tmp = File(dir, "${chat.id}.json.tmp")
        tmp.writeText(toJson(chat), Charsets.UTF_8)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IOException("could not save chat")
        }
    }

    fun load(id: String): Chat? = runCatching { fromJson(file(id).readText(Charsets.UTF_8)) }.getOrNull()

    /** All chats, most recently updated first. Unreadable files are skipped. */
    fun list(): List<ChatSummary> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { fromJson(f.readText(Charsets.UTF_8)) }.getOrNull() }
            .map { ChatSummary(it.id, it.title, it.created, it.updated) }
            .sortedByDescending { it.updated }

    fun delete(id: String) {
        file(id).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.name.endsWith(".json") || it.name.endsWith(".json.tmp")) it.delete() }
    }
}
