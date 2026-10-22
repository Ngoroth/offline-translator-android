#include <jni.h>
#include <nemo_speech/asr.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>

namespace {
constexpr jint kMaxSamples = 240000;
constexpr jint kWarmSamples = 32000;
constexpr size_t kMaxTextBytes = 65536;
constexpr size_t kMaxPathBytes = 4096;

using Recognizer = std::unique_ptr<nemo_speech_asr_recognizer, decltype(&nemo_speech_asr_destroy)>;
using Result = std::unique_ptr<nemo_speech_asr_result, decltype(&nemo_speech_asr_result_destroy)>;

std::string bounded(const char* text, size_t maximum) {
    if (!text) throw std::runtime_error("NeMo returned null text");
    const size_t length = strnlen(text, maximum + 1);
    if (length > maximum) throw std::runtime_error("NeMo UTF-8 result exceeds byte bound");
    return std::string(text, length);
}

std::string lastError(nemo_speech_asr_status status) {
    // Copy thread-local bytes before a result/recognizer destructor or any other C call.
    const char* error = nemo_speech_asr_last_error();
    return "NeMo C API status " + std::to_string(static_cast<int>(status)) + ": " +
        (error ? bounded(error, kMaxTextBytes) : "no error detail");
}

jbyteArray bytes(JNIEnv* env, const char* data, size_t length) {
    auto output = env->NewByteArray(static_cast<jsize>(length));
    if (output && length != 0) {
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(length),
            reinterpret_cast<const jbyte*>(data));
    }
    return output;
}

void throwFailure(JNIEnv* env, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    // NewStringUTF is only used for the constant encoding name, never SDK/transcript bytes.
    const size_t length = strnlen(message, kMaxTextBytes);
    jbyteArray encoded = bytes(env, message, length);
    if (!encoded) return;
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return;
    jmethodID stringConstructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    if (!stringConstructor) return;
    jstring encoding = env->NewStringUTF("UTF-8");
    if (!encoding) return;
    auto text = static_cast<jstring>(env->NewObject(stringClass, stringConstructor, encoded, encoding));
    if (!text) return;
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (!exceptionClass) return;
    jmethodID constructor = env->GetMethodID(exceptionClass, "<init>", "(Ljava/lang/String;)V");
    if (!constructor) return;
    auto failure = static_cast<jthrowable>(env->NewObject(exceptionClass, constructor, text));
    if (failure) env->Throw(failure);
}

void throwCurrent(JNIEnv* env) noexcept {
    try {
        throw;
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) {
            jclass type = env->FindClass("java/lang/OutOfMemoryError");
            if (type) env->ThrowNew(type, "NeMo native allocation failed");
        }
    } catch (const std::exception& error) {
        throwFailure(env, error.what());
    } catch (...) {
        throwFailure(env, "Unknown NeMo native exception");
    }
}

nemo_speech_asr_recognizer* recognizer(jlong handle) {
    if (handle == 0) throw std::invalid_argument("NeMo recognizer is closed");
    static_assert(sizeof(uintptr_t) <= sizeof(jlong));
    return reinterpret_cast<nemo_speech_asr_recognizer*>(static_cast<uintptr_t>(handle));
}

const float* samples(JNIEnv* env, jobject buffer, jint count) {
    if (!buffer || count <= 0 || count > kMaxSamples) {
        throw std::invalid_argument("NeMo requires 1..240000 mono 16 kHz samples");
    }
    void* data = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!data || capacity < static_cast<jlong>(count) * static_cast<jlong>(sizeof(float)) ||
        reinterpret_cast<uintptr_t>(data) % alignof(float) != 0) {
        throw std::invalid_argument("NeMo requires aligned direct F32 byte storage");
    }
    return static_cast<const float*>(data);
}

