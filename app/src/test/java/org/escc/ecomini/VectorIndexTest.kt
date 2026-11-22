package org.escc.ecomini

import org.escc.ecomini.engine.VectorIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/** Runs the real C++ search (vecsearch.cpp, built for the host) on the sample index. */
class VectorIndexTest {
    companion object {
        @BeforeClass @JvmStatic fun loadLib() {
            System.load(System.getProperty("eco.hostSearchLib") ?: error("run through Gradle"))
        }
    }

    private fun sampleIndex(): Pair<VectorIndex, List<TestFiles.Row>> {
        val rows = TestFiles.rows()
        val ix = VectorIndex(384, rows.size)
        val embs = ByteArray(rows.size * 384)
        rows.forEachIndexed { i, r -> System.arraycopy(r.emb, 0, embs, i * 384, 384) }
        ix.add(embs, rows.map { it.scale }.toFloatArray(), rows.map { it.id }.toLongArray(), rows.size)
        return ix to rows
    }

    @Test fun realQueryFindsOceanAcidification() {
        val (ix, rows) = sampleIndex()
        ix.use {
            val ids = it.search(TestFiles.oceanQuery, 3)
            assertEquals(3, ids.size)
            assertEquals("Ocean acidification", rows.first { r -> r.id == ids[0] }.title)
        }
    }

    @Test fun eachChunkFindsItself() {
        val (ix, rows) = sampleIndex()
        ix.use {
            for (r in rows) {
                val v = FloatArray(384) { d -> r.emb[d] * r.scale }
                assertEquals(r.id, it.search(v, 1)[0])
            }
        }
    }

    @Test fun ranksByScaledDotProductAndCapsK() {
        VectorIndex(4, 3).use { ix ->
            val embs = byteArrayOf(127, 0, 0, 0, 0, 127, 0, 0, 90, 90, 0, 0)
            ix.add(embs, floatArrayOf(1f / 127, 1f / 127, 1f / 127), longArrayOf(10, 20, 30), 3)
            assertEquals(3, ix.size)
            assertArrayEquals(longArrayOf(10, 30, 20), ix.search(floatArrayOf(1f, 0.1f, 0f, 0f), 5))
            assertArrayEquals(longArrayOf(30), ix.search(floatArrayOf(0.7f, 0.7f, 0f, 0f), 1))
        }
    }

    @Test fun wrongQuerySizeReturnsNothing() {
        VectorIndex(4, 1).use { ix ->
            ix.add(byteArrayOf(1, 2, 3, 4), floatArrayOf(1f), longArrayOf(1), 1)
            assertEquals(0, ix.search(FloatArray(3), 1).size)
        }
    }
}
