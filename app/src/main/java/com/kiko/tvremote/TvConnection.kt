package com.kiko.tvremote

import com.google.protobuf.ByteString
import com.kiko.tvremote.proto.Configuration
import com.kiko.tvremote.proto.ConfigurationAck
import com.kiko.tvremote.proto.Options
import com.kiko.tvremote.proto.OuterMessage
import com.kiko.tvremote.proto.PairingRequest
import com.kiko.tvremote.proto.RemoteDeviceInfo
import com.kiko.tvremote.proto.RemoteDirection
import com.kiko.tvremote.proto.RemoteEditInfo
import com.kiko.tvremote.proto.RemoteImeBatchEdit
import com.kiko.tvremote.proto.RemoteImeObject
import com.kiko.tvremote.proto.RemoteKeyCode
import com.kiko.tvremote.proto.RemoteKeyInject
import com.kiko.tvremote.proto.RemoteMessage
import com.kiko.tvremote.proto.RemoteConfigure
import com.kiko.tvremote.proto.RemotePingResponse
import com.kiko.tvremote.proto.RemoteSetActive
import com.kiko.tvremote.proto.Secret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingFailed(message: String) : Exception(message)
class NotConnected(message: String) : Exception(message)

/**
 * Talks the Android TV Remote Protocol v2 directly: pairing handshake on
 * port 6467, then key/text control on port 6466. Ported from the working
 * Python reference (androidtvremote2) message-for-message and byte-for-byte
 * for the pairing hash, rather than reverse-engineered independently.
 */
class TvConnection(private val keyStore: KeyStore) {
    companion object {
        private const val PAIR_PORT = 6467
        private const val REMOTE_PORT = 6466
        private const val CLIENT_NAME = "Kiko Remote"

        // Feature bitmask values, matching remote.py's Feature IntFlag.
        private const val FEATURE_PING = 1 shl 0
        private const val FEATURE_KEY = 1 shl 1
        private const val FEATURE_IME = 1 shl 2
        private const val FEATURE_POWER = 1 shl 5
        private const val FEATURE_VOLUME = 1 shl 6
        private const val FEATURE_APP_LINK = 1 shl 9
    }

    private val sslContext = CertUtil.buildSslContext(keyStore)

    private var remoteSocket: SSLSocket? = null
    private var remoteOut: OutputStream? = null
    private var readerJob: Job? = null
    private val writeMutex = Mutex()

    @Volatile var imeCounter: Int = 0
        private set
    @Volatile var imeFieldCounter: Int = 0
        private set
    @Volatile var connected: Boolean = false
        private set

    // ---------- Pairing (port 6467) ----------

    /**
     * Starts pairing: opens the pairing socket and negotiates up through
     * configuration_ack, at which point the TV displays the 6-digit code.
     * Keeps the socket open - call [finishPairing] with the code next.
     */
    suspend fun startPairing(host: String): PairingSession = withContext(Dispatchers.IO) {
        val socket = sslContext.socketFactory.createSocket(host, PAIR_PORT) as SSLSocket
        socket.startHandshake()
        val out = socket.outputStream
        val input = socket.inputStream

        fun send(builder: OuterMessage.Builder) {
            builder.setProtocolVersion(2).setStatus(OuterMessage.Status.STATUS_OK)
            builder.build().writeDelimitedTo(out)
            out.flush()
        }

        send(
            OuterMessage.newBuilder().setPairingRequest(
                PairingRequest.newBuilder().setClientName(CLIENT_NAME).setServiceName("atvremote"),
            ),
        )
        var msg = OuterMessage.parseDelimitedFrom(input) ?: throw PairingFailed("Connection closed by TV")
        if (!msg.hasPairingRequestAck()) throw PairingFailed("Unexpected reply: $msg")

        val hexEncoding = Options.Encoding.newBuilder()
            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
        send(
            OuterMessage.newBuilder().setOptions(
                Options.newBuilder()
                    .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
                    .addInputEncodings(hexEncoding),
            ),
        )
        msg = OuterMessage.parseDelimitedFrom(input) ?: throw PairingFailed("Connection closed by TV")
        if (!msg.hasOptions()) throw PairingFailed("Unexpected reply: $msg")

        send(
            OuterMessage.newBuilder().setConfiguration(
                Configuration.newBuilder()
                    .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
                    .setEncoding(hexEncoding),
            ),
        )
        msg = OuterMessage.parseDelimitedFrom(input) ?: throw PairingFailed("Connection closed by TV")
        if (!msg.hasConfigurationAck()) throw PairingFailed("Unexpected reply: $msg")

        PairingSession(socket, out, input)
    }

