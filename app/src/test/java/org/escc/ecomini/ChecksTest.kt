package org.escc.ecomini

import org.escc.ecomini.files.Checks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChecksTest {
    /** A minimal GGUF header with a big string array before the keys we look for, like a real model. */
    private fun gguf(arch: String, embd: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(), 0, n)
        fun str(s: String) { val b = s.toByteArray(); le(8, b.size.toLong()); out.write(b) }
        out.write("GGUF".toByteArray()); le(4, 3); le(8, 0); le(8, 4)
        str("tokenizer.ggml.tokens"); le(4, 9); le(4, 8); le(8, 1000); repeat(1000) { str("tok$it") }
        str("general.architecture"); le(4, 8); str(arch)
        str("general.alignment"); le(4, 4); le(4, 32)
        str("$arch.embedding_length"); le(4, 4); le(4, embd)
        return out.toByteArray()
    }

    @Test fun ecoModelAccepted() {
        val info = Checks.readGguf(ByteArrayInputStream(gguf("qwen3", 2560)))
        assertEquals("qwen3", info.architecture)
        assertEquals(2560L, info.embeddingLength)
        assertNull(Checks.checkModel(info))
        assertNotNull(Checks.checkEmbedder(info))
    }

    @Test fun wrongArchitectureRejectedAsModel() {
        assertNotNull(Checks.checkModel(Checks.readGguf(ByteArrayInputStream(gguf("llama", 4096)))))
    }

    @Test fun bundledEmbedderIsBert384() {
        val info = TestFiles.embedder.inputStream().use(Checks::readGguf)
        assertEquals("bert", info.architecture)
        assertEquals(384L, info.embeddingLength)
        assertNull(Checks.checkEmbedder(info))
        assertNotNull(Checks.checkModel(info))
    }

    @Test fun bertWithWrongSizeRejected() {
        assertNotNull(Checks.checkEmbedder(Checks.readGguf(ByteArrayInputStream(gguf("bert", 768)))))
    }

    @Test(expected = IOException::class) fun notGgufRejected() {
        Checks.readGguf(ByteArrayInputStream("SQLite format 3\u0000 not a model".toByteArray()))
    }

    @Test(expected = IOException::class) fun truncatedGgufRejected() {
        Checks.readGguf(ByteArrayInputStream(gguf("qwen3", 2560).copyOf(200)))
    }

    @Test fun sampleIndexAccepted() {
        assertNull(Checks.checkIndex(TestFiles.meta(), TestFiles.columns()))
    }

    @Test fun badIndexMetaRejected() {
        val good = TestFiles.meta()
        val cols = TestFiles.columns()
        assertNotNull(Checks.checkIndex(good + ("dim" to "768"), cols))
        assertNotNull(Checks.checkIndex(good + ("embed_model" to "all-MiniLM-L6-v2"), cols))
        assertNotNull(Checks.checkIndex(good + ("quant" to "float32"), cols))
        assertNotNull(Checks.checkIndex(good - "version", cols))
        assertNotNull(Checks.checkIndex(emptyMap(), cols))
        assertNotNull(Checks.checkIndex(good, cols - "emb"))
    }
}
