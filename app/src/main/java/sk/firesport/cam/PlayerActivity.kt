package sk.firesport.cam

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Prehrávač: spomalenie, krokovanie po snímkach, zoom, značky
 * a vystrihnutie úseku.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_SPEED = "speed"
        private val SPEEDS = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f)
        const val DEFAULT_PHASES = "Štart,Rozvinutie,Spoj,Prúdnica,Terč"
    }

    private lateinit var playerView: PlayerView
    private lateinit var posLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var controlsRow: LinearLayout
    private lateinit var markersRow: LinearLayout
    private lateinit var splitsRow: LinearLayout
    private lateinit var splitsLabel: TextView
    private val splits = ArrayList<Pair<String, Long>>()
    private var phases: List<String> = emptyList()
    private var bestRef: Pair<String, List<Pair<String, Long>>>? = null
    private var player: ExoPlayer? = null
    private var file = File("")

    private var speed = 1f
    private var resumePos = 0L
    private var resumePlaying = true
    private var loop = false
    private val speedChips = ArrayList<Pair<Float, TextView>>()
    private var loopChip: TextView? = null

    // značky a strih
    private val markers = ArrayList<Marker>()
    private var cutA = -1L
    private var cutB = -1L
    private var cutAChip: TextView? = null
    private var cutBChip: TextView? = null
    private var exporting: Transformer? = null

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
        val path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        file = File(path)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        posLabel = findViewById(R.id.posLabel)
        zoomLabel = findViewById(R.id.zoomLabel)
        speedLabel = findViewById(R.id.speedLabel)
        controlsRow = findViewById(R.id.controlsRow)
        markersRow = findViewById(R.id.markersRow)
        splitsRow = findViewById(R.id.splitsRow)
        splitsLabel = findViewById(R.id.splitsLabel)
        findViewById<TextView>(R.id.titleLabel).text = file.name
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        if (savedInstanceState != null) {
            speed = savedInstanceState.getFloat("speed", 1f)
            resumePos = savedInstanceState.getLong("pos", 0L)
            resumePlaying = savedInstanceState.getBoolean("playing", true)
            loop = savedInstanceState.getBoolean("loop", false)
            cutA = savedInstanceState.getLong("cutA", -1L)
            cutB = savedInstanceState.getLong("cutB", -1L)
        } else {
            speed = intent.getFloatExtra(EXTRA_SPEED, 1f)
            resumePos = intent.getLongExtra(EXTRA_START_MS, 0L)
        }

        markers.addAll(Markers.load(file))
        splits.addAll(Splits.load(file))
        phases = (androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("split_phases", DEFAULT_PHASES) ?: DEFAULT_PHASES)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_PHASES.split(",") }
        buildControls()
        buildMarkers()
        findBestReference()
        buildSplits()
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

    override fun onDestroy() {
        exporting?.cancel()
        super.onDestroy()
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
        outState.putLong("cutA", cutA)
        outState.putLong("cutB", cutB)
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
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
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

    private fun chip(row: LinearLayout, text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            setBackgroundResource(R.drawable.chip_bg)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (6 * dp).toInt()
        row.addView(tv, lp)
        return tv
    }

    private fun buildControls() {
        val r = controlsRow
        chip(r, "⏯") { player?.let { it.playWhenReady = !it.playWhenReady } }
        chip(r, "◀ snímka") { stepFrame(-1) }
        chip(r, "snímka ▶") { stepFrame(1) }
        chip(r, "−1 s") { seekBy(-1000) }
        chip(r, "+1 s") { seekBy(1000) }
        for (s in SPEEDS) {
            val label = if (s == 1f) "1×" else trimFloat(s) + "×"
            val c = chip(r, label) { setSpeed(s) }
            speedChips.add(s to c)
        }
        chip(r, "Zoom 1:1") { resetZoom() }
        chip(r, "Zoom +") { zoomBy(1.5f) }
        chip(r, "Zoom −") { zoomBy(1f / 1.5f) }
        loopChip = chip(r, "🔁 slučka") {
            loop = !loop
            player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updateSpeedUi()
        }
        cutAChip = chip(r, "✂ začiatok [", onLong = { cutA = -1L; updateCutUi() }) {
            cutA = player?.currentPosition ?: -1L
            updateCutUi()
        }
        cutBChip = chip(r, "] koniec ✂", onLong = { cutB = -1L; updateCutUi() }) {
            cutB = player?.currentPosition ?: -1L
            updateCutUi()
        }
        chip(r, "💾 Uložiť výrez") { exportCut() }
        updateCutUi()
    }

    private fun buildMarkers() {
        markersRow.removeAllViews()
        val r = markersRow
        chip(r, "📍+ značka") { addMarker() }
        if (markers.isNotEmpty()) {
            chip(r, "⏮ značka") { jumpMarker(-1) }
            chip(r, "značka ⏭") { jumpMarker(1) }
        }
        for (m in markers) {
            chip(r, "📍 ${Markers.format(m.ms)}  ${m.text}", onLong = { editMarker(m) }) {
                val before = 1000L
                seekTo(m.ms - before)
            }
        }
    }

    private fun addMarker() {
        val p = player ?: return
        val pos = p.currentPosition
        textDialog("Nová značka v ${Markers.format(pos)}", "") { text ->
            markers.add(Marker(pos, text.ifEmpty { "Značka ${markers.size + 1}" }))
            markers.sortBy { it.ms }
            Markers.save(file, markers)
            buildMarkers()
        }
    }

    private fun editMarker(m: Marker) {
        AlertDialog.Builder(this)
            .setTitle("Značka ${Markers.format(m.ms)}")
            .setItems(arrayOf("Premenovať", "Vymazať")) { _, w ->
                if (w == 0) {
                    textDialog("Text značky", m.text) { t ->
                        val i = markers.indexOf(m)
                        if (i >= 0) markers[i] = Marker(m.ms, t)
                        Markers.save(file, markers)
                        buildMarkers()
                    }
                } else {
                    markers.remove(m)
                    Markers.save(file, markers)
                    buildMarkers()
                }
            }
            .show()
    }

    private fun jumpMarker(dir: Int) {
        val p = player ?: return
        if (markers.isEmpty()) return
        val pos = p.currentPosition
        val target = if (dir > 0) markers.firstOrNull { it.ms - 1000 > pos + 50 }
        else markers.lastOrNull { it.ms - 1000 < pos - 300 }
        target?.let { seekTo(it.ms - 1000) }
    }

    // ------------------------------------------------------------------ medzičasy

    /** Najlepší pokus toho istého družstva v rovnakej súťaži, ktorý má medzičasy. */
    private fun findBestReference() {
        val team = Sidecars.loadInfo(file)?.team ?: ""
        if (team.isEmpty()) return
        val dir = file.parentFile ?: return
        val candidates = dir.listFiles()
            ?.filter { it.extension.equals("mp4", true) && it.absolutePath != file.absolutePath }
            ?.filter { Sidecars.loadInfo(it)?.team.equals(team, ignoreCase = true) }
            ?.map { it to Splits.load(it) }
            ?.filter { it.second.size >= 2 }
            ?: return
        val best = candidates.minByOrNull { it.second.last().second - it.second.first().second } ?: return
        bestRef = best.first.nameWithoutExtension to best.second
    }

    private fun buildSplits() {
        splitsRow.removeAllViews()
        val next = phases.getOrNull(splits.size)
        chip(splitsRow, if (next != null) "⏱ označiť: $next" else "⏱ všetky fázy označené") {
            val p = player ?: return@chip
            val n = phases.getOrNull(splits.size)
            if (n == null) {
                toast("Všetky fázy sú označené. Späť = zrušiť poslednú.")
                return@chip
            }
            p.pause()
            val pos = p.currentPosition
            if (splits.isNotEmpty() && pos <= splits.last().second) {
                toast("Fáza musí byť za predchádzajúcou (${Markers.format(splits.last().second)})")
                return@chip
            }
            splits.add(n to pos)
            Splits.save(file, splits)
            buildSplits()
        }
        if (splits.isNotEmpty()) {
            chip(splitsRow, "↶ späť") {
                splits.removeAt(splits.size - 1)
                Splits.save(file, splits)
                buildSplits()
            }
            chip(splitsRow, "✖ vymazať medzičasy") {
                AlertDialog.Builder(this)
                    .setTitle("Vymazať medzičasy tohto videa?")
                    .setPositiveButton("Vymazať") { _, _ ->
                        splits.clear()
                        Splits.save(file, splits)
                        buildSplits()
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
            for ((name, ms) in splits) {
                chip(splitsRow, "$name ${Markers.format(ms)}") { seekTo(ms) }
            }
        }
        updateSplitsLabel()
    }

    private fun updateSplitsLabel() {
        val d = Splits.durations(splits)
        if (d.isEmpty()) {
            splitsLabel.visibility = View.GONE
            return
        }
        val ref = bestRef?.second?.let { Splits.durations(it) }?.toMap() ?: emptyMap()
        val sb = StringBuilder()
        for ((name, sec) in d) {
            sb.append(String.format(Locale.ROOT, "%-11s %6.2f s", name, sec))
            ref[name]?.let { r ->
                val diff = sec - r
                sb.append(String.format(Locale.ROOT, "  (%+.2f)", diff))
            }
            sb.append('\n')
        }
        val total = (splits.last().second - splits.first().second) / 1000.0
        sb.append(String.format(Locale.ROOT, "%-11s %6.2f s", "Spolu", total))
        bestRef?.let { (name, ref2) ->
            val rt = (ref2.last().second - ref2.first().second) / 1000.0
            sb.append(String.format(Locale.ROOT, "  (%+.2f)", total - rt))
            sb.append("\nporovnanie s najlepším pokusom: ").append(name)
        }
        splitsLabel.text = sb.toString()
        splitsLabel.visibility = View.VISIBLE
    }

    private fun textDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun updateCutUi() {
        cutAChip?.text = if (cutA >= 0) "✂ [ ${Markers.format(cutA)}" else "✂ začiatok ["
        cutBChip?.text = if (cutB >= 0) "${Markers.format(cutB)} ] ✂" else "] koniec ✂"
        cutAChip?.setBackgroundResource(if (cutA >= 0) R.drawable.chip_bg_selected else R.drawable.chip_bg)
        cutBChip?.setBackgroundResource(if (cutB >= 0) R.drawable.chip_bg_selected else R.drawable.chip_bg)
    }

    private fun exportCut() {
        if (exporting != null) {
            toast("Ukladanie už prebieha…")
            return
        }
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val a = if (cutA >= 0) cutA else 0L
        val b = if (cutB >= 0) cutB else dur
        if (b <= a + 200) {
            toast("Nastav začiatok [ a koniec ] výrezu (koniec musí byť za začiatkom)")
            return
        }
        var out = File(file.parentFile, file.nameWithoutExtension + "_vyrez.mp4")
        var i = 2
        while (out.exists()) {
            out = File(file.parentFile, file.nameWithoutExtension + "_vyrez$i.mp4")
            i++
        }
        val target = out
        p.pause()
        toast("Ukladám výrez ${Markers.format(a)} – ${Markers.format(b)}…")
        exporting = VideoEditor.export(
            this,
            listOf(VideoEditor.Clip(file, a, if (b >= dur) Long.MIN_VALUE else b)),
            target,
            onProgress = { pct -> speedLabel.text = "💾 $pct %" }
        ) { ok, err ->
            exporting = null
            updateSpeedUi()
            if (ok) {
                val inside = markers.filter { it.ms in a..b }.map { Marker(it.ms - a, it.text) }
                Markers.save(target, inside)
                toast("Výrez uložený: ${target.name}")
            } else {
                toast("Ukladanie zlyhalo: ${err ?: ""}")
            }
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

    private fun updatePosition() {
        val p = player ?: return
        val dur = p.duration
        posLabel.text = Markers.format(p.currentPosition) + " / " + (if (dur > 0) Markers.format(dur) else "--:--.---")
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
