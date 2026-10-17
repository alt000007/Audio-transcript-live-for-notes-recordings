// The bridge between LocalEngine.kt and whisper.cpp.
//
// Deliberately thin: load a model, transcribe one piece of 16 kHz mono audio,
// free the model. Everything else — decoding, cutting, saving, retrying —
// happens in Kotlin, shared with the online engine.

#include <jni.h>
#include <string>
#include "whisper.h"

namespace {

struct Abort {
    JNIEnv *env;
    jobject check;
    jmethodID method;
};

// Called by whisper.cpp between steps, on the thread that called
// whisper_full, so the JNIEnv is valid here.
bool should_abort(void *user) {
    auto *a = static_cast<Abort *>(user);
    jboolean stop = a->env->CallBooleanMethod(a->check, a->method);
    if (a->env->ExceptionCheck()) {
        a->env->ExceptionClear();
        return true;
    }
    return stop == JNI_TRUE;
}

std::string clean(const char *text) {
    std::string out;
    for (const char *p = text; p && *p; ++p) {
        char c = *p;
        out += (c == '\t' || c == '\n' || c == '\r') ? ' ' : c;
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_scribe_LocalEngine_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_app_scribe_LocalEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// Returns UTF-8 bytes rather than a String: a token boundary can split a
// multi-byte character, and NewStringUTF aborts the process on invalid input.
// Kotlin decodes the bytes leniently instead.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_scribe_LocalEngine_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm,
                                             jstring lang, jstring prompt, jint threads, jobject abort) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const char *language = env->GetStringUTFChars(lang, nullptr);
    const char *initial = env->GetStringUTFChars(prompt, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads > 0 ? threads : 4;
    wp.translate = false;
    wp.language = language;
    wp.detect_language = false;
    wp.initial_prompt = (initial && *initial) ? initial : nullptr;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.suppress_blank = true;
    wp.temperature = 0.0f;

    Abort a{env, abort, nullptr};
    jclass cls = env->GetObjectClass(abort);
    a.method = env->GetMethodID(cls, "shouldAbort", "()Z");
    if (a.method != nullptr) {
        wp.abort_callback = should_abort;
        wp.abort_callback_user_data = &a;
    } else {
        env->ExceptionClear();
    }

    jsize n = env->GetArrayLength(pcm);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    env->ReleaseStringUTFChars(lang, language);
    env->ReleaseStringUTFChars(prompt, initial);
    if (rc != 0) return nullptr;

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        out += std::to_string(whisper_full_get_segment_t0(ctx, i));
        out += '\t';
        out += std::to_string(whisper_full_get_segment_t1(ctx, i));
        out += '\t';
        out += clean(whisper_full_get_segment_text(ctx, i));
        out += '\n';
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    }
    return result;
}
