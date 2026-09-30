package sk.firesport.cam

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Zoom (štipnutie) a posun (ťahanie) videa v PlayerView s TextureView.
 * [onChange] sa volá pri zmene – napr. na prepojenie dvoch videí.
 */
class VideoZoom(private val view: PlayerView, private val onChange: (VideoZoom) -> Unit = {}) {

    var zoom = 1f
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop

    private val detector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 10f)
            val fx = d.focusX - view.width / 2f
            val fy = d.focusY - view.height / 2f
            val k = newZoom / zoom
            tx = fx - k * (fx - tx)
            ty = fy - k * (fy - ty)
            zoom = newZoom
            apply(true)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        view.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    lastY = ev.y
                    panning = false
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = if (ev.actionIndex == 0) 1 else 0
                    if (remaining < ev.pointerCount) {
                        lastX = ev.getX(remaining)
                        lastY = ev.getY(remaining)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1 && zoom > 1.01f && !detector.isInProgress) {
                        val dx = ev.x - lastX
                        val dy = ev.y - lastY
                        if (panning || abs(dx) + abs(dy) > slop) {
                            panning = true
                            tx += dx
                            ty += dy
                            lastX = ev.x
                            lastY = ev.y
                            apply(true)
                        }
                    }
                }
            }
            true
        }
    }

    /** Nastaví zoom zvonku (napr. z prepojeného videa). */
    fun set(z: Float, x: Float, y: Float) {
        zoom = z.coerceIn(1f, 10f)
        tx = x
        ty = y
        apply(false)
    }

    fun reset() = set(1f, 0f, 0f).also { onChange(this) }

    fun zoomBy(f: Float) {
        zoom = (zoom * f).coerceIn(1f, 10f)
        tx *= f
        ty *= f
        apply(true)
    }

    private fun apply(notify: Boolean) {
        val v: View = view.videoSurfaceView ?: return
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
        if (notify) onChange(this)
    }
}
