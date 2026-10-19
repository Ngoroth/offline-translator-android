import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.artifact.SingleArtifact
import com.example.offlinetranslator.testkit.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

abstract class VerificationCompletion : BuildService<VerificationCompletion.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters { val repo: DirectoryProperty;val modelSet: Property<String> }
    @Volatile var armed=false
    @Volatile var completed=false
    @Volatile var workspace: RunWorkspace?=null
    @Volatile var startedAt=""
    fun arm() {
        try { workspace=RunWorkspace(parameters.repo.get().asFile.canonicalFile);startedAt=Instant.now().toString();armed=true }
        catch(failure: Exception) {completed=true;throw org.gradle.api.GradleException("Another verification owns the workspace; no prerequisites started",failure)}
    }
    override fun close() {
        if (armed && !completed) {
            val repo=parameters.repo.get().asFile
            val baseline=runCatching { ModelManifest.read(File(repo,"test-data/model-sets/baseline.json")) }.getOrDefault(emptyList())
            val rows=runCatching {
                val file=File(parameters.modelSet.get()).let {if(it.isAbsolute) it else File(repo,it.path)}
                ModelManifest.merge(baseline,ModelManifest.read(file))
            }.getOrDefault(baseline)
            val report=JSONObject().put("schemaVersion",1).put("startedAt",startedAt).put("finishedAt",Instant.now().toString()).put("runId",requireNotNull(workspace).id).put("verdict","FAIL")
                .put("reasons",JSONArray().put("Gradle prerequisite failed before verification action"))
                .put("candidates",JSONArray(rows.map { JSONObject().put("id",it.id).put("slot",it.slot).put("identity",it.json).put("verdict","FAIL").put("reasons",JSONArray().put("prerequisiteFailure")).put("metrics",JSONObject.NULL).put("notMeasured","Gradle prerequisite failure") }))
                .put("cleanup",JSONObject().put("deviceMutated",false).put("status","notStarted"))
            try {
                val owner=requireNotNull(workspace)
                if(owner.journal.isFile) {
                    try { report.put("cleanup",OwnedRunRecovery.recover(owner,AndroidSdk.adb(repo))) }
                    catch(failure: Exception) {
                        report.put("cleanup",JSONObject().put("status","unverified").put("reason",failure.message))
                        report.getJSONArray("reasons").put("Prior run cleanup failed; ownership journal retained")
                    }
                }
                RunWorkspace.safeDelete(File(repo,"build/reports/model-evaluation"),File(repo,"build"))
                ReportWriter.write(File(repo,"build/reports/model-evaluation"),report)
            }
            finally { workspace?.close();workspace=null }
        }
    }
}

class TranslatorVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val completion=gradle.sharedServices.registerIfAbsent("translatorVerificationCompletion",VerificationCompletion::class.java) {
            parameters.repo.set(layout.projectDirectory)
            parameters.modelSet.set(providers.gradleProperty("modelSet").orElse("test-data/model-sets/baseline.json"))
        }
        val cleanOwnership=gradle.sharedServices.registerIfAbsent("translatorCleanOwnership",CleanOwnership::class.java) {parameters.repo.set(layout.projectDirectory)}
        val clean=tasks.register("clean") {
            group="build";description="Remove module builds and verification reports while preserving the stable lock."
            dependsOn(":app:clean",":core:clean",":engine-mlkit:clean",":engine-sherpa:clean",":engine-sherpa-model-smoke:clean",":translator-test-kit:clean",gradle.includedBuild("build-logic").task(":clean"))
            usesService(cleanOwnership)
            notCompatibleWithConfigurationCache("Owns the verification cleanup lock across all clean dependencies")
            doLast {cleanOwnership.get().cleanRoot()}
        }
        val verify=tasks.register<VerifyTranslatorTask>("verifyTranslator") {
            val task=this
            task.group="verification"; task.description="Run isolated emulator regression and pinned speech evaluation; always clean inputs."
            task.repo.set(layout.projectDirectory)
            task.modelSet.set(providers.gradleProperty("modelSet").orElse("test-data/model-sets/baseline.json"))
            task.fault.set(providers.gradleProperty("evaluationFault").orElse(""))
            task.gradleVersion.set(gradle.gradleVersion)
            task.completion=completion
            task.usesService(completion)
            task.outputs.upToDateWhen { false }
            task.notCompatibleWithConfigurationCache("Owns external emulator lifecycle and a failure-finalizing build service")
            task.dependsOn(":core:testDebugUnitTest",":engine-mlkit:testDebugUnitTest",":engine-sherpa:testDebugUnitTest",":app:testDebugUnitTest",":translator-test-kit:testDebugUnitTest",":app:lintDebug",":engine-sherpa-model-smoke:assembleModelSmoke",":engine-sherpa-model-smoke:assembleModelSmokeAndroidTest",gradle.includedBuild("build-logic").task(":test"))
        }
        gradle.taskGraph.whenReady(object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
            override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
                if(!gradle.startParameter.isDryRun) {
                    require(!(graph.hasTask(clean.get()) && graph.hasTask(verify.get()))) {"Run clean and verifyTranslator as separate commands"}
                    if(graph.hasTask(clean.get())) cleanOwnership.get().arm()
                    if(graph.hasTask(verify.get())) completion.get().arm()
                }
            }
        })
        tasks.register("verifyCorpus") {
            val task=this
            task.inputs.dir(layout.projectDirectory.dir("test-data/corpus/v1"))
            task.doLast { CorpusManifest.read(requireNotNull(task.inputs.files.files.single { it.name=="manifest.json" }.parentFile)); task.logger.lifecycle("Verified 58 canonical WAVs") }
        }
        tasks.register("verifyModelManifests") {
            val task=this
            task.inputs.file(layout.projectDirectory.file("test-data/model-sets/baseline.json"))
            task.doLast {
                val models=ModelManifest.read(task.inputs.files.singleFile)
                models.filter { it.json.has("consumedManifestSha256") }.forEach { model ->
                    val rows=model.files.sortedBy { it.path.lowercase(java.util.Locale.ROOT) }.joinToString("") { "${it.path}\t${it.bytes}\t${it.sha256}\n" }
                    val hash=java.security.MessageDigest.getInstance("SHA-256").digest(rows.toByteArray()).joinToString("") { "%02x".format(it) }
                    require(hash==model.json.getString("consumedManifestSha256"))
                }
                task.logger.lifecycle("Verified five pinned model manifests")
            }
        }
        project(":engine-sherpa-model-smoke").plugins.withId("com.android.application") {
            val android=project(":engine-sherpa-model-smoke").extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            android.onVariants(android.selector().withBuildType("modelSmoke")) { variant ->
                verify.configure {
                    val task=this
                    task.targetApk.set(variant.artifacts.get(SingleArtifact.APK))
                    task.testApk.set(requireNotNull(variant.androidTest).artifacts.get(SingleArtifact.APK))
                }
            }
        }
    }
}
