package com.tricoredb.kt

import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An optional protocol capability, negotiated in the HELLO handshake. */
public enum class Feature(
    /** The bit this capability occupies in the feature bitmap. */
    public val bit: Long,
) {
    /** The server echoes a request's correlation id into its logs, audit trail and cancel registry. */
    CORRELATION_ID(1L shl 0),

    /** The server binds `?` placeholders from a typed `params` array. */
    SERVER_PARAMS(1L shl 1),

    /** The server holds a transaction open across requests on one connection. */
    SESSION_TXN(1L shl 2);

    /** Helpers over feature bitmaps. */
    public companion object {
        /** Every bit this SDK knows how to use. */
        public val ALL: Long = entries.fold(0L) { acc, f -> acc or f.bit }

        /** The features named by [mask]. */
        public fun of(mask: Long): Set<Feature> = entries.filterTo(LinkedHashSet()) { mask and it.bit != 0L }
    }
}

/**
 * How to connect to a TriCoreDB server.
 *
 * @property host server host name or address.
 * @property port the native protocol port.
 * @property user user to authenticate as; `null` skips AUTH (only useful against a server with auth off).
 * @property secret the user's password.
 * @property database database every request targets unless an operation says otherwise.
 * @property clientName the client name announced in HELLO.
 * @property connectTimeout deadline for TCP connect, TLS, HELLO and AUTH together.
 * @property readTimeout client-side deadline for one reply, or `null` to wait as long as the statement runs.
 *   Passing it closes the connection (see [TriCoreTimeoutException]); prefer [requestTimeout].
 * @property requestTimeout a server-side deadline sent as `options.timeout_ms`, which stops the work on the server.
 * @property tls TLS settings, or `null` for plain TCP.
 * @property features the capabilities to ask for; the server grants the intersection.
 */
public data class TriCoreConfig(
    val host: String = "127.0.0.1",
    val port: Int = Protocol.DEFAULT_PORT,
    val user: String? = null,
    val secret: String = "",
    val database: String = "main",
    val clientName: String = "tricoredb-kotlin/$SDK_VERSION",
    val connectTimeout: Duration = 10.seconds,
    val readTimeout: Duration? = null,
    val requestTimeout: Duration? = null,
    val tls: TlsOptions? = null,
    val features: Long = Feature.ALL,
) {
    init {
        require(port in 1..65535) { "port $port is outside 1..65535" }
        require(host.isNotBlank()) { "host must not be blank" }
    }

    /** Never prints the secret. */
    override fun toString(): String =
        "TriCoreConfig(host=$host, port=$port, user=$user, secret=***, database=$database, clientName=$clientName, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, requestTimeout=$requestTimeout, tls=$tls, features=$features)"
}

/**
 * TLS settings, implemented with JSSE.
 *
 * Verification is on by default: the server certificate must chain to [caFile] (or, when no CA file is given,
 * to the JVM's default trust store) and must name [serverName] (defaults to the connection host).
 *
 * @property caFile PEM CA bundle that verifies the server.
 * @property serverName the name expected in the server certificate and sent as SNI.
 * @property clientCertFile PEM client certificate chain for mutual TLS.
 * @property clientKeyFile PEM PKCS#8 (`BEGIN PRIVATE KEY`) key for [clientCertFile].
 * @property sslContext a fully configured context to use instead of the files above.
 * @property dangerAcceptInvalidCerts **development only**: skip certificate and host name verification.
 */
public data class TlsOptions(
    val caFile: File? = null,
    val serverName: String? = null,
    val clientCertFile: File? = null,
    val clientKeyFile: File? = null,
    val sslContext: SSLContext? = null,
    val dangerAcceptInvalidCerts: Boolean = false,
) {
    init {
        require((clientCertFile == null) == (clientKeyFile == null)) {
            "clientCertFile and clientKeyFile must be given together for mutual TLS"
        }
    }

    internal fun buildContext(): SSLContext {
        sslContext?.let { return it }
        val trustManagers: Array<TrustManager>? = when {
            dangerAcceptInvalidCerts -> arrayOf(AcceptAll)
            caFile != null -> {
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                readCertificates(caFile).forEachIndexed { i, c -> ks.setCertificateEntry("ca-$i", c) }
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers
            }
            else -> null
        }
        val keyManagers = if (clientCertFile != null && clientKeyFile != null) {
            val chain = readCertificates(clientCertFile)
            val key = readPrivateKey(clientKeyFile)
            val pass = CharArray(0)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            ks.setKeyEntry("client", key, pass, chain.toTypedArray())
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }.keyManagers
        } else {
            null
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
    }

    private fun readCertificates(file: File): List<X509Certificate> {
        val certs = try {
            file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        } catch (e: Exception) {
            throw IllegalArgumentException("tls: cannot read certificates from `${file.path}`: ${e.message}", e)
        }
        require(certs.isNotEmpty()) { "tls: `${file.path}` holds no certificate" }
        return certs.map { it as X509Certificate }
    }

    private fun readPrivateKey(file: File): PrivateKey {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            throw IllegalArgumentException("tls: cannot read key file `${file.path}`: ${e.message}", e)
        }
        val m = Regex("-----BEGIN PRIVATE KEY-----([^-]+)-----END PRIVATE KEY-----").find(text)
            ?: throw IllegalArgumentException(
                "tls: `${file.path}` is not a PKCS#8 PEM key (BEGIN PRIVATE KEY); convert it with `openssl pkcs8 -topk8 -nocrypt`"
            )
        val der = Base64.getMimeDecoder().decode(m.groupValues[1])
        val spec = PKCS8EncodedKeySpec(der)
        for (alg in listOf("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec)
            } catch (_: Exception) {
            }
        }
        throw IllegalArgumentException("tls: `${file.path}` holds a key that is not RSA, EC or Ed25519")
    }

    private object AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
