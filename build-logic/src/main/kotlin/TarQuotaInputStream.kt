import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarUtils

/** Bound structural GNU/PAX records before Commons Compress can allocate their contents. */
class TarQuotaInputStream(private val source: InputStream,private val maxEntries: Int,private val byteLimit: Long,
    private val deadline: Long = System.nanoTime()+900_000_000_000L): InputStream() {
    private val header=ByteArray(512)
    private var headerOffset=512
    private var payloadRemaining=0L
    private var entries=0
    private var consumed=0L
    override fun read(): Int {
        val byte=ByteArray(1);return if(read(byte,0,1)<0) -1 else byte[0].toInt() and 255
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
        require(offset>=0 && length>=0 && offset+length<=buffer.size)
        if(length==0) return 0
        require(System.nanoTime()<deadline) {"Archive extraction deadline exceeded"}
        if(headerOffset==512 && payloadRemaining==0L) {
            var count=0
            while(count<512) {val read=source.read(header,count,512-count);if(read<0) {require(count==0);return -1};require(read>0);count+=read}
            headerOffset=0
            if(header.any {it!=0.toByte()}) {
                require(++entries<=maxEntries)
                val size=TarUtils.parseOctalOrBinary(header,124,12);require(size>=0 && size<=byteLimit)
                val type=header[156].toInt().toChar()
                require(type in setOf('\u0000','0','5','L','x','g')) {"Forbidden tar structural type"}
                if(type=='L') require(size<=241) {"Oversized GNU filename"}
                if(type in setOf('x','g')) require(size<=32768) {"Oversized PAX metadata"}
                if(type=='5') require(size==0L)
                payloadRemaining=((size+511)/512)*512
            }
        }
        val count=if(headerOffset<512) {
            val count=minOf(length,512-headerOffset);header.copyInto(buffer,offset,headerOffset,headerOffset+count);headerOffset+=count;count
        } else {
            val count=source.read(buffer,offset,minOf(length.toLong(),payloadRemaining).toInt());require(count>0)
            payloadRemaining-=count;count
        }
        consumed+=count;require(consumed<=byteLimit+maxEntries*1024L+65536) {"Actual decompression quota exceeded"}
        return count
    }
    override fun close()=source.close()
}
