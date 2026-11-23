#include <jni.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_ngoroth_offlinetranslator_tencent_TencentCpuFeatures_supportsSimd(JNIEnv *, jobject) {
#if defined(__aarch64__)
    // Match -march=armv8-a+dotprod+fp16+i8mm without raising the baseline ISA.
    constexpr unsigned long required = HWCAP_ASIMDDP | HWCAP_FPHP | HWCAP_ASIMDHP;
    return (getauxval(AT_HWCAP) & required) == required && (getauxval(AT_HWCAP2) & HWCAP2_I8MM) != 0;
#else
    return JNI_FALSE;
#endif
}
