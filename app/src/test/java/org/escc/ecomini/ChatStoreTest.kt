package org.escc.ecomini

import org.escc.ecomini.chat.Chat
import org.escc.ecomini.chat.ChatStore
import org.escc.ecomini.chat.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun chat(id: String = ChatStore.newId(), updated: Long = 2000, messages: List<Msg>) =
        Chat(id, ChatStore.titleFrom(messages.first().text), 1000, updated, messages)

    @Test fun savedChatReloadsUnchangedAndInOrder() {
        val store = ChatStore(tmp.newFolder("chats"))
        val messages = listOf(
            Msg(true, "What is ocean acidification?"),
            Msg(false, "It's the drop in seawater pH as the ocean absorbs CO₂.\n\nIt harms shell-builders."),
            Msg(true, "Why does that harm oysters? \"Quotes\", back\\slash, tab\there"),
            Msg(false, "Emoji 🌊🐚, accents é ü, CJK 海洋, and trailing spaces   "),
            Msg(true, ""),
            Msg(true, "Two questions in a row (the app closed before an answer)"),
            Msg(false, "{\"looks\": [\"like\", \"json\"]}"),
        )
        val original = chat(messages = messages)
        store.save(original)

        val reloaded = ChatStore(tmp.root.resolve("chats")).load(original.id)!!
        assertEquals(original, reloaded)
        assertEquals(messages.size, reloaded.messages.size)
        messages.forEachIndexed { i, m ->
            assertEquals("message $i role", m.fromUser, reloaded.messages[i].fromUser)
            assertEquals("message $i text", m.text, reloaded.messages[i].text)
        }
        assertEquals(1000L, reloaded.created)
        assertEquals(2000L, reloaded.updated)
    }

    @Test fun savingAgainReplacesTheChat() {
        val store = ChatStore(tmp.newFolder("chats"))
        val first = chat(messages = listOf(Msg(true, "Q1")))
        store.save(first)
        val longer = first.copy(updated = 3000, messages = first.messages + Msg(false, "A1") + Msg(true, "Q2"))
        store.save(longer)
        assertEquals(longer, store.load(first.id))
        assertEquals(1, store.list().size)
    }

    @Test fun listIsNewestFirstAndDeleteWorks() {
        val store = ChatStore(tmp.newFolder("chats"))
        val old = chat(updated = 100, messages = listOf(Msg(true, "old")))
        val new = chat(updated = 900, messages = listOf(Msg(true, "new")))
        val mid = chat(updated = 500, messages = listOf(Msg(true, "mid")))
        listOf(old, new, mid).forEach(store::save)
        assertEquals(listOf("new", "mid", "old"), store.list().map { it.title })

        store.delete(mid.id)
        assertNull(store.load(mid.id))
        assertEquals(listOf("new", "old"), store.list().map { it.title })

        store.deleteAll()
        assertTrue(store.list().isEmpty())
    }

    @Test fun unreadableFilesAreSkipped() {
        val dir = tmp.newFolder("chats")
        val store = ChatStore(dir)
        store.save(chat(messages = listOf(Msg(true, "fine"))))
        dir.resolve("broken.json").writeText("{not json")
        assertEquals(listOf("fine"), store.list().map { it.title })
    }

    @Test fun titleIsFirstQuestionCutToAbout40Characters() {
        assertEquals("What is ocean acidification?", ChatStore.titleFrom("  What is ocean\nacidification?  "))
        val t = ChatStore.titleFrom("How do peatlands store so much carbon compared with forests?")
        assertEquals("How do peatlands store so much carbon…", t)
        assertTrue(t.length <= ChatStore.TITLE_LENGTH + 1)
        assertEquals("a".repeat(40) + "…", ChatStore.titleFrom("a".repeat(60)))
    }
}
