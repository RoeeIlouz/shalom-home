package com.saba.home

import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-build-only hooks so the voice pipeline can be exercised from adb with recorded audio.
 * test_wav runs are dry runs: they log what would happen and never launch or dial.
 *   adb shell am start -n com.saba.home/.MainActivity --es test_wav /sdcard/Download/t1.wav [--es lang auto]
 *   adb shell am start -n com.saba.home/.MainActivity --es demo_contact "מיכל|000|הבת שלי"
 */
object DebugHooks {
    fun handle(a: MainActivity, intent: Intent?) {
        if (intent == null || a.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

        intent.getStringExtra("lang")?.let { a.updateSettings(a.settings.copy(language = it)) }
        intent.getStringExtra("whisper")?.let { a.updateSettings(a.settings.copy(whisperModel = it)) }

        intent.getStringExtra("demo_contact")?.let { spec ->
            val parts = spec.split('|')
            val c = Contact(
                id = "c_demo_${parts[0].hashCode()}", name = parts[0], number = parts.getOrElse(1) { "000" },
                aliases = parts.getOrNull(2)?.split(',')?.map { it.trim() }.orEmpty(),
            )
            a.updateSettings(a.settings.copy(contacts = a.settings.contacts.filter { it.id != c.id } + c))
        }

        // Asks Android to pin a harmless test shortcut, exercising the same path Contacts uses on Samsung phones.
        if (intent.getBooleanExtra("pin_test", false)) {
            intent.removeExtra("pin_test")
            val sm = a.getSystemService(android.content.pm.ShortcutManager::class.java)
            val info = android.content.pm.ShortcutInfo.Builder(a, "pin_test")
                .setShortLabel("בדיקה")
                .setIcon(android.graphics.drawable.Icon.createWithResource(a, android.R.drawable.sym_def_app_icon))
                .setIntent(Intent(a, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                .build()
            android.util.Log.i("SabaExtras", "pin supported=${sm.isRequestPinShortcutSupported}")
            sm.requestPinShortcut(info, null)
        }

        // Static screens for the promo video, e.g. --es demo_state confirm. Nothing is recorded, dialed or opened.
        intent.getStringExtra("demo_state")?.let { st ->
            intent.removeExtra("demo_state")
            val contact = a.settings.contacts.firstOrNull()
            a.debugShowVoice(when (st) {
                "listening" -> VoiceState.Listening(0.7f)
                "thinking" -> VoiceState.Thinking
                "confirm" -> contact?.let { VoiceState.Confirm(Target.OfContact(it), 3) } ?: VoiceState.Idle
                "notunderstood" -> VoiceState.NotUnderstood("מה השעה עכשיו")
                else -> VoiceState.Idle
            })
        }

        intent.getStringExtra("test_wav")?.let { path ->
            intent.removeExtra("test_wav")
            runCatching { readWav(File(path)) }
                .onSuccess(a::runOnAudio)
                .onFailure { a.toast("test_wav: ${it.message}") }
        }
    }

    /** 16 kHz mono PCM16 WAV -> floats. Walks the chunks to find "data" rather than assuming a 44-byte header. */
    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, b.limit() - pos - 8) / 2
                return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        return FloatArray(0)
    }
}
