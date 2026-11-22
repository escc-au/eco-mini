package org.escc.ecomini

import org.escc.ecomini.engine.MemoryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPlanTest {
    private val gib = 1L shl 30
    private val model = 3_300_000_000L

    @Test fun eightGbPhoneUsesCpuWithoutRepackAndShortContext() {
        // Pixel 8a: 8 GB, Android reports about 7.3 GiB.
        val p = MemoryPlan.plan((7.3 * gib).toLong(), forceCpu = false, gpuBroken = false)
        assertTrue(p.lowMemory)
        assertFalse(p.allowGpu)
        assertFalse(p.repack)
        assertEquals(2048, p.contextSize)
        // Weights stay memory-mapped, so only KV cache and work space count.
        assertTrue(MemoryPlan.bytesNeeded(p, model) < 1L shl 30)
    }

    @Test fun twelveGbPhoneKeepsCurrentBehaviour() {
        // A 12 GB phone reports about 11 GiB: not low memory.
        val p = MemoryPlan.plan(11L * gib, forceCpu = false, gpuBroken = false)
        assertFalse(p.lowMemory)
        assertTrue(p.allowGpu)
        assertTrue(p.repack)
        assertEquals(4096, p.contextSize)
        assertTrue(MemoryPlan.bytesNeeded(p, model) > model)
        assertFalse(MemoryPlan.plan(15L * gib, forceCpu = true, gpuBroken = false).allowGpu)
        assertFalse(MemoryPlan.plan(15L * gib, forceCpu = false, gpuBroken = true).allowGpu)
    }
}
