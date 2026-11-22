package org.escc.ecomini.engine

import java.io.File

/** Picks the number of CPU threads: the fast cores only, skipping the slow efficiency cluster. */
object Threads {
    fun count(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).mapNotNull {
            runCatching { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        return fromFrequencies(freqs, n)
    }

    /** Cores at 60% or more of the fastest core's clock. */
    fun fromFrequencies(freqs: List<Long>, fallback: Int): Int {
        if (freqs.isEmpty()) return (fallback / 2).coerceIn(1, 4)
        val top = freqs.max()
        return freqs.count { it * 10 >= top * 6 }.coerceIn(1, 8)
    }
}
