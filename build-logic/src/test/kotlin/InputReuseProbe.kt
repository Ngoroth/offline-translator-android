import com.example.offlinetranslator.testkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.time.Instant

/** One explicit maintainer proof. Never runs verifyTranslator or writes its retained report. */
object InputReuseProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty() || args.toList()==listOf("--device-only-retry"))
        val deviceOnly=args.isNotEmpty()
        val repo=File(requireNotNull(System.getProperty("evaluation.repo"))).canonicalFile
        val original=File(repo,"build/reports/input-reuse/proof.json")
        val output=if(deviceOnly) File(original.parentFile,"device-retry.json") else original
        require(!output.exists()) {"The bounded proof has already been attempted; inspect its receipt before any retry"}
        val receipt=JSONObject().put("scope","input reuse only; no full inference validation").put("startedAt",Instant.now().toString()).put("status","FAIL")
        val phases=JSONArray();receipt.put("preparations",phases)
        val deadline=System.nanoTime()+1_800_000_000_000L
        val corpus=File(repo,"test-data/corpus/v1");val cases=CorpusManifest.read(corpus)
        val models=ModelManifest.read(File(repo,"test-data/model-sets/baseline.json"))
        val key=PreparedEvaluationInputs.key(corpus,cases)
        var coldIdentity: String?=if(deviceOnly) {
            val prior=Integrity.json(original)
            require(prior.getString("status")=="FAIL" && prior.getJSONArray("preparations").length()==2)
            val phases=prior.getJSONArray("preparations")
            require(phases.getJSONObject(0).getString("inputIdentity")==phases.getJSONObject(1).getString("inputIdentity"))
            receipt.put("originalProofSha256",Integrity.sha256(original)).put("scope","corrected device-only check; original cold/warm FAIL retained")
            phases.getJSONObject(1).getString("inputIdentity")
        } else null
        try {
            for(warm in if(deviceOnly) listOf(true) else listOf(false,true)) RunWorkspace(repo).use {owner ->
                val phase=JSONObject().put("kind",if(deviceOnly) "device-only cached staging" else if(warm) "warm" else "cold").put("runId",owner.id);phases.put(phase)
                val adb=AndroidSdk.adb(repo)
                var mutated=false
                var recoveryPending=Files.exists(owner.journal.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
                try {
                    val serial=adb.discover();receipt.put("serial",serial)
                    if(recoveryPending) {OwnedRunRecovery.recover(owner,adb);recoveryPending=false}
                    require(adb.command("shell","test -e ${AdbClient.quote(AdbClient.DEVICE_ROOT)} || test -L ${AdbClient.quote(AdbClient.DEVICE_ROOT)}").exitCode==1) {"Foreign device staging"}
                    val packages=adb.shell("pm","list","packages")
                    require(packages.lineSequence().none {it=="package:${AdbClient.TARGET}" || it=="package:${AdbClient.TEST}"}) {"Unowned test package pair"}
                    SpaceRequirements.host(repo.usableSpace)
                    val disk=adb.shell("df","-k","/data").lineSequence().last {it.isNotBlank()}.trim().split(Regex("\\s+"))
                    SpaceRequirements.device(disk[3].toLong())
                    val target=apk(repo,"modelSmoke",AdbClient.TARGET)
                    val test=apk(repo,"androidTest/modelSmoke",AdbClient.TEST)
                    val settings=JSONObject().put("wifi",adb.shell("settings","get","global","wifi_on"))
                        .put("airplane",adb.shell("settings","get","global","airplane_mode_on"))
                    phase.put("networkBefore",settings)
                    if(!warm) {
                        val cacheRoot=File(repo,".cache/translator-evaluation")
                        require(!cacheRoot.exists()) {"Cold proof requires the new canonical cache to be absent"}
                    }
                    owner.record(serial);mutated=true;adb.shell("mkdir",AdbClient.DEVICE_ROOT)
                    val input=File(owner.directory,"input");require(input.mkdirs())
                    require(corpus.copyRecursively(File(input,"corpus")))
                    val baselineManifest = JSONObject(File(repo,"test-data/model-sets/baseline.json").readText())
                    File(input,"speech-models.json").writeText(fixedDirectionSpeechCatalog(baselineManifest).toString())
                    val cache=EvaluationInputCache(owner,models,key)
                    val prepared=PreparedEvaluationInputs.stage(cache,File(input,"corpus"),cases,input,deadline);phase.put("prepared",prepared)
                    require(prepared.getBoolean("cacheHit")==warm)
                    if(warm) require(prepared.isNull("generationMs"))
                    val staging=JSONArray();phase.put("models",staging)
                    for(model in models) {
                        val row=ModelStager(input,cache).stage(model,deadline);staging.put(JSONObject().put("id",model.id).put("staging",row))
                        require(row.getBoolean("cacheHit")==warm)
                        if(warm) require(row.isNull("downloadMs") && row.isNull("extractionAndFileVerificationMs"))
                    }
                    File(input,"models.json").writeText(JSONObject().put("schemaVersion",1).put("runtime",ModelManifest.RUNTIME).put("candidates",JSONArray(models.map {it.json})).toString())
                    val identities=input.walkTopDown().filter(File::isFile).map {"${it.relativeTo(input).invariantSeparatorsPath}:${it.length()}:${Integrity.sha256(it)}"}.sorted().joinToString("\n")
                    val identity=EvaluationInputCache.key(identities);phase.put("inputIdentity",identity).put("cache",cache.receipt())
                    if(warm) {
                        require(identity==coldIdentity) {"Cold/warm input identities differ"}
                        receipt.put("apks",JSONObject().put("targetSha256",Integrity.sha256(target)).put("testSha256",Integrity.sha256(test)))
                        adb.checked("install","-r",target.absolutePath)
                        adb.checked("install","-r",test.absolutePath)
                        adb.checked("push",input.absolutePath,AdbClient.DEVICE_ROOT)
                        adb.shell("chmod","-R","a+rX",AdbClient.DEVICE_ROOT)
                        adb.shell("run-as",AdbClient.TARGET,"mkdir","-p","files/evaluation/${owner.id}")
                        adb.shell("run-as",AdbClient.TARGET,"cp","-R","${AdbClient.DEVICE_ROOT}/input/.","files/evaluation/${owner.id}/")
                        val command="am instrument -w -r -e class ${AdbClient.quote("com.example.offlinetranslator.modelsmoke.InputReuseProbeTest")} -e runId ${AdbClient.quote(owner.id)} -e phase smoke ${AdbClient.quote(AdbClient.TEST+"/com.example.offlinetranslator.modelsmoke.EvaluationInstrumentation")}"
                        val result=adb.command("shell",command,timeoutSeconds=180,maxBytes=1048576)
                        phase.put("instrumentation",result.text)
                        require(result.exitCode==0 && result.text.contains("OK (") && result.text.contains("HARNESS_COUNTS run=1 failed=0 skipped=0") && !result.text.contains("FAILURES!!!")) {"Input reuse instrumentation failed"}
                        val evidence=adb.command("exec-out","run-as",AdbClient.TARGET,"cat","files/evaluation/${owner.id}/input-reuse-proof.json",maxBytes=1048576)
                        require(evidence.exitCode==0);receipt.put("device",JSONObject(evidence.text))
                    } else coldIdentity=identity
                    val after=JSONObject().put("wifi",adb.shell("settings","get","global","wifi_on"))
                        .put("airplane",adb.shell("settings","get","global","airplane_mode_on"))
                    phase.put("networkAfter",after);require(settings.toString()==after.toString())
                } finally {
                    try {
                        check(!recoveryPending) {"Prior recovery unverified; journal retained"}
                        if(mutated) {
                            OwnedDeviceCleanup.remove(adb);phase.put("cleanup",OwnedDeviceCleanup.receipt(adb))
                            owner.cleanupHost();require(owner.journal.delete())
                            phase.getJSONObject("cleanup").put("hostTemporaryBytes",0).put("orphanCount",0)
                        } else phase.put("cleanup",JSONObject().put("status","notMutated"))
                    } catch(failure: Exception) {
                        phase.put("cleanup",JSONObject().put("status","unverified").put("reason",failure.message));throw failure
                    }
                }
            }
            receipt.put("status","PASS").put("quality","unmeasured").put("fullPipeline","unmeasured").put("stress","unmeasured")
        } catch(failure: Throwable) {receipt.put("reason","${failure.javaClass.simpleName}: ${failure.message}");throw failure}
        finally {
            receipt.put("finishedAt",Instant.now().toString());output.parentFile.mkdirs()
            val bytes=receipt.toString(2).toByteArray();require(bytes.size<=1048576)
            val partial=File(output.parentFile,"proof.partial.json")
            FileOutputStream(partial).use {it.write(bytes);it.fd.sync()}
            Files.move(partial.toPath(),output.toPath(),ATOMIC_MOVE)
            println("Input reuse proof: ${output.absolutePath}; ${receipt.getString("status")}")
        }
    }
    private fun apk(repo: File,variant: String,id: String): File {
        val directory=File(repo,"engine-sherpa-model-smoke/build/outputs/apk/$variant")
        val metadata=Integrity.json(File(directory,"output-metadata.json"))
        require(metadata.getString("applicationId")==id && metadata.getJSONObject("artifactType").getString("type")=="APK")
        val elements=metadata.getJSONArray("elements");require(elements.length()==1)
        val name=elements.getJSONObject(0).getString("outputFile")
        require(Integrity.safePath(name) && '/' !in name && name.endsWith(".apk"))
        return File(directory,name).also {require(it.isFile && it.canonicalFile==it.absoluteFile)}
    }
}
