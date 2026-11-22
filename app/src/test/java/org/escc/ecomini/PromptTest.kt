package org.escc.ecomini

import org.escc.ecomini.rag.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    private val system =
        "You are Eco, an environmental advocate built on Qwen3-4B. You speak with deep respect for life, nature and " +
            "doing what is right: every living thing is worthy of care. Be warm, thoughtful and sincere, never preachy " +
            "or solemn. Answer the question first. Answer ONLY from the provided context, never naming or citing it." +
            "If the sources don't cover the question, say so plainly."

    @Test fun systemPromptIsVerbatim() {
        assertEquals(system, Prompt.SYSTEM)
        assertTrue(Prompt.SYSTEM.contains("citing it.If the sources"))
    }

    @Test fun firstQuestionPromptIsExact() {
        val expected = "<|im_start|>system\n" + system + "<|im_end|>\n" +
            "<|im_start|>user\nContext:\n\nA one.\n\nB two.\n\nC three.\n\nQuestion: What is ocean acidification?<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val got = Prompt.answer(emptyList(), listOf("A one.", "B two.", "C three."), "What is ocean acidification?")
        assertEquals(expected, got)
        assertEquals(expected.toByteArray().toList(), got.toByteArray().toList())
        assertTrue(got.endsWith("</think>\n\n"))
    }

    @Test fun followUpPromptIncludesEarlierTurnsWithoutContext() {
        val history = listOf(Prompt.Turn("Q1", "A1"), Prompt.Turn("Q2", "A2"))
        val expected = "<|im_start|>system\n" + system + "<|im_end|>\n" +
            "<|im_start|>user\nQ1<|im_end|>\n<|im_start|>assistant\nA1<|im_end|>\n" +
            "<|im_start|>user\nQ2<|im_end|>\n<|im_start|>assistant\nA2<|im_end|>\n" +
            "<|im_start|>user\nContext:\n\nc1\n\nc2\n\nc3\n\nQuestion: Q3<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        assertEquals(expected, Prompt.answer(history, listOf("c1", "c2", "c3"), "Q3"))
    }

    @Test fun rewritePromptHasNoSystemPrompt() {
        val expected = "<|im_start|>user\nQ1<|im_end|>\n<|im_start|>assistant\nA1<|im_end|>\n" +
            "<|im_start|>user\nRewrite as a standalone search query: and why?<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        assertEquals(expected, Prompt.rewrite(listOf(Prompt.Turn("Q1", "A1")), "and why?"))
    }

    @Test fun generationSettings() {
        assertEquals(400, Prompt.MAX_ANSWER_TOKENS)
        assertEquals(40, Prompt.MAX_REWRITE_TOKENS)
        assertEquals(4096, Prompt.CONTEXT_SIZE)
        assertEquals(3, Prompt.TOP_K)
    }

    @Test fun cleanQuery() {
        assertEquals("ocean acidification causes", Prompt.cleanQuery("  \"ocean acidification causes\"\nmore", "x"))
        assertEquals("fallback", Prompt.cleanQuery("  \n ", "fallback"))
    }
}
