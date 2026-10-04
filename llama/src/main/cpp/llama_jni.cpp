#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "SabaLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Session {
    llama_model *model;
    llama_context *ctx;
    int n_ctx;
};

static void quiet_log(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Raw UTF-8 bytes: a token boundary can split a multi-byte character, which NewStringUTF rejects.
static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_saba_llama_LlamaLib_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint threads) {
    llama_log_set(quiet_log, nullptr);
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(jstr(env, jpath).c_str(), mparams);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = n_ctx;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }
    return reinterpret_cast<jlong>(new Session{model, ctx, n_ctx});
}

/**
 * Runs one chat turn and returns the reply. When [jgrammar] is non-empty the output is
 * constrained by that GBNF grammar, so the model can only answer with one of the allowed ids.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_saba_llama_LlamaLib_nativeChat(JNIEnv *env, jobject, jlong handle, jstring jsystem,
                                         jstring juser, jstring jgrammar, jint max_tokens) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return to_bytes(env, "");

    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    std::string system = jstr(env, jsystem);
    std::string user = jstr(env, juser);
    std::string grammar = jstr(env, jgrammar);

    llama_chat_message msgs[] = {{"system", system.c_str()}, {"user", user.c_str()}};
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(4 * (system.size() + user.size()) + 256);
    int len = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGE("chat template failed");
        return to_bytes(env, "");
    }
    std::string prompt(buf.data(), len);

    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true);
    if (n_prompt + max_tokens > s->n_ctx) {
        LOGE("prompt too long: %d tokens", n_prompt);
        return to_bytes(env, "");
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler_chain_add(smpl, llama_sampler_init_grammar(vocab, grammar.c_str(), "root"));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    llama_token tok;
    for (int i = 0; i < max_tokens; i++) {
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("decode failed");
            break;
        }
        tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        batch = llama_batch_get_one(&tok, 1);
    }
    llama_sampler_free(smpl);
    LOGI("reply: %s", out.c_str());
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_saba_llama_LlamaLib_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}
