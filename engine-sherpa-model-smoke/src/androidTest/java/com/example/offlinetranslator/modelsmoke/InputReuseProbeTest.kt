package com.example.offlinetranslator.modelsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.*
import com.example.offlinetranslator.testkit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Setup/import probe, deliberately not a model-quality or performance verdict. */
@RunWith(AndroidJUnit4::class)
class InputReuseProbeTest {
    @Test fun verifiesPreparedInputsRepeatedImportAndFreshNativeLoadRelease() = runBlocking(Dispatchers.IO) {
        InputVerifier.verify()
        val first=EvaluationSession.store(false)
        val copied=first.evaluationImportCopiedBytes
        require(first.catalog.packages.size == 3 && first.catalog.models.size == 3)
        require(first.catalog.models.count { it.role == SpeechModelRole.VAD } == 1)
        require(first.catalog.models.count { it.role == SpeechModelRole.ASR } == 2)
        require(first.catalog.models.single { it.role == SpeechModelRole.VAD }.adapter is SherpaSpeechModelAdapter.Silero)
        require(first.catalog.models.single { LanguageId("ru") in it.languages }.adapter is SherpaSpeechModelAdapter.ToneCtcOnline)
        require(first.catalog.models.single { LanguageId("en") in it.languages }.adapter is SherpaSpeechModelAdapter.Zipformer2Online)
        require(first.catalog.requiredPackages(first.catalog.models.mapTo(linkedSetOf()) { it.id }) ==
            first.catalog.packages.mapTo(linkedSetOf()) { it.id })
        require(copied>0);first.close()
        val second=EvaluationSession.store(false)
        try {
            require(second.evaluationImportCopiedBytes==0L && second.evaluationImportReusedPackages==3)
            val fixture=EvaluationSession.cases.first {it.id=="en-3" && it.cohort=="smoke"}
            val pcm=WaveReader.read(File(EvaluationSession.corpus,fixture.path),16000).samples
            val recognizer=EvaluationSession.recognizer(second)
            val text=try {recognizer.recognize(EvaluationSession.pair(fixture),SpeechSegment.fromPcm(pcm))} finally {recognizer.closeAndJoin()}
            EvaluationSession.segmenter(second).close()
            EvaluationSession.writeInputReuseProof(JSONObject().put("scope","input preparation/import only")
                .put("firstImportCopiedBytes",copied).put("secondImportCopiedBytes",second.evaluationImportCopiedBytes)
                .put("secondImportReusedPackages",second.evaluationImportReusedPackages).put("stressBoundary",EvaluationSession.stressBoundary)
                .put("freshNativeLoadRelease",true).put("fixture",fixture.id).put("recognition",text)
                .put("quality","unmeasured").put("MT","unmeasured").put("TTS","unmeasured").put("stress","unmeasured"))
        } finally {second.close()}
    }
}
