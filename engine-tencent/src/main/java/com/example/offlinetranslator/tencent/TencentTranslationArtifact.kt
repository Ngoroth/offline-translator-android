package com.example.offlinetranslator.tencent

/** One immutable multilingual artifact; never part of the APK or speech model catalog. */
internal data class TencentTranslationArtifact(val url: String, val bytes: Long, val sha256: String) {
    companion object {
        const val REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a"
        val PINNED = TencentTranslationArtifact(
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/$REVISION/Hy-MT2-1.8B-Q4_K_M.gguf",
            1_133_080_448L,
            "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
        )
        // Exact official delivery hosts captured during immutable artifact qualification.
        val DELIVERY_HOSTS = setOf("huggingface.co", "us.aws.cdn.hf.co")
    }
}
