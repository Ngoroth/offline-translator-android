#include "tencent_runtime.h"
#include <jni.h>
#include <new>

namespace {
void fail(JNIEnv *env, const char *code) noexcept {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("com/example/offlinetranslator/tencent/TencentNativeException");
    if (!type) return;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
    if (!constructor) return;
    // Only fixed ASCII failure codes use a JNI string; all model/user bytes use arrays.
    jstring value = env->NewStringUTF(code);
    if (!value) return;
    auto exception = static_cast<jthrowable>(env->NewObject(type, constructor, value));
    if (exception) env->Throw(exception);
}

template<class F> void boundary(JNIEnv *env, F &&action) noexcept {
    try { action(); }
    catch (const tencent::Failure &error) { fail(env, error.what()); }
    catch (const std::bad_alloc &) { fail(env, "memory"); }
    catch (...) { fail(env, "model_load"); }
}

tencent::Runtime &runtime(jlong handle) {
    if (!handle) throw tencent::Failure("closed");
    return *reinterpret_cast<tencent::Runtime *>(handle);
}
std::string bytes(JNIEnv *env, jbyteArray array) {
    if (!array) throw tencent::Failure("invalid_encoding");
    const auto length = env->GetArrayLength(array);
    std::string result(length, '\0');
    if (length) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw tencent::Failure("memory");
    return result;
}
}

#define JNI_METHOD(name) Java_com_example_offlinetranslator_tencent_TencentNativeRuntime_##name
extern "C" {
JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv *env, jobject) {
    jlong handle = 0;
    boundary(env, [&] { handle = reinterpret_cast<jlong>(new tencent::Runtime()); });
    return handle;
}
JNIEXPORT void JNICALL JNI_METHOD(nativeLoad)(JNIEnv *env, jobject, jlong handle, jbyteArray path) {
    boundary(env, [&] { runtime(handle).load(bytes(env, path)); });
}
JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeTranslate)(JNIEnv *env, jobject, jlong handle, jbyteArray target, jbyteArray text) {
    jbyteArray result = nullptr;
    boundary(env, [&] {
        const auto output = runtime(handle).translate(bytes(env, target), bytes(env, text));
        result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result && !output.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                                             reinterpret_cast<const jbyte *>(output.data()));
    });
    return result;
}
JNIEXPORT void JNICALL JNI_METHOD(nativeResetCancellation)(JNIEnv *env, jobject, jlong handle) {
    boundary(env, [&] { runtime(handle).reset_cancellation(); });
}
JNIEXPORT void JNICALL JNI_METHOD(nativeCancel)(JNIEnv *env, jobject, jlong handle) {
    boundary(env, [&] { runtime(handle).cancel(); });
}
JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv *env, jobject, jlong handle) {
    boundary(env, [&] { delete &runtime(handle); });
}
}