Result recognize(nemo_speech_asr_recognizer* owned, const float* data, jint count) {
    auto options = nemo_speech_asr_recognition_options_default();
    options.language_code = nullptr;
    options.enable_word_time_offsets = false;
    options.enable_automatic_punctuation = false;
    options.verbatim_transcripts = true;
    nemo_speech_asr_result* raw = nullptr;
    const auto status = nemo_speech_asr_recognize_f32(owned, &options, data,
        static_cast<size_t>(count), 16000, &raw);
    // Adopt even an error-returned result, but capture TLS error before destroy can change it.
    Result result(raw, &nemo_speech_asr_result_destroy);
    if (status != NEMO_SPEECH_ASR_OK) throw std::runtime_error(lastError(status));
    if (!result) throw std::runtime_error("NeMo returned no offline result");
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlinetranslator_nemo_NemoNative_create(JNIEnv* env, jobject, jbyteArray modelPath) {
    try {
        if (!modelPath) throw std::invalid_argument("Missing local GGUF path");
        const jsize length = env->GetArrayLength(modelPath);
        if (length <= 0 || static_cast<size_t>(length) > kMaxPathBytes) {
            throw std::invalid_argument("Invalid local GGUF path length");
        }
        std::string path(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(modelPath, 0, length, reinterpret_cast<jbyte*>(path.data()));
        if (env->ExceptionCheck()) return 0;
        if (path.front() != '/' || path.find('\0') != std::string::npos) {
            throw std::invalid_argument("Expected an absolute local GGUF path without NUL");
        }
        nemo_speech_asr_backend_config backend{};
        backend.size = sizeof(backend);
        backend.gpu = -1;
        nemo_speech_asr_model_config model{};
        model.size = sizeof(model);
        model.path = path.c_str();
        nemo_speech_asr_batching_config batching{};
        batching.size = sizeof(batching);
        batching.enable = false;
        // The prepared owner serializes transcription, so only one predictor state is needed.
        batching.state_arena_slots = 1;
        nemo_speech_asr_recognizer_config config{};
        config.size = sizeof(config);
        config.backend = &backend;
        config.model = &model;
        config.batching = &batching;
        nemo_speech_asr_recognizer* raw = nullptr;
        const auto status = nemo_speech_asr_create(&config, &raw);
        Recognizer owned(raw, &nemo_speech_asr_destroy);
        if (status != NEMO_SPEECH_ASR_OK) throw std::runtime_error(lastError(status));
        if (!owned) throw std::runtime_error("NeMo returned no recognizer");
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(owned.release()));
    } catch (...) {
        throwCurrent(env);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlinetranslator_nemo_NemoNative_warm(JNIEnv* env, jobject, jlong handle, jobject pcm) {
    try {
        auto result = recognize(recognizer(handle), samples(env, pcm, kWarmSamples), kWarmSamples);
    } catch (...) {
        throwCurrent(env);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_offlinetranslator_nemo_NemoNative_recognize(
    JNIEnv* env, jobject, jlong handle, jobject pcm, jint count) {
    try {
        auto result = recognize(recognizer(handle), samples(env, pcm, count), count);
        if (nemo_speech_asr_result_alternative_count(result.get()) == 0) {
            throw std::runtime_error("NeMo returned no transcript alternative");
        }
        const char* text = nemo_speech_asr_result_transcript(result.get(), 0);
        if (!text) throw std::runtime_error("NeMo returned null transcript");
        const size_t length = strnlen(text, kMaxTextBytes + 1);
        if (length > kMaxTextBytes) throw std::runtime_error("NeMo transcript exceeds UTF-8 byte bound");
        // Copy real UTF-8 while result owns it; Kotlin decodes strictly after this call completes.
        return bytes(env, text, length);
    } catch (...) {
        throwCurrent(env);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlinetranslator_nemo_NemoNative_destroy(JNIEnv* env, jobject, jlong handle) {
    try {
        if (handle != 0) nemo_speech_asr_destroy(recognizer(handle));
    } catch (...) {
        throwCurrent(env);
    }
}
