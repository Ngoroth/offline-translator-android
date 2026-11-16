import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
}

val nativeVersion = "8eada038-r2"
val nativeArchive = layout.projectDirectory.file("libs/mozilla-native-$nativeVersion.aar")
val nativeReceipt = layout.projectDirectory.file("libs/mozilla-native-$nativeVersion.receipt.properties")
val nativeSources = layout.projectDirectory.file("libs/mozilla-native-$nativeVersion.sources.tar.gz")
val nativeInputFiles = fileTree("src/main/cpp") + fileTree("LICENSES") + fileTree("src/androidTest") + files(
    "build.gradle.kts", "src/main/AndroidManifest.xml", "consumer-rules.pro", "THIRD_PARTY.md",
    "src/main/java/io/github/ngoroth/offlinetranslator/mozilla/MozillaNativeRuntime.kt",
    rootProject.file("tools/android/prepare-mozilla-native.py"),
    rootProject.file("tools/android/prepare-mozilla-native.ps1"),
    rootProject.file("tools/android/mozilla-native-tools.json"),
    rootProject.file("tools/android/test_mozilla_native_preparation.py"),
)

val verifyMozillaNativeArchive = tasks.register("verifyMozillaNativeArchive") {
    group = "verification"
    description = "Attests the prepared Mozilla AAR and corresponding source offline; never fetches or builds native code."
    inputs.files(nativeInputFiles)
    inputs.files(nativeArchive, nativeReceipt, nativeSources)
    inputs.property("archivePath", nativeArchive.asFile.absolutePath)
    inputs.property("receiptPath", nativeReceipt.asFile.absolutePath)
    inputs.property("sourcesPath", nativeSources.asFile.absolutePath)
    inputs.property("sourceRootPath", rootProject.projectDir.absolutePath)
    doLast {
        fun reject(reason: String): Nothing = throw GradleException(
            "Mozilla native artifact: $reason. Run pwsh -File tools/android/prepare-mozilla-native.ps1 " +
                "(Windows/WSL) or python3 tools/android/prepare-mozilla-native.py (Linux).",
        )
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
        val sources = File(inputs.properties.getValue("sourcesPath") as String)
        val sourceRoot = File(inputs.properties.getValue("sourceRootPath") as String)
        if (!archive.isFile || !receiptFile.isFile || !sources.isFile) reject("missing AAR, receipt or corresponding source")
        val receipt = Properties().apply { receiptFile.inputStream().use { load(it) } }
        val current = inputs.files.files.filter { it != archive && it != receiptFile && it != sources }.associate {
            it.relativeTo(sourceRoot).invariantSeparatorsPath to hash(it.inputStream())
        }.toSortedMap()
        val saved = receipt.stringPropertyNames().filter { it.startsWith("input.") }
            .associate { it.removePrefix("input.") to receipt.getProperty(it) }.toSortedMap()
        val inventory = current.entries.joinToString("") { "${it.key}\u0000${it.value}\n" }
        if (current != saved || hash(inventory.toByteArray(Charsets.UTF_8)) != receipt.getProperty("inputs.sha256")) {
            reject("stale native source/binding/tool/notice/module receipt")
        }
        if (receipt.getProperty("schema") != "1" ||
            receipt.getProperty("mozilla.commit") != "8eada038fd45bbf6fa58a412b22f80c79887fabc" ||
            receipt.getProperty("abis") != "arm64-v8a,x86_64"
        ) reject("incorrect source pin or ABI receipt")
        if (archive.length().toString() != receipt.getProperty("aar.bytes") ||
            hash(archive.inputStream()) != receipt.getProperty("aar.sha256")
        ) reject("AAR checksum/length mismatch")
        if (sources.length().toString() != receipt.getProperty("sources.bytes") ||
            hash(sources.inputStream()) != receipt.getProperty("sources.sha256")
        ) reject("corresponding-source checksum/length mismatch")
        try {
            ZipFile(archive).use { zipped ->
                val entries = zipped.entries().toList()
                val names = entries.map { it.name }
                if (names.size != names.toSet().size) reject("duplicate AAR members")
                val expected = receipt.stringPropertyNames().filter { it.startsWith("member.") }
                    .associate { it.removePrefix("member.") to receipt.getProperty(it) }
                if (names.toSet() != expected.keys) reject("incorrect AAR member inventory")
                entries.forEach { entry ->
                    if (hash(zipped.getInputStream(entry)) != expected[entry.name]) reject("member checksum mismatch")
                }
                val required = setOf(
                    "AndroidManifest.xml", "classes.jar", "proguard.txt",
                    "assets/mozilla-native/THIRD_PARTY.md", "assets/mozilla-native/licenses/mozilla-LICENSE",
                    "assets/mozilla-native/licenses/LGPL-2.1",
                ) + listOf("en", "es", "it", "zh", "de", "pl", "pt", "ru", "fr", "nl")
                    .map { "assets/mozilla/prefixes/$it.txt" }
                if (!names.containsAll(required)) reject("missing notices or source splitter data")
                val libraries = names.filter { it.startsWith("jni/") }.sorted()
                if (libraries != listOf("jni/arm64-v8a/libmozilla_jni.so", "jni/x86_64/libmozilla_jni.so")) {
                    reject("incorrect native library inventory")
                }
                libraries.forEach { name ->
                    val abi = name.split('/')[1]
                    val data = zipped.getInputStream(zipped.getEntry(name)).use { it.readBytes() }
                    if (data.size < 64 || !data.copyOfRange(0, 6).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1))) {
                        reject("expected little-endian ELF64")
                    }
                    val elf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    fun u16(at: Int) = elf.getShort(at).toInt() and 65535
                    fun range(offset: Long, size: Long): Int {
                        if (offset < 0 || size < 0 || offset > data.size || size > data.size - offset) reject("truncated ELF range")
                        return offset.toInt()
                    }
                    val machine = u16(18)
                    if (machine != if (abi == "arm64-v8a") 183 else 62) reject("incorrect ELF machine")
                    val offset = elf.getLong(32)
                    val width = u16(54)
                    val count = u16(56)
                    if (offset < 64 || width < 56 || count == 0) reject("invalid ELF segment table")
                    range(offset, width.toLong() * count)
                    val segments = (0 until count).map { index ->
                        val position = (offset + index.toLong() * width).toInt()
                        listOf(elf.getInt(position).toLong(), elf.getLong(position + 8), elf.getLong(position + 16),
                            elf.getLong(position + 32), elf.getLong(position + 48))
                    }
                    val loads = segments.filter { it[0] == 1L }
                    if (loads.isEmpty() || loads.any { it[4] < 16384L || (it[2] - it[1]) % 16384L != 0L }) {
                        reject("ELF LOAD lacks 16-KiB alignment")
                    }
                    val dynamic = segments.singleOrNull { it[0] == 2L } ?: reject("invalid ELF dynamic table")
                    if (dynamic[3] % 16L != 0L) reject("invalid ELF dynamic table size")
                    val start = range(dynamic[1], dynamic[3])
                    val tags = mutableListOf<Pair<Long, Long>>()
                    var position = start
                    val end = start + dynamic[3].toInt()
                    while (position < end) {
                        val tag = elf.getLong(position)
                        if (tag == 0L) break
                        tags += tag to elf.getLong(position + 8)
                        position += 16
                    }
                    val strings = tags.singleOrNull { it.first == 5L }?.second ?: reject("missing ELF dynamic strings")
                    val size = tags.singleOrNull { it.first == 10L }?.second ?: reject("missing ELF dynamic string size")
                    val mapped = loads.singleOrNull { strings >= it[2] && size >= 0 &&
                        strings - it[2] <= it[3] && size <= it[3] - (strings - it[2])
                    } ?: reject("unmapped ELF dynamic strings")
                    val stringStart = range(mapped[1] + strings - mapped[2], size)
                    val needed = tags.filter { it.first == 1L }.map { (_, value) ->
                        if (value < 0 || value >= size) reject("invalid ELF NEEDED offset")
                        val from = stringStart + value.toInt()
                        var to = from
                        while (to < stringStart + size.toInt() && data[to] != 0.toByte()) to++
                        if (to == stringStart + size.toInt()) reject("unterminated ELF NEEDED string")
                        String(data, from, to - from, Charsets.US_ASCII)
                    }.sorted()
                    if (!setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so").containsAll(needed)) {
                        reject("unapproved dynamic dependency")
                    }
                    if (receipt.getProperty("elf.$abi.machine") != machine.toString() ||
                        receipt.getProperty("elf.$abi.load_alignments") != loads.joinToString(",") { it[4].toString() } ||
                        receipt.getProperty("elf.$abi.needed") != needed.joinToString(",")
                    ) reject("ELF receipt mismatch")
                }
            }
        } catch (error: java.io.IOException) {
            reject("unreadable AAR: ${error.javaClass.simpleName}")
        }
        logger.lifecycle("Verified Mozilla CPU JNI AAR: ${archive.name}, SHA-256 ${receipt.getProperty("aar.sha256")}")
    }
}

android {
    namespace = "io.github.ngoroth.offlinetranslator.mozilla"
    testNamespace = "io.github.ngoroth.offlinetranslator.mozilla.test"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("io.github.ngoroth.offlinetranslator.native:mozilla-native:$nativeVersion") {
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

tasks.named("preBuild").configure { dependsOn(verifyMozillaNativeArchive) }
