import java.net.InetAddress
import java.util.concurrent.*

/** One resolver worker, no queued lookups, and an explicit deadline even if the OS ignores interruption. */
class DnsResolver(private val lookup: (String)->Array<InetAddress> = InetAddress::getAllByName): AutoCloseable {
    private val executor=ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,SynchronousQueue<Runnable>(),
        {work -> Thread(work,"model-dns").apply {isDaemon=true}})
    fun resolve(host: String,timeoutMillis: Long = 30000): Array<InetAddress> {
        require(timeoutMillis in 1..30000)
        val future=try {executor.submit<Array<InetAddress>> {lookup(host)}}
            catch(failure: RejectedExecutionException) {throw IllegalStateException("Model DNS resolver busy after an earlier timeout",failure)}
        try {return future.get(timeoutMillis,TimeUnit.MILLISECONDS)}
        catch(failure: TimeoutException) {throw IllegalStateException("Model DNS lookup deadline exceeded",failure)}
        finally {future.cancel(true)}
    }
    override fun close() {executor.shutdownNow()}
}
