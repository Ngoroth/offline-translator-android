import com.example.offlinetranslator.testkit.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

class EvaluationInputCacheTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun model(root: File,tar: Boolean=false): EvaluationModel {
        val source=File(root,"source");val contents="weights".toByteArray()
        if(tar) TarArchiveOutputStream(BZip2CompressorOutputStream(source.outputStream())).use {out ->
            val entry=TarArchiveEntry("pin/silero_vad.onnx");entry.size=contents.size.toLong();out.putArchiveEntry(entry);out.write(contents);out.closeArchiveEntry()
        } else source.writeBytes(contents)
        val plain=File(root,"plain").also {it.writeBytes(contents)}
        return EvaluationModel(JSONObject().put("id","fixture").put("component","VAD").put("language","both").put("adapter","silero")
            .put("localSource","source").put("format",if(tar) "tar.bz2" else "raw").put("rootPrefix",if(tar) "pin" else "")
            .put("maxEntries",16).put("maxUnpackedBytes",1024).put("archiveBytes",source.length()).put("archiveSha256",Integrity.sha256(source))
            .put("files",JSONArray().put(JSONObject().put("path","silero_vad.onnx").put("bytes",contents.size).put("sha256",Integrity.sha256(plain)))),root)
    }
    private fun stage(root: File,cache: EvaluationInputCache,model: EvaluationModel): JSONObject =
        ModelStager(temporary.newFolder(),cache).stage(model)
    @Test fun rawAndTarActionHitsRevalidateAndRebuildCorruptOwnedContent() {
        for(tar in listOf(false,true)) {
            val root=temporary.newFolder();val model=model(root,tar)
            RunWorkspace(root).use {owner ->
                val cache=EvaluationInputCache(owner,listOf(model))
                val first=stage(root,cache,model);val second=stage(root,cache,model)
                assertFalse(first.getBoolean("cacheHit"));assertTrue(second.getBoolean("cacheHit"))
                assertTrue(second.isNull("downloadMs"));assertTrue(second.isNull("extractionAndFileVerificationMs"))
                assertEquals(model.json.getLong("archiveBytes")+model.files.sumOf(ModelFile::bytes),second.getLong("verifiedBytes"))
                val bundle=File(cache.root,"bundles/${EvaluationInputCache.bundleKey(model)}/data/silero_vad.onnx")
                bundle.writeText("damaged")
                assertFalse(stage(root,cache,model).getBoolean("bundleCacheHit"))
                File(cache.root,"archives/${EvaluationInputCache.archiveKey(model)}/data/archive").writeText("bad")
                assertFalse(stage(root,cache,model).getBoolean("archiveCacheHit"))
                File(root,"source").writeText("bad")
                assertThrows(Exception::class.java) {stage(root,cache,model)}
            }
        }
    }
    @Test fun keysCoverFilesPrefixQuotasAndPolicyNotIdOrSourcePath() {
        val root=temporary.newFolder();val model=model(root)
        val original=EvaluationInputCache.bundleKey(model)
        for(field in listOf("rootPrefix","maxEntries","maxUnpackedBytes")) {
            val copy=JSONObject(model.json.toString());copy.put(field,if(field=="rootPrefix") "other" else 3)
            assertNotEquals(original,EvaluationInputCache.bundleKey(EvaluationModel(copy,root)))
        }
        assertNotEquals(original,EvaluationInputCache.bundleKey(model,"new-policy"))
        val changed=JSONObject(model.json.toString());changed.getJSONArray("files").getJSONObject(0).put("sha256","0".repeat(64))
        assertNotEquals(original,EvaluationInputCache.bundleKey(EvaluationModel(changed,root)))
        val renamed=JSONObject(model.json.toString()).put("id","other").put("localSource","different")
        assertEquals(original,EvaluationInputCache.bundleKey(EvaluationModel(renamed,root)))
    }
    @Test fun reportSetupIsEscapedSameJsonAndMissingTimesRemainUnmeasured() {
        val report=JSONObject().put("verdict","FAIL").put("inputCache",JSONObject().put("root","<unsafe>"))
            .put("preparedInputs",JSONObject().put("cacheHit",true).put("generationMs",JSONObject.NULL).put("totalMs",1.0))
            .put("candidates",JSONArray().put(JSONObject().put("id","<candidate>").put("staging",JSONObject().put("cacheHit",true).put("downloadMs",JSONObject.NULL))))
        val root=temporary.newFolder();ReportWriter.write(root,report)
        val html=File(root,"report.html").readText()
        assertFalse(html.contains("<unsafe>"));assertTrue(html.contains("generationMs=unmeasured"));assertTrue(html.contains("downloadMs=unmeasured"))
        val embedded=html.substringAfter("<pre id=\"report-json\">").substringBefore("</pre>").replace("&quot;","\"").replace("&#39;","'").replace("&gt;",">").replace("&lt;","<").replace("&amp;","&")
        assertEquals(JSONObject(File(root,"report.json").readText()).toString(),JSONObject(embedded).toString())
    }
    @Test fun quotaDeadlineAndInterruptedBuildCannotPublishPartials() {
        val root=temporary.newFolder();val model=model(root)
        RunWorkspace(root).use {owner ->
            val small=EvaluationInputCache(owner,listOf(model),modelBudget=6)
            assertThrows(Exception::class.java) {stage(root,small,model)}
            val cache=EvaluationInputCache(owner,listOf(model))
            assertThrows(Exception::class.java) {
                cache.obtain("bundles",EvaluationInputCache.bundleKey(model),model.files,7,Long.MAX_VALUE) {dir ->
                    File(dir,"incomplete").writeText("partial");error("interrupted")
                }
            }
            assertTrue(File(cache.root,"bundles").list()!!.isEmpty())
            assertThrows(Exception::class.java) {ModelStager(temporary.newFolder(),cache).stage(model,0)}
            assertTrue(File(cache.root,"archives").list()!!.isEmpty())
            assertThrows(Exception::class.java) {SpaceRequirements.host(SpaceRequirements.HOST_BYTES,SpaceRequirements.HOST_BYTES+1)}
        }
    }
    @Test fun foreignRootMarkerAndEntriesAreNotDeleted() {
        val root=temporary.newFolder();val model=model(root)
        RunWorkspace(root).use {owner ->
            val cache=EvaluationInputCache(owner,listOf(model));stage(root,cache,model)
            val entry=File(cache.root,"bundles/${EvaluationInputCache.bundleKey(model)}")
            val marker=File(entry,"owner.json");marker.writeText("{}")
            assertThrows(Exception::class.java) {stage(root,cache,model)}
            assertTrue(marker.exists());assertEquals("{}",marker.readText())
        }
        val foreign=temporary.newFolder();File(foreign,".cache/translator-evaluation").mkdirs()
        val keep=File(foreign,".cache/translator-evaluation/keep").also {it.writeText("foreign")}
        RunWorkspace(foreign).use {owner -> assertThrows(Exception::class.java) {EvaluationInputCache(owner,emptyList())} }
        assertEquals("foreign",keep.readText())
    }
    @Test fun cleanupKeepsCacheAndRejectsThousandCompetingOwners() {
        val root=temporary.newFolder();val model=model(root)
        RunWorkspace(root).use {owner ->
            val cache=EvaluationInputCache(owner,listOf(model));stage(root,cache,model)
            repeat(1000) {assertThrows(Exception::class.java) {RunWorkspace(root)}}
            owner.record("emulator-1234");owner.cleanupHost();assertFalse(owner.directory.exists())
            assertTrue(cache.receipt().getLong("retainedBytes")>0)
            owner.journal.delete();RootBuildCleanup.clear(owner)
            assertTrue(File(cache.root,"owner.txt").exists())
        }
    }
    @Test fun ownedCrashPartialAndInactiveKeysArePrunedButAliasesFailClosed() {
        val root=temporary.newFolder();val model=model(root)
        RunWorkspace(root).use {owner ->
            val cache=EvaluationInputCache(owner,listOf(model));stage(root,cache,model)
            val archive=File(cache.root,"archives/${EvaluationInputCache.archiveKey(model)}")
            val partial=File(cache.root,"archives/partial-${owner.id}")
            archive.copyRecursively(partial)
            EvaluationInputCache(owner,listOf(model));assertFalse(partial.exists())
            EvaluationInputCache(owner,emptyList());assertTrue(File(cache.root,"archives").list()!!.isEmpty())
            val linked=File(cache.root,"archives/${EvaluationInputCache.archiveKey(model)}")
            val outside=temporary.newFolder();File(outside,"keep").writeText("outside")
            // Windows symlinks require developer mode; a junction exercises the same canonical guard.
            val result=if(System.getProperty("os.name").startsWith("Windows"))
                BoundedProcess.run(listOf("cmd","/c","mklink","/J",linked.absolutePath,outside.absolutePath),10,4096)
            else {Files.createSymbolicLink(linked.toPath(),outside.toPath());CommandResult(0,byteArrayOf())}
            assertEquals(0,result.exitCode)
            try {assertThrows(Exception::class.java) {EvaluationInputCache(owner,listOf(model))};assertEquals("outside",File(outside,"keep").readText())}
            finally {Files.delete(linked.toPath())}
            stage(root,cache,model)
            val cached=File(linked,"data/archive")
            val hardlink=File(outside,"hardlink")
            Files.createLink(hardlink.toPath(),cached.toPath())
            try {assertThrows(Exception::class.java) {stage(root,cache,model)};assertEquals("weights",hardlink.readText())}
            finally {Files.delete(hardlink.toPath())}
            assertTrue(stage(root,cache,model).getBoolean("cacheHit"))
        }
    }
}
