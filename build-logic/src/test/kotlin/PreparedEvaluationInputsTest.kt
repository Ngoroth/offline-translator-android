import com.example.offlinetranslator.testkit.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreparedEvaluationInputsTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun allPinnedPreparedBytesMatchOriginalAlgorithmsAndWarmHitDoesNotGenerate() {
        val corpus=File(System.getProperty("evaluation.repo"),"test-data/corpus/v1");val cases=CorpusManifest.read(corpus)
        val root=temporary.newFolder()
        RunWorkspace(root).use {owner ->
            val key=PreparedEvaluationInputs.key(corpus,cases);val cache=EvaluationInputCache(owner,emptyList(),key)
            val cold=temporary.newFolder();val warm=temporary.newFolder()
            assertFalse(PreparedEvaluationInputs.stage(cache,corpus,cases,cold,Long.MAX_VALUE).getBoolean("cacheHit"))
            val second=PreparedEvaluationInputs.stage(cache,corpus,cases,warm,Long.MAX_VALUE)
            assertTrue(second.getBoolean("cacheHit"));assertTrue(second.isNull("generationMs"))
            val old=File(temporary.newFolder(),"old-stress.wav")
            val boundary=StressTimeline.write(corpus,old)
            assertArrayEquals(old.readBytes(),File(warm,"prepared-inputs/stress.wav").readBytes())
            assertEquals(boundary,StressInputs.verify(corpus,File(warm,"prepared-inputs"),cases))
            for(case in cases.filter {it.cohort=="quality"}) {
                val raw=WaveReader.read(File(corpus,case.path),16000).samples
                val original=ShortArray(raw.size+32000);raw.copyInto(original,8000)
                assertArrayEquals(original,StressInputs.padded(File(warm,"prepared-inputs"),case))
            }
            val corrupted=File(cache.root,"derived/$key/data/padded/${cases.first {it.cohort=="quality"}.id}.wav")
            val bytes=corrupted.readBytes();bytes[20000]=(bytes[20000].toInt() xor 1).toByte();corrupted.writeBytes(bytes)
            // Even a forged updated content receipt cannot legitimize altered PCM.
            val receipt=File(cache.root,"derived/$key/receipt.json");val json=JSONObject(receipt.readText());val rows=json.getJSONArray("files")
            (0 until rows.length()).map(rows::getJSONObject).single {it.getString("path")==corrupted.relativeTo(File(cache.root,"derived/$key/data")).invariantSeparatorsPath}
                .put("sha256",Integrity.sha256(corrupted));receipt.writeText(json.toString())
            assertFalse(PreparedEvaluationInputs.stage(cache,corpus,cases,temporary.newFolder(),Long.MAX_VALUE).getBoolean("cacheHit"))
            File(warm,"prepared-inputs/stress.wav").delete()
            assertThrows(Exception::class.java) {StressInputs.verify(corpus,File(warm,"prepared-inputs"),cases)}
        }
    }
    @Test fun corpusLocksHashesAndProcessingVersionDefineDerivedIdentity() {
        val corpus=File(System.getProperty("evaluation.repo"),"test-data/corpus/v1");val cases=CorpusManifest.read(corpus)
        val copy=temporary.newFolder();corpus.copyRecursively(copy,overwrite=true)
        val key=PreparedEvaluationInputs.key(copy,cases)
        File(copy,"manifest.json").appendText(" ")
        assertNotEquals(key,PreparedEvaluationInputs.key(copy,cases))
        assertNotEquals(key,EvaluationInputCache.key(StressInputs.VERSION+"new processing"))
    }
}
