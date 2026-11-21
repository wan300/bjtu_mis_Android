package cn.edu.bjtu.mis.data.network

import android.content.Context
import android.net.http.X509TrustManagerExtensions
import androidx.annotation.Keep
import cn.edu.bjtu.mis.R
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

internal fun OkHttpClient.withMisIntermediate(context: Context): OkHttpClient {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val platform = factory.trustManagers.singleOrNull() as? X509ExtendedTrustManager ?: return this
    // GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1, published by DigiCert.
    val intermediate = context.resources.openRawResource(R.raw.mis_tls_intermediate).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    val trustManager = MisIntermediateTrustManager(platform, intermediate)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }
    val defaultHostnameVerifier = hostnameVerifier
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        // Keep OkHttp hostname verification and prevent cross-host HTTP/2 coalescing.
        .hostnameVerifier { host, session -> defaultHostnameVerifier.verify(host, session) }
        .build()
}

internal class MisIntermediateTrustManager(
    private val platform: X509ExtendedTrustManager,
    private val intermediate: X509Certificate,
) : X509ExtendedTrustManager() {
    private val platformExtensions by lazy { X509TrustManagerExtensions(platform) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
        checkServerChain(chain, (socket as? SSLSocket)?.handshakeSession?.peerHost) {
            platform.checkServerTrusted(it, authType, socket)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
        checkServerChain(chain, engine.handshakeSession?.peerHost ?: engine.peerHost) {
            platform.checkServerTrusted(it, authType, engine)
        }

    // Android's X509TrustManagerExtensions looks up this overload for OkHttp's chain cleaner.
    @Keep
    @Throws(CertificateException::class)
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> =
        checkServerChain(chain, host) { platformExtensions.checkServerTrusted(it, authType, host) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
        platform.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
        platform.checkClientTrusted(chain, authType, engine)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    private inline fun <T> checkServerChain(
        chain: Array<X509Certificate>,
        host: String?,
        check: (Array<X509Certificate>) -> T,
    ): T {
        try {
            return check(chain)
        } catch (failure: CertificateException) {
            if (!"mis.bjtu.edu.cn".equals(host, ignoreCase = true) || chain.size != 1 ||
                chain.single().issuerX500Principal != intermediate.subjectX500Principal
            ) {
                throw failure
            }
            // Supply chain material only. The same system trust manager must still accept the chain.
            return check(chain + intermediate)
        }
    }
}
