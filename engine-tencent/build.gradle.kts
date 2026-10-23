import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
}

val nativeVersion = "d8d92973-r2"
val nativeArchive = layout.projectDirectory.file("libs/tencent-native-$nativeVersion.aar")
val nativeReceipt = layout.projectDirectory.file("libs/tencent-native-$nativeVersion.receipt.properties")
val nativeInputFiles = fileTree("src/main/cpp") + files(
    "build.gradle.kts", "src/main/AndroidManifest.xml",
    "src/main/java/com/example/offlinetranslator/tencent/TencentNativeRuntime.kt",
    "THIRD_PARTY.md", rootProject.file("tools/android/prepare-tencent-native.py"),
    rootProject.file("tools/android/prepare-tencent-native.ps1"),
    rootProject.file("tools/android/tencent-native-tools.json"),
)

val verifyTencentNativeArchive = tasks.register("verifyTencentNativeArchive") {
    group = "verification"
    description = "Verifies prepared native bytes against the current native source, recipe and settings; never downloads."
    inputs.files(nativeInputFiles)
    inputs.files(nativeArchive, nativeReceipt)
    inputs.property("archivePath", nativeArchive.asFile.absolutePath)
    inputs.property("receiptPath", nativeReceipt.asFile.absolutePath)
    inputs.property("sourceRootPath", rootProject.projectDir.absolutePath)
    // Always attest the local artifact before consumption, including after external replacement.
    doLast {
        val instructions = "Run python3 tools/android/prepare-tencent-native.py on Linux, or " +
            "pwsh -File tools/android/prepare-tencent-native.ps1 on Windows/WSL."
        fun reject(reason: String): Nothing = throw GradleException("Tencent native artifact: $reason. $instructions")
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        fun hash(stream: java.io.InputStream): String = stream.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        val archive = File(inputs.properties.getValue("archivePath") as String)
        val receiptFile = File(inputs.properties.getValue("receiptPath") as String)
        val sourceRoot = File(inputs.properties.getValue("sourceRootPath") as String)
        if (!archive.isFile || !receiptFile.isFile) reject("missing prepared AAR or receipt")
        val receipt = Properties().apply { receiptFile.inputStream().use { load(it) } }
        val current = inputs.files.files.filter { it != archive && it != receiptFile }.associate {
            it.relativeTo(sourceRoot).invariantSeparatorsPath to hash(it.inputStream())
        }.toSortedMap()
        val saved = receipt.stringPropertyNames().filter { it.startsWith("input.") }
            .associate { it.removePrefix("input.") to receipt.getProperty(it) }.toSortedMap()
        val inventory = current.entries.joinToString("") { "${it.key}\u0000${it.value}\n" }
        if (current != saved || hash(inventory.toByteArray(Charsets.UTF_8)) != receipt.getProperty("inputs.sha256")) {
            reject("stale native source/recipe/settings receipt")
        }
        if (receipt.getProperty("schema") != "1" ||
            receipt.getProperty("llama.commit") != "d8d92973c8c21bb4dd5d592f0fec26fe2cd1e654" ||
            receipt.getProperty("abis") != "arm64-v8a,x86_64") reject("unexpected runtime pin or ABI receipt")
        if (archive.length().toString() != receipt.getProperty("aar.bytes") ||
            hash(archive.inputStream()) != receipt.getProperty("aar.sha256")) reject("AAR checksum/size mismatch")
        try {
            ZipFile(archive).use { zipped ->
                val entries = zipped.entries().toList()
                val names = entries.map { it.name }
                if (names.size != names.toSet().size) reject("duplicate AAR members")
                val expected = receipt.stringPropertyNames().filter { it.startsWith("member.") }
                    .associate { it.removePrefix("member.") to receipt.getProperty(it) }
                if (names.toSet() != expected.keys) reject("AAR member inventory mismatch")
                entries.forEach { entry ->
                    if (hash(zipped.getInputStream(entry)) != expected[entry.name]) reject("member checksum mismatch: ${entry.name}")
                }
                val libraries = names.filter { it.startsWith("jni/") }.sorted()
                if (libraries != listOf("jni/arm64-v8a/libtencent_jni.so", "jni/x86_64/libtencent_jni.so")) {
                    reject("unexpected native library inventory")
                }
                if ("assets/tencent-native/LICENSE" !in names) reject("missing runtime license")
                libraries.forEach { name ->
                    zipped.getInputStream(zipped.getEntry(name)).use { stream ->
                        val header = stream.readNBytes(64)
                        if (header.size != 64 || !header.copyOfRange(0, 6).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1))) {
                            reject("expected little-endian ELF64: $name")
                        }
                        val elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val machine = elf.getShort(18).toInt() and 65535
                        if (machine != if (name.contains("arm64-v8a")) 183 else 62) reject("incorrect ELF machine: $name")
                        val offset = elf.getLong(32)
                        val width = elf.getShort(54).toInt() and 65535
                        val count = elf.getShort(56).toInt() and 65535
                        if (offset < 64 || width < 56 || count == 0) reject("invalid ELF segment table: $name")
                        stream.skipNBytes(offset - 64)
                        var loads = 0
                        repeat(count) {
                            val bytes = stream.readNBytes(width)
                            if (bytes.size != width) reject("truncated ELF segments: $name")
                            val segment = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            if (segment.getInt(0) == 1) {
                                loads++
                                if (segment.getLong(48) < 16384 || (segment.getLong(16) - segment.getLong(8)) % 16384L != 0L) {
                                    reject("ELF LOAD is not 16-KiB aligned: $name")
                                }
                            }
                        }
                        if (loads == 0) reject("ELF has no LOAD segments: $name")
                    }
                }
            }
        } catch (error: java.io.IOException) {
            reject("unreadable AAR: ${error.javaClass.simpleName}")
        }
        logger.lifecycle("Verified Tencent CPU JNI AAR: ${archive.name}, SHA-256 ${receipt.getProperty("aar.sha256")}")
    }
}

android {
    namespace = "com.example.offlinetranslator.tencent"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.example.offlinetranslator.native:tencent-native:$nativeVersion") {
        artifact { type = "aar" }
    }
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.jvm)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.named("preBuild").configure { dependsOn(verifyTencentNativeArchive) }
