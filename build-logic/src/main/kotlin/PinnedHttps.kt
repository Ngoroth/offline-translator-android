import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Resolve once, validate every address, connect to that IP, authenticate the original hostname. */
object PinnedHttps {
    private val resolver=DnsResolver()
    fun validate(uri: URI,hosts: Set<String>,addresses: Array<InetAddress>): InetAddress {
        require(uri.scheme == "https" && uri.host in hosts && uri.userInfo == null && uri.port in setOf(-1,443))
        require(addresses.isNotEmpty() && addresses.all {
            !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress &&
                !(it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
                && !(it.address.size==4 && it.address.map {byte -> byte.toInt() and 255}.let {octets ->
                    octets[0]==0 || octets[0]>=224 || (octets[0]==100 && octets[1] in 64..127) ||
                        (octets[0]==198 && octets[1] in 18..19) || (octets[0]==192 && octets[1]==0 && octets[2] in 0..2) ||
                        (octets[0]==198 && octets[1]==51 && octets[2]==100) || (octets[0]==203 && octets[1]==0 && octets[2]==113)
                })
        }) { "Nonpublic download destination" }
        return addresses.first()
    }
    fun download(initial: URI,hosts: Set<String>,target: File,expectedBytes: Long,outerDeadline: Long = Long.MAX_VALUE) {
        val deadline = minOf(System.nanoTime() + 900_000_000_000L,outerDeadline)
        fun remainingMillis(): Int {
            val remaining=(deadline-System.nanoTime())/1_000_000L
            require(remaining>0) {"Model download deadline exceeded"}
            return minOf(30000,remaining).toInt()
        }
        var uri = initial
        for(redirect in 0..5) {
            require(uri.scheme=="https" && uri.host in hosts && uri.userInfo==null && uri.port in setOf(-1,443)) {"Untrusted redirect endpoint"}
            val address = validate(uri,hosts,resolver.resolve(uri.host,remainingMillis().toLong()))
            require(System.nanoTime() < deadline)
            val tcp = Socket()
            try {
                tcp.connect(InetSocketAddress(address,443),remainingMillis());tcp.soTimeout = remainingMillis()
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(tcp,uri.host,443,true) as SSLSocket).use { tls ->
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS";serverNames = listOf(SNIHostName(uri.host))
                    }
                    tls.soTimeout = remainingMillis();tls.startHandshake()
                    val path = (uri.rawPath.takeIf(String::isNotEmpty) ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                    require('\r' !in path && '\n' !in path && path.length <= 16384)
                    tls.outputStream.write("GET $path HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\nAccept-Encoding: identity\r\nUser-Agent: translator-evaluation/1\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    tls.outputStream.flush()
                    val boundedInput=object: java.io.FilterInputStream(tls.inputStream) {
                        override fun read(): Int {tls.soTimeout=remainingMillis();return super.read()}
                        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                            tls.soTimeout=remainingMillis();return `in`.read(bytes,offset,length)
                        }
                    }
                    val input = BufferedInputStream(boundedInput,65536)
                    var headerBytes = 0
                    fun line(): String {
                        val text = StringBuilder()
                        while(true) {
                            val byte = input.read();require(byte >= 0 && ++headerBytes <= 65536) { "Truncated/oversized HTTP header" }
                            if(byte == 10) { require(text.lastOrNull() == '\r');return text.dropLast(1).toString() }
                            text.append(byte.toChar())
                        }
                    }
                    val status = line().split(' ');require(status.size >= 2 && status[0] in setOf("HTTP/1.1","HTTP/1.0"))
                    val headers = LinkedHashMap<String,String>()
                    while(true) { val row = line();if(row.isEmpty()) break
                        val key = row.substringBefore(':').lowercase(java.util.Locale.ROOT)
                        require(':' in row && key !in headers);headers[key] = row.substringAfter(':').trim()
                    }
                    val code = status[1].toInt()
                    if(code in setOf(301,302,303,307,308)) {
                        uri = uri.resolve(requireNotNull(headers["location"]));return@use
                    }
                    require(code == 200) { "Model HTTP status $code" }
                    require(headers["content-encoding"] in setOf(null,"identity"))
                    require(headers["transfer-encoding"] == null) { "Expected bounded Content-Length model response" }
                    require(headers["content-length"]?.toLong() == expectedBytes) { "Model response size differs from lock" }
                    copy(input,target,expectedBytes,deadline)
                    return
                }
            } finally { tcp.close() }
        }
        error("Redirect limit exceeded")
    }
    fun copy(input: InputStream,target: File,expectedBytes: Long,deadline: Long) {
        var remaining = expectedBytes
        target.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while(remaining > 0) {
                require(System.nanoTime() < deadline) { "Download deadline exceeded" }
                val count = input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt());require(count > 0) { "Truncated response" }
                output.write(buffer,0,count);remaining -= count
            }
            require(input.read() == -1) { "Response exceeds locked size" }
        }
    }
}
