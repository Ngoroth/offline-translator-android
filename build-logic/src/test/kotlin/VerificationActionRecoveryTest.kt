import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Executes the real verification action, including its catch/finally and report writer. */
class VerificationActionRecoveryTest {
    private class FakeAdb(var fault: String) : AdbClient(File("unused")) {
        val installed=mutableSetOf(TARGET,TEST,"foreign.package","com.example.offlinetranslator")
        val commands=mutableListOf<List<String>>()
        var staging=true
        var wifi="0"
        var airplane="1"
        override fun command(vararg args: String,timeoutSeconds: Long,maxBytes: Int): CommandResult {
            commands.add(args.toList())
            val text=args.joinToString(" ")
            if(args.first()=="devices") return result(0,when(fault) {
                "unavailable" -> "List of devices attached\n"
                "multiple" -> "List of devices attached\nemulator-1234 device\nemulator-9999 device\n"
                "offline" -> "List of devices attached\nemulator-1234 offline\n"
                "wrongDevice" -> "List of devices attached\nemulator-9999 device\n"
                else -> "List of devices attached\nemulator-1234 device\n"
            })
            if(text.contains("ro.kernel.qemu")) return result(0,"1")
            if(text.contains("ro.product.cpu.abi")) return result(0,"x86_64")
            if(text.contains("ro.build.version.sdk")) return result(0,"37")
            if(fault=="metadata" && args.first()=="emu") return result(1,"device lost")
            if(fault=="cleanup" && args.first()=="uninstall") return result(1,"device lost")
            if(args.first()=="uninstall") installed.remove(args.last())
            if(text.contains("rm") && text.contains(DEVICE_ROOT)) staging=false
            if(text.contains("mkdir") && text.contains(DEVICE_ROOT)) staging=true
            if(text.contains("airplane-mode") && fault!="network") airplane=if(text.contains("disable")) "0" else "1"
            if(text.contains("svc") && text.contains("wifi")) wifi=if(text.contains("enable")) "1" else "0"
            if(text.contains("wifi_on")) return result(0,wifi)
            if(text.contains("airplane_mode_on")) return result(0,airplane)
            if(text.contains("packages")) return result(0,installed.filter { pkg ->
                !text.contains(TARGET) || text.contains(pkg)
            }.joinToString("\n") {"package:$it"})
            if(text.contains("test -e")) return result(if(staging || fault=="receipt") 0 else 1,"")
            // Stop the recovered next action before any download or instrumentation.
            if(text.contains("df")) return result(0,"Filesystem 1K-blocks Used Available Use% Mounted\n/data 100 100 0 100% /data")
            return result(0,"")
        }
        private fun result(code: Int,text: String)=CommandResult(code,text.toByteArray())
    }

    private fun fixture(root: File) {
        val repo=File(System.getProperty("evaluation.repo"))
        File(repo,"test-data/corpus/v1").copyRecursively(File(root,"test-data/corpus/v1"))
        File(root,"test-data/model-sets").mkdirs()
        File(repo,"test-data/model-sets/baseline.json").copyTo(File(root,"test-data/model-sets/baseline.json"))
        val aar=File(root,"engine-sherpa/.native-cache/android/sherpa-onnx-1.13.8-pair-v1.aar")
        aar.parentFile.mkdirs()
        File(repo,"engine-sherpa/.native-cache/android/sherpa-onnx-1.13.8-pair-v1.aar").copyTo(aar)
        // Read-only git commands in the action use the existing repository's object database.
        val gitDirectory=BoundedProcess.run(listOf("git","rev-parse","--absolute-git-dir"),10,4096,repo)
        check(gitDirectory.exitCode==0) { "Fixture requires the current repository's Git directory" }
        File(root,".git").writeText("gitdir: ${gitDirectory.text.trim()}\n")
        for(module in listOf("core","engine-mlkit","engine-sherpa","app","translator-test-kit","build-logic")) {
            val xml=File(root,"$module/build/test-results/unit/TEST-fixture.xml")
            xml.parentFile.mkdirs();xml.writeText("<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>")
        }
        File(root,"app/build/reports").mkdirs()
        File(root,"app/build/reports/lint-results-debug.html").writeText("fixture lint")
    }

    private fun action(root: File,adb: FakeAdb,modelSet: String="test-data/model-sets/baseline.json"): JSONObject {
        val project=ProjectBuilder.builder().withProjectDir(root).build()
        val task=project.tasks.register("verifyTranslator",VerifyTranslatorTask::class.java).get()
        task.repo.set(root);task.modelSet.set(modelSet);task.fault.set("");task.gradleVersion.set("test")
        task.adbFactory={adb}
        assertThrows(GradleException::class.java) {task.verify()}
        return JSONObject(File(root,"build/reports/model-evaluation/report.json").readText())
    }

