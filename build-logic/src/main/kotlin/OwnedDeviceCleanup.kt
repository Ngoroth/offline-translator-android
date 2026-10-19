import org.json.JSONObject

/** These constants are the whole deletion authority; manifests cannot expand it. */
object OwnedDeviceCleanup {
    fun remove(adb: AdbClient) {
        for(pkg in listOf(AdbClient.TEST,AdbClient.TARGET)) {
            adb.shell("am","force-stop",pkg)
            val installed=adb.shell("pm","list","packages",pkg).lineSequence().any { it=="package:$pkg" }
            if(installed) adb.checked("uninstall",pkg)
        }
        adb.shell("rm","-rf",AdbClient.DEVICE_ROOT)
    }
    fun receipt(adb: AdbClient): JSONObject {
        val packages=adb.shell("pm","list","packages").lineSequence().count {it=="package:${AdbClient.TARGET}" || it=="package:${AdbClient.TEST}"}
        require(packages==0)
        require(adb.command("shell","test -e ${AdbClient.quote(AdbClient.DEVICE_ROOT)}").exitCode==1) {"Device staging cleanup cannot be verified"}
        return JSONObject().put("packageCount",0).put("deviceInputBytes",0).put("deviceModelBytes",0)
            .put("deviceCorpusBytes",0).put("deviceTemporaryWavBytes",0).put("deviceIntermediateBytes",0).put("status","verified")
    }
}
