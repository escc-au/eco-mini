package org.escc.ecomini.files

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** File checks: SHA-256, GGUF header contents and the index's meta table. */
object Checks {
    const val EMBED_DIM = 384
    const val ECO_ARCH = "qwen3"

    fun sha256(file: File, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True if the file has the size and SHA-256 the manifest lists. */
    fun matches(file: File, expected: ManifestFile, onProgress: (Long) -> Unit = {}): Boolean =
        file.isFile && file.length() == expected.size && sha256(file, onProgress) == expected.sha256

    data class GgufInfo(val architecture: String?, val embeddingLength: Long?)

    /** Reads general.architecture and <arch>.embedding_length from a GGUF header. */
    fun readGguf(input: InputStream): GgufInfo {
        val r = LeReader(BufferedInputStream(input, 1 shl 16))
        val magic = r.bytes(4)
        if (!magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())))
            throw IOException("not a GGUF file")
        val version = r.u32()
        if (version < 2 || version > 3) throw IOException("unsupported GGUF version $version")
        r.u64() // tensor count
        val kvCount = r.u64()
        var arch: String? = null
        var embd: Long? = null
        for (i in 0 until kvCount) {
            val key = r.string()
            val type = r.u32().toInt()
            if (key == "general.architecture" && type == T_STRING) {
                arch = r.string()
            } else if (arch != null && key == "$arch.embedding_length" && type in INT_TYPES) {
                embd = r.int(type)
            } else {
                r.skipValue(type)
            }
            if (arch != null && embd != null) break
        }
        return GgufInfo(arch, embd)
    }

    /** Returns null if this is the Eco model, otherwise the reason it is not. */
    fun checkModel(info: GgufInfo): String? = when {
        info.architecture != ECO_ARCH -> "This is not the Eco model (architecture: ${info.architecture ?: "unknown"}, expected $ECO_ARCH)."
        else -> null
    }

    /** Returns null if this is a usable BERT-type embedder with 384 dimensions. */
    fun checkEmbedder(info: GgufInfo): String? = when {
        info.architecture?.contains("bert") != true -> "Embedder is not a BERT-type model (${info.architecture})."
        info.embeddingLength != EMBED_DIM.toLong() -> "Embedder size is ${info.embeddingLength}, expected $EMBED_DIM."
        else -> null
    }

    val REQUIRED_CHUNK_COLUMNS = setOf("id", "title", "text", "emb", "scale")

    /** Returns null if the index's meta table and chunk columns match what the app expects. */
    fun checkIndex(meta: Map<String, String>, chunkColumns: Set<String>): String? {
        val missing = REQUIRED_CHUNK_COLUMNS - chunkColumns
        return when {
            missing.isNotEmpty() -> "Index chunks table is missing: ${missing.joinToString()}."
            meta["embed_model"] != "bge-small-en-v1.5" -> "Index was built with a different embedder (${meta["embed_model"]})."
            meta["dim"] != EMBED_DIM.toString() -> "Index dimension is ${meta["dim"]}, expected $EMBED_DIM."
            meta["quant"] != "int8" -> "Index quantisation is ${meta["quant"]}, expected int8."
            meta["version"].isNullOrBlank() -> "Index has no version."
            else -> null
        }
    }

    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private val INT_TYPES = setOf(0, 1, 2, 3, 4, 5, 10, 11)

    private class LeReader(private val input: InputStream) {
        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return b
        }

        private fun le(n: Int): Long {
            val b = bytes(n)
            var v = 0L
            for (i in n - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }

        fun u32(): Long = le(4)
        fun u64(): Long = le(8)

        fun string(): String {
            val len = u64()
            if (len < 0 || len > 1 shl 20) throw IOException("bad GGUF string length")
            return String(bytes(len.toInt()), Charsets.UTF_8)
        }

        fun int(type: Int): Long = when (type) {
            0 -> le(1)
            1 -> le(1).toByte().toLong()
            2 -> le(2)
            3 -> le(2).toShort().toLong()
            4 -> le(4)
            5 -> le(4).toInt().toLong()
            else -> le(8)
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                } else {
                    left -= s
                }
            }
        }

        fun skipValue(type: Int) {
            when (type) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                T_STRING -> skip(u64())
                T_ARRAY -> {
                    val inner = u32().toInt()
                    val n = u64()
                    for (i in 0 until n) skipValue(inner)
                }
                else -> throw IOException("bad GGUF value type $type")
            }
        }
    }
}
