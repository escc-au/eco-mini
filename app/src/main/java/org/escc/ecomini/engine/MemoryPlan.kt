package org.escc.ecomini.engine

import org.escc.ecomini.rag.Prompt

/**
 * How to load the model given the phone's RAM.
 *
 * On a low-memory phone (an 8 GB Pixel 8a, say) both the GPU path (Vulkan copies every layer into
 * its own buffer) and the default CPU path (weights repacked into a CPU_REPACK copy) keep a second
 * 3+ GB copy of the model in anonymous memory, and the low-memory killer ends the app. There we use
 * the CPU only, leave the weights memory-mapped from the file and halve the context.
 */
object MemoryPlan {
    /**
     * "Under 12 GB" phones. totalMem is what the kernel can use after firmware and kernel
     * reservations: 8 GB phones report about 7.3 GiB, 12 GB phones about 11 GiB. A 10 GiB cut-off
     * separates them; a literal 12 GB test would wrongly catch 12 GB phones.
     */
    const val LOW_MEMORY_TOTAL: Long = 10L shl 30
    const val LOW_MEMORY_CONTEXT = 2048

    /** KV cache per token of context for Eco-4B (Qwen3-4B: 36 layers x 8 KV heads x 128 x K+V x f16). */
    const val KV_BYTES_PER_TOKEN: Long = 36L * 8 * 128 * 2 * 2
    /** Compute buffers and working space, with a margin. */
    const val WORK_BYTES: Long = 600L shl 20

    data class Plan(val lowMemory: Boolean, val allowGpu: Boolean, val repack: Boolean, val contextSize: Int)

    fun plan(totalMem: Long, forceCpu: Boolean, gpuBroken: Boolean): Plan =
        if (totalMem in 1 until LOW_MEMORY_TOTAL) {
            Plan(lowMemory = true, allowGpu = false, repack = false, contextSize = LOW_MEMORY_CONTEXT)
        } else {
            Plan(lowMemory = false, allowGpu = !forceCpu && !gpuBroken, repack = true, contextSize = Prompt.CONTEXT_SIZE)
        }

    /**
     * Anonymous memory the Eco model will need on top of what is already loaded. Memory-mapped
     * weights are file-backed and reclaimable, so they only count when they are copied
     * (GPU buffers, or CPU repacking).
     */
    fun bytesNeeded(plan: Plan, modelBytes: Long): Long {
        val copiesWeights = plan.allowGpu || plan.repack
        return (if (copiesWeights) modelBytes else 0L) + KV_BYTES_PER_TOKEN * plan.contextSize + WORK_BYTES
    }

    fun gib(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
}
