#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "SabaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static void quiet_log(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_saba_whisper_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring jpath) {
    whisper_log_set(quiet_log, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

// Returns raw UTF-8 bytes: NewStringUTF aborts on malformed sequences, which Whisper can emit.
static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_saba_whisper_WhisperLib_nativeTranscribe(JNIEnv *env, jobject, jlong handle,
                                                  jfloatArray jsamples, jstring jlang,
                                                  jstring jprompt, jint threads,
                                                  jint audio_ctx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return to_bytes(env, "");

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    // The encoder normally processes a fixed 30 s window; sizing it to the clip makes short commands much faster.
    p.audio_ctx = audio_ctx;
    p.language = lang;
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    // Biasing the decoder with the tile names (e.g. "אמא, וואטסאפ") makes short commands far more accurate.
    p.initial_prompt = prompt;

    std::string out;
    if (whisper_full(ctx, p, samples, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    }

    env->ReleaseStringUTFChars(jprompt, prompt);
    env->ReleaseStringUTFChars(jlang, lang);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    LOGI("transcript: %s", out.c_str());
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_saba_whisper_WhisperLib_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
