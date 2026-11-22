package org.escc.ecomini.engine

/**
 * In-memory int8 vectors searched by brute-force dot product in C++ (vecsearch.cpp).
 * The caller loads the native library ("ecosearch") first.
 */
class VectorIndex(val dim: Int, capacity: Int) : AutoCloseable {
    private var handle = nativeCreate(dim, capacity).also {
        if (it == 0L) throw OutOfMemoryError("cannot allocate index for $capacity vectors")
    }

    val size: Int get() = nativeSize(handle)

    /** Adds n vectors: embs holds n*dim int8 values, scales and ids hold n values each. */
    fun add(embs: ByteArray, scales: FloatArray, ids: LongArray, n: Int) {
        check(nativeAdd(handle, embs, scales, ids, n)) { "index add failed" }
    }

    /** Ids of the k best chunks for an L2-normalised query, best first. */
    fun search(query: FloatArray, k: Int): LongArray = nativeSearch(handle, query, k)

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    private companion object {
        @JvmStatic external fun nativeCreate(dim: Int, capacity: Int): Long
        @JvmStatic external fun nativeAdd(h: Long, embs: ByteArray, scales: FloatArray, ids: LongArray, n: Int): Boolean
        @JvmStatic external fun nativeSize(h: Long): Int
        @JvmStatic external fun nativeSearch(h: Long, query: FloatArray, k: Int): LongArray
        @JvmStatic external fun nativeFree(h: Long)
    }
}
