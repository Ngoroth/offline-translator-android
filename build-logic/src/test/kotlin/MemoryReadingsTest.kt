import org.junit.Assert.*
import org.junit.Test

class MemoryReadingsTest {
    @Test fun nativeHeapSizeNeverUsesRssAllocationOrSummaryPss() {
        for(row in listOf("658068 658032 0 144 658920 795984 714230 58492","658068 658032 0 144 795984 714230 58492")) {
            val result=MemoryReadings.parse("Native Heap $row\nTOTAL PSS: 802127\nNative Heap: 658032\n")
            assertEquals(795984,result.getLong("nativeKiB"));assertEquals(802127,result.getLong("pssKiB"))
        }
        assertThrows(IllegalArgumentException::class.java) {MemoryReadings.parse("TOTAL PSS: 802127\nNative Heap: 658032\n")}
    }
}
