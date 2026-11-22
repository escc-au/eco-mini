package org.escc.ecomini

import org.escc.ecomini.chat.Chat
import org.escc.ecomini.chat.ChatStore
import org.escc.ecomini.chat.History
import org.escc.ecomini.chat.Msg
import org.escc.ecomini.rag.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Stand-in tokenizer: one token per whitespace-separated word (the app uses llama.cpp's). */
    private val tokens: (String) -> Int = { s -> s.split(Regex("\\s+")).count { it.isNotEmpty() } }

    private val chunks = listOf("Passage one " + "word ".repeat(150), "Passage two " + "word ".repeat(150), "Passage three " + "word ".repeat(150))

    /** 40 turns of about 150 words each: far more than 4,096 tokens. */
    private fun longChat(): Chat {
        val msgs = (1..40).flatMap { i ->
            listOf(Msg(true, "Question $i " + "q$i ".repeat(20)), Msg(false, "Answer $i " + "a$i ".repeat(130)))
        }
        return Chat(ChatStore.newId(), "long", 1, 2, msgs)
    }

    @Test fun longChatLeavesOutOnlyTheOldestTurnsAndNeverChangesTheSavedChat() {
        val store = ChatStore(tmp.newFolder("chats"))
        val chat = longChat()
        store.save(chat)
        val before = chat.messages.map { it.copy() }

        val history = History.turns(chat.messages)
        assertEquals(40, history.size)
        val question = "And what about wetlands?"
        val (prompt, dropped) = History.fit(history, Prompt.MAX_ANSWER_TOKENS, tokens) {
            Prompt.answer(it, chunks, question)
        }

        // It fits the 4,096-token context together with the 400-token answer.
        assertTrue(tokens(prompt) + Prompt.MAX_ANSWER_TOKENS <= Prompt.CONTEXT_SIZE)
        assertTrue("some turns must be left out", dropped in 1 until history.size)
        // Exactly the oldest turns are left out; the most recent ones are kept, in order.
        assertEquals(Prompt.answer(history.drop(dropped), chunks, question), prompt)
        // And no more than necessary: one more old turn would not fit.
        val withOneMore = Prompt.answer(history.drop(dropped - 1), chunks, question)
        assertTrue(tokens(withOneMore) + Prompt.MAX_ANSWER_TOKENS > Prompt.CONTEXT_SIZE)
        assertTrue(prompt.contains("Question 40 "))
        assertFalse(prompt.contains("Question 1 "))
        assertTrue(prompt.startsWith("<|im_start|>system\n" + Prompt.SYSTEM + "<|im_end|>\n"))
        assertTrue(prompt.endsWith("Question: $question<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))

        // The chat itself is untouched, in memory and on disk.
        assertEquals(before, chat.messages)
        assertEquals(80, chat.messages.size)
        assertEquals(chat, store.load(chat.id))
        assertEquals(40, history.size)
    }

    @Test fun rewritePromptIsFittedTheSameWay() {
        val history = History.turns(longChat().messages)
        val (prompt, dropped) = History.fit(history, Prompt.MAX_REWRITE_TOKENS, tokens) { Prompt.rewrite(it, "why?") }
        assertTrue(tokens(prompt) + Prompt.MAX_REWRITE_TOKENS <= Prompt.CONTEXT_SIZE)
        assertEquals(Prompt.rewrite(history.drop(dropped), "why?"), prompt)
    }

    @Test fun shortChatKeepsEveryTurn() {
        val history = listOf(Prompt.Turn("Q1", "A1"), Prompt.Turn("Q2", "A2"))
        val (prompt, dropped) = History.fit(history, Prompt.MAX_ANSWER_TOKENS, tokens) { Prompt.answer(it, chunks, "Q3") }
        assertEquals(0, dropped)
        assertEquals(Prompt.answer(history, chunks, "Q3"), prompt)
    }

    @Test fun emptyHistoryIsFine() {
        val (prompt, dropped) = History.fit(emptyList(), Prompt.MAX_ANSWER_TOKENS, tokens) { Prompt.answer(it, chunks, "Q") }
        assertEquals(0, dropped)
        assertEquals(Prompt.answer(emptyList(), chunks, "Q"), prompt)
    }

    @Test fun turnsPairEachQuestionWithTheAnswerAfterIt() {
        val msgs = listOf(
            Msg(true, "Q1"), Msg(false, " A1 "),
            Msg(true, "Q2 (no answer: the app closed)"),
            Msg(true, "Q3"), Msg(false, "A3"),
            Msg(true, "Q4"), Msg(false, ""),
        )
        assertEquals(listOf(Prompt.Turn("Q1", "A1"), Prompt.Turn("Q3", "A3")), History.turns(msgs))
    }
}
