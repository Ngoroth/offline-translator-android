import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceLifecycleFaultTest {
    private class FakeAdb(var fail: String=""): AdbClient(File("unused"),"emulator-1234") {
        val installed=mutableSetOf(AdbClient.TARGET,AdbClient.TEST,"com.example.offlinetranslator","foreign.package")
        var staging=true;val commands=ArrayList<List<String>>()
        override fun command(vararg args: String,timeoutSeconds: Long,maxBytes: Int): CommandResult {
            commands.add(args.toList());val joined=args.joinToString(" ")
            if(fail=="disconnect") return CommandResult(1,"device offline".toByteArray())
            if(fail=="install" && args.first()=="install") return CommandResult(1,"INSTALL_FAILED".toByteArray())
            if(fail=="preparation" && joined.contains("instrument")) throw IllegalStateException("Command deadline exceeded")
            if(args.first()=="uninstall") {
                if(fail=="cleanup") return CommandResult(1,"uninstall failure".toByteArray())
                installed.remove(args.last())
            }
            if(joined.contains("rm") && joined.contains(AdbClient.DEVICE_ROOT)) staging=false
            val output=if(joined.contains("pm") && joined.contains("packages")) installed.filter {pkg ->
                !joined.contains(AdbClient.TARGET) || joined.contains(pkg)
            }.joinToString("\n") {"package:$it"} else if(joined.contains("wifi_on")) "1" else if(joined.contains("airplane_mode_on")) "0" else ""
            return CommandResult(if(joined.contains("test -e") && !staging) 1 else 0,output.toByteArray())
        }
    }
    @Test fun installFailureAndPreparationDeadlineAlwaysCleanOnlyOwnedPair() {
        for(fault in listOf("install","preparation")) {
            val adb=FakeAdb(fault)
            assertThrows(IllegalStateException::class.java) {
                try { if(fault=="install") adb.checked("install","-r","runner.apk") else adb.checked("shell","am instrument",timeoutSeconds=660) }
                finally { OwnedDeviceCleanup.remove(adb) }
            }
            assertEquals(0,OwnedDeviceCleanup.receipt(adb).getInt("packageCount"))
            assertEquals(setOf("com.example.offlinetranslator","foreign.package"),adb.installed)
            assertTrue(adb.commands.filter {it.first()=="uninstall"}.all {it.last() in setOf(AdbClient.TEST,AdbClient.TARGET)})
        }
    }
    @Test fun cleanupFailureAndDeviceLossRetainJournalForReconnection() {
        for(fault in listOf("cleanup","disconnect")) {
            val root=Files.createTempDirectory("cleanup-fault").toFile()
            try {
                RunWorkspace(root).use { owner ->
                    owner.record("emulator-1234")
                    assertThrows(IllegalStateException::class.java) {OwnedDeviceCleanup.remove(FakeAdb(fault))}
                    assertTrue(owner.journal.isFile)
                }
            } finally {root.deleteRecursively()}
        }
    }
    @Test fun prerequisiteFailureRecoversOwnedRunOrKeepsJournalOnDeviceLoss() {
        for(fault in listOf("","disconnect")) {
            val root=Files.createTempDirectory("prerequisite-orphan").toFile()
            try {
                RunWorkspace(root).use {owner ->
                    owner.record("emulator-1234");owner.saveNetwork("1","0")
                    File(owner.directory,"partial-input").writeText("owned")
                }
                RunWorkspace(root).use {next ->
                    val adb=FakeAdb(fault)
                    if(fault.isEmpty()) {
                        val receipt=OwnedRunRecovery.recover(next,adb)
                        assertEquals("verified",receipt.getString("status"))
                        assertEquals(0,receipt.getInt("orphanCount"));assertFalse(next.journal.exists())
                        assertEquals(setOf("com.example.offlinetranslator","foreign.package"),adb.installed)
                    } else {
                        assertThrows(IllegalStateException::class.java) {OwnedRunRecovery.recover(next,adb)}
                        assertTrue(next.journal.exists())
                    }
                }
            } finally {root.deleteRecursively()}
        }
    }
}
