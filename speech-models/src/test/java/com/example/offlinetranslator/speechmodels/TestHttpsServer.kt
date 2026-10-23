package com.example.offlinetranslator.speechmodels

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Local TLS fixture. Its test-only key is not used or trusted by the production app. */
internal class TestHttpsServer(
    handler: (HttpExchange) -> Unit,
) : AutoCloseable {
    private val keyStore = KeyStore.getInstance("PKCS12").apply {
        val stream = checkNotNull(TestHttpsServer::class.java.getResourceAsStream("/model-fixture.p12")) {
            "Missing test-only TLS fixture /model-fixture.p12. Tracked test resource must be present in speech-models/src/test/resources/."
        }
        stream.use { load(it, FIXTURE_PASSWORD.toCharArray()) }
    }
    private val sslContext = SSLContext.getInstance("TLS").apply {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, FIXTURE_PASSWORD.toCharArray())
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        init(keyManagers.keyManagers, trustManagers.trustManagers, null)
    }
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(sslContext)
        createContext("/") { exchange -> handler(exchange) }
        this.executor = this@TestHttpsServer.executor
        start()
    }

    val port: Int get() = server.address.port

    fun openConnection(source: URL): HttpURLConnection {
        require(source.protocol == "https")
        val fixtureUrl = URL("https://localhost:$port${source.file}")
        return (fixtureUrl.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sslContext.socketFactory
            connectTimeout = 3_000
            readTimeout = 3_000
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        private const val FIXTURE_PASSWORD = "fixture-only"
    }
}
