package org.escc.ecomini.engine

/** JNI bridge to llama.cpp (app/src/main/cpp/eco_jni.cpp). Strings travel as UTF-8 bytes. */
object LlamaNative {
    fun interface TokenSink {
        fun onToken(bytes: ByteArray)
    }

    const val DONE = 0
    const val MAX_TOKENS = 1
    const val STOPPED = 2
    const val ERROR = -1
    const val TOO_LONG = -2

    @JvmStatic external fun initBackends(libDir: String, gpu: Boolean)
    @JvmStatic external fun listDevices(): Array<String>
    @JvmStatic external fun loadModel(path: String, device: String, nThreads: Int, nCtx: Int): Long
    @JvmStatic external fun loadEmbedder(path: String, nThreads: Int): Long
    @JvmStatic external fun countTokens(handle: Long, text: ByteArray): Int
    @JvmStatic external fun embed(handle: Long, text: ByteArray): FloatArray?
    @JvmStatic external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, sink: TokenSink): Int
    @JvmStatic external fun resetStop(handle: Long)
    @JvmStatic external fun requestStop(handle: Long)
    @JvmStatic external fun free(handle: Long)
}
