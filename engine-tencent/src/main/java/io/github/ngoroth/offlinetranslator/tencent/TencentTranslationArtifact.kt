package io.github.ngoroth.offlinetranslator.tencent

import io.github.ngoroth.offlinetranslator.core.TranslationProviderDescriptor

/** Immutable weights and their effective legal identity; never packaged in the APK. */
internal data class TencentTranslationArtifact(
    val url: String?,
    val bytes: Long,
    val sha256: String,
    val fileName: String = CURRENT_FILE_NAME,
    val descriptor: TranslationProviderDescriptor = CURRENT_DESCRIPTOR,
) {
    companion object {
        private const val CURRENT_REVISION = "4c4370b31f13ccac4ac71133e3a0967c88cc792c"
        private const val RETIRED_REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a"
        private const val CURRENT_FILE_NAME = "Hy-MT3-1.8B-Q4_K_M.gguf"

        private fun descriptor(model: String, revision: String) = TranslationProviderDescriptor(
            id = "tencent", displayName = "Tencent $model", provider = "Tencent",
            sdkVersion = "$model-1.8B Q4_K_M ${revision.take(8)} / llama.cpp v1.0.0 b1b770d9",
            attribution = "$model by Tencent",
            termsUrl = "https://huggingface.co/tencent/$model-1.8B-GGUF/blob/$revision/LICENSE.txt",
        )

        private val CURRENT_DESCRIPTOR = descriptor("Hy-MT3", CURRENT_REVISION)
        val CURRENT = TencentTranslationArtifact(
            "https://huggingface.co/tencent/Hy-MT3-1.8B-GGUF/resolve/$CURRENT_REVISION/$CURRENT_FILE_NAME",
            1_348_432_160L,
            "bae6e591103654ff43ef0de7d1492f6e6df1f955505f6bb2cc52e86fc399a942",
        )
        val RETIRED = TencentTranslationArtifact(
            null,
            1_133_080_448L,
            "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
            "Hy-MT2-1.8B-Q4_K_M.gguf",
            descriptor("Hy-MT2", RETIRED_REVISION),
        )
        // Exact official delivery hosts captured during immutable artifact qualification.
        val DELIVERY_HOSTS = setOf("huggingface.co", "us.aws.cdn.hf.co")
    }
}
