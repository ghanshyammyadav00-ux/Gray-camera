package com.example.procam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln

enum class Mode { PHOTO, VIDEO }
enum class Ext(val label: String, val mode: Int) {
    OFF("Off", ExtensionMode.NONE), AUTO("Auto", ExtensionMode.AUTO), HDR("HDR", ExtensionMode.HDR),
    NIGHT("Night", ExtensionMode.NIGHT), BOKEH("Portrait", ExtensionMode.BOKEH),
    RETOUCH("Retouch", ExtensionMode.FACE_RETOUCH)
}
enum class Wb(val label: String, val v: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    SUN("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUD("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TUNG("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUO("Fluor.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
}

@SuppressLint("MissingPermission")
class CameraEngine(private val ctx: Context) {
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var factory: MeteringPointFactory? = null

    // ---- settings / UI state ----
    var ready by mutableStateOf(false)
    var mode by mutableStateOf(Mode.PHOTO)
    var front by mutableStateOf(false)
    var ratio43 by mutableStateOf(true)
    var flashIdx by mutableIntStateOf(0) // 0 off, 1 auto, 2 on/torch
    var timer by mutableIntStateOf(0)
    var burst by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var level by mutableStateOf(true)
    var histogramOn by mutableStateOf(true)
    var ext by mutableStateOf(Ext.OFF)
    var extActive by mutableStateOf(false)
    var qIdx by mutableIntStateOf(2)
    var stabilize by mutableStateOf(true)

    // pro controls
    var manualSupported by mutableStateOf(false)
    var manualExposure by mutableStateOf(false)
    var manualFocus by mutableStateOf(false)
    var isoPos by mutableFloatStateOf(0.1f)
    var shutterPos by mutableFloatStateOf(0.5f)
    var focusPos by mutableFloatStateOf(0f)
    var wb by mutableStateOf(Wb.AUTO)
    var evIndex by mutableIntStateOf(0)
    var evLo by mutableIntStateOf(0)
    var evHi by mutableIntStateOf(0)
    var evStep by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    private var minZoom = 1f
    private var maxZoom = 1f
    private var isoLo = 50; private var isoHi = 3200
    private var shLo = 100_000L; private var shHi = 1_000_000_000L
    private var minFocusDist = 0f

    // smart features
    var faceAf by mutableStateOf(true)
    var smile by mutableStateOf(false)
    var qr by mutableStateOf(true)
    var faces by mutableStateOf<List<Rect>>(emptyList())
    var qrValue by mutableStateOf<String?>(null)
    var hint by mutableStateOf<String?>(null)
    var hist by mutableStateOf(IntArray(64))
    var roll by mutableFloatStateOf(0f)

    // runtime
    var viewW = 1f; var viewH = 1f
    var countdown by mutableIntStateOf(0)
    var focusPt by mutableStateOf<Offset?>(null)
    var lastUri by mutableStateOf<Uri?>(null)
    var isRecording by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var seconds by mutableIntStateOf(0)
    private var busy = false

    val qualities = listOf(Quality.SD to "480p", Quality.HD to "720p", Quality.FHD to "1080p", Quality.UHD to "4K")
    val iso get() = (isoLo + isoPos * (isoHi - isoLo)).toInt()
    val shutterNs get() = exp(ln(shLo.toDouble()) + shutterPos * (ln(shHi.toDouble()) - ln(shLo.toDouble()))).toLong()
    val shutterLabel get() = shutterNs.let { if (it >= 500_000_000L) "%.1fs".format(it / 1e9) else "1/${(1e9 / it).toInt()}" }
    val focusLabel get() = if (focusPos < 0.02f) "∞" else "%.0f cm".format(100f / (focusPos * minFocusDist).coerceAtLeast(0.1f))
    val zoomText get() = "%.1fx".format(zoom)

    init {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            provider = f.get()
            try {
                val ef = ExtensionsManager.getInstanceAsync(ctx, provider!!)
                ef.addListener({ extMgr = try { ef.get() } catch (_: Exception) { null }; ready = true }, main)
            } catch (_: Exception) { ready = true }
        }, main)
    }

    private fun baseSelector() = CameraSelector.Builder()
        .requireLensFacing(if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK).build()

    fun extAvailable(e: Ext) = e == Ext.OFF || extMgr?.isExtensionAvailable(baseSelector(), e.mode) == true

    // ---------- binding ----------
    fun bind(owner: LifecycleOwner, pv: PreviewView) {
        val p = provider ?: return
        if (isRecording) recording?.stop()
        p.unbindAll()
        val base = baseSelector()
        val aspect = if (ratio43) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val useExt = mode == Mode.PHOTO && ext != Ext.OFF && extMgr?.isExtensionAvailable(base, ext.mode) == true
        extActive = useExt
        val selector = if (useExt) extMgr!!.getExtensionEnabledCameraSelector(base, ext.mode) else base

        val preview = Preview.Builder().setTargetAspectRatio(aspect).build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        val cases = mutableListOf<UseCase>(preview)
        if (mode == Mode.PHOTO) {
            imageCapture = ImageCapture.Builder().setTargetAspectRatio(aspect)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            cases += imageCapture!!; videoCapture = null
        } else {
            val rec = Recorder.Builder().setQualitySelector(
                QualitySelector.from(qualities[qIdx].first, FallbackStrategy.lowerQualityOrHigherThan(qualities[qIdx].first))).build()
            videoCapture = VideoCapture.Builder(rec).setVideoStabilizationEnabled(stabilize).build()
            cases += videoCapture!!; imageCapture = null
        }
        if (!useExt) {
            cases += ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                .also { it.setAnalyzer(analysisExec, analyzer) }
        }
        try {
            camera = p.bindToLifecycle(owner, selector, *cases.toTypedArray())
        } catch (e: Exception) { hint = "Camera error: ${e.message}"; return }
        factory = pv.meteringPointFactory
        readCapabilities()
        zoom = 1f; evIndex = 0
        applyFlash(); applyCaptureOptions()
    }

    private fun readCapabilities() {
        val cam = camera ?: return
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val isoR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        manualSupported = isoR != null && shR != null && !extActive
        if (isoR != null) { isoLo = isoR.lower; isoHi = minOf(isoR.upper, 6400) }
        if (shR != null) { shLo = maxOf(shR.lower, 100_000L); shHi = minOf(shR.upper, 1_000_000_000L) }
        minFocusDist = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        cam.cameraInfo.zoomState.value?.let { minZoom = it.minZoomRatio; maxZoom = it.maxZoomRatio }
        cam.cameraInfo.exposureState.let { evLo = it.exposureCompensationRange.lower; evHi = it.exposureCompensationRange.upper
            evStep = it.exposureCompensationStep.toFloat() }
    }

    fun applyCaptureOptions() {
        val cam = camera ?: return
        try {
            val b = CaptureRequestOptions.Builder()
            if (manualSupported && manualExposure) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            }
            if (manualSupported && manualFocus && minFocusDist > 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusPos * minFocusDist)
            }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.v)
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (_: Exception) { }
    }

    // ---------- controls ----------
    fun cycleFlash() { flashIdx = (flashIdx + 1) % 3; applyFlash() }
    private fun applyFlash() {
        imageCapture?.flashMode = listOf(ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON)[flashIdx]
        camera?.cameraControl?.enableTorch(mode == Mode.VIDEO && flashIdx == 2)
    }
    fun cycleTimer() { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
    fun setEv(i: Int) { evIndex = i; camera?.cameraControl?.setExposureCompensationIndex(i) }
    fun zoomBy(f: Float) { zoom = (zoom * f).coerceIn(minZoom, maxZoom); camera?.cameraControl?.setZoomRatio(zoom) }
    fun focusAt(x: Float, y: Float) {
        val pt = factory?.createPoint(x, y) ?: return
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
        focusPt = Offset(x, y)
    }

    // ---------- capture ----------
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PRO_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ProCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = front }).build()
        ic.takePicture(opts, main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { lastUri = r.savedUri }
            override fun onError(e: ImageCaptureException) { hint = "Save failed: ${e.message}" }
        })
    }

    fun shutter() {
        if (mode == Mode.VIDEO) { toggleRecord(); return }
        if (busy) return
        busy = true
        scope.launch {
            for (t in timer downTo 1) { countdown = t; delay(1000) }
            countdown = 0
            repeat(if (burst) 5 else 1) { takePhoto(); delay(450) }
            busy = false
        }
    }

    private fun toggleRecord() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); return }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PRO_${stamp()}")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ProCam")
        }
        val out = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pr = vc.output.prepareRecording(ctx, out)
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pr = pr.withAudioEnabled()
        recording = pr.start(main) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { isRecording = true; isPaused = false }
                is VideoRecordEvent.Status -> seconds = (ev.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                is VideoRecordEvent.Finalize -> {
                    isRecording = false; isPaused = false; recording = null; seconds = 0
                    if (!ev.hasError()) lastUri = ev.outputResults.outputUri else hint = "Video error ${ev.error}"
                }
            }
        }
    }

    fun togglePause() { val r = recording ?: return; if (isPaused) { r.resume(); isPaused = false } else { r.pause(); isPaused = true } }

    // ---------- smart analysis ----------
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).build())
    private val barcodeScanner = BarcodeScanning.getClient()
    private var lastMl = 0L; private var lastAf = 0L; private var lastSmile = 0L

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private val analyzer = ImageAnalysis.Analyzer { img ->
        histogram(img)
        val now = SystemClock.elapsedRealtime()
        val media = img.image
        if (media != null && now - lastMl > 350 && (faceAf || smile || qr)) {
            lastMl = now
            val rot = img.imageInfo.rotationDegrees
            val w = if (rot % 180 == 0) img.width else img.height
            val h = if (rot % 180 == 0) img.height else img.width
            val input = InputImage.fromMediaImage(media, rot)
            val tasks = mutableListOf<Task<*>>()
            if (faceAf || smile) tasks += faceDetector.process(input).addOnSuccessListener { fs ->
                val rects = fs.map {
                    val b = it.boundingBox
                    var l = b.left / w.toFloat(); var r = b.right / w.toFloat()
                    if (front) { val nl = 1f - r; r = 1f - l; l = nl }
                    Rect(l, b.top / h.toFloat(), r, b.bottom / h.toFloat())
                }
                faces = rects
                if (smile && now - lastSmile > 3000 && fs.any { (it.smilingProbability ?: 0f) > 0.85f }) {
                    lastSmile = now; shutter()
                }
                if (faceAf && !manualFocus && rects.isNotEmpty() && now - lastAf > 2500) {
                    lastAf = now
                    val c = rects.maxBy { it.width * it.height }.center
                    focusAt(c.x * viewW, c.y * viewH)
                }
            } else faces = emptyList()
            if (qr) tasks += barcodeScanner.process(input).addOnSuccessListener { qrValue = it.firstOrNull()?.rawValue }
            else qrValue = null
            Tasks.whenAllComplete(tasks).addOnCompleteListener { img.close() }
        } else img.close()
    }

    private fun histogram(img: ImageProxy) {
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        val bins = IntArray(64); var n = 0; var hi = 0; var lo = 0
        for (y in 0 until img.height step 8) for (x in 0 until img.width step 8) {
            val v = buf.get(y * rs + x * ps).toInt() and 0xFF
            bins[v shr 2]++; n++
            if (v >= 250) hi++; if (v <= 6) lo++
        }
        hist = bins
        hint = when {
            n == 0 -> null
            hi * 100 / n > 8 -> "Highlights clipping – lower EV or shutter"
            lo * 100 / n > 45 -> "Very dark – raise ISO / try Night"
            else -> null
        }
    }

    // ---------- sensors / cleanup ----------
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sl = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            roll = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    fun startSensors() { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(sl, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stopSensors() = sm.unregisterListener(sl)
    fun shutdown() { recording?.stop(); stopSensors(); provider?.unbindAll(); scope.cancel() }
}
