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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var showKeys by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val sliderPos = remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(level) {
        if (!dragging) level?.let { sliderPos.value = it / MeshConfig.LIGHTNESS_MAX.toFloat() }
    }

    val uiScope = rememberCoroutineScope()
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
            Box(Modifier.fillMaxSize()) {
                val glow = sliderPos.value
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

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(48.dp))
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

                    BulbOrb(level = sliderPos.value, connected = state is ConnState.Ready)

                    Spacer(Modifier.weight(0.10f))

                    Text(
                        percentText(sliderPos.value, level),
                        fontSize = 56.sp, fontWeight = FontWeight.Light,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(28.dp))

                    Slider(
                        value = sliderPos.value,
                        onValueChange = {
                            sliderPos.value = it; dragging = true
                            vm.liveSet((it * MeshConfig.LIGHTNESS_MAX).roundToInt())
                        },
                        onValueChangeFinished = {
                            dragging = false
                            vm.setBrightness((sliderPos.value * MeshConfig.LIGHTNESS_MAX).roundToInt())
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Amber, activeTrackColor = AmberDeep,
                            inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(0f to "off", 0.1f to "ember", 0.45f to "low", 1f to "max").forEach { (v, name) ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    dragging = false
                                    val target = (v * MeshConfig.LIGHTNESS_MAX).roundToInt()
                                    vm.rampTo(target)
                                    sliderPos.value = v
                                },
                                label = { Text(name, fontSize = 13.sp) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = NightCard,
                                    labelColor = Color.White.copy(alpha = 0.75f)
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

                    Spacer(Modifier.weight(0.08f))

                    val connected = state is ConnState.Ready
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
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
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Icon(if (connected) Icons.Default.LinkOff else Icons.Default.Bluetooth,
                                null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (connected) "Disconnect" else "Connect", fontWeight = FontWeight.Medium)
                        }
                        FilledTonalButton(
                            onClick = { showPair = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = NightCard, contentColor = Amber),
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) { Text("Pair", fontWeight = FontWeight.Medium) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showKeys = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Keys") }
                        OutlinedButton(
                            onClick = { showExport = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Export") }
                        OutlinedButton(
                            onClick = { showHelp = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(0.6f)
                        ) { Text("?") }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                if (showKeys) {
                    KeyDialog { showKeys = false }
                }
                if (showHelp) {
                    HelpDialog { showHelp = false }
                }
                if (showPair) {
                    PairConfirmDialog(
                        onConfirm = { showPair = false; vm.startPairing() },
                        onDismiss = { showPair = false })
                }
                if (showExport) {
                    ExportDialog(
                        keysJson = vm.exportedKeysJson(),
                        netJson = vm.exportNetworkJson(),
                        onDismiss = { showExport = false })
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

private fun percentText(pos: Float, level: Int?): String {
    val pct = (pos * 100).roundToInt()
    return if (pct == 0 && level == 0) "off" else "$pct%"
}

/** Glowing bulb orb; breathing animation when lit. */
@Composable
private fun BulbOrb(level: Float, connected: Boolean) {
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

    val size = 210.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 80.dp)) {
        Box(
            Modifier
                .size(size + 70.dp)
                .scale(if (connected) pulse else 1f)
                .blur(60.dp)
                .background(
                    Brush.radialGradient(listOf(
                        Amber.copy(alpha = 0.55f * animatedLevel),
                        Ember.copy(alpha = 0.25f * animatedLevel),
                        Color.Transparent
                    )),
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
        Icon(
            Icons.Default.Lightbulb, null,
            tint = Color.White.copy(alpha = 0.55f + 0.45f * animatedLevel),
            modifier = Modifier.size(84.dp).scale(0.95f + 0.05f * animatedLevel)
        )
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val steps = listOf(
        "1. Provision the bulb first (one-off)",
        "    Power-cycle the bulb (off 5s, on). In the free nRF Mesh app: Scan → tap the unprovisioned LEDVANCE device → Provision with defaults → bind an App Key → add Generic Light model.",
        "2. Export your keys",
        "    In nRF Mesh, open the network → Export → Network JSON (or use a Raspberry Pi provisioner's state file). You need: Network key, App key, and the bulb's Device key — each 32 hex characters.",
        "3. Enter them here",
        "    Tap Keys, paste the three values. Optional: bulb MAC (AA:BB:CC:...) to force connecting to a specific device, and its unicast address (usually 0x0002). Save and restart the app.",
        "4. Connect & control",
        "    Keep the bulb powered — it only advertises the BLE mesh proxy while powered. Tap Connect; the orb shows the live level and the slider drives it.",
        "Trouble?",
        "    • Proxy not found → power-cycle the bulb and keep it on",
        "    • Timeout/Decryption failed → keys from a different network",
        "    • This app only supports BLE mesh bulbs — not WiFi/cloud bulbs."
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

@Composable
private fun KeyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var net by remember { mutableStateOf("") }
    var app by remember { mutableStateOf("") }
    var dev by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var uni by remember { mutableStateOf("0x0002") }
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
        title = { Text("Mesh keys", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text("Import file", color = Amber)
                }
            TextButton(onClick = {
                val uniOk = uni.toIntOrNull(16)?.let { it in 1..0x7FFF } == true ||
                            uni.startsWith("0x") && uni.substring(2).toIntOrNull(16) != null
                if (net.length == 32 && app.length == 32 && dev.length == 32 && uniOk) {
                    val u = uni.removePrefix("0x").removePrefix("0X").toInt(16)
                    KeyStore.save(ctx, MeshKeys(net, app, dev, mac, u))
                    Toast.makeText(ctx, "Saved — restart app", Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    Toast.makeText(ctx, "Each key must be 32 hex chars", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Save", color = Amber) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
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

@Composable
private fun ExportDialog(keysJson: String, netJson: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightCard,
        title = { Text("Export keys", color = Color.White) },
        text = {
            Column {
                Text("Share these with another aBulb install (Keys \u2192 Import file). " +
                     "Anyone with this file can control your bulb.",
                     color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                if (keysJson.isBlank()) {
                    Text("No keys configured yet.", color = Color.White.copy(alpha = 0.6f))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                            i.type = "application/json"
                            i.putExtra(android.content.Intent.EXTRA_SUBJECT, "abulb-keys.json")
                            i.putExtra(android.content.Intent.EXTRA_TEXT, keysJson)
                            ctx.startActivity(android.content.Intent.createChooser(i, "Share keys"))
                        }) { Text("Key file", color = Amber) }
                        OutlinedButton(onClick = {
                            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                            i.type = "application/json"
                            i.putExtra(android.content.Intent.EXTRA_SUBJECT, "abulb-network.json")
                            i.putExtra(android.content.Intent.EXTRA_TEXT, netJson.ifBlank { keysJson })
                            ctx.startActivity(android.content.Intent.createChooser(i, "Share network"))
                        }) { Text("Full network", color = Amber) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Amber) } }
    )
}
