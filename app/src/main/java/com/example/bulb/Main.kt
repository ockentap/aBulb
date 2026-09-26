package com.example.bulb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crash = files?.resolve("last-crash.txt")?.takeIf { it.exists() }?.readText()
        files?.resolve("last-crash.txt")?.delete()
        setContent { App(crash) }
    }

    companion object {
        private var files: java.io.File? = null

        fun installCrashLogger(ctx: android.content.Context) {
            files = ctx.filesDir
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    ctx.filesDir.resolve("last-crash.txt").writeText(sw.toString())
                } catch (_: Exception) {}
                prev?.uncaughtException(t, e)
            }
        }
    }
}

private val Amber = Color(0xFFFFC468)
private val AmberDeep = Color(0xFFFF9E2C)
private val Ember = Color(0xFFFF6B35)
private val Night = Color(0xFF0A0A12)
private val NightSurface = Color(0xFF15151F)
private val NightCard = Color(0xFF1D1D2B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(crash: String? = null, vm: MeshViewModel = viewModel()) {
    val state by vm.connState.collectAsState()
    val level by vm.brightness.collectAsState()
    val status by vm.statusText.collectAsState()
    val link by vm.linkState.collectAsState()
    var showKeys by remember { mutableStateOf(false) }      // keys + sharing, one dialog
    var showPair by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    // last level the user actually lit, for the orb's double-tap toggle
    var lastOn by remember { mutableStateOf((vm.rememberedLevel() ?: MeshConfig.LIGHTNESS_MAX).coerceAtLeast(1)) }
    val haptics = LocalHapticFeedback.current
    val sliderPos = remember {
        mutableStateOf((vm.rememberedLevel() ?: 0).toFloat() / MeshConfig.LIGHTNESS_MAX)
    }
    var dragging by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(level) {
        if (!dragging) level?.let {
            sliderPos.value = it / MeshConfig.LIGHTNESS_MAX.toFloat()
            if (it > 0) lastOn = it
        }
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.connectBulb()
    }

    // auto-connect on launch when keys are already configured
    LaunchedEffect(Unit) {
        if (vm.keys != null) {
            if (vm.hasPermissions(ctx)) vm.connectBulb()
            else if (Build.VERSION.SDK_INT >= 31) perms.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ))
            else perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    var showCrash by remember { mutableStateOf(crash != null) }
    if (showCrash && crash != null) {
        AlertDialog(
            onDismissRequest = { showCrash = false },
            confirmButton = { TextButton(onClick = { showCrash = false }) { Text("OK") } },
            title = { Text("Previous crash") },
            text = {
                Column {
                    Text("Last run crashed. Details:", fontSize = 13.sp, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(crash.take(1200), fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber, background = Night, surface = NightSurface,
            onBackground = Color.White, onSurface = Color.White,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Night) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val glow = sliderPos.value
                // Shrink the orb on short screens so the controls never get pushed off the bottom.
                val orbSize = (maxHeight * 0.30f).coerceIn(120.dp, 210.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Amber.copy(alpha = 0.30f * glow + 0.03f),
                                    Ember.copy(alpha = 0.14f * glow + 0.02f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Everything that isn't "light the bulb" lives behind this.
                IconButton(
                    onClick = { showSheet = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options",
                        tint = Color.White.copy(alpha = 0.55f))
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text("the bulb", fontSize = 15.sp, letterSpacing = 6.sp,
                        color = Color.White.copy(alpha = 0.45f))
                    Spacer(Modifier.height(6.dp))
                    AnimatedContent(targetState = stateLabel(state), transitionSpec = {
                        fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 } togetherWith
                        fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 }
                    }, label = "state") { s ->
                        Text(s, color = Amber, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                    }

                    Spacer(Modifier.weight(0.06f))

                    BulbOrb(
                        level = sliderPos.value,
                        connected = state is ConnState.Ready,
                        orbSize = orbSize,
                        label = percentText(sliderPos.value),
                        onDoubleTap = {
                            // double-tap the bulb: off <-> the last level it was lit at
                            dragging = false
                            val from = (sliderPos.value * MeshConfig.LIGHTNESS_MAX).roundToInt()
                            if (from > 0) {
                                lastOn = from
                                vm.rampTo(0)
                                sliderPos.value = 0f
                            } else {
                                vm.rampTo(lastOn)
                                sliderPos.value = lastOn / MeshConfig.LIGHTNESS_MAX.toFloat()
                            }
                        }
                    )

                    Spacer(Modifier.weight(0.10f))

                    Slider(
                        value = sliderPos.value,
                        onValueChange = {
                            sliderPos.value = it; dragging = true
                            vm.liveSet((it * MeshConfig.LIGHTNESS_MAX).roundToInt())
                        },
                        onValueChangeFinished = {
                            dragging = false
                            val lvl = (sliderPos.value * MeshConfig.LIGHTNESS_MAX).roundToInt()
                            if (lvl > 0) lastOn = lvl
                            vm.setBrightness(lvl)
                        },
                        valueRange = 0f..1f,
                        // gradient track + a thumb that grows while you drag: the control itself
                        // shows the range instead of a flat grey bar
                        thumb = { _ ->
                            val d by animateDpAsState(if (dragging) 28.dp else 20.dp, label = "thumb")
                            Box(Modifier.size(d).background(Amber, CircleShape))
                        },
                        track = { _ ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.10f))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(sliderPos.value.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(Ember, Amber, AmberDeep)))
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(0f to "off", 0.1f to "ember", 0.45f to "low", 1f to "max").forEach { (v, name) ->
                            FilterChip(
                                selected = abs(sliderPos.value - v) < 0.02f,
                                onClick = {
                                    dragging = false
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val target = (v * MeshConfig.LIGHTNESS_MAX).roundToInt()
                                    if (target > 0) lastOn = target
                                    vm.rampTo(target)
                                    sliderPos.value = v
                                },
                                label = { Text(name, fontSize = 13.sp) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = NightCard,
                                    labelColor = Color.White.copy(alpha = 0.75f),
                                    selectedContainerColor = AmberDeep,
                                    selectedLabelColor = Night,
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    AnimatedVisibility(
                        visible = status.isNotBlank(),
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        Text(status, color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                    }

                    // The link can be up while the bulb ignores us — offer the one-tap recovery.
                    AnimatedVisibility(visible = link == LinkState.NO_REPLY) {
                        OutlinedButton(
                            onClick = { vm.recoverLink() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                            modifier = Modifier.padding(top = 6.dp)
                        ) { Text("Fix link", fontSize = 13.sp) }
                    }

                    Spacer(Modifier.weight(0.08f))

                    val connected = state is ConnState.Ready
                    FilledTonalButton(
                        onClick = {
                            if (connected) vm.disconnect()
                            else if (vm.hasPermissions(ctx)) vm.connectBulb()
                            else {
                                val needed = if (Build.VERSION.SDK_INT >= 31)
                                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                perms.launch(needed)
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (connected) NightCard else AmberDeep,
                            contentColor = if (connected) Color.White.copy(alpha = 0.8f) else Night
                        ),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Text(if (connected) "Disconnect" else "Connect",
                            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (showKeys) {
                    KeysDialog(
                        keysJson = vm.exportedKeysJson(),
                        netJson = vm.exportNetworkJson(),
                        onDismiss = { showKeys = false })
                }
                if (showHelp) {
                    HelpDialog(diag = vm.diagLine(), onDismiss = { showHelp = false })
                }
                if (showPair) {
                    PairConfirmDialog(
                        onConfirm = { showPair = false; vm.startPairing() },
                        onDismiss = { showPair = false })
                }
                if (showSheet) {
                    OptionsSheet(
                        connected = state is ConnState.Ready,
                        onKeys = { showSheet = false; showKeys = true },
                        onPair = { showSheet = false; showPair = true },
                        onDisconnect = { showSheet = false; vm.disconnect() },
                        onHelp = { showSheet = false; showHelp = true },
                        onDismiss = { showSheet = false })
                }
            }
        }
    }
}

private fun stateLabel(s: ConnState) = when (s) {
    ConnState.Pairing -> "pairing…"
    ConnState.Idle -> "offline"
    ConnState.Scanning -> "finding bulb…"
    ConnState.Connecting -> "connecting…"
    ConnState.Ready -> "connected"
    is ConnState.Error -> "needs attention"
}

private fun percentText(pos: Float): String {
    val pct = (pos * 100).roundToInt()
    return if (pct <= 0) "off" else "$pct%"
}

/** Glowing bulb orb; breathing animation when lit. Double-tap toggles it. */
@Composable
private fun BulbOrb(
    level: Float,
    connected: Boolean,
    orbSize: Dp = 210.dp,
    label: String = "",
    onDoubleTap: () -> Unit = {},
) {
    val infinite = rememberInfiniteTransition(label = "breathe")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )
    val animatedLevel by animateFloatAsState(level,
        animationSpec = tween(350, easing = FastOutSlowInEasing), label = "lvl")

    val size = orbSize
    // Explicit px radius: the default radial-gradient radius is measured to the *corners*, so the
    // outer colour is still opaque where the circle cuts it off — a hard rim (and, blurred, a square).
    val density = LocalDensity.current
    val glowRadius = with(density) { (size + 70.dp).toPx() / 2f }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size + 80.dp)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
    ) {
        Box(
            Modifier
                .size(size + 70.dp)
                .scale(if (connected) pulse else 1f)
                // Unbounded: the default Rectangle edge treatment clips the blur to a square.
                .blur(60.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Amber.copy(alpha = 0.55f * animatedLevel),
                            Ember.copy(alpha = 0.25f * animatedLevel),
                            Color.Transparent
                        ),
                        radius = glowRadius
                    ),
                    CircleShape
                )
        )
        Box(
            Modifier
                .size(size)
                .scale(0.96f + 0.04f * animatedLevel)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f + 0.55f * animatedLevel),
                            Amber.copy(alpha = 0.35f + 0.60f * animatedLevel),
                            if (animatedLevel > 0.02f) AmberDeep.copy(alpha = 0.5f + 0.4f * animatedLevel)
                            else NightCard.copy(alpha = 0.95f),
                            NightCard
                        )
                    ),
                    CircleShape
                )
        )
        // The level reads inside the orb instead of as a separate block — the orb IS the readout.
        // Glyphs flip dark once the glass lights up, so they stay legible at every level.
        val lit = animatedLevel > 0.45f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lightbulb, contentDescription = "bulb",
                tint = if (lit) Night.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(size * 0.20f).scale(0.95f + 0.05f * animatedLevel)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                fontSize = (size.value * 0.24f).sp,
                fontWeight = FontWeight.Light,
                color = if (lit) Night.copy(alpha = 0.80f) else Color.White,
            )
        }
    }
}

@Composable
private fun HelpDialog(diag: String, onDismiss: () -> Unit) {
    val steps = listOf(
        "1. Provision the bulb first (one-off)",
        "    Power-cycle the bulb (off 5s, on). In the free nRF Mesh app: Scan → tap the unprovisioned LEDVANCE device → Provision with defaults → bind an App Key → add Generic Light model.",
        "2. Export your keys",
        "    In nRF Mesh, open the network → Export → Network JSON (or use a Raspberry Pi provisioner's state file). You need: Network key, App key, and the bulb's Device key — each 32 hex characters.",
        "3. Enter them here",
        "    Tap ⋯ (top right) → Keys & sharing, paste the three values. Optional: bulb MAC (AA:BB:CC:...) to force connecting to a specific device, and its unicast address (usually 0x0002). Save and restart the app.",
        "4. Connect & control",
        "    Keep the bulb powered — it only advertises the BLE mesh proxy while powered. Tap Connect; the orb shows the live level and the slider drives it. Double-tap the orb to switch between off and your last level.",
        "Also in ⋯:",
        "    Pair a new bulb (joins a factory-reset bulb to a new network), Disconnect, and this help page.",
        "Trouble?",
        "    • Proxy not found → power-cycle the bulb and keep it on",
        "    • Timeout/Decryption failed → keys from a different network",
        "    • This app only supports BLE mesh bulbs — not WiFi/cloud bulbs.",
        "Two phones, one bulb?",
        "    Each install writes from its own mesh address, so both can control the bulb — but only one of them can hold the BLE link to it at a time. If it says connected while brightness ignores you, tap Fix link (that re-joins from a fresh address).",
        "    $diag",
        "    aBulb ${MeshConfig.APP_VERSION} — keys stay on this phone, nothing is uploaded."
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightCard,
        title = { Text("Getting started", color = Color.White) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                steps.forEach { line ->
                    val bold = !line.startsWith("    ")
                    Text(line.trimEnd(),
                        color = if (bold) Amber else Color.White.copy(alpha = 0.75f),
                        fontSize = if (bold) 14.sp else 12.sp,
                        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Amber) } }
    )
}

/** Everything that isn't "light the bulb": keys, pairing, disconnecting, help. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(
    connected: Boolean,
    onKeys: () -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    onHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NightCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.15f)) }
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            SheetRow(Icons.Default.VpnKey, "Keys & sharing",
                "Enter keys, import a file, or hand them to another phone", onKeys)
            SheetRow(Icons.Default.Bluetooth, "Pair a new bulb",
                "One-off: joins a factory-reset bulb to a new network", onPair)
            if (connected) {
                SheetRow(Icons.Default.LinkOff, "Disconnect",
                    "Free the bulb's radio for another controller", onDisconnect)
            }
            SheetRow(Icons.Default.HelpOutline, "Help & diagnostics",
                "How it works, and what this install is doing", onHelp)
        }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Amber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun KeysDialog(keysJson: String, netJson: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val saved = remember { KeyStore.load(ctx) }   // prefill with the keys currently in use
    var net by remember { mutableStateOf(saved?.net ?: "") }
    var app by remember { mutableStateOf(saved?.app ?: "") }
    var dev by remember { mutableStateOf(saved?.dev ?: "") }
    var mac by remember { mutableStateOf(saved?.mac ?: "") }
    var uni by remember { mutableStateOf(saved?.let { String.format("0x%04X", it.bulbUnicast) } ?: "0x0002") }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) try {
            val txt = ctx.contentResolver.openInputStream(uri)?.readBytes()
                ?.toString(Charsets.UTF_8) ?: ""
            val o = org.json.JSONObject(txt)
            if (o.optString("format") == "abulb-keys-v1") {
                net = o.getString("netKey"); app = o.getString("appKey"); dev = o.getString("deviceKey")
                mac = o.optString("mac").uppercase()
                if (o.has("bulbUnicast")) {
                    val u = o.get("bulbUnicast")
                    uni = if (u is Number) String.format("0x%04X", u.toInt())
                          else u.toString().lowercase().removePrefix("0x").let { "0x$it" }
                }
                Toast.makeText(ctx, "Keys loaded — tap Save", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(ctx, "Not an aBulb key file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Bad file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightCard,
        title = { Text("Keys & sharing", color = Color.White) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                Text("Paste the keys from your own provisioned network (see the README for how to export them from a Raspberry Pi).",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                OutlinedTextField(net, { net = it }, label = { Text("Network key") },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                OutlinedTextField(app, { app = it }, label = { Text("App key") },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                OutlinedTextField(dev, { dev = it }, label = { Text("Bulb device key") },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                OutlinedTextField(mac, { mac = it }, label = { Text("Bulb MAC (optional, e.g. AA:BB:CC:DD:EE:FF)") },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                OutlinedTextField(uni, { uni = it }, label = { Text("Bulb unicast address (hex, usually 0x0002)") },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))

                Spacer(Modifier.height(2.dp))
                Text("Share with another phone", color = Amber, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("Send these to another aBulb install (they open it via Import file). Each install " +
                     "picks its own mesh address, so both can control the bulb. Anyone holding this file " +
                     "can control it too.",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                if (keysJson.isBlank()) {
                    Text("No keys configured yet.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shareJson(ctx, "abulb-keys.json", keysJson, "Share key file") }) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Key file", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = {
                            shareJson(ctx, "abulb-network.json", netJson.ifBlank { keysJson }, "Share network")
                        }) { Text("Full network", fontSize = 13.sp) }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text("Import file", color = Amber)
                }
            TextButton(onClick = {
                val n = net.trim().uppercase()
                val a = app.trim().uppercase()
                val d = dev.trim().uppercase()
                val hex32 = Regex("^[0-9A-F]{32}$")
                val u = uni.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                when {
                    !hex32.matches(n) || !hex32.matches(a) || !hex32.matches(d) ->
                        Toast.makeText(ctx, "Each key must be 32 hex chars (0-9, A-F)", Toast.LENGTH_SHORT).show()
                    u == null || u !in 0x0001..0x7FFF ->
                        Toast.makeText(ctx, "Unicast address must be 0001-7FFF, e.g. 0x0002", Toast.LENGTH_SHORT).show()
                    else -> {
                        KeyStore.save(ctx, MeshKeys(n, a, d, mac.trim().uppercase(), u))
                        Toast.makeText(ctx, "Saved — restart app", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            }) { Text("Save", color = Amber) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    KeyStore.clear(ctx)
                    net = ""; app = ""; dev = ""; mac = ""; uni = "0x0002"
                    Toast.makeText(ctx, "Keys cleared — restart app", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text("Clear", color = Ember) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
            }
        }
    )
}

@Composable
private fun PairConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightCard,
        title = { Text("Pair a bulb", color = Color.White) },
        text = {
            Text(
                "This creates a brand-new mesh network owned by this phone and joins a " +
                "bulb to it.\n\n" +
                "The bulb must be UNPROVISIONED: power-cycle it 6 times quickly " +
                "(off 1s, on 1s, repeat) until it stops remembering its old network, " +
                "or factory-reset it in its current app.\n\n" +
                "If the bulb already belongs to a network you can join with Keys/Import " +
                "instead — pairing does not steal an existing bulb.",
                color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Start pairing", color = Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) } }
    )
}

/** Hand a JSON blob to any share target (whatever the phone has installed). */
private fun shareJson(ctx: android.content.Context, name: String, json: String, title: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND)
    i.type = "application/json"
    i.putExtra(android.content.Intent.EXTRA_SUBJECT, name)
    i.putExtra(android.content.Intent.EXTRA_TEXT, json)
    ctx.startActivity(android.content.Intent.createChooser(i, title))
}
