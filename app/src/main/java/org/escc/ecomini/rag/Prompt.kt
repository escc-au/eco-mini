package org.escc.ecomini.rag

/** Builds the prompts exactly as Eco was trained on (Qwen ChatML, written by hand). */
object Prompt {
    // Verbatim, including the missing space after "it.".
    const val SYSTEM =
        "You are Eco, an environmental advocate built on Qwen3-4B. You speak with deep respect for life, " +
            "nature and doing what is right: every living thing is worthy of care. Be warm, thoughtful and " +
            "sincere, never preachy or solemn. Answer the question first. Answer ONLY from the provided " +
            "context, never naming or citing it.If the sources don't cover the question, say so plainly."

    const val REWRITE_PREFIX = "Rewrite as a standalone search query: "
    const val MAX_ANSWER_TOKENS = 400
    const val MAX_REWRITE_TOKENS = 40
    const val CONTEXT_SIZE = 4096
    const val TOP_K = 3

    data class Turn(val question: String, val answer: String)

    private const val ASSISTANT_START = "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    private fun StringBuilder.turns(history: List<Turn>) {
        for (t in history) {
            append("<|im_start|>user\n").append(t.question).append("<|im_end|>\n")
            append("<|im_start|>assistant\n").append(t.answer).append("<|im_end|>\n")
        }
    }

    /** The answering prompt: system, earlier turns, then the retrieved context and the question. */
    fun answer(history: List<Turn>, chunks: List<String>, question: String): String = buildString {
        append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        turns(history)
        append("<|im_start|>user\nContext:\n\n")
        append(chunks.joinToString("\n\n"))
        append("\n\nQuestion: ").append(question).append("<|im_end|>\n")
        append(ASSISTANT_START)
    }

    /** Follow-up rewrite: no system prompt, the chat history, then the rewrite request. */
    fun rewrite(history: List<Turn>, question: String): String = buildString {
        turns(history)
        append("<|im_start|>user\n").append(REWRITE_PREFIX).append(question).append("<|im_end|>\n")
        append(ASSISTANT_START)
    }

    /** Tidies the model's rewritten query; falls back to the question if nothing usable came back. */
    fun cleanQuery(raw: String, fallback: String): String {
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val unquoted = line.trim('"', '\'', '“', '”', '`').trim()
        return unquoted.ifEmpty { fallback }
    }
}
