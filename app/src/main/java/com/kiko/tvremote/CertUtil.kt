package com.kiko.tvremote

import android.content.Context
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Generates and persists the client's self-signed identity certificate, and
 * builds an SSLContext that presents it to the TV. Mirrors
 * certificate_generator.py: RSA-2048, CN/SAN = client name, self-signed,
 * BasicConstraints(ca=true, pathlen=0), SHA256withRSA, valid 10 years.
 *
 * The TV never validates this certificate against a CA - it just remembers
 * it after a successful pairing, so keeping the same identity across runs is
 * what lets a previously paired TV recognize this app without pairing again.
 */
object CertUtil {
    private const val KEYSTORE_FILE = "tv_client_identity.p12"
    private const val KEYSTORE_PASSWORD = "tvremote"
    private const val ALIAS = "client"
    private const val CLIENT_NAME = "Kiko Remote"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun keystoreFile(context: Context): File = File(context.filesDir, KEYSTORE_FILE)

    /** Loads the persisted identity, generating and saving a new one if missing. */
    fun loadOrCreateIdentity(context: Context): KeyStore {
        val file = keystoreFile(context)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
            return ks
        }
        ks.load(null, null)
        val (privateKey, cert) = generateSelfSignedIdentity()
        ks.setKeyEntry(ALIAS, privateKey, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }
        return ks
    }

    private fun generateSelfSignedIdentity(): Pair<java.security.PrivateKey, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(10 * 365))
        val name = org.bouncycastle.asn1.x500.X500NameBuilder()
            .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.CN, CLIENT_NAME)
            .build()

        val builder = JcaX509v3CertificateBuilder(
            name, // issuer
            BigInteger.valueOf(1000), // serial number, matches reference
            now,
            notAfter,
            name, // subject (self-signed)
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(0))
        val altNames = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, CLIENT_NAME)))
        builder.addExtension(Extension.subjectAlternativeName, false, altNames)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        return keyPair.private to cert
    }

    /** Our own client certificate, needed for the pairing-code hash math. */
    fun clientCertificate(ks: KeyStore): X509Certificate =
        ks.getCertificate(ALIAS) as X509Certificate

    /**
     * SSLContext presenting our identity and accepting whatever certificate
     * the TV presents, matching ssl_context.check_hostname = False and
     * verify_mode = CERT_NONE in the reference implementation - the pairing
     * code, not certificate validation, is what proves this is the right TV.
     */
    fun buildSslContext(ks: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())

        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        return context
    }
}
