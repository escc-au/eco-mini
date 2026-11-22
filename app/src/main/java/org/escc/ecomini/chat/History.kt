package org.escc.ecomini.chat

import org.escc.ecomini.rag.Prompt

/** Turns a chat's messages into the history the prompts use. Never changes the chat itself. */
object History {
    /**
     * Completed question/answer pairs, in order: each question paired with the answer right after
     * it. A question with no answer (e.g. the app closed while answering) is left out.
     */
    fun turns(messages: List<Msg>): List<Prompt.Turn> {
        val out = ArrayList<Prompt.Turn>()
        var i = 0
        while (i < messages.size) {
            val q = messages[i]
            val a = messages.getOrNull(i + 1)
            if (q.fromUser && a != null && !a.fromUser) {
                if (a.text.isNotBlank()) out += Prompt.Turn(q.text, a.text.trim())
                i += 2
            } else {
                i += 1
            }
        }
        return out
    }

    /**
     * Builds the prompt from as many of the most recent turns as fit: the oldest turns are left
     * out until prompt + [reserve] tokens fit in [limit]. Only the prompt is shortened; [history]
     * is not modified. Returns the prompt and how many of the oldest turns were left out.
     */
    fun fit(
        history: List<Prompt.Turn>,
        reserve: Int,
        countTokens: (String) -> Int,
        limit: Int = Prompt.CONTEXT_SIZE,
        build: (List<Prompt.Turn>) -> String,
    ): Pair<String, Int> {
        fun fits(dropped: Int) = countTokens(build(history.subList(dropped, history.size))) + reserve <= limit
        if (history.isEmpty() || fits(0)) return build(history) to 0
        // Leaving out more turns only makes the prompt shorter, so binary search the fewest to drop.
        var lo = 1
        var hi = history.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (fits(mid)) hi = mid else lo = mid + 1
        }
        return build(history.subList(lo, history.size)) to lo
    }
}