    @Test fun earlyActionAndRecoveryFailuresRetainAuthorityAndNextActionRecoversOnlyOwnedState() {
        for(fault in listOf("invalidManifest","missingManifest","unavailable","multiple","offline","wrongDevice","metadata","cleanup","network","receipt")) {
            val root=Files.createTempDirectory("verification-action-$fault").toFile()
            try {
                fixture(root)
                val priorDirectory=RunWorkspace(root).use { owner ->
                    owner.record("emulator-1234");owner.saveNetwork("1","0")
                    File(owner.directory,"partial-input").writeText("owned")
                    owner.directory
                }
                val journal=File(root,"build/translator-verification/run-state.json")
                val originalJournal=journal.readBytes()
                val foreign=File(root,"build/translator-verification/runs/foreign/input")
                foreign.parentFile.mkdirs();foreign.writeText("foreign")
                File(root,"invalid.json").writeText("{}")
                val adb=FakeAdb(fault)
                val model=when(fault) {"invalidManifest" -> "invalid.json";"missingManifest" -> "missing.json";else -> "test-data/model-sets/baseline.json"}
                val report=action(root,adb,model)
                assertEquals("FAIL",report.getString("verdict"))
                val cleanup=report.getJSONObject("cleanup")
                assertEquals("unverified",cleanup.getString("status"))
                for(key in listOf("packageCount","deviceInputBytes","hostTemporaryBytes","orphanCount","orphanBytes")) assertFalse("$fault fabricated $key",cleanup.has(key))
                assertArrayEquals(originalJournal,journal.readBytes())
                assertTrue(File(priorDirectory,"partial-input").isFile)
                assertEquals("foreign",foreign.readText())
                if(fault !in setOf("cleanup","network","receipt")) {
                    assertEquals(setOf(AdbClient.TARGET,AdbClient.TEST,"foreign.package","com.example.offlinetranslator"),adb.installed)
                    assertTrue(adb.staging);assertEquals("0",adb.wifi);assertEquals("1",adb.airplane)
                }
                adb.fault="";adb.serial=null
                val next=action(root,adb)
                assertEquals("verified",next.getJSONObject("orphanRecovery").getString("status"))
                assertEquals("verified",next.getJSONObject("cleanup").getString("status"))
                assertFalse(journal.exists());assertFalse(priorDirectory.exists())
                assertFalse(adb.staging);assertEquals("1",adb.wifi);assertEquals("0",adb.airplane)
                assertEquals(setOf("foreign.package","com.example.offlinetranslator"),adb.installed)
                assertEquals("foreign",foreign.readText())
                assertTrue(adb.commands.filter {it.first()=="uninstall"}.all {it.last() in setOf(AdbClient.TARGET,AdbClient.TEST)})
                assertTrue(adb.commands.filter {it.joinToString(" ").contains("rm")}.all {it.last().contains(AdbClient.DEVICE_ROOT)})
                assertFalse(adb.commands.any {it.first()=="install" || it.joinToString(" ").contains("instrument")})
            } finally {root.deleteRecursively()}
        }
    }

    @Test fun mismatchedPackageOwnershipNeverGrantsCleanupAuthority() {
        val root=Files.createTempDirectory("verification-foreign-owner").toFile()
        try {
            fixture(root)
            val directory=RunWorkspace(root).use { owner ->
                owner.record("emulator-1234");owner.saveNetwork("1","0")
                File(owner.directory,"partial-input").writeText("preserved")
                val foreign=JSONObject(owner.journal.readText()).put("target","foreign.package")
                owner.journal.writeText(foreign.toString())
                owner.directory
            }
            val journal=File(root,"build/translator-verification/run-state.json")
            val original=journal.readBytes()
            val adb=FakeAdb("")
            assertEquals("unverified",action(root,adb).getJSONObject("cleanup").getString("status"))
            assertArrayEquals(original,journal.readBytes())
            assertEquals("preserved",File(directory,"partial-input").readText())
            assertEquals(setOf(AdbClient.TARGET,AdbClient.TEST,"foreign.package","com.example.offlinetranslator"),adb.installed)
            assertTrue(adb.staging);assertEquals("0",adb.wifi);assertEquals("1",adb.airplane)
            assertFalse(adb.commands.any {it.first()=="uninstall" || it.joinToString(" ").contains("rm")})
        } finally {root.deleteRecursively()}
    }
}
