package com.kiko.tvremote

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

enum class Phase { CONNECT, PAIR, REMOTE }

data class UiState(
    val phase: Phase = Phase.CONNECT,
    val host: String = "",
    val message: String = "Enter your TV's IP address",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val imeMode: Boolean = false,
    val gestureMode: Boolean = false,
)

/**
 * Owns the TV connection so it survives configuration changes, serialises
 * every outgoing action through one queue (so key order is always preserved)
 * and transparently reconnects when the link has dropped - e.g. after the
 * app was in the background and the socket died.
 */
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("tvremote", Context.MODE_PRIVATE)
    private val connection = TvConnection(CertUtil.loadOrCreateIdentity(app))

    private val _state = MutableStateFlow(UiState(host = prefs.getString("host", "") ?: ""))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pairingSession: TvConnection.PairingSession? = null
    private val connectMutex = Mutex()
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        connection.onDisconnected = {
            _state.update {
                if (it.phase == Phase.REMOTE) it.copy(connected = false, message = "Connection lost - reconnects on next press")
                else it.copy(connected = false)
            }
        }

        viewModelScope.launch {
            for (job in queue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Error: ${e.message}") }
                }
            }
        }

        if (_state.value.host.isNotEmpty() && prefs.getBoolean("paired", false)) {
            viewModelScope.launch { connectNow() }
        }
    }

    // ---------- UI actions ----------

    fun setHost(host: String) {
        _state.update { it.copy(host = host) }
    }

    fun connect() {
        val host = _state.value.host.trim()
        if (host.isEmpty()) return
        prefs.edit().putString("host", host).apply()
        _state.update { it.copy(host = host) }
        viewModelScope.launch { connectNow() }
    }

    /** Called when the app comes back to the foreground. */
    fun onForeground() {
        val s = _state.value
        if (s.phase == Phase.REMOTE && !connection.connected) {
            viewModelScope.launch { connectNow() }
        }
    }

    fun reconnect() {
        viewModelScope.launch { connectNow() }
    }

    fun changeTv() {
        connection.disconnect()
        pairingSession = null
        _state.update { it.copy(phase = Phase.CONNECT, connected = false, busy = false, message = "Enter your TV's IP address") }
    }

    fun submitCode(code: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Pairing...") }
            try {
                val session = pairingSession ?: throw PairingFailed("No pairing in progress - go back and connect again")
                session.finishPairing(code.trim().uppercase())
                pairingSession = null
                prefs.edit().putBoolean("paired", true).apply()
                _state.update { it.copy(message = "Paired - connecting...") }
                connectNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Pairing failed: ${e.message}") }
            }
        }
    }

    fun toggleGesture() {
        _state.update { it.copy(gestureMode = !it.gestureMode) }
    }

    fun toggleIme() {
        val newMode = !_state.value.imeMode
        _state.update { it.copy(imeMode = newMode, message = "Switching mode...") }
        viewModelScope.launch {
            connection.disconnect()
            connectNow()
        }
    }

    /** Sends a key. Safe to call from any gesture callback; order is preserved. */
    private val holdStartedAt = HashMap<String, Long>()

    /**
     * Press-down of a key the TV only honours as a held key (volume on some
     * sets ignores quick SHORT presses). Pair with [holdEnd].
     */
    fun holdStart(name: String) {
        queue.trySend {
            holdStartedAt[name] = System.currentTimeMillis()
            withReconnect { connection.sendKey(name, "START_LONG") }
        }
    }

    /**
     * Release of [holdStart]. Waits until the key has been down at least
     * [minHoldMs] first, so a quick tap still counts as one step.
     */
    fun holdEnd(name: String, minHoldMs: Long = 120L) {
        queue.trySend {
            val started = holdStartedAt.remove(name) ?: System.currentTimeMillis()
            val wait = minHoldMs - (System.currentTimeMillis() - started)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            withReconnect { connection.sendKey(name, "END_LONG") }
        }
    }

    fun key(name: String, direction: String = "SHORT") {
        queue.trySend { withReconnect { connection.sendKey(name, direction) } }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        queue.trySend { withReconnect { connection.sendText(text) } }
    }

    fun clearText() {
        queue.trySend {
            withReconnect { repeat(40) { connection.sendKey("DEL") } }
            _state.update { it.copy(message = "Cleared (sent DEL x40)") }
        }
    }

    // ---------- Connection handling ----------

    private suspend fun withReconnect(block: suspend () -> Unit) {
        if (!connection.connected && !connectNow()) return
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Stale socket: reconnect once and retry; a second failure surfaces as an error.
            connection.disconnect()
            if (connectNow()) block()
        }
    }

    private suspend fun connectNow(): Boolean = connectMutex.withLock {
        if (connection.connected) return@withLock true
        val s = _state.value
        _state.update { it.copy(busy = true, message = "Connecting...") }
        try {
            connection.connect(s.host, s.imeMode)
            prefs.edit().putBoolean("paired", true).apply()
            _state.update { it.copy(phase = Phase.REMOTE, connected = true, busy = false, message = "Connected") }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleConnectError(e)
            false
        }
    }

    private suspend fun handleConnectError(e: Exception) {
        connection.disconnect()
        val host = _state.value.host
        val paired = prefs.getBoolean("paired", false)
        when {
            e is ConnectException -> fail("Can't reach the TV at $host (${e.message})")
            e is NoRouteToHostException -> fail("No route to $host - check the IP and that you're on the same network")
            e is SocketTimeoutException -> fail("The TV at $host didn't respond - is it on?")
            looksUnpaired(e, paired) -> beginPairing()
            else -> fail("Connection failed: ${e.message}")
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(connected = false, busy = false, message = message) }
    }

    /**
     * The TV rejecting our client certificate shows up wrapped in different
     * exception types (protobuf wraps TLS read errors), so walk the cause chain.
     */
    private fun looksUnpaired(e: Throwable, paired: Boolean): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SSLException) return true
            val m = t.message ?: ""
            if (m.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) || m.contains("SSL")) return true
            // Plain resets/closed sockets only mean "unpaired" if we've never paired.
            if (!paired && (t is SocketException || t is NotConnected)) return true
            t = t.cause
        }
        return false
    }

    private suspend fun beginPairing() {
        connection.disconnect()
        _state.update { it.copy(phase = Phase.PAIR, connected = false, busy = true, message = "Pairing required - requesting code...") }
        try {
            pairingSession = connection.startPairing(_state.value.host)
            _state.update { it.copy(busy = false, message = "Enter the code shown on the TV") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = Phase.CONNECT, busy = false, message = "Pairing failed: ${e.message}") }
        }
    }

    override fun onCleared() {
        connection.onDisconnected = null
        connection.disconnect()
        queue.close()
    }
}
