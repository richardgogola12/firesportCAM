package sk.firesport.cam

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import kotlin.math.roundToInt

/**
 * Živý náhľad overlayov v nastaveniach (pomer 16:9).
 * Upravovaný overlay je zvýraznený a dá sa potiahnuť prstom.
 */
class OverlayPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Index prvku (0..2 = overlay, LOGO_INDEX = logo), ktorý sa v tejto karte upravuje. */
    var highlight = 0
        set(v) {
            field = v
            invalidate()
        }

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val renderer = OverlayRenderer()
    private var styles: List<OverlayStyle> = OverlayState.loadStyles(prefs)
    private var logo: LogoStyle = LogoStyle.load(prefs)
    private var logoBmp: android.graphics.Bitmap? = null
    private var logoKey = ""
    private val boxes = arrayOfNulls<RectF>(TEXT_OVERLAYS + 1)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("ov") || key.startsWith("logo") || key == "event_name") reload()
    }

    private val bgPaint = Paint()
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        strokeWidth = 2f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFEB3B.toInt()
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private var dragging = false
    private var grabDx = 0f
    private var grabDy = 0f

    private fun reload() {
        if (!dragging) {
            styles = OverlayState.loadStyles(prefs)
            logo = LogoStyle.load(prefs)
            val f = java.io.File(logo.path)
            val key = logo.path + ":" + f.lastModified()
            if (key != logoKey) {
                logoKey = key
                var bmp: android.graphics.Bitmap? = null
                if (logo.path.isNotEmpty()) {
                    try {
                        bmp = android.graphics.BitmapFactory.decodeFile(logo.path)
                    } catch (_: Exception) {
                    }
                }
                logoBmp = bmp
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        reload()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 9 / 16)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            0xFF455A64.toInt(), 0xFF1B2A30.toInt(), Shader.TileMode.CLAMP
        )
        hintPaint.textSize = h / 14f
    }

    /** Ukážkový text: predvolený text, alebo vzorová hodnota, ak je prázdny. */
    private fun sampleText(st: OverlayStyle): String? {
        if (!st.enabled) return null
        return OverlayState.displayFor(st, st.defaultText.ifEmpty { "12.34" })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // šikmé pruhy – aby bolo vidieť priesvitnosť pozadia
        var x = -h
        while (x < w) {
            canvas.drawLine(x, h, x + h, 0f, stripePaint)
            x += h / 8f
        }
        canvas.drawText("náhľad videa", w / 2f, h / 2f, hintPaint)

        val lb = logoBmp
        boxes[LOGO_INDEX] = if (logo.enabled && lb != null) renderer.drawLogo(canvas, w, h, logo, lb) else null
        for (i in 0 until TEXT_OVERLAYS) {
            val st = styles.getOrNull(i)
            val text = if (st != null) sampleText(st) else null
            boxes[i] = if (st != null && text != null) renderer.drawOverlay(canvas, w, h, st, text) else null
        }
        boxes.getOrNull(highlight)?.let { b ->
            val m = framePaint.strokeWidth
            canvas.drawRect(b.left * w - m, b.top * h - m, b.right * w + m, b.bottom * h + m, framePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val b = boxes.getOrNull(highlight) ?: return false
        if (w <= 0f || h <= 0f) return false
        val nx = event.x / w
        val ny = event.y / h
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                // ak ťukne mimo overlay, overlay "skočí" stredom pod prst
                if (b.contains(nx, ny)) {
                    grabDx = nx - b.left
                    grabDy = ny - b.top
                } else {
                    grabDx = b.width() / 2f
                    grabDy = b.height() / 2f
                    move(b, nx, ny)
                }
            }
            MotionEvent.ACTION_MOVE -> move(b, nx, ny)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                move(b, nx, ny)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val (kx, ky) = OverlayState.positionKeys(highlight)
                val pos = if (highlight == LOGO_INDEX) logo.posX to logo.posY
                else styles.getOrNull(highlight)?.let { it.posX to it.posY }
                if (pos != null) {
                    prefs.edit()
                        .putInt(kx, (pos.first * 100).roundToInt())
                        .putInt(ky, (pos.second * 100).roundToInt())
                        .apply()
                }
            }
        }
        return true
    }

    private fun move(b: RectF, nx: Float, ny: Float) {
        val bw = b.width()
        val bh = b.height()
        val px = if (bw < 1f) (nx - grabDx) / (1f - bw) else 0f
        val py = if (bh < 1f) (ny - grabDy) / (1f - bh) else 0f
        val i = highlight
        if (i == LOGO_INDEX) {
            logo = logo.copy(posX = px.coerceIn(0f, 1f), posY = py.coerceIn(0f, 1f))
        } else {
            val list = styles.toMutableList()
            if (i !in list.indices) return
            list[i] = list[i].copy(posX = px.coerceIn(0f, 1f), posY = py.coerceIn(0f, 1f))
            styles = list
        }
        invalidate()
    }
}

/** Položka nastavení, ktorá zobrazí [OverlayPreviewView]. */
class OverlayPreviewPreference(context: Context, private val index: Int) : Preference(context) {
    init {
        layoutResource = R.layout.pref_overlay_preview
        isSelectable = false
        isPersistent = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.overlay_preview) as? OverlayPreviewView)?.highlight = index
    }
}
