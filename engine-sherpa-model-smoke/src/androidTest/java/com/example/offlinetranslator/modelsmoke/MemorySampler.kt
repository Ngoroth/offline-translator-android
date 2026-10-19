package com.example.offlinetranslator.modelsmoke

import android.os.Debug
import org.json.JSONObject

/** Exact release milestone complements host whole-process samples collected every second. */
object MemorySampler {
    fun snapshot(): JSONObject {
        val memory = Debug.MemoryInfo();Debug.getMemoryInfo(memory)
        return JSONObject().put("atNanos",nowNanos()).put("pssKiB",memory.totalPss)
            .put("nativeKiB",Debug.getNativeHeapSize()/1024)
    }
}
