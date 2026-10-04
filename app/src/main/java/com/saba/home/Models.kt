package com.saba.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ModelSpec(val title: String, val titleHe: String, val fileName: String, val url: String, val bytes: Long)

/**
 * Open-weight models the setup screen can fetch. They are downloaded once over Wi-Fi;
 * after that every word Grandpa says is processed on the phone.
 */
object ModelCatalog {
    val WHISPER_BASE = ModelSpec(
        "Whisper base (60MB, fast)", "Whisper בסיסי (60MB, מהיר)", "ggml-base-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", 59_707_625,
    )
    val WHISPER_SMALL = ModelSpec(
        "Whisper small (190MB, better Hebrew)", "Whisper קטן (190MB, עברית טובה יותר)", "ggml-small-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190_085_487,
    )
    val GEMMA_1B = ModelSpec(
        "Gemma 3 1B (800MB, understands requests)", "Gemma 3 1B (800MB, מבין בקשות)", "gemma-3-1b-it-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf", 806_058_272,
    )
    val whisperModels = listOf(WHISPER_BASE, WHISPER_SMALL)
    val all = listOf(WHISPER_BASE, WHISPER_SMALL, GEMMA_1B)

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Any model file present in the folder counts, so a custom model can be side-loaded (see README). */
    fun file(context: Context, fileName: String) = File(dir(context), fileName)

    fun isReady(context: Context, spec: ModelSpec) = file(context, spec.fileName).let { it.exists() && it.length() > 0 }

    suspend fun download(context: Context, spec: ModelSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dest = file(context, spec.fileName)
        val part = File(dest.parentFile, dest.name + ".part")
        var conn = URL(spec.url).openConnection() as HttpURLConnection
        // Hugging Face redirects to a CDN on another host, which HttpURLConnection will not follow by itself.
        for (hop in 0 until 5) {
            conn.instanceFollowRedirects = false
            if (part.exists()) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            if (conn.responseCode !in 300..399) break
            val next = URL(conn.url, conn.getHeaderField("Location"))
            conn.disconnect()
            conn = next.openConnection() as HttpURLConnection
        }
        check(conn.responseCode in 200..299) { "download failed: HTTP ${conn.responseCode}" }
        val resumed = conn.responseCode == 206
        if (!resumed) part.delete()
        val total = if (resumed) part.length() + conn.contentLengthLong else conn.contentLengthLong
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = part.length()
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 1_000_000) {
                        last = done
                        onProgress(done.toFloat() / (if (total > 0) total else spec.bytes))
                    }
                }
            }
        }
        check(part.renameTo(dest)) { "could not finalize ${dest.name}" }
        onProgress(1f)
    }
}
