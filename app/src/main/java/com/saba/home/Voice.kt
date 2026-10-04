package com.saba.home

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.saba.llama.LlamaLib
import com.saba.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "SabaVoice"
const val SAMPLE_RATE = 16_000

/**
 * Records one utterance: waits for speech, stops after a pause.
 * Grandpa should not have to hold a button or know when to stop.
 */
object Recorder {
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    suspend fun recordUtterance(onLevel: (Float) -> Unit): FloatArray = withContext(Dispatchers.IO) {
        val chunk = SAMPLE_RATE / 10 // 100 ms
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 4),
        )
        val out = ArrayList<Float>(SAMPLE_RATE * 8)
        val buf = ShortArray(chunk)
        var noise = 0f
        var chunks = 0
        var heard = false
        var silentChunks = 0
        try {
            rec.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val f = buf[i] / 32768f
                    out += f
                    sum += f * f
                }
                val rms = sqrt(sum / n).toFloat()
                onLevel((rms * 12).coerceIn(0f, 1f))
                chunks++
                if (chunks <= 3) {
                    noise = maxOf(noise, rms) // calibrate on the first 300 ms
                    continue
                }
                val speaking = rms > maxOf(noise * 2.5f, 0.012f)
                if (speaking) {
                    heard = true
                    silentChunks = 0
                } else silentChunks++

                val seconds = chunks / 10f
                if (heard && silentChunks >= 12) break // 1.2 s pause ends the request
                if (!heard && seconds >= 5f) break // he never started speaking
                if (seconds >= 10f) break
            }
        } finally {
            rec.stop()
            rec.release()
        }
        if (!heard) FloatArray(0) else out.toFloatArray()
    }
}

/** Holds the two open models in memory and runs them off the main thread. */
class Brain(private val context: Context) {
    private val lock = Mutex()
    private var whisper: WhisperLib? = null
    private var whisperFile: String? = null
    private var llama: LlamaLib? = null
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    suspend fun warmUp(settings: Settings) = lock.withLock {
        withContext(Dispatchers.IO) {
            ensureWhisper(settings)
            if (settings.useLlm) ensureLlama()
        }
    }

    private fun ensureWhisper(settings: Settings): WhisperLib? {
        if (whisperFile != settings.whisperModel) {
            whisper?.close()
            whisper = null
            val f = ModelCatalog.file(context, settings.whisperModel)
            if (f.exists()) {
                whisper = WhisperLib.load(f.absolutePath)
                whisperFile = settings.whisperModel
            }
        }
        return whisper
    }

    private fun ensureLlama(): LlamaLib? {
        if (llama == null) {
            val f = ModelCatalog.file(context, ModelCatalog.GEMMA_1B.fileName)
            if (f.exists()) llama = LlamaLib.load(f.absolutePath, nCtx = 4096, threads = threads)
        }
        return llama
    }

    data class Heard(val transcript: String, val resolution: Resolution, val millis: Long)

    suspend fun understand(audio: FloatArray, settings: Settings, apps: List<AppInfo>): Heard = lock.withLock {
        withContext(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            val w = ensureWhisper(settings) ?: return@withContext Heard("", Resolution(null, "no speech model"), 0)
            // Whisper needs at least a second of audio; pad short commands like "אמא" with silence.
            val padded = if (audio.size < SAMPLE_RATE * 3 / 2) audio.copyOf(SAMPLE_RATE * 3 / 2) else audio
            // Prime Whisper with the names he is likely to say, so "מיכל" is not heard as a similar-sounding word.
            val hint = (settings.contacts.flatMap { listOf(it.name) + it.aliases } +
                settings.tiles.filter { it.kind == TileKind.APP }.map { it.label }).distinct().joinToString(", ")
            val text = w.transcribe(padded, settings.language, hint, threads)
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "whisper ${t1 - t0}ms: $text")

            val resolver = Resolver(settings.contacts, settings.tiles, apps)
            resolver.byKeywords(text)?.let {
                return@withContext Heard(text, Resolution(it, "keywords"), System.currentTimeMillis() - t0)
            }
            val llm = if (settings.useLlm && text.isNotBlank()) ensureLlama() else null
            val picked = llm?.let { resolver.byModel(it, text) }
            Log.i(TAG, "gemma ${System.currentTimeMillis() - t1}ms -> ${picked?.spokenName}")
            Heard(text, Resolution(picked, if (llm != null) "gemma" else "none"), System.currentTimeMillis() - t0)
        }
    }

    fun close() {
        whisper?.close()
        llama?.close()
    }
}
