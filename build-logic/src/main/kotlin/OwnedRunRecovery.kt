import org.json.JSONObject
import java.io.File
import java.util.Properties

object AndroidSdk {
    fun adb(repo: File): AdbClient {
        val properties=Properties()
        File(repo,"local.properties").takeIf(File::isFile)?.inputStream()?.use(properties::load)
        val sdk=properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Android SDK is missing")
        return AdbClient(File(sdk,"platform-tools/adb"+if(System.getProperty("os.name").startsWith("Windows")) ".exe" else ""))
    }
}

/** Also used when a Gradle prerequisite fails before the orchestration action starts. */
object OwnedRunRecovery {
    fun recover(workspace: RunWorkspace,adb: AdbClient): JSONObject {
        val prior=JSONObject(workspace.journal.readText())
        val serial=adb.serial ?: adb.discover()
        require(prior.getString("serial")==serial && prior.getString("target")==AdbClient.TARGET && prior.getString("test")==AdbClient.TEST)
        OwnedDeviceCleanup.remove(adb)
        if(prior.has("airplane")) adb.shell("cmd","connectivity","airplane-mode",if(prior.getString("airplane")=="1") "enable" else "disable")
        if(prior.has("wifi")) adb.shell("svc","wifi",if(prior.getString("wifi")=="1") "enable" else "disable")
        val receipt=OwnedDeviceCleanup.receipt(adb)
        for((key,setting) in listOf("wifi" to "wifi_on","airplane" to "airplane_mode_on")) if(prior.has(key)) {
            val actual=adb.shell("settings","get","global",setting)
            require(actual==prior.getString(key)) { "Network restoration cannot be verified" }
            receipt.put("restored-$key",actual)
        }
        workspace.cleanupHost()
        require(workspace.journal.delete())
        return receipt.put("recoveredOrphan",prior.getString("runId")).put("hostTemporaryBytes",0).put("orphanCount",0).put("orphanBytes",0)
    }
}
