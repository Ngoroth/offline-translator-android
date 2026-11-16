package io.github.ngoroth.offlinetranslator.mozilla

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.core.LanguageId
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in download/preparation phase, invoked and receipted separately from runtime checks. */
@RunWith(AndroidJUnit4::class)
class MozillaFixturePreparationTest {
    @Test fun prepareQualificationFixtures() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit allowModelDownload=true is required for fixture preparation",
            arguments.getString("allowModelDownload") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "mozilla-native-fixtures")
        assertTrue(root.mkdirs() || root.isDirectory)
        val routes = setOf("en-ru", "ru-en", "en-zh", "zh-en", "hi-en", "en-hi", "en-id", "id-en")
        val pairs = routes.map { route ->
            val (source, target) = route.split('-')
            LanguagePair(LanguageId(source), LanguageId(target))
        }.toSet()
        val configurations = MozillaTranslationPreparation(context).prepareConfigurations(pairs)
        assertEquals(routes, configurations.keys)
        for ((route, path) in configurations) {
            val source = File(path)
            assertTrue(source.isAbsolute && source.isFile)
            val staged = File.createTempFile("$route-", ".staging", root)
            try {
                source.inputStream().use { input ->
                    FileOutputStream(staged).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                Files.move(staged.toPath(), File(root, "$route.yml").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { staged.delete() }
        }
    }
}