    /** Holds the still-open pairing socket between [startPairing] and [finishPairing]. */
    inner class PairingSession(
        private val socket: SSLSocket,
        private val out: OutputStream,
        private val input: InputStream,
    ) {
        /**
         * Completes pairing using the 6-character hex code shown on the TV.
         * @throws PairingFailed if the code doesn't match or the TV rejects it.
         */
        suspend fun finishPairing(pairingCode: String) = withContext(Dispatchers.IO) {
            if (pairingCode.length != 6) throw PairingFailed("Code must be 6 hex characters")

            val clientCert = CertUtil.clientCertificate(keyStore)
            val serverCert = socket.session.peerCertificates[0] as X509Certificate
            val secretBytes = computeSecret(clientCert, serverCert, pairingCode)

            val msg = OuterMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(OuterMessage.Status.STATUS_OK)
                .setSecret(Secret.newBuilder().setSecret(ByteString.copyFrom(secretBytes)))
                .build()
            msg.writeDelimitedTo(out)
            out.flush()

            val reply = OuterMessage.parseDelimitedFrom(input) ?: throw PairingFailed("Connection closed by TV")
            socket.close()
            if (!reply.hasSecretAck()) throw PairingFailed("TV rejected the pairing code")
        }
    }

    /**
     * Ported exactly from pairing.py's hash computation: SHA-256 over the
     * client and server RSA moduli/exponents (as minimal hex byte strings,
     * with the exponent forced to an even-length hex string the same way
     * the reference does) plus the last 4 hex characters of the pairing
     * code. The first byte of the digest must match the code's first byte.
     */
    private fun computeSecret(clientCert: X509Certificate, serverCert: X509Certificate, pairingCode: String): ByteArray {
        val (clientN, clientE) = modulusAndExponent(clientCert)
        val (serverN, serverE) = modulusAndExponent(serverCert)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(hexToBytes(clientN.toString(16).uppercase()))
        digest.update(hexToBytes("0" + clientE.toString(16).uppercase()))
        digest.update(hexToBytes(serverN.toString(16).uppercase()))
        digest.update(hexToBytes("0" + serverE.toString(16).uppercase()))
        digest.update(hexToBytes(pairingCode.substring(2)))
        val result = digest.digest()

        val expectedFirstByte = pairingCode.substring(0, 2).toInt(16)
        if ((result[0].toInt() and 0xFF) != expectedFirstByte) {
            throw PairingFailed("Code doesn't match this TV - check you typed it correctly")
        }
        return result
    }

