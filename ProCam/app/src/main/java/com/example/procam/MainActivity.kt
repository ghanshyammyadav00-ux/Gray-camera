package com.example.procam

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var engine: CameraEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = CameraEngine(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val perms = buildList {
                    add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }.toTypedArray()
                var granted by remember { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = it[Manifest.permission.CAMERA] == true
                }
                LaunchedEffect(Unit) { launcher.launch(perms) }
                if (granted) CameraScreen(engine, this@MainActivity)
                else Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                    Button(onClick = { launcher.launch(perms) }) { Text("Grant camera permission") }
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); engine.shutdown() }
}

@Composable
fun Chip(t: String, on: Boolean = false, f: () -> Unit) = Text(
    t, color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
    modifier = Modifier.padding(3.dp).clip(RoundedCornerShape(50))
        .background(if (on) Color(0xFFFFC107) else Color(0x44FFFFFF))
        .clickable(onClick = f).padding(horizontal = 12.dp, vertical = 7.dp)
)

@Composable
fun Lbl(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, on: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        Slider(v, on, Modifier.weight(1f), valueRange = range, steps = steps)
        Text(value, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(56.dp))
    }
}

@Composable
fun CameraScreen(e: CameraEngine, owner: LifecycleOwner) {
    val ctx = LocalContext.current
    val pv = remember { PreviewView(ctx) }
    var panel by remember { mutableIntStateOf(0) } // 0 none, 1 pro, 2 smart

    DisposableEffect(Unit) { e.startSensors(); onDispose { e.stopSensors() } }
    LaunchedEffect(e.ready, e.mode, e.front, e.ratio43, e.ext, e.qIdx, e.stabilize) { if (e.ready) e.bind(owner, pv) }
    LaunchedEffect(e.focusPt) { if (e.focusPt != null) { delay(1200); e.focusPt = null } }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        // ---- top toolbar ----
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            Chip(listOf("⚡ Off", "⚡ Auto", "⚡ On")[e.flashIdx], e.flashIdx > 0) { e.cycleFlash() }
            Chip(if (e.ratio43) "4:3" else "16:9") { e.ratio43 = !e.ratio43 }
            Chip(if (e.timer == 0) "⏱ Off" else "⏱ ${e.timer}s", e.timer > 0) { e.cycleTimer() }
            Chip("Burst", e.burst) { e.burst = !e.burst }
            Chip("Grid", e.grid) { e.grid = !e.grid }
            Chip("Level", e.level) { e.level = !e.level }
            Chip("Histo", e.histogramOn) { e.histogramOn = !e.histogramOn }
        }

        // ---- viewfinder ----
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Box(Modifier.aspectRatio(if (e.ratio43) 3f / 4f else 9f / 16f).clip(RoundedCornerShape(8.dp))) {
                AndroidView({ pv }, Modifier.fillMaxSize().onSizeChanged { e.viewW = it.width.toFloat(); e.viewH = it.height.toFloat() })
                Box(Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { o -> e.focusAt(o.x, o.y) } }
                    .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> e.zoomBy(z) } })

                Canvas(Modifier.fillMaxSize()) {
                    if (e.grid) for (i in 1..2) {
                        val c = Color(0x66FFFFFF)
                        drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                        drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                    }
                    e.faces.forEach {
                        drawRect(Color(0xFFFFEB3B), Offset(it.left * size.width, it.top * size.height),
                            Size(it.width * size.width, it.height * size.height), style = Stroke(4f))
                    }
                    if (e.level) {
                        val ok = kotlin.math.abs(e.roll) < 1.5f
                        val col = if (ok) Color(0xFF4CAF50) else Color(0xFFFFFFFF)
                        rotate(e.roll) { drawLine(col, Offset(size.width * 0.3f, size.height / 2), Offset(size.width * 0.7f, size.height / 2), 4f) }
                    }
                    e.focusPt?.let { drawCircle(Color(0xFFFFC107), 60f, it, style = Stroke(4f)) }
                }
                if (e.histogramOn) Canvas(Modifier.align(Alignment.TopStart).padding(8.dp).size(112.dp, 52.dp).background(Color(0x66000000))) {
                    val mx = (e.hist.maxOrNull() ?: 1).coerceAtLeast(1)
                    val bw = size.width / e.hist.size
                    e.hist.forEachIndexed { i, v -> drawRect(Color.White, Offset(i * bw, size.height * (1 - v / mx.toFloat())), Size(bw, size.height * v / mx)) }
                }
                Text(e.zoomText, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                e.hint?.let { Text(it, color = Color(0xFFFFC107), fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(6.dp)) }
                e.qrValue?.let { v -> Text("🔗 $v", color = Color.Black, fontSize = 13.sp, maxLines = 2,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Color.White, RoundedCornerShape(10.dp))
                        .clickable { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
                        .padding(10.dp)) }
                if (e.countdown > 0) Text("${e.countdown}", color = Color.White, fontSize = 96.sp, modifier = Modifier.align(Alignment.Center))
                if (e.isRecording) Text("● %02d:%02d".format(e.seconds / 60, e.seconds % 60) + if (e.isPaused) " ⏸" else "",
                    color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp))
            }
        }

        // ---- panels ----
        if (panel == 1) Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Chip("Manual exp.", e.manualExposure) { if (e.manualSupported) { e.manualExposure = !e.manualExposure; e.applyCaptureOptions() } }
                Chip("Manual focus", e.manualFocus) { if (e.manualSupported) { e.manualFocus = !e.manualFocus; e.applyCaptureOptions() } }
                Wb.values().forEach { w -> Chip(w.label, e.wb == w) { e.wb = w; e.applyCaptureOptions() } }
            }
            if (e.evHi > e.evLo) Lbl("EV", "%+.1f".format(e.evIndex * e.evStep), e.evIndex.toFloat(), e.evLo.toFloat()..e.evHi.toFloat(), e.evHi - e.evLo - 1) { e.setEv(it.toInt()) }
            if (!e.manualSupported) Text("Manual ISO/shutter/focus not available (device or extension mode)", color = Color.Gray, fontSize = 11.sp)
            if (e.manualSupported && e.manualExposure) {
                Lbl("ISO", "${e.iso}", e.isoPos, 0f..1f) { e.isoPos = it; e.applyCaptureOptions() }
                Lbl("Shutter", e.shutterLabel, e.shutterPos, 0f..1f) { e.shutterPos = it; e.applyCaptureOptions() }
            }
            if (e.manualSupported && e.manualFocus) Lbl("Focus", e.focusLabel, e.focusPos, 0f..1f) { e.focusPos = it; e.applyCaptureOptions() }
        }
        if (panel == 2) Column(Modifier.padding(horizontal = 6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Chip("Face AF", e.faceAf) { e.faceAf = !e.faceAf; if (!e.faceAf) e.faces = emptyList() }
                Chip("Smile shutter", e.smile) { e.smile = !e.smile }
                Chip("QR scan", e.qr) { e.qr = !e.qr }
                Chip("Stabilize", e.stabilize) { e.stabilize = !e.stabilize }
                if (e.mode == Mode.VIDEO) Chip("🎞 " + e.qualities[e.qIdx].second) { e.qIdx = (e.qIdx + 1) % e.qualities.size }
            }
            if (e.mode == Mode.PHOTO) Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text("Scene:", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 6.dp))
                Ext.values().filter { e.extAvailable(it) }.forEach { x -> Chip(x.label, e.ext == x) { e.ext = x } }
            }
        }

        // ---- mode + panel buttons ----
        Row(Modifier.fillMaxWidth(), Arrangement.Center) {
            Chip("PRO", panel == 1) { panel = if (panel == 1) 0 else 1 }
            Chip("PHOTO", e.mode == Mode.PHOTO) { if (!e.isRecording) e.mode = Mode.PHOTO }
            Chip("VIDEO", e.mode == Mode.VIDEO) { if (!e.isRecording) e.mode = Mode.VIDEO }
            Chip("SMART", panel == 2) { panel = if (panel == 2) 0 else 2 }
        }

        // ---- shutter row ----
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 4.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) {
                if (e.lastUri != null) Chip("🖼") {
                    e.lastUri?.let { u -> ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            Box(Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                .background(if (e.mode == Mode.VIDEO) Color.Red else Color.White).clickable { e.shutter() })
            if (e.isRecording) Chip(if (e.isPaused) "▶" else "⏸") { e.togglePause() }
            else Chip("🔄") { e.front = !e.front }
        }
    }
}
