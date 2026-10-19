import com.example.offlinetranslator.testkit.*
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

abstract class VerifyTranslatorTask : DefaultTask() {
    @get:Internal abstract val repo: DirectoryProperty
    @get:Internal abstract val targetApk: DirectoryProperty
    @get:Internal abstract val testApk: DirectoryProperty
    @get:Internal abstract val modelSet: Property<String>
    @get:Internal abstract val fault: Property<String>
    @get:Internal abstract val gradleVersion: Property<String>
    @get:Internal var completion: Provider<VerificationCompletion>? = null
    @get:Internal internal var adbFactory: (File) -> AdbClient = AndroidSdk::adb

    @TaskAction fun verify() {
        val root=repo.get().asFile.canonicalFile
        val reportRoot=File(root,"build/reports/model-evaluation")
        val report=JSONObject().put("schemaVersion",1).put("startedAt",Instant.now().toString())
            .put("verdict","FAIL").put("reasons",JSONArray()).put("stages",JSONArray()).put("candidates",JSONArray())
        val failures=report.getJSONArray("reasons")
        val workspace: RunWorkspace = try { completion?.get()?.workspace ?: RunWorkspace(root) } catch (failure: Exception) {
            completion?.get()?.completed = true
            throw GradleException("Verification workspace already owned or unavailable", failure)
        }
        var adb: AdbClient?=null; var mutated=false
        // Until recovery completes, this action has no authority to finalize the prior run.
        var recoveryPending=java.nio.file.Files.exists(workspace.journal.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
        var previousWifi: String?=null; var previousAirplane: String?=null
        val totalDeadline=System.nanoTime()+7_200_000_000_000L
        fun remainingTimeout(maximum: Long): Long {
            val seconds=(totalDeadline-System.nanoTime())/1_000_000_000L
            require(seconds>0) { "Whole-run deadline exceeded" }
            return minOf(maximum,seconds)
        }
        try {
            RunWorkspace.safeDelete(reportRoot,File(root,"build")); reportRoot.mkdirs()
            report.put("buildTests",BuildEvidence.collect(root))
            val cases=CorpusManifest.read(File(root,"test-data/corpus/v1"))
            report.put("corpus",JSONObject().put("total",58).put("smoke",10).put("quality",48).put("ru",24).put("en",24)
                .put("manifestSha256",Integrity.sha256(File(root,"test-data/corpus/v1/manifest.json"))).put("cases",JSONArray(cases.map { it.json })))
            val baseline=ModelManifest.read(File(root,"test-data/model-sets/baseline.json"))
            val customFile=File(modelSet.get()).let { if(it.isAbsolute) it else File(root,modelSet.get()) }
            val models=ModelManifest.merge(baseline,ModelManifest.read(customFile))
            val rows=report.getJSONArray("candidates")
            models.forEach { rows.put(JSONObject().put("id",it.id).put("slot",it.slot).put("identity",it.json).put("verdict","FAIL").put("reasons",JSONArray().put("notStarted")).put("metrics",JSONObject.NULL)) }
            report.put("candidateCount",models.size).put("baselineManifestSha256",Integrity.sha256(File(root,"test-data/model-sets/baseline.json")))
                .put("customManifestSha256",Integrity.sha256(customFile)).put("runtime",ModelManifest.RUNTIME)
                .put("aarSha256",Integrity.sha256(File(root,"engine-sherpa/libs/sherpa-onnx-1.13.8.aar")))
                .put("host",JSONObject().put("java",System.getProperty("java.version")).put("os",System.getProperty("os.name")).put("gradle",gradleVersion.get()))
            val git=BoundedProcess.run(listOf("git","rev-parse","HEAD"),10,1024,root)
            require(git.exitCode==0);report.put("commit",git.text.trim())
            val dirty=BoundedProcess.run(listOf("git","status","--porcelain"),10,4*1024*1024,root)
            require(dirty.exitCode==0);report.put("dirty",dirty.text.isNotBlank())
            adb=adbFactory(root)
            val serial=adb.discover()
            report.put("emulator",JSONObject().put("serial",serial).put("avd",adb.checked("emu","avd","name"))
                .put("fingerprint",adb.shell("getprop","ro.build.fingerprint")).put("api",adb.shell("getprop","ro.build.version.sdk"))
                .put("abi",adb.shell("getprop","ro.product.cpu.abi")).put("cpu",adb.shell("cat","/proc/cpuinfo").take(8192))
                .put("memory",adb.shell("cat","/proc/meminfo").take(8192)))
            // An existing lease is recoverable only with our durable journal for this serial.
            val prior=if(workspace.journal.isFile) JSONObject(workspace.journal.readText()) else null
            if(prior!=null) {
                require(prior.getString("serial")==serial && prior.getString("target")==AdbClient.TARGET && prior.getString("test")==AdbClient.TEST)
                report.put("orphanRecovery",OwnedRunRecovery.recover(workspace,adb))
                recoveryPending=false
                report.put("recoveredOrphan",prior.getString("runId"))
            } else {
                val path=AdbClient.quote(AdbClient.DEVICE_ROOT)
                val probe=adb.command("shell","test -e $path || test -L $path")
                require(probe.exitCode==1) { "Foreign lease exists or emulator state cannot be verified" }
            }
            workspace.cleanupHost()
            workspace.record(serial)
            report.put("runId",workspace.id)
            mutated=true
            adb.shell("mkdir",AdbClient.DEVICE_ROOT)
            SpaceRequirements.host(root.usableSpace)
            val disk=adb.shell("df","-k","/data").lineSequence().last { it.isNotBlank() }.trim().split(Regex("\\s+"))
            SpaceRequirements.device(disk[3].toLong())
            val input=File(workspace.directory,"input"); input.mkdirs()
            File(root,"test-data/corpus/v1").copyRecursively(File(input,"corpus"))
            val baselineManifest = JSONObject(File(root,"test-data/model-sets/baseline.json").readText())
            File(input,"speech-models.json").writeText(fixedDirectionSpeechCatalog(baselineManifest).toString())
            val staged=JSONArray()
            val cache=EvaluationInputCache(workspace,models,PreparedEvaluationInputs.key(File(input,"corpus"),cases))
            report.put("preparedInputs",PreparedEvaluationInputs.stage(cache,File(input,"corpus"),cases,input,totalDeadline))
            for((index,model) in models.withIndex()) {
                val row=rows.getJSONObject(index)
                try {
                    remainingTimeout(1200)
                    row.put("staging",ModelStager(input,cache).stage(model,totalDeadline))
                    staged.put(model.json)
                    row.put("reasons",JSONArray().put("awaitingMeasurement"))
                } catch(failure: Exception) { row.put("reasons",JSONArray().put("inputFailure: ${failure.message}")) }
                ReportWriter.write(reportRoot,report)
            }
            File(input,"models.json").writeText(JSONObject().put("schemaVersion",1).put("runtime",ModelManifest.RUNTIME).put("candidates",staged).toString())
            report.put("inputCache",cache.receipt())
            val target=apkFile(targetApk.get().asFile,AdbClient.TARGET)
            val test=apkFile(testApk.get().asFile,AdbClient.TEST)
            report.put("apks",JSONObject().put("targetSha256",Integrity.sha256(target)).put("testSha256",Integrity.sha256(test)))
            adb.checked("install","-r",target.absolutePath,timeoutSeconds=remainingTimeout(60))
            adb.checked("install","-r",test.absolutePath,timeoutSeconds=remainingTimeout(60))
            adb.checked("push",input.absolutePath,AdbClient.DEVICE_ROOT,timeoutSeconds=remainingTimeout(60))
            adb.shell("chmod","-R","a+rX",AdbClient.DEVICE_ROOT)
            adb.shell("run-as",AdbClient.TARGET,"mkdir","-p","files/evaluation/${workspace.id}")
            adb.shell("run-as",AdbClient.TARGET,"cp","-R","${AdbClient.DEVICE_ROOT}/input/.","files/evaluation/${workspace.id}/")
            fun attempt(phase: String,classes: String,timeout: Long = 1200) {
                try { runStage(adb,workspace.id,phase,classes,report,timeout=remainingTimeout(timeout)) }
                catch(failure: Exception) { failures.put("$phase: ${failure.message}");adb.shell("am","force-stop",AdbClient.TARGET) }
            }
            attempt("regression","com.example.offlinetranslator.translation.TranslationScreenTest,com.example.offlinetranslator.conversation.ConversationScreenTest")
            previousWifi=adb.shell("settings","get","global","wifi_on")
            previousAirplane=adb.shell("settings","get","global","airplane_mode_on")
            workspace.saveNetwork(previousWifi,previousAirplane)
            adb.shell("cmd","connectivity","airplane-mode","disable"); adb.shell("svc","wifi","enable")
            attempt("preparation","com.example.offlinetranslator.modelsmoke.PreparationTest",660)
            adb.shell("svc","wifi","disable"); adb.shell("cmd","connectivity","airplane-mode","enable")
            adb.shell("am","force-stop",AdbClient.TARGET)
            attempt("feature","com.example.offlinetranslator.modelsmoke.FeatureIntegrationTest")
            attempt("smoke","com.example.offlinetranslator.modelsmoke.SpeechModelSmokeTest,com.example.offlinetranslator.modelsmoke.ControlledSpeechCorpusTest")
            for ((index,model) in models.withIndex()) {
                val row=rows.getJSONObject(index)
                if(row.getJSONArray("reasons").toString().contains("inputFailure")) continue
                try {
                    require(System.nanoTime()<totalDeadline) { "Whole-run deadline exceeded" }
                    adb.shell("am","force-stop",AdbClient.TARGET)
                    val telemetry=runStage(adb,workspace.id,"candidate","com.example.offlinetranslator.modelsmoke.ModelEvaluationTest",report,model.id,remainingTimeout(1200),fault.get())
                    val device=adb.command("exec-out","run-as",AdbClient.TARGET,"cat","files/evaluation/${workspace.id}/result.json",maxBytes=16*1024*1024)
                    require(device.exitCode==0)
                    val result=JSONObject(device.text)
                    require(result.getString("candidate")==model.id) {"Candidate result identity mismatch"}
                    row.put("metrics",result).put("reasons",result.getJSONArray("reasons")).put("verdict",result.getString("verdict")).put("memorySamples",telemetry)
                    val peaks=memoryPeaks(telemetry,result)
                    row.put("memory",peaks)
                    if(peaks.getLong("peakPssKiB")>EvaluationGates.MAX_PSS_KIB || peaks.getLong("peakNativeKiB")>EvaluationGates.MAX_NATIVE_KIB ||
                        peaks.getLong("releasePssKiB")>EvaluationGates.RELEASE_PSS_KIB || peaks.getLong("releaseNativeKiB")>EvaluationGates.RELEASE_NATIVE_KIB) {
                        row.put("verdict","FAIL"); row.getJSONArray("reasons").put("memoryGate")
                    }
                } catch(failure: Exception) {
                    adb.shell("am","force-stop",AdbClient.TARGET)
                    row.put("verdict","FAIL").put("reasons",JSONArray().put("runnerFailure: ${failure.message}"))
                    val partial=adb.command("exec-out","run-as",AdbClient.TARGET,"cat","files/evaluation/${workspace.id}/result.json",maxBytes=16*1024*1024)
                    if(partial.exitCode==0) runCatching {
                        val result=JSONObject(partial.text)
                        require(result.getString("candidate")==model.id)
                        row.put("metrics",result)
                    }
                }
                row.optJSONObject("metrics")?.let {MeasurementCompleteness.annotate(it,model,cases)}
                try { harvestSamples(adb,workspace.id,reportRoot,model.id) }
                catch(failure: Exception) { failures.put("sampleHarvestFailure: ${failure.message}") }
                ReportWriter.write(reportRoot,report)
            }
            CandidateVerdicts.applyDependencies(rows,baseline.map { it.id }.toSet())
            CandidateVerdicts.deltas(rows,baseline.map { it.id }.toSet())
            CandidateVerdicts.missingWinners(rows).forEach { failures.put("No passing candidate for $it") }
            attempt("cleanup","com.example.offlinetranslator.modelsmoke.CleanupProbeTest")
        } catch(failure: Exception) { failures.put("infrastructure: ${failure.javaClass.simpleName}: ${failure.message}") }
        finally {
            val cleanup=JSONObject()
            try {
                check(!recoveryPending) { "Prior run cleanup unverified; ownership journal retained" }
                if(mutated && adb!=null) {
                    cleanupDevice(adb)
                    if(previousAirplane!=null) adb.shell("cmd","connectivity","airplane-mode",if(previousAirplane=="1") "enable" else "disable")
                    if(previousWifi!=null) adb.shell("svc","wifi",if(previousWifi=="1") "enable" else "disable")
                    cleanup.put("restoredWifi",adb.shell("settings","get","global","wifi_on"))
                        .put("restoredAirplane",adb.shell("settings","get","global","airplane_mode_on"))
                    require(previousWifi==null || cleanup.getString("restoredWifi")==previousWifi)
                    require(previousAirplane==null || cleanup.getString("restoredAirplane")==previousAirplane)
                    val receipt=OwnedDeviceCleanup.receipt(adb)
                    receipt.keys().forEach {key -> cleanup.put(key,receipt.get(key))}
                } else cleanup.put("status","notMutated").put("packageCount",0).put("deviceInputBytes",0)
                workspace?.cleanupHost()
                if(workspace.journal.exists()) check(workspace.journal.delete()) { "Ownership journal could not be cleared" }
                cleanup.put("hostTemporaryBytes",0).put("orphanCount",0).put("orphanBytes",0)
            } catch(failure: Exception) { cleanup.put("status","unverified").put("reason",failure.message); failures.put("cleanupFailure: ${failure.message}") }
            finally { workspace.close();completion?.get()?.workspace=null }
            report.put("cleanup",cleanup).put("finishedAt",Instant.now().toString()).put("verdict",if(failures.length()==0) "PASS" else "FAIL")
            report.getJSONArray("candidates").let { rows -> (0 until rows.length()).forEach { index ->
                val row=rows.getJSONObject(index)
                if(row.isNull("metrics")) row.put("notMeasured",row.getJSONArray("reasons").toString())
            } }
            ReportWriter.write(reportRoot,report)
            completion?.get()?.completed=true
        }
        report.getJSONArray("candidates").let {rows -> (0 until rows.length()).forEach {index ->
            val row=rows.getJSONObject(index);val metrics=row.optJSONObject("metrics")
            val quality=listOf("asrScore-ru","asrScore-en","ttsScore").mapNotNull {key ->
                metrics?.optJSONObject(key)?.let {"$key WER=${it.opt("wer")} CER=${it.opt("cer")}"}
            }.joinToString("; ").ifEmpty {"quality unmeasured"}
            logger.lifecycle("${row.getString("verdict")} ${row.getString("id")}: $quality; memory=${row.optJSONObject("memory") ?: "unmeasured"}; reasons=${row.getJSONArray("reasons").length()} (see report)")
        } }
        logger.lifecycle("Report: ${File(reportRoot,"report.json")} and ${File(reportRoot,"report.html")}")
        if(failures.length()!=0) throw GradleException("Translator verification FAIL: $failures")
    }

    private fun runStage(adb: AdbClient, id: String, phase: String, classes: String, report: JSONObject, candidate: String="", timeout: Long=1200, fault: String=""): JSONArray {
        val samples=java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
        val errors=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val running=AtomicBoolean(true)
        val monitor=thread(isDaemon=true) {
            var tick=System.nanoTime()
            while(running.get()) {
                try {
                    val text=adb.checked("shell","dumpsys meminfo ${AdbClient.quote(AdbClient.TARGET)}",timeoutSeconds=5)
                    if(!text.contains("No process found")) {
                        samples.add(MemoryReadings.parse(text).put("atMs",System.currentTimeMillis()))
                    }
                } catch(failure: Exception) { errors.add(failure.message ?: "memoryFailure") }
                tick+=1_000_000_000L
                val pause=tick-System.nanoTime()
                if(pause>0) Thread.sleep(pause/1_000_000L,(pause%1_000_000L).toInt())
            }
        }
        val stage=JSONObject().put("phase",phase).put("candidate",candidate)
        report.getJSONArray("stages").put(stage)
        try {
            val args=mutableListOf("shell","am instrument -w -r -e class ${AdbClient.quote(classes)} -e runId ${AdbClient.quote(id)} -e phase ${AdbClient.quote(phase)} -e candidate ${AdbClient.quote(candidate)} -e controlCorpus true -e evaluationFault ${AdbClient.quote(fault)} ${AdbClient.quote(AdbClient.TEST+"/com.example.offlinetranslator.modelsmoke.EvaluationInstrumentation")}")
            val result=adb.command(*args.toTypedArray(),timeoutSeconds=timeout)
            val stages=report.getJSONArray("stages")
            val used=(0 until stages.length()).sumOf {stages.getJSONObject(it).optString("output").toByteArray(Charsets.UTF_8).size}
            val remaining=(4*1024*1024-used).coerceAtLeast(0)
            val bytes=result.text.toByteArray(Charsets.UTF_8)
            stage.put("output",bytes.copyOf(minOf(bytes.size,remaining)).toString(Charsets.UTF_8)).put("exitCode",result.exitCode)
            if(bytes.size>remaining) stage.put("logTruncated",true)
            require(result.exitCode==0 && result.text.contains("OK (") && !result.text.contains("FAILURES!!!") && !result.text.contains("INSTRUMENTATION_FAILED")) { "Instrumentation $phase failed (exit=${result.exitCode}); see stages output" }
            val counts=Regex("HARNESS_COUNTS run=(\\d+) failed=(\\d+) skipped=(\\d+)").find(result.text)
            val expected=if(phase=="regression") 11 else if(phase in setOf("smoke","feature")) 2 else 1
            require(counts!=null && counts.groupValues[1].toInt()==expected && counts.groupValues[2]=="0" && counts.groupValues[3]=="0") { "Unexpected/missing test counts for $phase" }
            stage.put("testCount",expected).put("skipped",0)
            require(errors.isEmpty()) { "Memory measurement failed: ${errors.peek()}" }
            stage.put("verdict","PASS")
        } catch(failure: Exception) { stage.put("verdict","FAIL").put("reason",failure.message); throw failure }
        finally {
            running.set(false); monitor.join(6000);require(!monitor.isAlive) { "Memory sampler did not stop" }
            stage.put("memorySamples",JSONArray(samples.toList()))
            runCatching {
                val device=adb.command("exec-out","run-as",AdbClient.TARGET,"cat","files/evaluation/$id/stage.json",maxBytes=65536)
                if(device.exitCode==0) stage.put("junit",JSONObject(device.text))
                if(phase in setOf("preparation","feature","smoke")) {
                    val evidence=adb.command("exec-out","run-as",AdbClient.TARGET,"cat","files/evaluation/$id/evidence-$phase.json",maxBytes=262144)
                    if(evidence.exitCode==0) stage.put("evidence",JSONObject(evidence.text))
                }
            }
            ReportWriter.write(File(repo.get().asFile,"build/reports/model-evaluation"),report)
        }
        return JSONArray(samples.toList())
    }
    private fun memoryPeaks(samples: JSONArray,result: JSONObject): JSONObject {
        require(samples.length()>10) { "Missing load/release memory measurements" }
        val rows=(0 until samples.length()).map(samples::getJSONObject)
        return JSONObject().put("peakPssKiB",rows.maxOf { it.getLong("pssKiB") }).put("peakNativeKiB",rows.maxOf { it.getLong("nativeKiB") })
            .put("releasePssKiB",result.getJSONObject("releaseMemory").getLong("pssKiB"))
            .put("releaseNativeKiB",result.getJSONObject("releaseMemory").getLong("nativeKiB"))
    }
    private fun harvestSamples(adb: AdbClient,id: String,root: File,candidate: String) {
        val listing=adb.command("shell","run-as ${AdbClient.quote(AdbClient.TARGET)} ls ${AdbClient.quote("files/evaluation/$id/samples")}")
        if(listing.exitCode!=0) return
        val samples=File(root,"samples/$candidate");samples.mkdirs()
        listing.text.lineSequence().filter(String::isNotBlank).forEach { name ->
            require(Integrity.safePath(name) && '/' !in name && name.endsWith(".wav"))
            val bytes=adb.command("exec-out","run-as",AdbClient.TARGET,"cat","files/evaluation/$id/samples/$name",maxBytes=1024*1024)
            require(bytes.exitCode==0)
            require(root.walkTopDown().filter { it.isFile && it.extension=="wav" }.sumOf(File::length)+bytes.output.size<=32L*1024*1024)
            File(samples,name).writeBytes(bytes.output)
        }
        adb.shell("run-as",AdbClient.TARGET,"rm","-rf","files/evaluation/$id/samples")
    }
    private fun cleanupDevice(adb: AdbClient) {
        OwnedDeviceCleanup.remove(adb)
    }
    private fun apkFile(directory: File,expectedId: String): File {
        val metadata=JSONObject(File(directory,"output-metadata.json").readText())
        require(metadata.getString("applicationId")==expectedId && metadata.getJSONObject("artifactType").getString("type")=="APK") { "Unexpected APK application ID/artifact" }
        val elements=metadata.getJSONArray("elements"); require(elements.length()==1)
        val name=elements.getJSONObject(0).getString("outputFile")
        require(name.endsWith(".apk") && '/' !in name && '\\' !in name)
        return File(directory,name).also { require(it.isFile && it.canonicalFile==it.absoluteFile) }
    }
}

/** Historical input decoding and typed projection live in the host-safe test-kit boundary. */
internal fun fixedDirectionSpeechCatalog(baselineManifest: JSONObject): JSONObject =
    com.example.offlinetranslator.testkit.ModelManifest.fixedDirectionSpeechCatalog(baselineManifest)
