import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EvaluationSpeechCatalogTest {
    @Test fun schemaTwoProjectsOnlyThreeOriginalPackagesAndTypedModels() {
        val baseline = baselineManifest()
        val projection = fixedDirectionSpeechCatalog(baseline)
        assertEquals(2, projection.getInt("schemaVersion"))
        assertEquals(baseline.getString("runtime"), projection.getString("runtime"))
        val packages = projection.getJSONArray("packages")
        val definitions = projection.getJSONArray("models")
        assertEquals(3, packages.length()); assertEquals(3, definitions.length())
        val historical = baseline.getJSONArray("candidates")
        for (index in 0 until 3) {
            val original = historical.getJSONObject(index)
            val pkg = packages.getJSONObject(index)
            assertEquals(original.getString("id"), pkg.getString("id"))
            for (field in listOf("archiveBytes", "archiveSha256", "maxUnpackedBytes", "maxEntries", "files",
                "url", "allowedRedirectHosts", "format")) assertEquals(original.get(field).toString(), pkg.get(field).toString())
            assertFalse(pkg.has("component")); assertFalse(pkg.has("sampleRateHz")); assertFalse(pkg.has("parameters"))
            val definition = definitions.getJSONObject(index)
            assertEquals(pkg.getString("id"), definition.getString("id"))
            assertEquals(original.getInt("sampleRateHz"), definition.getInt("sampleRateHz"))
            val adapter = definition.getJSONObject("adapter")
            assertEquals(original.getJSONObject("parameters").getInt("numThreads"), adapter.getInt("numThreads"))
            for (key in adapter.keys().asSequence().filter { adapter.get(it) is JSONObject }) {
                val ref = adapter.getJSONObject(key)
                assertEquals(pkg.getString("id"), ref.getString("packageId"))
                val files = pkg.getJSONArray("files")
                assertTrue((0 until files.length()).any { files.getJSONObject(it).getString("path") == ref.getString("path") })
            }
        }
        assertEquals("VAD", definitions.getJSONObject(0).getString("role"))
        assertEquals(0, definitions.getJSONObject(0).getJSONArray("languages").length())
        assertEquals("toneCtcOnline", definitions.getJSONObject(1).getJSONObject("adapter").getString("family"))
        assertEquals("zipformer2Online", definitions.getJSONObject(2).getJSONObject("adapter").getString("family"))
        assertEquals("ru", definitions.getJSONObject(1).getJSONArray("languages").getString(0))
        assertEquals("en", definitions.getJSONObject(2).getJSONArray("languages").getString(0))
    }

    @Test fun unsafeReferencesAndUnsupportedNativeParametersRejectBeforeProjection() {
        val unsafe = baselineManifest()
        unsafe.getJSONArray("candidates").getJSONObject(1).getJSONArray("files").getJSONObject(0).put("path", "../model.onnx")
        assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(unsafe) }
        val missingRef = baselineManifest()
        missingRef.getJSONArray("candidates").getJSONObject(2).getJSONArray("files").remove(0)
        assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(missingRef) }
        val unsupported = baselineManifest()
        unsupported.getJSONArray("candidates").getJSONObject(1).getJSONObject("parameters").put("provider", "cuda")
        assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(unsupported) }
        val mismatchedRole = baselineManifest()
        mismatchedRole.getJSONArray("candidates").getJSONObject(1).put("language", "en")
        assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(mismatchedRole) }
        val badHash = baselineManifest()
        badHash.getJSONArray("candidates").getJSONObject(0).getJSONArray("files").getJSONObject(0).put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(badHash) }
        val unsafeSource = baselineManifest()
        unsafeSource.getJSONArray("candidates").getJSONObject(1).put("url", "http://github.com/model")
        assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(unsafeSource) }
    }

    @Test fun eachMissingOriginalComponentIsRejected() {
        repeat(3) { index ->
            val baseline = baselineManifest()
            baseline.getJSONArray("candidates").remove(index)
            assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(baseline) }
        }
    }

    @Test fun eachDuplicateOriginalComponentIsRejected() {
        repeat(3) { index ->
            val baseline = baselineManifest()
            val candidates = baseline.getJSONArray("candidates")
            candidates.put(candidates.getJSONObject(index))
            assertThrows(IllegalArgumentException::class.java) { fixedDirectionSpeechCatalog(baseline) }
        }
    }

    @Test fun malformedCandidateMembershipIsRejected() {
        val missingComponent = baselineManifest()
        missingComponent.getJSONArray("candidates").getJSONObject(3).remove("component")
        assertThrows(JSONException::class.java) { fixedDirectionSpeechCatalog(missingComponent) }
        val malformedCandidate = baselineManifest()
        malformedCandidate.getJSONArray("candidates").put(0, "not a descriptor")
        assertThrows(JSONException::class.java) { fixedDirectionSpeechCatalog(malformedCandidate) }
    }

    @Test fun missingNativeDescriptorFieldIsRejected() {
        val baseline = baselineManifest()
        baseline.getJSONArray("candidates").getJSONObject(1).remove("archiveSha256")
        assertThrows(JSONException::class.java) { fixedDirectionSpeechCatalog(baseline) }
    }

    @Test fun packagesCannotReplaceBaselineCandidates() {
        val baseline = baselineManifest()
        baseline.put("packages", baseline.remove("candidates"))
        assertThrows(JSONException::class.java) { fixedDirectionSpeechCatalog(baseline) }
    }

    private fun baselineManifest(): JSONObject = JSONObject(
        File(File(System.getProperty("evaluation.repo")), "test-data/model-sets/baseline.json").readText()
    )
}
