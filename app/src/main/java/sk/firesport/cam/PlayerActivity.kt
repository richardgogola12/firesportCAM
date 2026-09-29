package sk.firesport.cam

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Prehrávač so spomalením, krokovaním po snímkach a zoomom (štipnutie + posun). */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private val SPEEDS = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f)
    }

    private lateinit var playerView: PlayerView
    private lateinit var posLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var controlsRow: LinearLayout
    private var player: ExoPlayer? = null
    private var path = ""

    private var speed = 1f
    private var resumePos = 0L
    private var resumePlaying = true
    private var loop = false
    private val speedChips = ArrayList<Pair<Float, TextView>>()
    private var loopChip: TextView? = null

    // zoom
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private var gestureUsed = false
    private var touchSlop = 0

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        posLabel = findViewById(R.id.posLabel)
        zoomLabel = findViewById(R.id.zoomLabel)
        speedLabel = findViewById(R.id.speedLabel)
        controlsRow = findViewById(R.id.controlsRow)
        findViewById<TextView>(R.id.titleLabel).text = File(path).name
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        if (savedInstanceState != null) {
            speed = savedInstanceState.getFloat("speed", 1f)
            resumePos = savedInstanceState.getLong("pos", 0L)
            resumePlaying = savedInstanceState.getBoolean("playing", true)
            loop = savedInstanceState.getBoolean("loop", false)
        }

        buildControls()
        setupZoom()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            resumePos = it.currentPosition
            resumePlaying = it.playWhenReady
        }
        outState.putFloat("speed", speed)
        outState.putLong("pos", resumePos)
        outState.putBoolean("playing", resumePlaying)
        outState.putBoolean("loop", loop)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initPlayer() {
        if (player != null) return
        val p = ExoPlayer.Builder(this).build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setPlaybackSpeed(speed)
        p.prepare()
        if (resumePos > 0) p.seekTo(resumePos)
        p.playWhenReady = resumePlaying
        playerView.player = p
        player = p
        updateSpeedUi()
    }

    private fun releasePlayer() {
        val p = player ?: return
        resumePos = p.currentPosition
        resumePlaying = p.playWhenReady
        playerView.player = null
        p.release()
        player = null
    }

    // ------------------------------------------------------------------ ovládanie

    private fun chip(text: String, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            setBackgroundResource(R.drawable.chip_bg)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (6 * dp).toInt()
        controlsRow.addView(tv, lp)
        return tv
    }

    private fun buildControls() {
        chip("⏯") { player?.let { it.playWhenReady = !it.playWhenReady } }
        chip("◀ snímka") { stepFrame(-1) }
        chip("snímka ▶") { stepFrame(1) }
        chip("−1 s") { seekBy(-1000) }
        chip("+1 s") { seekBy(1000) }
        for (s in SPEEDS) {
            val label = if (s == 1f) "1×" else String.format(Locale.US, "%s×", trimFloat(s))
            val c = chip(label) { setSpeed(s) }
            speedChips.add(s to c)
        }
        chip("Zoom 1:1") { resetZoom() }
        chip("Zoom +") { zoomBy(1.5f) }
        chip("Zoom −") { zoomBy(1f / 1.5f) }
        loopChip = chip("🔁 slučka") {
            loop = !loop
            player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updateSpeedUi()
        }
    }

    private fun trimFloat(f: Float): String {
        val s = String.format(Locale.US, "%.2f", f)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun setSpeed(s: Float) {
        speed = s
        player?.setPlaybackSpeed(s)
        updateSpeedUi()
    }

    private fun updateSpeedUi() {
        for ((s, c) in speedChips) {
            c.setBackgroundResource(if (s == speed) R.drawable.chip_bg_selected else R.drawable.chip_bg)
        }
        loopChip?.setBackgroundResource(if (loop) R.drawable.chip_bg_selected else R.drawable.chip_bg)
        speedLabel.text = if (speed == 1f) "1×" else trimFloat(speed) + "×"
    }

    private fun frameDurationMs(): Long {
        val fps = player?.videoFormat?.frameRate ?: -1f
        val f = if (fps > 1f) fps else 30f
        return (1000f / f).toLong().coerceAtLeast(1L)
    }

    private fun stepFrame(dir: Int) {
        val p = player ?: return
        p.pause()
        seekTo(p.currentPosition + dir * frameDurationMs())
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        seekTo(p.currentPosition + ms)
    }

    private fun seekTo(pos: Long) {
        val p = player ?: return
        val dur = p.duration
        val target = if (dur > 0) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L)
        p.seekTo(target)
    }

    private fun fmt(ms: Long): String {
        if (ms < 0) return "--:--.---"
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, milli)
    }

    private fun updatePosition() {
        val p = player ?: return
        val dur = p.duration
        posLabel.text = fmt(p.currentPosition) + " / " + (if (dur > 0) fmt(dur) else "--:--.---")
    }

    // ------------------------------------------------------------------ zoom

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                gestureUsed = true
                val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 10f)
                val cx = playerView.width / 2f
                val cy = playerView.height / 2f
                val fx = detector.focusX - cx
                val fy = detector.focusY - cy
                val k = newZoom / zoom
                tx = fx - k * (fx - tx)
                ty = fy - k * (fy - ty)
                zoom = newZoom
                applyZoom()
                return true
            }
        })

        playerView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    lastY = ev.y
                    panning = false
                    gestureUsed = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> gestureUsed = true
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = if (ev.actionIndex == 0) 1 else 0
                    if (remaining < ev.pointerCount) {
                        lastX = ev.getX(remaining)
                        lastY = ev.getY(remaining)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1 && zoom > 1.01f && !scaleDetector.isInProgress) {
                        val dx = ev.x - lastX
                        val dy = ev.y - lastY
                        if (panning || abs(dx) + abs(dy) > touchSlop) {
                            panning = true
                            gestureUsed = true
                            tx += dx
                            ty += dy
                            applyZoom()
                            lastX = ev.x
                            lastY = ev.y
                        }
                    }
                }
            }
            gestureUsed
        }
    }

    private fun zoomBy(f: Float) {
        zoom = (zoom * f).coerceIn(1f, 10f)
        tx *= f
        ty *= f
        applyZoom()
    }

    private fun resetZoom() {
        zoom = 1f
        tx = 0f
        ty = 0f
        applyZoom()
    }

    private fun applyZoom() {
        val v: View = playerView.videoSurfaceView ?: return
        if (zoom <= 1.001f) {
            zoom = 1f
            tx = 0f
            ty = 0f
        }
        val maxX = v.width * (zoom - 1f) / 2f
        val maxY = v.height * (zoom - 1f) / 2f
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
        v.scaleX = zoom
        v.scaleY = zoom
        v.translationX = tx
        v.translationY = ty
        zoomLabel.text = if (zoom > 1f) String.format(Locale.US, "🔍 %.1fx", zoom) else ""
    }
}