    private fun modulusAndExponent(cert: X509Certificate): Pair<BigInteger, BigInteger> {
        val pub = cert.publicKey as RSAPublicKey
        return pub.modulus to pub.publicExponent
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd-length hex string: $hex" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    // ---------- Remote control (port 6466) ----------

    /**
     * Connects to the TV's control port and completes the
     * configure/set_active/start handshake. [enableIme] controls whether we
     * declare IME support to the TV - see MainActivity for what that changes.
     * Throws if pairing is needed (TLS handshake fails without a trusted
     * client cert) or the TV doesn't respond in time.
     */
    suspend fun connect(host: String, enableIme: Boolean): Unit = withContext(Dispatchers.IO) {
        val socket = sslContext.socketFactory.createSocket(host, REMOTE_PORT) as SSLSocket
        socket.startHandshake()
        remoteSocket = socket
        remoteOut = socket.outputStream
        val input = socket.inputStream

        var activeFeatures = FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK or
            (if (enableIme) FEATURE_IME else 0)

        var msg = RemoteMessage.parseDelimitedFrom(input) ?: throw NotConnected("Connection closed by TV")
        if (!msg.hasRemoteConfigure()) throw NotConnected("Unexpected first message: $msg")
        activeFeatures = activeFeatures and msg.remoteConfigure.code1
        sendRemoteBlocking(
            RemoteMessage.newBuilder().setRemoteConfigure(
                RemoteConfigure.newBuilder()
                    .setCode1(activeFeatures)
                    .setDeviceInfo(
                        RemoteDeviceInfo.newBuilder()
                            .setUnknown1(1)
                            .setUnknown2("1")
                            .setPackageName("atvremote")
                            .setAppVersion("1.0.0"),
                    ),
            ).build(),
        )

        msg = RemoteMessage.parseDelimitedFrom(input) ?: throw NotConnected("Connection closed by TV")
        if (!msg.hasRemoteSetActive()) throw NotConnected("Unexpected message: $msg")
        sendRemoteBlocking(
            RemoteMessage.newBuilder()
                .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(activeFeatures))
                .build(),
        )

        msg = RemoteMessage.parseDelimitedFrom(input) ?: throw NotConnected("Connection closed by TV")
        if (!msg.hasRemoteStart()) throw NotConnected("Unexpected message: $msg")

        connected = true
        readerJob = CoroutineScope(Dispatchers.IO).launch { readLoop(input) }
    }

    private suspend fun readLoop(input: InputStream) {
        try {
            while (true) {
                val msg = RemoteMessage.parseDelimitedFrom(input) ?: break
                when {
                    msg.hasRemotePingRequest() -> sendRemote(
                        RemoteMessage.newBuilder().setRemotePingResponse(
                            RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1),
                        ).build(),
                    )
                    msg.hasRemoteImeBatchEdit() -> {
                        imeCounter = msg.remoteImeBatchEdit.imeCounter
                        imeFieldCounter = msg.remoteImeBatchEdit.fieldCounter
                    }
                    // remote_start/volume/etc. intentionally ignored - not used by this app.
                }
            }
        } catch (_: Exception) {
            // Socket closed or TV went away; connected flag below reflects it.
        } finally {
            connected = false
        }
    }

    private fun sendRemoteBlocking(msg: RemoteMessage) {
        val out = remoteOut ?: throw NotConnected("Not connected")
        msg.writeDelimitedTo(out)
        out.flush()
    }

    private suspend fun sendRemote(msg: RemoteMessage) {
        val out = remoteOut ?: return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                msg.writeDelimitedTo(out)
                out.flush()
            }
        }
    }

    /** Sends a key press. [direction] is "SHORT", "START_LONG", or "END_LONG". */
    suspend fun sendKey(keyName: String, direction: String = "SHORT") {
        val keyCode = RemoteKeyCode.valueOf("KEYCODE_$keyName")
        val dir = RemoteDirection.valueOf(direction.uppercase())
        sendRemote(
            RemoteMessage.newBuilder().setRemoteKeyInject(
                RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(dir),
            ).build(),
        )
    }

    /**
     * One raw text-replace call, exactly matching send_text() in remote.py -
     * no clearing or diffing here, that logic lives in the caller if needed.
     */
    suspend fun sendText(text: String) {
        require(text.isNotEmpty()) { "Text cannot be empty" }
        val paramValue = text.length - 1
        val imeObject = RemoteImeObject.newBuilder().setStart(paramValue).setEnd(paramValue).setValue(text)
        val editInfo = RemoteEditInfo.newBuilder().setInsert(1).setTextFieldStatus(imeObject)
        val batch = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(imeCounter)
            .setFieldCounter(imeFieldCounter)
            .addEditInfo(editInfo)
        sendRemote(RemoteMessage.newBuilder().setRemoteImeBatchEdit(batch).build())
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        connected = false
        try {
            remoteSocket?.close()
        } catch (_: Exception) {
        }
        remoteSocket = null
        remoteOut = null
    }
}
