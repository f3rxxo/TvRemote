package com.kiko.tvremote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Hold time before a D-pad button switches from a short press to a long press. */
const val HOLD_MS = 80L

/** Gesture pad keeps a longer hold: 80 ms would turn every normal tap into a long-press OK. */
const val GESTURE_HOLD_MS = 400L

/** Two taps on the gesture pad within this window send BACK instead of OK. */
const val DOUBLE_TAP_MS = 250L

@Composable
fun TvRemoteApp(vm: TvViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (st.phase) {
                Phase.CONNECT -> ConnectScreen(st, vm)
                Phase.PAIR -> PairScreen(st, vm)
                Phase.REMOTE -> RemoteScreen(st, vm)
            }
        }
    }
}

// ---------------------------------------------------------------- connect / pair

@Composable
private fun ConnectScreen(st: UiState, vm: TvViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TV Remote", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Control your Google TV over Wi-Fi",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = st.host,
                    onValueChange = vm::setHost,
                    label = { Text("TV IP address") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = vm::connect,
                    enabled = !st.busy && st.host.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (st.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            st.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PairScreen(st: UiState, vm: TvViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pair with TV", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            st.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isLetterOrDigit() }.take(6).uppercase() },
                    label = { Text("6-character code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.submitCode(code) },
                    enabled = !st.busy && code.length == 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (st.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Pair")
                    }
                }
                TextButton(onClick = vm::changeTv, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- remote

@Composable
private fun RemoteScreen(st: UiState, vm: TvViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header: status + change TV
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = cs.surfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { if (!st.connected) vm.reconnect() },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (st.connected) Color(0xFF4CAF50) else Color(0xFFE57373), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (st.busy) "Connecting..." else if (st.connected) "Connected" else "Tap to reconnect",
                        fontSize = 14.sp,
                    )
                }
            }
            TextButton(onClick = vm::changeTv) { Text("Change TV") }
        }

        // Mode toggles
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = st.gestureMode,
                onClick = vm::toggleGesture,
                label = { Text(if (st.gestureMode) "Gesture pad" else "D-pad") },
            )
            FilterChip(
                selected = st.imeMode,
                onClick = vm::toggleIme,
                label = { Text(if (st.imeMode) "IME typing" else "Native keyboard") },
            )
        }

        // Back / Home / Keyboard
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PressKey(
                Modifier
                    .weight(1f)
                    .height(56.dp),
                onPress = { vm.key("BACK") },
            ) { IconOnly(Icons.AutoMirrored.Filled.ArrowBack) }
            PressKey(
                Modifier
                    .weight(1f)
                    .height(56.dp),
                onPress = { vm.key("HOME") },
            ) { IconOnly(Icons.Filled.Home) }
            PressKey(
                Modifier
                    .weight(1f)
                    .height(56.dp),
                container = if (showKeyboard) cs.primaryContainer else null,
                onPress = { showKeyboard = !showKeyboard },
            ) { Text("⌨", fontSize = 26.sp) }
        }

        // Text input, only while the keyboard button has it open
        AnimatedVisibility(visible = showKeyboard) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text to send to TV") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.sendText(text) }, modifier = Modifier.weight(1f)) { Text("Send") }
                        FilledTonalButton(onClick = { vm.key("ENTER") }) { Text("Enter") }
                        FilledTonalButton(onClick = vm::clearText) { Text("Clear") }
                        PressKey(Modifier.size(width = 56.dp, height = 40.dp), repeat = true, onPress = { vm.key("DEL") }) { Text("⌫") }
                    }
                    TextButton(onClick = { showKeyboard = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Hide")
                    }
                }
            }
        }

        // Navigation area
        if (st.gestureMode) {
            GesturePad(vm, Modifier.fillMaxWidth().height(if (showKeyboard) 300.dp else 440.dp))
        } else {
            DPad(vm)
        }

        // Play/pause + volume (press immediately, hold to repeat)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Tap = one step; hold = the TV's own key repeat (START_LONG/END_LONG),
            // which it handles far more reliably than a stream of separate presses.
            VolumeKey(Modifier.weight(1f).height(64.dp), "VOLUME_DOWN", vm) { Text("Vol −", fontWeight = FontWeight.Medium) }
            PressKey(Modifier.weight(1f).height(64.dp), onPress = { vm.key("VOLUME_MUTE") }) { Text("Mute", fontWeight = FontWeight.Medium) }
            VolumeKey(Modifier.weight(1f).height(64.dp), "VOLUME_UP", vm) { Text("Vol +", fontWeight = FontWeight.Medium) }
        }
        PressKey(Modifier.fillMaxWidth().height(64.dp), onPress = { vm.key("MEDIA_PLAY_PAUSE") }) { Text("⏯", fontSize = 26.sp) }

        Text(
            st.message,
            color = cs.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun IconOnly(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
}

@Composable
private fun DPad(vm: TvViewModel) {
    val cell = Modifier.aspectRatio(1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(cell.weight(1f))
            HoldKey(cell.weight(1f), "DPAD_UP", vm) { IconOnly(Icons.Filled.KeyboardArrowUp) }
            Spacer(cell.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldKey(cell.weight(1f), "DPAD_LEFT", vm) { IconOnly(Icons.Filled.KeyboardArrowLeft) }
            HoldKey(cell.weight(1f), "DPAD_CENTER", vm, container = MaterialTheme.colorScheme.primaryContainer) {
                Text("OK", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            HoldKey(cell.weight(1f), "DPAD_RIGHT", vm) { IconOnly(Icons.Filled.KeyboardArrowRight) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(cell.weight(1f))
            HoldKey(cell.weight(1f), "DPAD_DOWN", vm) { IconOnly(Icons.Filled.KeyboardArrowDown) }
            Spacer(cell.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- touch widgets

@Composable
private fun KeySurface(
    modifier: Modifier,
    pressed: Boolean,
    container: Color?,
    gesture: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = if (pressed) cs.primary else (container ?: cs.secondaryContainer),
        contentColor = if (pressed) cs.onPrimary else cs.onSecondaryContainer,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(gesture),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/**
 * Fires on touch-down (no waiting for release, so no missed taps). With
 * [repeat] it keeps firing while held - used for volume and backspace.
 */
@Composable
private fun PressKey(
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    container: Color? = null,
    onPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)

    val gesture = Modifier.pointerInput(repeat) {
        awaitEachGesture {
            awaitFirstDown()
            pressed = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            press()
            if (repeat) {
                var wait = 450L
                while (true) {
                    val released = withTimeoutOrNull(wait) {
                        waitForUpOrCancellation()
                        true
                    }
                    if (released != null) break
                    press()
                    wait = 110L
                }
            } else {
                waitForUpOrCancellation()
            }
            pressed = false
        }
    }
    KeySurface(modifier, pressed, container, gesture, content)
}

/**
 * Volume key. This TV answers a held key reliably but drops or delays quick
 * single presses, so every touch is sent as a hold: down starts it, release
 * ends it (never sooner than a short minimum, so a tap is one step and a hold
 * keeps repeating on the TV's own timer).
 */
@Composable
private fun VolumeKey(
    modifier: Modifier,
    keyName: String,
    vm: TvViewModel,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val gesture = Modifier.pointerInput(keyName) {
        awaitEachGesture {
            awaitFirstDown()
            pressed = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.holdStart(keyName)
            waitForUpOrCancellation()
            vm.holdEnd(keyName)
            pressed = false
        }
    }
    KeySurface(modifier, pressed, null, gesture, content)
}

/**
 * D-pad key with press-and-hold: a quick tap sends a short press; holding
 * past [HOLD_MS] sends START_LONG and, on release, END_LONG.
 */
@Composable
private fun HoldKey(
    modifier: Modifier,
    keyName: String,
    vm: TvViewModel,
    container: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val gesture = Modifier.pointerInput(keyName) {
        awaitEachGesture {
            awaitFirstDown()
            pressed = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            var timedOut = true
            var up: Any? = null
            withTimeoutOrNull(HOLD_MS) {
                up = waitForUpOrCancellation()
                timedOut = false
            }
            if (timedOut) {
                vm.key(keyName, "START_LONG")
                waitForUpOrCancellation()
                vm.key(keyName, "END_LONG")
            } else if (up != null) {
                vm.key(keyName, "SHORT")
            }
            pressed = false
        }
    }
    KeySurface(modifier, pressed, container, gesture, content)
}

/**
 * Swipe = one D-pad press, tap = OK, double tap = BACK, hold = long OK.
 * A single tap is sent after [DOUBLE_TAP_MS] so a double tap can replace it.
 */
@Composable
private fun GesturePad(vm: TvViewModel, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val tapJob = remember { arrayOfNulls<Job>(1) }

    fun tap() {
        val pending = tapJob[0]
        if (pending != null && pending.isActive) {
            pending.cancel()
            tapJob[0] = null
            vm.key("BACK")
        } else {
            tapJob[0] = scope.launch {
                delay(DOUBLE_TAP_MS)
                vm.key("DPAD_CENTER")
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = if (pressed) cs.primaryContainer else cs.surfaceVariant,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val swipeMin = 30.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val start = down.position
                        var end = start
                        var state = 0 // 0 = held still, 1 = unused, 2 = lifted (tap), 3 = moving (swipe)

                        withTimeoutOrNull(GESTURE_HOLD_MS) {
                            while (true) {
                                val c = awaitPointerEvent().changes.first()
                                end = c.position
                                if (!c.pressed) {
                                    state = 2
                                    break
                                }
                                if ((c.position - start).getDistance() > slop) {
                                    c.consume()
                                    state = 3
                                    break
                                }
                            }
                        }

                        when (state) {
                            0 -> {
                                // Long OK until the finger lifts.
                                vm.key("DPAD_CENTER", "START_LONG")
                                while (true) {
                                    val c = awaitPointerEvent().changes.first()
                                    if (!c.pressed) break
                                }
                                vm.key("DPAD_CENTER", "END_LONG")
                            }
                            3 -> {
                                while (true) {
                                    val c = awaitPointerEvent().changes.first()
                                    end = c.position
                                    c.consume()
                                    if (!c.pressed) break
                                }
                                val dx = end.x - start.x
                                val dy = end.y - start.y
                                if (abs(dx) < swipeMin && abs(dy) < swipeMin) {
                                    tap()
                                } else if (abs(dx) > abs(dy)) {
                                    vm.key(if (dx > 0) "DPAD_RIGHT" else "DPAD_LEFT")
                                } else {
                                    vm.key(if (dy > 0) "DPAD_DOWN" else "DPAD_UP")
                                }
                            }
                            else -> tap()
                        }
                        pressed = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Swipe to move\nTap = OK  ·  Double tap = Back\nHold = long press OK",
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
