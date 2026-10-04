package com.saba.whisper

import java.io.Closeable

/** On-device speech-to-text backed by whisper.cpp. One instance per loaded model; not thread-safe. */
class WhisperLib private constructor(private var handle: Long) : Closeable {

    /**
     * [samples] are 16 kHz mono PCM floats in [-1, 1]. [fitContext] shrinks the encoder window to the
     * clip length (50 frames per second), trading a little accuracy for a large speedup on short commands.
     */
    fun transcribe(samples: FloatArray, language: String, prompt: String, threads: Int, fitContext: Boolean = true): String {
        check(handle != 0L) { "model closed" }
        val ctx = if (fitContext) minOf(1500, samples.size / 320 + 64) else 0
        return String(nativeTranscribe(handle, samples, language, prompt, threads, ctx), Charsets.UTF_8).trim()
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeTranscribe(
        handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int, audioCtx: Int,
    ): ByteArray

    private external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("saba_whisper")
        }

        @JvmStatic
        private external fun nativeInit(path: String): Long

        /** Returns null if the model file cannot be loaded. */
        fun load(path: String): WhisperLib? = nativeInit(path).takeIf { it != 0L }?.let(::WhisperLib)
    }
}
