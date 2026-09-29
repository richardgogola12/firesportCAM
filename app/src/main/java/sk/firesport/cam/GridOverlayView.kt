package sk.firesport.cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Mriežka tretín len v náhľade (nenahráva sa do videa). */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val content = RectF()

    fun setContent(r: RectF) {
        if (r != content) {
            content.set(r)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = if (content.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else content
        for (k in 1..2) {
            val x = r.left + r.width() * k / 3f
            val y = r.top + r.height() * k / 3f
            canvas.drawLine(x, r.top, x, r.bottom, paint)
            canvas.drawLine(r.left, y, r.right, y, paint)
        }
    }
}
