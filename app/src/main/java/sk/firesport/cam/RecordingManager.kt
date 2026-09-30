package sk.firesport.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Riadi nahrávanie:
 *  - bežné nahrávanie,
 *  - predstih (neustále sa nahrávajú krátke úseky, pri štarte sa pridá posledný),
 *  - automatické zastavenie, značky, nahrávanie na pozadí.
 * Všetky metódy volať z hlavného vlákna.
 */
class RecordingManager(
    context: Context,
    private val prefs: SharedPreferences,
    private val cb: Callback
) {
    interface Callback {
        fun onStateChanged()
        fun onSaved(file: File, markers: List<Marker>)
        fun onMessage(msg: String)
        fun onMarker(marker: Marker)
    }

    enum class State { IDLE, BUFFERING, RECORDING, PROCESSING }

    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private inner class Session(val file: File, var user: Boolean) {
        var recording: Recording? = null
        var startedAt = 0L
        var lastNanos = 0L
        var lastAt = 0L
        var discard = false
        var rotate = false
        var userOffsetMs = 0L

        fun elapsedMs(): Long {
            val now = SystemClock.elapsedRealtime()
            return when {
                lastAt > 0 -> lastNanos / 1_000_000 + (now - lastAt)
                startedAt > 0 -> now - startedAt
                else -> 0L
            }
        }
    }

    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var state = State.IDLE
        private set

    /** Posledná hlasitosť mikrofónu 0..1 (-1 = neznáma). */
    var audioLevel = -1.0
        private set

    private var active: Session? = null
    private var prevSegment: File? = null
    private var prevSegmentMs = 0L
    private var pendingUserStart = false
    private var autoStopAt = 0L
    private var userPressedAt = 0L
    private var userStartEpoch = 0L
    private var cameraReady = false
    val collector = MarkerCollector()

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 200)
        }
    }

    init {
        VideoStore.clearBuffer(ctx)
        handler.post(tick)
        collector.onFinal = { m ->
            val after = Prefs.int(prefs, "auto_stop_after_final", 0)
            if (after > 0 && state == State.RECORDING) {
                val at = SystemClock.elapsedRealtime() + after * 1000L
                autoStopAt = if (autoStopAt > 0) minOf(autoStopAt, at) else at
            }
            cb.onMarker(m)
        }
    }

    // ------------------------------------------------------------------ nastavenia

    private fun bufferEnabled() = prefs.getBoolean("preroll_enabled", false)
    private fun bufferSeconds() = Prefs.int(prefs, "preroll_seconds", 5).coerceIn(1, 60)
    private fun audioOk() = prefs.getBoolean("audio_enabled", true) &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val isUserRecording get() = state == State.RECORDING
    val isBusy get() = state == State.RECORDING || state == State.PROCESSING

    /** Dĺžka aktuálneho nahrávania od stlačenia (ms). */
    fun userElapsedMs(): Long = if (state == State.RECORDING && userPressedAt > 0)
        SystemClock.elapsedRealtime() - userPressedAt else 0L

    // ------------------------------------------------------------------ kamera

    fun onCameraBound(vc: VideoCapture<Recorder>) {
        videoCapture = vc
        cameraReady = true
        if (state == State.IDLE && bufferEnabled()) startSegment(false)
    }

    /** Pred zmenou kamery / zastavením: zahodí predstih (bežiace nahrávanie nechá). */
    fun onCameraUnbinding() {
        cameraReady = false
        if (state == State.BUFFERING) {
            active?.let {
                it.discard = true
                it.recording?.stop()
            }
            active = null
            state = State.IDLE
            dropPrev()
        }
    }

    /** Nastavenia sa zmenili – prípadne zapne/vypne predstih. */
    fun refresh() {
        if (!cameraReady) return
        if (state == State.IDLE && bufferEnabled()) startSegment(false)
        else if (state == State.BUFFERING && !bufferEnabled()) {
            onCameraUnbinding()
            cameraReady = true
        }
    }

    fun release() {
        if (state == State.RECORDING) stopUser()
        else onCameraUnbinding()
        handler.removeCallbacks(tick)
    }

    private fun dropPrev() {
        prevSegment?.delete()
        prevSegment = null
        prevSegmentMs = 0L
    }

    // ------------------------------------------------------------------ ovládanie

    fun toggle() {
        if (state == State.RECORDING) stopUser() else startUser()
    }

    fun startUser() {
        when (state) {
            State.RECORDING, State.PROCESSING -> return
            State.BUFFERING -> {
                val s = active
                if (s != null && !s.rotate && s.recording != null) {
                    s.user = true
                    s.userOffsetMs = s.elapsedMs()
                    enterRecording()
                } else {
                    // práve sa strieda úsek predstihu – začne sa hneď po ňom
                    pendingUserStart = true
                    enterRecording()
                }
            }
            State.IDLE -> {
                if (videoCapture == null) {
                    cb.onMessage("Kamera nie je pripravená")
                    return
                }
                if (startSegment(true)) enterRecording()
            }
        }
    }

    fun stopUser() {
        if (state != State.RECORDING) return
        val s = active
        if (pendingUserStart || s == null) {
            pendingUserStart = false
            state = if (s != null) State.BUFFERING else State.IDLE
            leaveRecording()
            return
        }
        state = State.PROCESSING
        leaveRecording()
        s.recording?.stop()
    }

    /** Pridá značku v aktuálnom čase nahrávania. */
    fun addMarker(text: String) {
        val s = active ?: return
        if (state != State.RECORDING || !s.user) return
        val label = text.ifEmpty { "Značka ${collector.markers.size + 1}" }
        val m = Marker(s.elapsedMs(), label)
        if (collector.add(m)) cb.onMarker(m)
    }

    /** Text z UDP (volať z hlavného vlákna). */
    fun onUdpText(i: Int, text: String) {
        val s = active ?: return
        if (state != State.RECORDING || !s.user) return
        collector.onText(i, text, s.elapsedMs())
    }

    private fun enterRecording() {
        state = State.RECORDING
        userPressedAt = SystemClock.elapsedRealtime()
        userStartEpoch = System.currentTimeMillis()
        OverlayState.recStartedAt = userPressedAt
        OverlayState.attemptNo = Teams.nextAttemptNo(ctx, OverlayState.eventName, OverlayState.teamName)
        collector.mode = prefs.getString("marker_mode", "stable") ?: "stable"
        collector.stableMs = Prefs.int(prefs, "marker_stable_ms", 1000).toLong().coerceAtLeast(100L)
        collector.ignoreZero = prefs.getBoolean("marker_ignore_zero", true)
        collector.reset((0 until TEXT_OVERLAYS).map { OverlayState.displayText(it) })
        val maxS = Prefs.int(prefs, "auto_stop_after", 0)
        autoStopAt = if (maxS > 0) userPressedAt + maxS * 1000L else 0L
        if (prefs.getBoolean("bg_record", false)) RecordingService.start(ctx)
        cb.onStateChanged()
    }

    private fun leaveRecording() {
        OverlayState.recStartedAt = 0L
        autoStopAt = 0L
        cb.onStateChanged()
    }

    private fun onTick() {
        val s = active
        if (state == State.BUFFERING && s != null && !s.rotate && s.recording != null) {
            if (s.elapsedMs() >= bufferSeconds() * 1000L) {
                s.rotate = true
                s.recording?.stop()
            }
        }
        if (state == State.RECORDING) {
            collector.tick()
            if (autoStopAt > 0 && SystemClock.elapsedRealtime() >= autoStopAt) {
                cb.onMessage("Automatické zastavenie")
                stopUser()
            }
        }
    }

    // ------------------------------------------------------------------ nahrávanie

    @SuppressLint("MissingPermission")
    private fun startSegment(user: Boolean): Boolean {
        val vc = videoCapture ?: return false
        val file = File(VideoStore.bufferDir(ctx), "seg_${System.currentTimeMillis()}.mp4")
        val s = Session(file, user)
        return try {
            var pending = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
            if (audioOk()) pending = pending.withAudioEnabled()
            s.recording = pending.start(ContextCompat.getMainExecutor(ctx)) { e -> onEvent(s, e) }
            active = s
            if (!user) state = State.BUFFERING
            true
        } catch (e: Exception) {
            Log.e("FiresportCam", "start segment", e)
            cb.onMessage("Nahrávanie sa nepodarilo spustiť: ${e.message}")
            false
        }
    }

    private fun onEvent(s: Session, e: VideoRecordEvent) {
        val stats = e.recordingStats
        s.lastNanos = stats.recordedDurationNanos
        s.lastAt = SystemClock.elapsedRealtime()
        when (e) {
            is VideoRecordEvent.Start -> {
                s.startedAt = SystemClock.elapsedRealtime()
                s.lastAt = 0L
            }
            is VideoRecordEvent.Status -> {
                if (s.user) {
                    audioLevel = try {
                        stats.audioStats.audioAmplitude
                    } catch (_: Throwable) {
                        -1.0
                    }
                }
            }
            is VideoRecordEvent.Finalize -> onFinalize(s, e)
            else -> Unit
        }
    }

    private fun onFinalize(s: Session, e: VideoRecordEvent.Finalize) {
        val ok = s.file.exists() && s.file.length() > 0
        if (active === s) active = null

        if (s.discard) {
            s.file.delete()
            return
        }

        if (!s.user) {
            if (s.rotate && ok) {
                prevSegment?.delete()
                prevSegment = s.file
                prevSegmentMs = s.lastNanos / 1_000_000
            } else {
                s.file.delete()
            }
            when {
                pendingUserStart -> {
                    pendingUserStart = false
                    if (!startSegment(true)) {
                        state = State.IDLE
                        leaveRecording()
                    }
                }
                state == State.BUFFERING && cameraReady && bufferEnabled() -> startSegment(false)
                state == State.BUFFERING -> state = State.IDLE
            }
            return
        }

        // používateľské nahrávanie
        audioLevel = -1.0
        if (prefs.getBoolean("bg_record", false)) RecordingService.stop(ctx)
        if (state == State.RECORDING) {
            // skončilo samé (chyba / kamera odpojená)
            leaveRecording()
        }
        if (!ok) {
            state = State.IDLE
            cb.onMessage("Chyba nahrávania (kód ${e.error})")
            dropPrev()
            cb.onStateChanged()
            restartBuffer()
            return
        }
        state = State.PROCESSING
        cb.onStateChanged()

        val markers = ArrayList(collector.markers)
        // údaje o pokuse: finálne časy (ustálené), inak posledný prijatý text
        val finals = (0 until TEXT_OVERLAYS).mapNotNull { i ->
            val st = OverlayState.styles.getOrNull(i)
            if (st == null || !st.enabled) return@mapNotNull null
            (collector.finals[i] ?: OverlayState.lastText(i))?.trim()?.takeIf { Times.parse(it) != null }
        }.distinct()
        val info = AttemptInfo(
            team = OverlayState.teamName,
            event = OverlayState.eventName,
            camera = OverlayState.cameraName,
            attemptNo = OverlayState.attemptNo,
            startEpochMs = userStartEpoch,
            durationMs = s.lastNanos / 1_000_000 - s.userOffsetMs,
            finals = finals
        )
        pendingInfo = info
        val target = VideoStore.newFile(ctx, OverlayState.eventName, baseName(info))
        val prerollMs = bufferSeconds() * 1000L
        val prev = prevSegment
        val need = prerollMs - s.userOffsetMs

        if (bufferEnabled() && prev != null && need > 0 && prevSegmentMs > 0) {
            val clipStart = (prevSegmentMs - need).coerceAtLeast(0L)
            val shift = prevSegmentMs - clipStart
            cb.onMessage("Spájam predstih…")
            VideoEditor.export(
                ctx,
                listOf(VideoEditor.Clip(prev, clipStart), VideoEditor.Clip(s.file)),
                target
            ) { success, err ->
                if (success) {
                    s.file.delete()
                    finish(target, markers.map { Marker(it.ms + shift, it.text) })
                } else {
                    Log.e("FiresportCam", "concat: $err")
                    // záloha: uloží sa aspoň hlavné video
                    VideoStore.move(s.file, target)
                    finish(target, markers)
                }
                dropPrev()
            }
        } else {
            VideoStore.move(s.file, target)
            dropPrev()
            finish(target, markers)
        }
    }

    private var pendingInfo: AttemptInfo? = null

    /** Začiatok názvu súboru: družstvo, čas, číslo pokusu, kamera. */
    private fun baseName(info: AttemptInfo): String {
        if (!prefs.getBoolean("name_by_team", true)) return ""
        val parts = ArrayList<String>()
        if (info.team.isNotEmpty()) parts.add(info.team)
        Times.forFileName(info.result).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (info.attemptNo > 0) parts.add("${info.attemptNo}.pokus")
        if (info.camera.isNotEmpty()) parts.add(info.camera)
        return parts.joinToString("_")
    }

    private fun finish(target: File, markers: List<Marker>) {
        Markers.save(target, markers)
        pendingInfo?.let { Sidecars.saveInfo(target, it) }
        pendingInfo = null
        state = State.IDLE
        cb.onStateChanged()
        cb.onSaved(target, markers)
        restartBuffer()
    }

    private fun restartBuffer() {
        if (state == State.IDLE && cameraReady && bufferEnabled()) startSegment(false)
    }
}
