package sk.firesport.cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Kreslí oba overlaye na plátno v "vzpriamených" súradniciach (w x h).
 * Používa sa v OverlayEffect, takže výsledok je v náhľade aj v nahranom videu.
 */
class OverlayRenderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val typefaces = HashMap<String, Typeface>()

    /** Posledné vykreslené boxy, normalizované 0..1 (pre ťahanie prstom). */
    @Volatile var boxes: Array<RectF?> = arrayOfNulls(TEXT_OVERLAYS + 1)

    /** Pomer strán vzpriameného obrazu (šírka / výška). */
    @Volatile var aspect = 0f

    fun draw(canvas: Canvas, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        aspect = w / h
        val styles = OverlayState.styles
        val newBoxes = arrayOfNulls<RectF>(TEXT_OVERLAYS + 1)
        val logo = OverlayState.logo
        val bmp = OverlayState.logoBitmap
        if (logo.enabled && bmp != null) newBoxes[LOGO_INDEX] = drawLogo(canvas, w, h, logo, bmp)
        for (i in 0 until TEXT_OVERLAYS) {
            val st = styles.getOrNull(i) ?: continue
            val text = OverlayState.displayText(i) ?: continue
            newBoxes[i] = drawOverlay(canvas, w, h, st, text)
        }
        boxes = newBoxes
    }

    /**
     * Nakreslí jeden overlay. [w] x [h] je veľkosť celého obrazu.
     * @return box overlayu normalizovaný na 0..1
     */
    fun drawOverlay(canvas: Canvas, w: Float, h: Float, st: OverlayStyle, text: String): RectF {
        val lines = text.split('\n')

        val ts = h * st.textSizePct / 100f
        textPaint.textSize = ts
        textPaint.typeface = typeface(st.font, st.bold)
        textPaint.color = st.textColor
        val fm = textPaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        var tw = 0f
        for (l in lines) tw = maxOf(tw, textPaint.measureText(l))

        val pad = ts * st.paddingPct / 100f
        val bw = tw + 2 * pad
        val bh = lineH * lines.size + 2 * pad
        val left = (w - bw).coerceAtLeast(0f) * st.posX
        val top = (h - bh).coerceAtLeast(0f) * st.posY
        rect.set(left, top, left + bw, top + bh)

        if (st.bgOpacity > 0) {
            bgPaint.color = st.bgColor
            bgPaint.alpha = (st.bgOpacity * 255 / 100).coerceIn(0, 255)
            val r = ts * st.cornerPct / 100f
            canvas.drawRoundRect(rect, r, r, bgPaint)
        }

        var y = top + pad - fm.ascent
        for (l in lines) {
            val x = left + pad
            if (st.outline) {
                strokePaint.textSize = ts
                strokePaint.typeface = textPaint.typeface
                strokePaint.strokeWidth = (ts * 0.12f).coerceAtLeast(2f)
                strokePaint.color = if (st.bgColor == st.textColor) Color.BLACK else st.bgColor
                canvas.drawText(l, x, y, strokePaint)
            }
            canvas.drawText(l, x, y, textPaint)
            y += lineH
        }
        return RectF(left / w, top / h, (left + bw) / w, (top + bh) / h)
    }

    /** Nakreslí logo. @return box normalizovaný na 0..1 */
    fun drawLogo(canvas: Canvas, w: Float, h: Float, st: LogoStyle, bmp: Bitmap): RectF? {
        if (bmp.width <= 0 || bmp.height <= 0) return null
        val lh = h * st.sizePct / 100f
        val lw = lh * bmp.width / bmp.height
        val left = (w - lw).coerceAtLeast(0f) * st.posX
        val top = (h - lh).coerceAtLeast(0f) * st.posY
        rect.set(left, top, left + lw, top + lh)
        logoPaint.alpha = (st.opacity * 255 / 100).coerceIn(0, 255)
        canvas.drawBitmap(bmp, null, rect, logoPaint)
        return RectF(left / w, top / h, (left + lw) / w, (top + lh) / h)
    }

    private fun typeface(font: String, bold: Boolean): Typeface {
        val key = font + bold
        return typefaces.getOrPut(key) {
            val family = when (font) {
                "mono" -> "monospace"
                "serif" -> "serif"
                "condensed" -> "sans-serif-condensed"
                else -> "sans-serif"
            }
            Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
