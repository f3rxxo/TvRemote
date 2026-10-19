package com.kiko.tvremote

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import javax.net.ssl.SSLException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val longPressMs = 400L
    private val swipeMinPx get() = 30 * resources.displayMetrics.density

    private lateinit var connection: TvConnection
    private var pairingSession: TvConnection.PairingSession? = null
    private var host: String = ""
    private var imeMode = false
    private var gestureMode = false

    private lateinit var msg: TextView
    private lateinit var connectSection: View
    private lateinit var pairSection: View
    private lateinit var remoteSection: View
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var modeBtn: Button
    private lateinit var padBtn: Button
    private lateinit var dpadArrows: View
    private lateinit var gesturePad: View
    private lateinit var gestureHint: View
    private lateinit var txt: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val keyStore = CertUtil.loadOrCreateIdentity(this)
        connection = TvConnection(keyStore)

        msg = findViewById(R.id.msg)
        connectSection = findViewById(R.id.connectSection)
        pairSection = findViewById(R.id.pairSection)
        remoteSection = findViewById(R.id.remoteSection)
        hostInput = findViewById(R.id.hostInput)
        codeInput = findViewById(R.id.codeInput)
        modeBtn = findViewById(R.id.modeBtn)
        padBtn = findViewById(R.id.padBtn)
        dpadArrows = findViewById(R.id.dpadArrows)
        gesturePad = findViewById(R.id.gesturePad)
        gestureHint = findViewById(R.id.gestureHint)
        txt = findViewById(R.id.txt)

        val prefs = getSharedPreferences("tvremote", MODE_PRIVATE)
        hostInput.setText(prefs.getString("host", ""))

        findViewById<Button>(R.id.connectBtn).setOnClickListener {
            val h = hostInput.text.toString().trim()
            if (h.isEmpty()) return@setOnClickListener
            host = h
            prefs.edit().putString("host", host).apply()
            attemptConnect()
        }

        findViewById<Button>(R.id.pairBtn).setOnClickListener {
            val code = codeInput.text.toString().trim().uppercase()
            lifecycleScope.launch {
                try {
                    msg.text = "Pairing..."
                    pairingSession?.finishPairing(code)
                    msg.text = "Paired - connecting..."
                    attemptConnect()
                } catch (e: Exception) {
                    msg.text = "Pairing failed: ${e.message}"
                }
            }
        }

        setupSimpleButton(R.id.powerBtn, "POWER")
        setupSimpleButton(R.id.homeBtn, "HOME")
        setupSimpleButton(R.id.backBtn, "BACK")
        setupSimpleButton(R.id.upBtn, "DPAD_UP")
        setupSimpleButton(R.id.downBtn, "DPAD_DOWN")
        setupSimpleButton(R.id.volDownBtn, "VOLUME_DOWN")
        setupSimpleButton(R.id.muteBtn, "VOLUME_MUTE")
        setupSimpleButton(R.id.volUpBtn, "VOLUME_UP")
        setupSimpleButton(R.id.rewindBtn, "MEDIA_REWIND")
        setupSimpleButton(R.id.playPauseBtn, "MEDIA_PLAY_PAUSE")
        setupSimpleButton(R.id.forwardBtn, "MEDIA_FAST_FORWARD")
        setupSimpleButton(R.id.delBtn, "DEL")

        setupHoldButton(findViewById(R.id.okBtn), "DPAD_CENTER")
        setupHoldButton(findViewById(R.id.leftBtn), "DPAD_LEFT")
        setupHoldButton(findViewById(R.id.rightBtn), "DPAD_RIGHT")
        setupGesturePad(gesturePad)

        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val text = txt.text.toString()
            if (text.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                try {
                    connection.sendText(text)
                } catch (e: Exception) {
                    msg.text = "Error: ${e.message}"
                }
            }
        }

        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            lifecycleScope.launch {
                try {
                    repeat(40) { connection.sendKey("DEL") }
                    msg.text = "cleared (sent DEL x40)"
                } catch (e: Exception) {
                    msg.text = "Error: ${e.message}"
                }
            }
        }

        modeBtn.setOnClickListener {
            imeMode = !imeMode
            applyModeUi()
            lifecycleScope.launch {
                msg.text = "switching mode..."
                connection.disconnect()
                try {
                    connection.connect(host, enableIme = imeMode)
                    msg.text = "connected"
                } catch (e: Exception) {
                    msg.text = "Mode switch failed: ${e.message}"
                }
            }
        }

        padBtn.setOnClickListener {
            gestureMode = !gestureMode
            applyPadUi()
        }
    }

    private fun attemptConnect() {
        lifecycleScope.launch {
            msg.text = "Connecting..."
            try {
                connection.connect(host, enableIme = imeMode)
                onConnected()
            } catch (e: ConnectException) {
                msg.text = "Can't reach the TV at $host (${e.message})"
            } catch (e: NoRouteToHostException) {
                msg.text = "No route to $host - check the IP and that you're on the same network"
            } catch (e: SSLException) {
                // TLS rejected our client certificate: this TV hasn't paired with us yet.
                startPairingFlow()
            } catch (e: NotConnected) {
                // TV accepted the socket then closed it before sending its configure
                // message, which is what an unpaired client looks like on TLS 1.3.
                startPairingFlow()
            } catch (e: SocketException) {
                // Connection reset right after the handshake: same unpaired case.
                startPairingFlow()
            } catch (e: Exception) {
                msg.text = "Connection failed: ${e.message}"
            }
        }
    }

    private fun startPairingFlow() {
        lifecycleScope.launch {
            try {
                connection.disconnect() // drop any half-open control socket first
                msg.text = "Pairing required - requesting code..."
                pairingSession = connection.startPairing(host)
                pairSection.visibility = View.VISIBLE
                msg.text = "Enter the code shown on the TV"
            } catch (e: Exception) {
                msg.text = "Pairing failed: ${e.message}"
            }
        }
    }

    private fun onConnected() {
        connectSection.visibility = View.GONE
        remoteSection.visibility = View.VISIBLE
        msg.text = "connected"
    }

    private fun applyModeUi() {
        modeBtn.text = "Mode: " + if (imeMode) "IME typing" else "Native keyboard"
        val color = if (imeMode) R.color.toggle_on else R.color.toggle_off
        modeBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun applyPadUi() {
        padBtn.text = "Pad: " + if (gestureMode) "Gesture" else "D-pad"
        val color = if (gestureMode) R.color.toggle_on else R.color.toggle_off
        padBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        dpadArrows.visibility = if (gestureMode) View.GONE else View.VISIBLE
        gesturePad.visibility = if (gestureMode) View.VISIBLE else View.GONE
        gestureHint.visibility = if (gestureMode) View.VISIBLE else View.GONE
    }

    private fun sendKey(name: String, direction: String = "SHORT") {
        lifecycleScope.launch {
            try {
                connection.sendKey(name, direction)
            } catch (e: Exception) {
                msg.text = "Error: ${e.message}"
            }
        }
    }

    private fun setupSimpleButton(id: Int, keyName: String) {
        findViewById<Button>(id).setOnClickListener { sendKey(keyName) }
    }

    /** Press-and-hold: quick tap sends SHORT, holding past [longPressMs] sends START_LONG/END_LONG. */
    private fun setupHoldButton(view: View, keyName: String) {
        var isLong = false
        var pending: Runnable? = null
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLong = false
                    val r = Runnable {
                        isLong = true
                        sendKey(keyName, "START_LONG")
                    }
                    pending = r
                    handler.postDelayed(r, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pending?.let { handler.removeCallbacks(it) }
                    if (isLong) sendKey(keyName, "END_LONG") else sendKey(keyName, "SHORT")
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    /** Swipe = one D-pad press, tap = OK, hold = OK long-press (Nuvio submenu). */
    private fun setupGesturePad(view: View) {
        var startX = 0f
        var startY = 0f
        var isLong = false
        var pending: Runnable? = null
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isLong = false
                    val r = Runnable {
                        isLong = true
                        sendKey("DPAD_CENTER", "START_LONG")
                    }
                    pending = r
                    handler.postDelayed(r, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pending?.let { handler.removeCallbacks(it) }
                    if (isLong) {
                        sendKey("DPAD_CENTER", "END_LONG")
                        return@setOnTouchListener true
                    }
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (abs(dx) < swipeMinPx && abs(dy) < swipeMinPx) {
                        sendKey("DPAD_CENTER")
                    } else if (abs(dx) > abs(dy)) {
                        sendKey(if (dx > 0) "DPAD_RIGHT" else "DPAD_LEFT")
                    } else {
                        sendKey(if (dy > 0) "DPAD_DOWN" else "DPAD_UP")
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.disconnect()
    }
}
