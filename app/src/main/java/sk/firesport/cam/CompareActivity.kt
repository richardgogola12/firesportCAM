package sk.firesport.cam

import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale

/** Porovnanie dvoch pokusov vedľa seba (synchronizované ovládanie). */
@OptIn(UnstableApi::class)
class CompareActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_A = "a"
        const val EXTRA_B = "b"
        private val SPEEDS = floatArrayOf(0.1f, 0.25f, 0.5f, 1f)
    }

    private class Side(val file: File, val view: PlayerView, val label: TextView) {
        var player: ExoPlayer? = null
        lateinit var zoom: VideoZoom
        var markers: List<Marker> = emptyList()
        var resumePos = 0L
    }

    private lateinit var a: Side
    private lateinit var b: Side
    private var speed = 0.5f
    private var linked = true
    private var linkChip: TextView? = null
    private val speedChips = ArrayList<Pair<Float, TextView>>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateLabels()
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pa = intent.getStringExtra(EXTRA_A)
        val pb = intent.getStringExtra(EXTRA_B)
        if (pa == null || pb == null) {
            finish()
            return
        }
        val dp = resources.displayMetrics.density
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        scroll.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        a = makeSide(File(pa), content, landscape)
        b = makeSide(File(pb), content, landscape)
        for (side in listOf(a, b)) {
            side.zoom = VideoZoom(side.view) { z -> onZoom(side, z) }
            side.zoom.attach()
        }
        a.markers = Markers.load(a.file)
        b.markers = Markers.load(b.file)
        speed = savedInstanceState?.getFloat("speed", 0.5f) ?: 0.5f
        a.resumePos = savedInstanceState?.getLong("posA", 0L) ?: startOf(a)
        b.resumePos = savedInstanceState?.getLong("posB", 0L) ?: startOf(b)

        fun chip(text: String, onClick: () -> Unit): TextView {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setBackgroundResource(R.drawable.chip_bg)
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * dp).toInt()
            bar.addView(tv, lp)
            return tv
        }

        chip("⏯ oba") { togglePlay() }
        chip("◀ snímka") { step(-1, a, b) }
        chip("snímka ▶") { step(1, a, b) }
        for (s in SPEEDS) {
            val c = chip(if (s == 1f) "1×" else String.format(Locale.US, "%s×", s.toString().trimEnd('0').trimEnd('.'))) { setSpeed(s) }
            speedChips.add(s to c)
        }
        chip("📍 zarovnať na značky") { alignToMarkers() }
        chip("⏮ od začiatku") { seek(a, 0); seek(b, 0) }
        linkChip = chip("🔗 spoločný zoom") {
            linked = !linked
            updateSpeedUi()
        }
        chip("Zoom 1:1") {
            a.zoom.reset()
            b.zoom.reset()
        }
        chip("A ◀") { step(-1, a) }
        chip("A ▶") { step(1, a) }
        chip("B ◀") { step(-1, b) }
        chip("B ▶") { step(1, b) }

        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        updateSpeedUi()
    }

    private fun makeSide(file: File, parent: LinearLayout, landscape: Boolean): Side {
        val frame = FrameLayout(this)
        val pv = layoutInflater.inflate(R.layout.compare_player, frame, false) as PlayerView
        frame.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(0x99000000.toInt())
            setPadding(12, 6, 12, 6)
        }
        frame.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ))
        val lp = if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.setMargins(2, 2, 2, 2)
        parent.addView(frame, lp)
        return Side(file, pv, label)
    }

    private fun startOf(s: Side): Long = s.markers.firstOrNull()?.let { (it.ms - 3000).coerceAtLeast(0L) } ?: 0L

    override fun onStart() {
        super.onStart()
        for (s in listOf(a, b)) {
            val p = ExoPlayer.Builder(this).build()
            p.setSeekParameters(SeekParameters.EXACT)
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(s.file)))
            p.setPlaybackSpeed(speed)
            p.volume = if (s === a) 1f else 0f
            p.prepare()
            p.seekTo(s.resumePos)
            p.playWhenReady = false
            s.view.player = p
            s.player = p
        }
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        for (s in listOf(a, b)) {
            s.player?.let {
                s.resumePos = it.currentPosition
                it.release()
            }
            s.view.player = null
            s.player = null
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("speed", speed)
        outState.putLong("posA", a.player?.currentPosition ?: a.resumePos)
        outState.putLong("posB", b.player?.currentPosition ?: b.resumePos)
    }

    private fun togglePlay() {
        val pa = a.player ?: return
        val pb = b.player ?: return
        val play = !pa.playWhenReady
        pa.playWhenReady = play
        pb.playWhenReady = play
    }

    private fun frameMs(p: ExoPlayer): Long {
        val fps = p.videoFormat?.frameRate ?: -1f
        return (1000f / (if (fps > 1f) fps else 30f)).toLong().coerceAtLeast(1L)
    }

    private fun step(dir: Int, vararg sides: Side) {
        for (s in sides) {
            val p = s.player ?: continue
            p.pause()
            seek(s, p.currentPosition + dir * frameMs(p))
        }
    }

    private fun seek(s: Side, pos: Long) {
        val p = s.player ?: return
        val d = p.duration
        p.seekTo(if (d > 0) pos.coerceIn(0L, d) else pos.coerceAtLeast(0L))
    }

    private fun alignToMarkers() {
        val ma = a.markers.firstOrNull()
        val mb = b.markers.firstOrNull()
        if (ma == null || mb == null) {
            android.widget.Toast.makeText(this, "Obe videá musia mať aspoň jednu značku", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        a.player?.pause()
        b.player?.pause()
        seek(a, ma.ms - 3000)
        seek(b, mb.ms - 3000)
    }

    private fun setSpeed(s: Float) {
        speed = s
        a.player?.setPlaybackSpeed(s)
        b.player?.setPlaybackSpeed(s)
        updateSpeedUi()
    }

    private fun updateSpeedUi() {
        for ((s, c) in speedChips) c.setBackgroundResource(if (s == speed) R.drawable.chip_bg_selected else R.drawable.chip_bg)
        linkChip?.setBackgroundResource(if (linked) R.drawable.chip_bg_selected else R.drawable.chip_bg)
    }

    /** Pri spoločnom zoome sa zoom a posun prenesú aj na druhé video. */
    private fun onZoom(from: Side, z: VideoZoom) {
        if (!linked) return
        val other = if (from === a) b else a
        other.zoom.set(z.zoom, z.tx, z.ty)
    }

    private fun updateLabels() {
        for ((name, s) in listOf("A" to a, "B" to b)) {
            val p = s.player ?: continue
            s.label.text = "$name  ${Markers.format(p.currentPosition)}  ${s.file.nameWithoutExtension}"
        }
    }
}
