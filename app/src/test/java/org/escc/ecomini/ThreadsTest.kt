package org.escc.ecomini

import org.escc.ecomini.engine.Threads
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadsTest {
    @Test fun skipsEfficiencyCores() {
        // Tensor G3 (Pixel 8a): 1 x 2.91 GHz, 4 x 2.37 GHz, 4 x 1.70 GHz
        assertEquals(5, Threads.fromFrequencies(listOf(2910L) + List(4) { 2370L } + List(4) { 1700L }, 9))
        // Snapdragon 8 Elite (OnePlus 13): 2 x 4.32 GHz, 6 x 3.53 GHz
        assertEquals(8, Threads.fromFrequencies(List(2) { 4320L } + List(6) { 3530L }, 8))
        assertEquals(4, Threads.fromFrequencies(emptyList(), 8))
    }
}
