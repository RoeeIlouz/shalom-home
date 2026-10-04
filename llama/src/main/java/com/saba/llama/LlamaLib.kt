package com.saba.llama

import java.io.Closeable

/** On-device text generation backed by llama.cpp. One instance per loaded model; not thread-safe. */
class LlamaLib private constructor(private var handle: Long) : Closeable {

    /** One stateless chat turn. A non-empty [grammar] (GBNF, rule "root") constrains the reply. */
    fun chat(system: String, user: String, grammar: String = "", maxTokens: Int = 16): String {
        check(handle != 0L) { "model closed" }
        return String(nativeChat(handle, system, user, grammar, maxTokens), Charsets.UTF_8).trim()
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeChat(
        handle: Long, system: String, user: String, grammar: String, maxTokens: Int,
    ): ByteArray

    private external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("saba_llama")
        }

        @JvmStatic
        private external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long

        /** Returns null if the model file cannot be loaded. */
        fun load(path: String, nCtx: Int = 2048, threads: Int = 4): LlamaLib? =
            nativeLoad(path, nCtx, threads).takeIf { it != 0L }?.let(::LlamaLib)
    }
}
