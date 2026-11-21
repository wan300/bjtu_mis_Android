package cn.edu.bjtu.mis.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.net.Socket
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

class MisIntermediateTrustManagerTest {
    private val certificates = CertificateFactory.getInstance("X.509")
    private val intermediate = File("src/main/res/raw/mis_tls_intermediate.pem").inputStream().use {
        certificates.generateCertificate(it) as X509Certificate
    }
    private val misChain = requireNotNull(javaClass.getResourceAsStream("/fixtures/tls/mis_leaf_and_root.pem")).use {
        certificates.generateCertificates(it).map { cert -> cert as X509Certificate }
    }
    private val leaf = misChain.first()
    private val root = misChain.last()

    @Test
    fun retriesMisWithIntermediateAndOriginalSystemRoot() {
        val platform = PkixTrustManager(root)
        val manager = MisIntermediateTrustManager(platform, intermediate)

        manager.checkServerTrusted(arrayOf(leaf), "RSA", engine("mis.bjtu.edu.cn"))

        assertEquals(listOf(listOf(leaf), listOf(leaf, intermediate)), platform.attemptedChains)
        assertEquals(listOf(root), manager.acceptedIssuers.toList())
    }

    @Test
    fun leavesValidServerChainAlone() {
        val platform = PkixTrustManager(root)
        val manager = MisIntermediateTrustManager(platform, intermediate)

        manager.checkServerTrusted(arrayOf(leaf, intermediate), "RSA", engine("mis.bjtu.edu.cn"))

        assertEquals(listOf(listOf(leaf, intermediate)), platform.attemptedChains)
    }

    @Test
    fun doesNotCompleteChainsForOtherHostsOrWithoutHostname() {
        val platform = PkixTrustManager(root)
        val manager = MisIntermediateTrustManager(platform, intermediate)

        for (otherHost in listOf("sso.bjtu.edu.cn", "mis.bjtu.edu.cn.example.com")) {
            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(arrayOf(leaf), "RSA", engine(otherHost))
            }
        }
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(leaf), "RSA") }
        assertEquals(List(3) { listOf(leaf) }, platform.attemptedChains)
    }

    @Test
    fun systemStillRejectsExpiredChainOrMissingTrustAnchor() {
        val expiredPlatform = PkixTrustManager(root, Instant.parse("2027-06-04T00:00:00Z"))
        val wrongRootPlatform = PkixTrustManager(leaf)
        for (platform in listOf(expiredPlatform, wrongRootPlatform)) {
            val manager = MisIntermediateTrustManager(platform, intermediate)

            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(arrayOf(leaf), "RSA", engine("mis.bjtu.edu.cn"))
            }
            assertEquals(listOf(listOf(leaf), listOf(leaf, intermediate)), platform.attemptedChains)
        }
    }

    private fun engine(host: String): SSLEngine = SSLContext.getDefault().createSSLEngine(host, 443)

    private class PkixTrustManager(
        private val root: X509Certificate,
        private val validationTime: Instant = Instant.parse("2026-11-21T00:00:00Z"),
    ) : X509ExtendedTrustManager() {
        val attemptedChains = mutableListOf<List<X509Certificate>>()

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            attemptedChains.add(chain.toList())
            val path = CertificateFactory.getInstance("X.509").generateCertPath(chain.toList())
            val params = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
                date = Date.from(validationTime)
                isRevocationEnabled = false
            }
            try {
                CertPathValidator.getInstance("PKIX").validate(path, params)
            } catch (error: CertPathValidatorException) {
                throw CertificateException(error)
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit = error("Unexpected client check")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket): Unit = error("Unexpected client check")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine): Unit = error("Unexpected client check")

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(root)
    }
}
