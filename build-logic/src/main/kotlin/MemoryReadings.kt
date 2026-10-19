import org.json.JSONObject

object MemoryReadings {
    fun parse(text: String): JSONObject {
        val pss=Regex("TOTAL PSS:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLong()
            ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toLong()
        val native=Regex("(?m)^\\s*Native Heap\\s+([^\\r\\n]+)").find(text)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+"))?.map {it.toLong()}
        require(pss!=null && native!=null && native.size in 7..9 && native.all {it>=0}) {"Memory output parse failed"}
        // Heap Size/Alloc/Free are the last three columns; newer Android adds RSS earlier.
        return JSONObject().put("pssKiB",pss).put("nativeKiB",native[native.size-3])
    }
}
