package sk.firesport.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class CameraActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener,
    PacketListener, RecordingManager.Callback, RemoteServer.Handler {

    companion object {
        private const val TAG = "FiresportCam"

        /** Časy uzávierky ako menovateľ 1/x s. */
        private val SHUTTERS = intArrayOf(8000, 4000, 2000, 1000, 500, 250, 125, 100, 60, 50, 30, 25, 15, 8, 4, 2)

        private val ALL_WB = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Denné svetlo",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Zamračené",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "Tieň",
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Súmrak",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Žiarivka",
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Teplá žiarivka",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Žiarovka"
        )
    }

    private enum class Tab(val label: String) {
        ZOOM("Zoom"), EV("Expozícia"), FOCUS("Ostrenie"), ISO("ISO"), SHUTTER("Uzávierka"), WB("Vyváženie bielej")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var previewView: PreviewView
    private lateinit var gridView: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var recLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var btnTorch: View
    private lateinit var btnControls: View
    private lateinit var btnSettings: View
    private lateinit var btnSwitch: View
    private lateinit var btnRecord: View
    private lateinit var btnGallery: View
    private lateinit var btnMark: View
    private lateinit var btnTeam: TextView
    private lateinit var btnClearUdp: TextView
    private lateinit var controlsPanel: View
    private lateinit var tabBar: LinearLayout
    private lateinit var autoCheck: CheckBox
    private lateinit var lockCheck: CheckBox
    private lateinit var controlSeek: SeekBar
    private lateinit var controlValue: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var overlayEffect: OverlayEffect? = null
    private val effectThread = HandlerThread("overlay-effect").apply { start() }
    private val effectHandler = Handler(effectThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = OverlayRenderer()
    private var udp: UdpReceiver? = null
    private var remote: RemoteServer? = null
    private lateinit var rec: RecordingManager
    private lateinit var device: DeviceStatus
    private val camOwner = CameraLifecycleOwner()
    private var activityStarted = false
    private var fpsNote = ""
    private var shownWarnings = ""
    @Volatile private var udpStatus = ""
    private var udpAddress = ""

    // schopnosti aktuálnej kamery
    private var minFocus = 0f
    private var isoRange: Range<Int>? = null
    private var expRange: Range<Long>? = null
    private var manualSensor = false
    private var shutterList: List<Int> = emptyList()
    private var wbModes: List<Pair<Int, String>> = emptyList()
    private var fpsRanges: List<Range<Int>> = emptyList()
    private var hasEis = false
    private var hasOis = false
    private var cameraLabel = ""
    private var qualityLabel = ""
    private var zoomRatio = 1f
    private var ignoreZoomSaveUntil = 0L

    private var currentTab = Tab.ZOOM
    private var updatingUi = false

    // dotyky
    private var dragIndex = -1
    private var grabDx = 0f
    private var grabDy = 0f
    private var downX = 0f
    private var downY = 0f
    private var touchMoved = false
    private var multiTouch = false
    private var touchSlop = 0
    private lateinit var scaleDetector: ScaleGestureDetector

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            mainHandler.postDelayed(this, 250)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) startCamera()
            else toast("Bez povolenia kamery aplikácia nemôže nahrávať.")
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.initDefaults(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        applyOrientation()
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        gridView = findViewById(R.id.gridView)
        focusRing = findViewById(R.id.focusRing)
        recLabel = findViewById(R.id.recLabel)
        statusLabel = findViewById(R.id.statusLabel)
        btnTorch = findViewById(R.id.btnTorch)
        btnControls = findViewById(R.id.btnControls)
        btnSettings = findViewById(R.id.btnSettings)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnRecord = findViewById(R.id.btnRecord)
        btnGallery = findViewById(R.id.btnGallery)
        btnMark = findViewById(R.id.btnMark)
        btnTeam = findViewById(R.id.btnTeam)
        btnClearUdp = findViewById(R.id.btnClearUdp)
        controlsPanel = findViewById(R.id.controlsPanel)
        tabBar = findViewById(R.id.tabBar)
        autoCheck = findViewById(R.id.autoCheck)
        lockCheck = findViewById(R.id.lockCheck)
        controlSeek = findViewById(R.id.controlSeek)
        controlValue = findViewById(R.id.controlValue)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        OverlayState.load(prefs)
        rec = RecordingManager(this, prefs, this)
        device = DeviceStatus(this)
        OverlayState.listener = this
        setupButtons()
        setupControls()
        setupTouch()

        if (hasCameraPermission()) startCamera()
        else permLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> {
        val list = arrayListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        camOwner.start()
        OverlayState.load(prefs)
        startUdp()
        startRemote()
    }

    override fun onStop() {
        activityStarted = false
        val keepRunning = rec.isBusy && prefs.getBoolean("bg_record", false)
        if (!keepRunning) {
            if (rec.isUserRecording) rec.stopUser()
            stopBackgroundWork()
        }
        super.onStop()
    }

    /** Zastaví kameru, UDP a server (keď aktivita nie je viditeľná a nenahráva sa). */
    private fun stopBackgroundWork() {
        rec.onCameraUnbinding()
        camOwner.stop()
        udp?.stop()
        udp = null
        remote?.stop()
        remote = null
    }

    override fun onResume() {
        super.onResume()
        if (!rec.isUserRecording) applyOrientation()
        hideSystemUi()
        if (prefs.getBoolean("keep_screen_on", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gridView.visibility = if (prefs.getBoolean("grid", false)) View.VISIBLE else View.GONE
        OverlayState.load(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        if (cameraProvider != null) {
            if (rec.isBusy) preview?.setSurfaceProvider(previewView.surfaceProvider)
            else bindCamera()
        }
        updateButtons()
        updateTeamLabel()
        mainHandler.post(ticker)
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(rotationCheck)
        displayManager.unregisterDisplayListener(displayListener)
        super.onPause()
    }

    override fun onDestroy() {
        if (OverlayState.listener === this) OverlayState.listener = null
        rec.release()
        stopBackgroundWork()
        camOwner.destroy()
        RecordingService.stop(this)
        super.onDestroy()
        overlayEffect?.close()
        overlayEffect = null
        effectThread.quitSafely()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        val k = key ?: ""
        if (key == null || k.startsWith("ov") || k.startsWith("udp_") || k.startsWith("logo") ||
            k.startsWith("cmd_") || k == "event_name" || k == "profile_name" ||
            k == "team_name" || k == "camera_name" || k == "clear_mode"
        ) OverlayState.load(sp)
        if (key == null || k == "team_name" || k == "event_name" || k == "attempt_numbering" || k.startsWith("attempt_reset")) updateTeamLabel()
        if (key == null || k.startsWith("preroll")) rec.refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isRecKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        if (isRecKey && prefs.getBoolean("volume_record", true)) {
            if (event.repeatCount == 0) userToggle()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ orientácia

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }
    private var boundRotation = -1

    /** Otočenie o 180° (napr. šírka → opačná šírka) nevyvolá nové vytvorenie aktivity – treba znova naviazať kameru. */
    private val rotationCheck = Runnable {
        val rot = previewView.display?.rotation ?: return@Runnable
        if (rot != boundRotation && !rec.isBusy && cameraProvider != null) bindCamera()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            mainHandler.removeCallbacks(rotationCheck)
            mainHandler.postDelayed(rotationCheck, 300)
        }
    }

    /** Nastaví orientáciu podľa nastavení. @return true, ak sa zmenila. */
    private fun applyOrientation(): Boolean {
        val want = when (prefs.getString("orientation", "auto")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        if (requestedOrientation != want) {
            requestedOrientation = want
            return true
        }
        return false
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ UDP

    private fun startUdp() {
        udp?.stop()
        udp = null
        val ips = NetUtil.ipAddresses()
        udpAddress = if (ips.isEmpty()) "bez siete" else ips.joinToString(", ")
        if (!prefs.getBoolean("udp_enabled", true)) {
            udpStatus = "UDP vypnuté"
            return
        }
        val port = (prefs.getString("udp_port", "5000") ?: "5000").trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 5000
        val cs = try {
            Charset.forName(prefs.getString("udp_encoding", "UTF-8") ?: "UTF-8")
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        val group = prefs.getString("udp_multicast", "") ?: ""
        udpStatus = "UDP štartuje na porte $port"
        udp = UdpReceiver(
            this, port, cs, group,
            onMessage = { msg, from ->
                if (from.isNotEmpty()) OverlayState.lastSender = from
                OverlayState.onPacket(msg)
            },
            onStatus = { s -> udpStatus = s },
            announcePort = if (prefs.getBoolean("udp_announce", true)) Prefs.int(prefs, "udp_announce_port", 5001) else 0,
            deviceName = Prefs.str(prefs, "camera_name", "")
        ).also { it.start() }
    }

    // ------------------------------------------------------------------ UDP príkazy a texty

    override fun onCommand(cmd: String, arg: String) {
        mainHandler.post {
            if (!prefs.getBoolean("udp_commands", true)) return@post
            when (cmd) {
                "start" -> rec.startUser()
                "stop" -> rec.stopUser()
                "mark" -> rec.addMarker(arg)
                "team" -> setTeam(arg, fromRemote = true)
                "reset" -> resetAttempts(arg.ifEmpty { null }, fromRemote = true)
            }
        }
    }

    override fun onText(index: Int, text: String) {
        mainHandler.post { rec.onUdpText(index, text) }
    }

    // ------------------------------------------------------------------ nahrávanie – spätné volania

    override fun onStateChanged() {
        if (rec.isUserRecording) {
            // počas nahrávania sa obrazovka neotáča (inak by sa nahrávanie prerušilo)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else if (!rec.isBusy) {
            applyOrientation()
            if (!activityStarted) stopBackgroundWork()
        }
        updateButtons()
    }

    override fun onMessage(msg: String) = toast(msg)

    override fun onMarker(marker: Marker) {
        if (activityStarted) toast("📍 ${Markers.format(marker.ms)}  ${marker.text}")
    }

    override fun onSaved(file: File, markers: List<Marker>) {
        toast("Uložené: ${file.name}" + if (markers.isNotEmpty()) " (${markers.size} značiek)" else "")
        updateTeamLabel()
        if (prefs.getBoolean("auto_copy_gallery", false)) {
            val appCtx = applicationContext
            Thread { VideoStore.copyToGallery(appCtx, file) }.start()
        }
        if (prefs.getBoolean("replay_enabled", false) && activityStarted) openReplay(file, markers)
    }

    /** Okamžité prehratie posledného pokusu. */
    private fun openReplay(file: File, markers: List<Marker>) {
        val before = Prefs.int(prefs, "replay_before_s", 3) * 1000L
        val start = when (prefs.getString("replay_start", "marker")) {
            "marker" -> markers.firstOrNull()?.let { (it.ms - before).coerceAtLeast(0L) } ?: 0L
            "last_marker" -> markers.lastOrNull()?.let { (it.ms - before).coerceAtLeast(0L) } ?: 0L
            else -> 0L
        }
        val speed = (prefs.getString("replay_speed", "0.5") ?: "0.5").toFloatOrNull() ?: 0.5f
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PATH, file.absolutePath)
                .putExtra(PlayerActivity.EXTRA_START_MS, start)
                .putExtra(PlayerActivity.EXTRA_SPEED, speed)
        )
    }

    // ------------------------------------------------------------------ diaľkové ovládanie

    private fun startRemote() {
        remote?.stop()
        remote = null
        if (!prefs.getBoolean("remote_enabled", false)) return
        val port = Prefs.int(prefs, "remote_port", 8080).takeIf { it in 1024..65535 } ?: 8080
        remote = RemoteServer(port, Prefs.str(prefs, "remote_pin", ""), this).also { it.start() }
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    override fun statusJson(): String {
        val state = when (rec.state) {
            RecordingManager.State.RECORDING -> "nahráva"
            RecordingManager.State.PROCESSING -> "ukladá sa"
            RecordingManager.State.BUFFERING -> "pripravená (predstih)"
            RecordingManager.State.IDLE -> "pripravená"
        }
        val info = device.summary() + " • " + udpStatus +
            (if (OverlayState.lastPacket.isNotEmpty()) " • UDP: " + OverlayState.lastPacket.take(30) else "")
        return "{\"state\":\"${jsonEscape(state)}\",\"recording\":${rec.isUserRecording}," +
            "\"duration\":\"${formatDuration(rec.userElapsedMs() * 1_000_000L)}\"," +
            "\"info\":\"${jsonEscape(info)}\"}"
    }

    // znovu používané bitmapy pre stream (volá sa vždy z jedného vlákna naraz)
    private var remoteSrc: Bitmap? = null
    private var remoteDst: Bitmap? = null
    private val remotePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    private fun findTexture(v: View): android.view.TextureView? {
        if (v is android.view.TextureView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /**
     * Malý snímok náhľadu pre diaľkové ovládanie. Obraz sa zmenšuje priamo na GPU
     * (TextureView.getBitmap do malej bitmapy), takže je to rýchle.
     */
    override fun snapshot(): ByteArray? {
        if (!activityStarted) return null
        val targetW = Prefs.int(prefs, "remote_width", 640).coerceIn(240, 1920)
        val quality = Prefs.int(prefs, "remote_quality", 60).coerceIn(20, 95)
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        mainHandler.post {
            try {
                val tv = findTexture(previewView)
                val vw = previewView.width
                val vh = previewView.height
                val crop = contentRect()
                if (tv != null && tv.isAvailable && vw > 0 && vh > 0 && crop.width() > 0f && crop.height() > 0f) {
                    val sc = targetW / crop.width()
                    val sw = (vw * sc).toInt().coerceAtLeast(1)
                    val sh = (vh * sc).toInt().coerceAtLeast(1)
                    var src = remoteSrc
                    if (src == null || src.width != sw || src.height != sh) {
                        src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                        remoteSrc = src
                    }
                    tv.getBitmap(src)
                    val dw = targetW
                    val dh = (crop.height() * sc).toInt().coerceAtLeast(1)
                    var dst = remoteDst
                    if (dst == null || dst.width != dw || dst.height != dh) {
                        dst = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                        remoteDst = dst
                    }
                    // transformácia náhľadu (otočenie, prispôsobenie) prepočítaná na zmenšený obraz
                    val m = android.graphics.Matrix()
                    m.setScale(1f / sc, 1f / sc)
                    m.postConcat(tv.getTransform(null))
                    m.postScale(sc, sc)
                    m.postTranslate(-crop.left * sc, -crop.top * sc)
                    val c = android.graphics.Canvas(dst)
                    c.drawColor(Color.BLACK)
                    c.drawBitmap(src, m, remotePaint)
                    result = dst
                } else {
                    result = previewView.bitmap
                }
            } catch (_: Exception) {
            }
            latch.countDown()
        }
        if (!latch.await(1, TimeUnit.SECONDS)) return null
        val b = result ?: return null
        val out = ByteArrayOutputStream(64 * 1024)
        b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Snímky za sekundu pre stream. */
    override fun streamFps(): Int = Prefs.int(prefs, "remote_fps", 15).coerceIn(1, 30)

    override fun command(cmd: String, arg: String): String {
        val latch = CountDownLatch(1)
        var msg = ""
        mainHandler.post {
            msg = when (cmd) {
                "rec" -> { userStart(); "Nahrávanie spustené" }
                "stop" -> { userStop(); "Nahrávanie zastavené" }
                "toggle" -> { userToggle(); "OK" }
                "mark" -> { rec.addMarker(arg); if (rec.isUserRecording) "Značka pridaná" else "Značka sa dá pridať len počas nahrávania" }
                "clear" -> { OverlayState.clear(null); "Text z UDP vymazaný" }
                else -> "Neznámy príkaz"
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return msg
    }

    // ------------------------------------------------------------------ kamera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider", e)
                toast("Kameru sa nepodarilo spustiť: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun camId(info: CameraInfo): String = try {
        Camera2CameraInfo.from(info).cameraId
    } catch (_: Exception) {
        info.hashCode().toString()
    }

    private fun lensFacing(info: CameraInfo): Int? = try {
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    } catch (_: Exception) {
        null
    }

    private fun describeCamera(info: CameraInfo, index: Int, total: Int): String {
        val facing = when (lensFacing(info)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "predná"
            CameraCharacteristics.LENS_FACING_BACK -> "zadná"
            else -> "externá"
        }
        val focal = try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        val f = if (focal != null) String.format(Locale.US, " %.1fmm", focal) else ""
        return "Kamera ${index + 1}/$total ($facing$f)"
    }

    private fun qualitySelector(): QualitySelector {
        val code = prefs.getString("video_quality", "FHD") ?: "FHD"
        val q = when (code) {
            "UHD" -> Quality.UHD
            "FHD" -> Quality.FHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            "LOWEST" -> Quality.LOWEST
            else -> Quality.HIGHEST
        }
        return if (code == "HIGHEST" || code == "LOWEST") QualitySelector.from(q)
        else QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (rec.isBusy) return
        rec.onCameraUnbinding()
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        provider.unbindAll()
        overlayEffect?.close()
        overlayEffect = null
        camera = null

        val infos = provider.availableCameraInfos
        if (infos.isEmpty()) {
            toast("Nenašla sa žiadna kamera")
            return
        }
        val savedId = prefs.getString("cam_id", null)
        val info = infos.firstOrNull { camId(it) == savedId }
            ?: infos.firstOrNull { lensFacing(it) == CameraCharacteristics.LENS_FACING_BACK }
            ?: infos[0]
        val targetId = camId(info)
        cameraLabel = describeCamera(info, infos.indexOf(info), infos.size)
        val selector = CameraSelector.Builder()
            .addCameraFilter { list -> list.filter { camId(it) == targetId } }
            .build()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        this.preview = preview

        val recorderBuilder = Recorder.Builder().setQualitySelector(qualitySelector())
        val mbps = (prefs.getString("video_bitrate", "0") ?: "0").toIntOrNull() ?: 0
        if (mbps > 0) recorderBuilder.setTargetVideoEncodingBitRate(mbps * 1_000_000)
        val vc = VideoCapture.withOutput(recorderBuilder.build())

        val fps = (prefs.getString("video_fps", "0") ?: "0").toIntOrNull() ?: 0
        qualityLabel = (prefs.getString("video_quality", "FHD") ?: "FHD") +
            (if (fps > 0) " ${fps}fps" else "") + (if (mbps > 0) " ${mbps}Mbps" else "")

        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, effectHandler
        ) { t -> Log.e(TAG, "Overlay effect error", t) }
        effect.setOnDrawListener { frame -> drawFrame(frame) }

        val cam: Camera? = try {
            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(vc)
                .addEffect(effect)
                .build()
            val c = provider.bindToLifecycle(camOwner, selector, group)
            overlayEffect = effect
            c
        } catch (e: Exception) {
            Log.e(TAG, "Bind with overlay failed", e)
            effect.close()
            toast("Overlay do videa nie je na tomto zariadení podporený: ${e.message}")
            try {
                provider.unbindAll()
                provider.bindToLifecycle(camOwner, selector, preview, vc)
            } catch (e2: Exception) {
                Log.e(TAG, "Bind failed", e2)
                toast("Kameru sa nepodarilo spustiť: ${e2.message}")
                null
            }
        }
        if (cam == null) return
        camera = cam
        boundRotation = previewView.display?.rotation ?: -1
        rec.onCameraBound(vc)

        readCharacteristics(info)
        ignoreZoomSaveUntil = SystemClock.elapsedRealtime() + 1500
        applyAllCameraSettings()
        cam.cameraInfo.zoomState.observe(this) { zs ->
            zoomRatio = zs.zoomRatio
            if (SystemClock.elapsedRealtime() > ignoreZoomSaveUntil) {
                val lin = (zs.linearZoom * 100).roundToInt().coerceIn(0, 100)
                if (prefs.getInt("zoom", 0) != lin) prefs.edit().putInt("zoom", lin).apply()
                if (currentTab == Tab.ZOOM && !seekTracking) {
                    updatingUi = true
                    controlSeek.progress = lin
                    updatingUi = false
                }
            }
            if (currentTab == Tab.ZOOM) updateValueLabel()
        }
        showTab(currentTab)
        updateButtons()
    }

    private fun readCharacteristics(info: CameraInfo) {
        val c2 = Camera2CameraInfo.from(info)
        minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        expRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            isoRange != null && expRange != null
        val er = expRange
        shutterList = SHUTTERS.filter { den ->
            if (er == null) true else (1_000_000_000L / den) in er.lower..er.upper
        }
        val awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        wbModes = ALL_WB.filter { awb.contains(it.first) }
        fpsRanges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()
        hasEis = (c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: IntArray(0)).contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        hasOis = (c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?: IntArray(0)).contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    private fun applyAllCameraSettings() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(prefs.getBoolean("torch", false))
        cam.cameraControl.setLinearZoom(prefs.getInt("zoom", 0).coerceIn(0, 100) / 100f)
        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            val r = es.exposureCompensationRange
            cam.cameraControl.setExposureCompensationIndex(prefs.getInt("ev", 0).coerceIn(r.lower, r.upper))
        }
        applyCamera2()
    }

    /** Všetky "pokročilé" nastavenia cez Camera2 interop – musia sa posielať naraz. */
    private fun applyCamera2() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()

        // ostrenie
        if (prefs.getBoolean("manual_focus", false) && minFocus > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance())
        }

        // expozícia
        val fps = (prefs.getString("video_fps", "0") ?: "0").toIntOrNull() ?: 0
        if (prefs.getBoolean("manual_exposure", false) && manualSensor) {
            val exp = currentExposureNs()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso())
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            val frameNs = 1_000_000_000L / (if (fps > 0) fps else 30)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, frameNs))
        } else if (prefs.getBoolean("ae_lock", false)) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }

        // snímková frekvencia
        fpsNote = ""
        if (fps > 0) {
            val r = bestFpsRange(fps)
            if (r != null) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                if (r.upper != fps) fpsNote = "${fps}fps nepodporované → ${r.upper}fps"
            } else fpsNote = "fps nepodporované"
        }

        // vyváženie bielej
        val wb = prefs.getInt("wb_mode", CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (wbModes.any { it.first == wb }) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb)
        if (prefs.getBoolean("awb_lock", false)) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)

        // stabilizácia
        if (hasEis) {
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (prefs.getBoolean("stabilization", false)) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        if (hasOis) {
            b.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (prefs.getBoolean("ois", true)) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        // blikanie svetiel
        when (prefs.getString("antibanding", "auto")) {
            "50" -> b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ)
            "60" -> b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ)
            "off" -> b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF)
        }

        // šum a ostrosť
        when (prefs.getString("noise_reduction", "default")) {
            "off" -> b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            "fast" -> b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            "hq" -> b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        when (prefs.getString("edge_mode", "default")) {
            "off" -> b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            "fast" -> b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            "hq" -> b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }

        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 options", e)
        }
    }

    private fun bestFpsRange(fps: Int): Range<Int>? {
        val exact = fpsRanges.filter { it.upper == fps }.minByOrNull { it.upper - it.lower }
        if (exact != null) return exact
        return fpsRanges.minByOrNull { abs(it.upper - fps) * 100 + (it.upper - it.lower) }
    }

    private fun focusDistance(): Float = minFocus * prefs.getInt("focus", 0).coerceIn(0, 100) / 100f

    private fun currentIso(): Int {
        val r = isoRange ?: return 400
        return prefs.getInt("iso", 400).coerceIn(r.lower, r.upper)
    }

    private fun currentShutterDen(): Int {
        val den = prefs.getInt("shutter_den", 100)
        if (shutterList.isEmpty()) return den
        return shutterList.minByOrNull { abs(it - den) } ?: den
    }

    private fun currentExposureNs(): Long {
        val ns = 1_000_000_000L / currentShutterDen()
        val r = expRange ?: return ns
        return ns.coerceIn(r.lower, r.upper)
    }

    private fun isoFromProgress(p: Int): Int {
        val r = isoRange ?: return 400
        val lo = r.lower.toDouble().coerceAtLeast(1.0)
        val hi = r.upper.toDouble()
        return (lo * (hi / lo).pow(p / 100.0)).roundToInt()
    }

    private fun progressFromIso(iso: Int): Int {
        val r = isoRange ?: return 0
        val lo = r.lower.toDouble().coerceAtLeast(1.0)
        val hi = r.upper.toDouble()
        if (hi <= lo) return 0
        return (ln(iso / lo) / ln(hi / lo) * 100).roundToInt().coerceIn(0, 100)
    }

    // ------------------------------------------------------------------ overlay v obraze

    /** Volané pre každý snímok – kreslí overlay do náhľadu aj do videa. */
    private fun drawFrame(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val crop = frame.cropRect
        val cw = crop.width().toFloat()
        val ch = crop.height().toFloat()
        if (cw <= 0f || ch <= 0f) return true
        val rot = frame.rotationDegrees
        val swap = rot % 180 != 0
        val uw = if (swap) ch else cw
        val uh = if (swap) cw else ch
        canvas.save()
        canvas.translate(crop.exactCenterX(), crop.exactCenterY())
        canvas.rotate(-rot.toFloat())
        if (frame.isMirroring) canvas.scale(-1f, 1f)
        canvas.translate(-uw / 2f, -uh / 2f)
        renderer.draw(canvas, uw, uh)
        canvas.restore()
        return true
    }

    /** Oblasť v PreviewView, kde je reálne zobrazený obraz (fitCenter). */
    private fun contentRect(): RectF {
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        val a = renderer.aspect
        if (vw <= 0f || vh <= 0f || a <= 0f) return RectF(0f, 0f, vw, vh)
        return if (vw / vh > a) {
            val w = vh * a
            RectF((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        } else {
            val h = vw / a
            RectF(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        }
    }

    private fun hitOverlay(x: Float, y: Float): Int {
        val r = contentRect()
        val extra = 20 * resources.displayMetrics.density
        val boxes = renderer.boxes
        for (i in boxes.indices.reversed()) {
            val b = boxes[i] ?: continue
            val vr = RectF(
                r.left + b.left * r.width() - extra, r.top + b.top * r.height() - extra,
                r.left + b.right * r.width() + extra, r.top + b.bottom * r.height() + extra
            )
            if (vr.contains(x, y)) return i
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                val ratio = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                ignoreZoomSaveUntil = 0L
                cam.cameraControl.setZoomRatio(ratio)
                return true
            }
        })

        previewView.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multiTouch = false
                    touchMoved = false
                    downX = ev.x
                    downY = ev.y
                    dragIndex = hitOverlay(ev.x, ev.y)
                    if (dragIndex >= 0) {
                        val r = contentRect()
                        val b = renderer.boxes[dragIndex]
                        if (b != null && r.width() > 0f && r.height() > 0f) {
                            grabDx = (ev.x - r.left) / r.width() - b.left
                            grabDy = (ev.y - r.top) / r.height() - b.top
                        } else dragIndex = -1
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    dragIndex = -1
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!multiTouch) {
                        if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) touchMoved = true
                        if (dragIndex >= 0 && touchMoved) moveOverlay(dragIndex, ev.x, ev.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragIndex >= 0 && touchMoved) {
                        saveOverlayPosition(dragIndex)
                    } else if (!multiTouch && !touchMoved) {
                        tapToFocus(ev.x, ev.y)
                        v.performClick()
                    }
                    dragIndex = -1
                }
                MotionEvent.ACTION_CANCEL -> dragIndex = -1
            }
            true
        }
    }

    private fun moveOverlay(i: Int, x: Float, y: Float) {
        val r = contentRect()
        val b = renderer.boxes[i] ?: return
        if (r.width() <= 0f || r.height() <= 0f) return
        val left = (x - r.left) / r.width() - grabDx
        val top = (y - r.top) / r.height() - grabDy
        val bw = b.width()
        val bh = b.height()
        val px = if (bw < 1f) left / (1f - bw) else 0f
        val py = if (bh < 1f) top / (1f - bh) else 0f
        OverlayState.setPosition(i, px, py)
    }

    private fun saveOverlayPosition(i: Int) {
        val (x, y) = OverlayState.positionOf(i) ?: return
        val (kx, ky) = OverlayState.positionKeys(i)
        prefs.edit()
            .putInt(kx, (x * 100).roundToInt())
            .putInt(ky, (y * 100).roundToInt())
            .apply()
    }

    private fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        if (!prefs.getBoolean("tap_to_focus", true)) return
        if (prefs.getBoolean("manual_focus", false)) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRing.animate().cancel()
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    // ------------------------------------------------------------------ tlačidlá

    private fun setupButtons() {
        btnRecord.setOnClickListener { userToggle() }
        btnTeam.setOnClickListener { chooseTeam() }
        btnClearUdp.setOnClickListener {
            OverlayState.clear(null)
            toast("Text z UDP vymazaný")
        }
        btnClearUdp.setOnLongClickListener {
            val items = (1..TEXT_OVERLAYS).map { "Vymazať iba overlay $it" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Vymazať text z UDP")
                .setItems(items) { _, w -> OverlayState.clear(w) }
                .show()
            true
        }
        btnMark.setOnClickListener { rec.addMarker("") }

        btnTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (!cam.cameraInfo.hasFlashUnit()) {
                toast("Táto kamera nemá svetlo")
                return@setOnClickListener
            }
            val on = !prefs.getBoolean("torch", false)
            prefs.edit().putBoolean("torch", on).apply()
            cam.cameraControl.enableTorch(on)
            updateButtons()
        }

        btnSwitch.setOnClickListener {
            if (rec.isBusy) return@setOnClickListener
            val provider = cameraProvider ?: return@setOnClickListener
            val infos = provider.availableCameraInfos
            if (infos.size < 2) {
                toast("K dispozícii je iba jedna kamera")
                return@setOnClickListener
            }
            val currentId = camera?.let { camId(it.cameraInfo) }
            val idx = infos.indexOfFirst { camId(it) == currentId }
            val next = infos[(idx + 1).mod(infos.size)]
            prefs.edit().putString("cam_id", camId(next)).putBoolean("torch", false).apply()
            bindCamera()
            toast(cameraLabel)
        }

        btnControls.setOnClickListener {
            controlsPanel.visibility = if (controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateButtons()
        }

        btnSettings.setOnClickListener {
            if (rec.isBusy) toast("Najprv zastav nahrávanie")
            else startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnGallery.setOnClickListener {
            if (rec.isBusy) toast("Najprv zastav nahrávanie")
            else startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    private fun updateButtons() {
        val torchOn = prefs.getBoolean("torch", false)
        btnTorch.alpha = if (camera?.cameraInfo?.hasFlashUnit() == true) 1f else 0.35f
        btnTorch.setBackgroundResource(if (torchOn) R.drawable.chip_bg_selected else R.drawable.btn_round)
        btnControls.setBackgroundResource(
            if (controlsPanel.visibility == View.VISIBLE) R.drawable.chip_bg_selected else R.drawable.btn_round
        )
        val lockUi = rec.isBusy
        btnSwitch.alpha = if (lockUi) 0.35f else 1f
        btnSettings.alpha = if (lockUi) 0.35f else 1f
        btnGallery.alpha = if (lockUi) 0.35f else 1f
        btnRecord.setBackgroundResource(if (rec.isUserRecording) R.drawable.btn_record_stop else R.drawable.btn_record)
        btnRecord.alpha = if (rec.state == RecordingManager.State.PROCESSING) 0.4f else 1f
        btnMark.visibility = if (rec.isUserRecording) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ družstvá a viac kamier

    private fun isMaster() = prefs.getString("camera_role", "single") == "master"

    private fun udpPort() = Prefs.int(prefs, "udp_port", 5000).takeIf { it in 1..65535 } ?: 5000

    /** Nahrávanie spustené na tomto telefóne (tlačidlo, hlasitosť, web) – hlavná kamera ho pošle ďalej. */
    private fun userStart() {
        rec.startUser()
        if (isMaster()) CamLink.send(udpPort(), OverlayState.cmdStart)
    }

    private fun userStop() {
        rec.stopUser()
        if (isMaster()) CamLink.send(udpPort(), OverlayState.cmdStop)
    }

    private fun userToggle() {
        if (rec.isUserRecording) userStop() else if (!rec.isBusy) userStart()
    }

    private fun setTeam(name: String, fromRemote: Boolean = false) {
        val n = name.trim()
        prefs.edit().putString("team_name", n).apply()
        if (n.isNotEmpty()) Teams.add(prefs, n)
        OverlayState.teamName = n
        updateTeamLabel()
        if (!fromRemote && isMaster()) CamLink.send(udpPort(), "${OverlayState.cmdTeam}:$n")
        if (fromRemote && activityStarted) toast("Družstvo: ${n.ifEmpty { "—" }}")
    }

    private fun updateTeamLabel() {
        if (!::btnTeam.isInitialized) return
        val team = Prefs.str(prefs, "team_name", "")
        if (team.isEmpty()) {
            btnTeam.text = "Družstvo: —"
            return
        }
        val appCtx = applicationContext
        val event = Prefs.str(prefs, "event_name", "")
        btnTeam.text = "Družstvo: $team"
        Thread {
            val no = Teams.nextAttemptNo(appCtx, event, team)
            mainHandler.post {
                if (Prefs.str(prefs, "team_name", "") == team) btnTeam.text = "Družstvo: $team • $no. pokus"
            }
        }.start()
    }

    private fun chooseTeam() {
        if (rec.isBusy) {
            toast("Družstvo sa mení pred nahrávaním")
            return
        }
        val teams = Teams.list(prefs)
        val labels = ArrayList<String>()
        labels.add("➕ Nové družstvo…")
        labels.add("Bez družstva")
        labels.addAll(teams)
        labels.add("🔄 Vynulovať počítadlo pokusov…")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Družstvo pre ďalší pokus")
            .setItems(labels.toTypedArray()) { _, w ->
                when {
                    w == 0 -> newTeamDialog()
                    w == 1 -> setTeam("")
                    w == labels.size - 1 -> resetAttemptsDialog()
                    else -> setTeam(teams[w - 2])
                }
            }
            .show()
    }

    /** Vynulovanie počítadla pokusov – ďalší pokus bude opäť 1. */
    private fun resetAttemptsDialog() {
        val team = Prefs.str(prefs, "team_name", "")
        val options = ArrayList<String>()
        if (team.isNotEmpty()) options.add("Len družstvo „$team“")
        options.add("Všetky družstvá")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Vynulovať počítadlo pokusov")
            .setItems(options.toTypedArray()) { _, w ->
                val onlyTeam = team.isNotEmpty() && w == 0
                resetAttempts(if (onlyTeam) team else null)
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun resetAttempts(team: String?, fromRemote: Boolean = false) {
        Teams.resetCounter(prefs, team)
        updateTeamLabel()
        if (!fromRemote && isMaster()) {
            CamLink.send(udpPort(), if (team == null) OverlayState.cmdReset else "${OverlayState.cmdReset}:$team")
        }
        toast(if (team == null) "Pokusy všetkých družstiev sa rátajú znova od 1" else "Pokusy „$team“ sa rátajú znova od 1")
    }

    private fun newTeamDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "napr. Hasiči Dolany"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nové družstvo")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> setTeam(input.text.toString()) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // ------------------------------------------------------------------ stav

    private fun formatDuration(nanos: Long): String {
        val total = nanos / 1_000_000_000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Ukazovateľ hlasitosti mikrofónu z amplitúdy 0..1. */
    private fun levelBar(a: Double): String {
        if (a < 0) return ""
        val db = if (a > 0) 20 * kotlin.math.log10(a) else -60.0
        val n = ((db + 60) / 60 * 8).roundToInt().coerceIn(0, 8)
        return "  🎤" + "▮".repeat(n) + "▯".repeat(8 - n) + if (n >= 8) " !" else ""
    }

    private var statusTick = 0

    private fun updateStatus() {
        when (rec.state) {
            RecordingManager.State.RECORDING -> {
                recLabel.visibility = View.VISIBLE
                val blink = (SystemClock.elapsedRealtime() / 500) % 2 == 0L
                recLabel.text = (if (blink) "● " else "○ ") + "REC " +
                    formatDuration(rec.userElapsedMs() * 1_000_000L) + levelBar(rec.audioLevel) +
                    (if (rec.collector.markers.isNotEmpty()) "  📍${rec.collector.markers.size}" else "")
            }
            RecordingManager.State.PROCESSING -> {
                recLabel.visibility = View.VISIBLE
                recLabel.text = "⏳ Ukladám…"
            }
            RecordingManager.State.BUFFERING -> {
                recLabel.visibility = View.VISIBLE
                recLabel.text = "◌ predstih ${Prefs.int(prefs, "preroll_seconds", 5)} s pripravený"
            }
            else -> recLabel.visibility = View.GONE
        }

        // stav zariadenia stačí zisťovať raz za 2 s
        if (statusTick++ % 8 == 0) {
            device.update(prefs)
            val w = device.warnings.joinToString(", ")
            if (w.isNotEmpty() && w != shownWarnings) toast("⚠ $w")
            shownWarnings = w
        }

        if (prefs.getBoolean("show_status", true)) {
            val sb = StringBuilder()
            sb.append(cameraLabel).append(" • ").append(qualityLabel)
                .append(" • ").append(String.format(Locale.US, "%.1fx", zoomRatio))
            if (overlayEffect == null && camera != null) sb.append(" • overlay nedostupný")
            if (fpsNote.isNotEmpty()) sb.append(" • ").append(fpsNote)
            if (OverlayState.eventName.isNotEmpty()) sb.append(" • 🏆 ").append(OverlayState.eventName)
            if (OverlayState.profileName.isNotEmpty()) sb.append(" • 👤 ").append(OverlayState.profileName)
            sb.append('\n').append(device.summary())
            if (device.warnings.isNotEmpty()) sb.append("  ⚠ ").append(device.warnings.joinToString(", "))
            sb.append('\n').append(udpStatus).append(" • IP: ").append(udpAddress)
            if (OverlayState.lastSender.isNotEmpty()) sb.append(" • vysielač: ").append(OverlayState.lastSender)
            remote?.let { sb.append(" • 🌐 :").append(Prefs.int(prefs, "remote_port", 8080)) }
            val last = OverlayState.lastPacketAt
            if (last > 0) {
                val age = (SystemClock.elapsedRealtime() - last) / 1000f
                sb.append('\n').append("posledná správa pred ")
                    .append(String.format(Locale.US, "%.1f s", age)).append(": ")
                    .append(OverlayState.lastPacket.replace('\n', ' ').take(40))
            }
            statusLabel.text = sb
            statusLabel.visibility = View.VISIBLE
        } else {
            statusLabel.visibility = View.GONE
        }

        if (gridView.visibility == View.VISIBLE) gridView.setContent(contentRect())
    }

    // ------------------------------------------------------------------ panel manuálnych nastavení

    private var seekTracking = false

    private fun setupControls() {
        val dp = resources.displayMetrics.density
        for (tab in Tab.values()) {
            val tv = TextView(this).apply {
                text = tab.label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { showTab(tab) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * dp).toInt()
            tabBar.addView(tv, lp)
        }

        controlSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingUi) onSeek(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seekTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekTracking = false
            }
        })

        autoCheck.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            when (currentTab) {
                Tab.FOCUS -> prefs.edit().putBoolean("manual_focus", !checked).apply()
                Tab.ISO, Tab.SHUTTER -> {
                    if (!checked && !manualSensor) {
                        toast("Táto kamera nepodporuje manuálnu expozíciu")
                    } else {
                        prefs.edit().putBoolean("manual_exposure", !checked).apply()
                    }
                }
                else -> Unit
            }
            applyCamera2()
            showTab(currentTab)
        }

        lockCheck.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            when (currentTab) {
                Tab.EV -> prefs.edit().putBoolean("ae_lock", checked).apply()
                Tab.WB -> prefs.edit().putBoolean("awb_lock", checked).apply()
                else -> Unit
            }
            applyCamera2()
        }
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        for (i in 0 until tabBar.childCount) {
            val child = tabBar.getChildAt(i)
            child.setBackgroundResource(if (i == tab.ordinal) R.drawable.chip_bg_selected else R.drawable.chip_bg)
        }
        updatingUi = true
        autoCheck.visibility = View.GONE
        lockCheck.visibility = View.GONE
        autoCheck.isEnabled = true
        controlSeek.isEnabled = camera != null
        val cam = camera
        val manualExp = prefs.getBoolean("manual_exposure", false) && manualSensor

        when (tab) {
            Tab.ZOOM -> {
                controlSeek.max = 100
                controlSeek.progress = prefs.getInt("zoom", 0)
            }
            Tab.EV -> {
                val es = cam?.cameraInfo?.exposureState
                if (es == null || !es.isExposureCompensationSupported) {
                    controlSeek.max = 1
                    controlSeek.progress = 0
                    controlSeek.isEnabled = false
                } else {
                    val r = es.exposureCompensationRange
                    controlSeek.max = r.upper - r.lower
                    controlSeek.progress = prefs.getInt("ev", 0).coerceIn(r.lower, r.upper) - r.lower
                    controlSeek.isEnabled = !manualExp
                }
                lockCheck.visibility = View.VISIBLE
                lockCheck.text = "Zámok AE"
                lockCheck.isChecked = prefs.getBoolean("ae_lock", false)
            }
            Tab.FOCUS -> {
                autoCheck.visibility = View.VISIBLE
                autoCheck.isChecked = !prefs.getBoolean("manual_focus", false)
                autoCheck.isEnabled = minFocus > 0f
                controlSeek.max = 100
                controlSeek.progress = prefs.getInt("focus", 0)
                controlSeek.isEnabled = !autoCheck.isChecked && minFocus > 0f
            }
            Tab.ISO -> {
                autoCheck.visibility = View.VISIBLE
                autoCheck.isChecked = !manualExp
                autoCheck.isEnabled = manualSensor
                controlSeek.max = 100
                controlSeek.progress = progressFromIso(currentIso())
                controlSeek.isEnabled = manualExp
            }
            Tab.SHUTTER -> {
                autoCheck.visibility = View.VISIBLE
                autoCheck.isChecked = !manualExp
                autoCheck.isEnabled = manualSensor
                controlSeek.max = (shutterList.size - 1).coerceAtLeast(0)
                controlSeek.progress = shutterList.indexOf(currentShutterDen()).coerceAtLeast(0)
                controlSeek.isEnabled = manualExp && shutterList.isNotEmpty()
            }
            Tab.WB -> {
                controlSeek.max = (wbModes.size - 1).coerceAtLeast(0)
                val wb = prefs.getInt("wb_mode", CameraMetadata.CONTROL_AWB_MODE_AUTO)
                controlSeek.progress = wbModes.indexOfFirst { it.first == wb }.coerceAtLeast(0)
                controlSeek.isEnabled = wbModes.size > 1
                lockCheck.visibility = View.VISIBLE
                lockCheck.text = "Zámok WB"
                lockCheck.isChecked = prefs.getBoolean("awb_lock", false)
            }
        }
        updatingUi = false
        updateValueLabel()
    }

    private fun onSeek(p: Int) {
        val cam = camera ?: return
        when (currentTab) {
            Tab.ZOOM -> {
                prefs.edit().putInt("zoom", p).apply()
                ignoreZoomSaveUntil = SystemClock.elapsedRealtime() + 300
                cam.cameraControl.setLinearZoom(p / 100f)
            }
            Tab.EV -> {
                val r = cam.cameraInfo.exposureState.exposureCompensationRange
                val idx = r.lower + p
                prefs.edit().putInt("ev", idx).apply()
                cam.cameraControl.setExposureCompensationIndex(idx)
            }
            Tab.FOCUS -> {
                prefs.edit().putInt("focus", p).apply()
                applyCamera2()
            }
            Tab.ISO -> {
                prefs.edit().putInt("iso", isoFromProgress(p)).apply()
                applyCamera2()
            }
            Tab.SHUTTER -> {
                shutterList.getOrNull(p)?.let { prefs.edit().putInt("shutter_den", it).apply() }
                applyCamera2()
            }
            Tab.WB -> {
                wbModes.getOrNull(p)?.let { prefs.edit().putInt("wb_mode", it.first).apply() }
                applyCamera2()
            }
        }
        updateValueLabel()
    }

    private fun updateValueLabel() {
        val cam = camera
        val manualExp = prefs.getBoolean("manual_exposure", false) && manualSensor
        controlValue.text = when (currentTab) {
            Tab.ZOOM -> String.format(Locale.US, "%.1fx", zoomRatio)
            Tab.EV -> {
                val es = cam?.cameraInfo?.exposureState
                if (es == null || !es.isExposureCompensationSupported) "nepodporované"
                else if (manualExp) "manuálne"
                else {
                    val idx = prefs.getInt("ev", 0)
                    String.format(Locale.US, "%+.1f EV", idx * es.exposureCompensationStep.toFloat())
                }
            }
            Tab.FOCUS -> {
                if (minFocus <= 0f) "pevné ohnisko"
                else if (!prefs.getBoolean("manual_focus", false)) "Auto (AF)"
                else {
                    val d = focusDistance()
                    if (d <= 0.001f) "∞" else String.format(Locale.US, "%.2f m", 1f / d)
                }
            }
            Tab.ISO -> if (!manualSensor) "nepodporované" else if (!manualExp) "Auto" else "ISO ${currentIso()}"
            Tab.SHUTTER -> if (!manualSensor) "nepodporované" else if (!manualExp) "Auto" else "1/${currentShutterDen()} s"
            Tab.WB -> {
                val wb = prefs.getInt("wb_mode", CameraMetadata.CONTROL_AWB_MODE_AUTO)
                wbModes.firstOrNull { it.first == wb }?.second ?: "Auto"
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
