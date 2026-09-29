package sk.firesport.cam

import android.content.SharedPreferences
import android.graphics.Color
import android.os.SystemClock

/** Kľúče nastavení. */
object Keys {
    fun ov(n: Int, name: String) = "ov${n}_$name"

    /** Stav kamery, ktorý sa ukladá priebežne a dá sa resetovať. */
    val CAMERA_STATE = listOf(
        "zoom", "torch", "ev", "manual_focus", "focus", "manual_exposure",
        "iso", "shutter_den", "wb_mode", "ae_lock", "awb_lock"
    )
}

/** Predvolené hodnoty jedného overlayu. */
data class OverlayDefaults(
    val enabled: Boolean,
    val prefix: String,
    val suffix: String,
    val defaultText: String,
    val textSize: Int,      // % výšky videa
    val textColor: Int,
    val bgColor: Int,
    val bgOpacity: Int,     // 0..100
    val posX: Int,          // 0..100
    val posY: Int,          // 0..100
    val font: String,
    val bold: Boolean,
    val outline: Boolean,
    val padding: Int,       // % veľkosti textu
    val corner: Int         // % veľkosti textu
)

object Defaults {
    fun overlay(n: Int): OverlayDefaults = if (n == 1) {
        OverlayDefaults(
            enabled = true, prefix = "", suffix = "", defaultText = "Overlay 1",
            textSize = 6, textColor = Color.WHITE, bgColor = Color.BLACK, bgOpacity = 60,
            posX = 3, posY = 4, font = "sans", bold = true, outline = false, padding = 30, corner = 20
        )
    } else {
        OverlayDefaults(
            enabled = true, prefix = "", suffix = "", defaultText = "Overlay 2",
            textSize = 6, textColor = Color.YELLOW, bgColor = Color.BLACK, bgOpacity = 60,
            posX = 97, posY = 94, font = "mono", bold = true, outline = false, padding = 30, corner = 20
        )
    }
}

/** Aktuálny štýl overlayu (načítaný z nastavení). */
data class OverlayStyle(
    val enabled: Boolean,
    val prefix: String,
    val suffix: String,
    val defaultText: String,
    val textSizePct: Float,
    val textColor: Int,
    val bgColor: Int,
    val bgOpacity: Int,
    val posX: Float,  // 0..1
    val posY: Float,  // 0..1
    val font: String,
    val bold: Boolean,
    val outline: Boolean,
    val paddingPct: Float,
    val cornerPct: Float
)

/**
 * Zdieľaný stav overlayov – čítaný z vlákna, ktoré kreslí do videa,
 * a zapisovaný z UDP vlákna a UI.
 */
object OverlayState {
    @Volatile var styles: List<OverlayStyle> = listOf(fromDefaults(1), fromDefaults(2))

    @Volatile var udpMode = "same"
    @Volatile var separator = ";"
    @Volatile var trim = true
    @Volatile var timeoutMs = 0L
    @Volatile var maxLen = 0

    @Volatile var lastPacket = ""
    @Volatile var lastPacketAt = 0L

    private val texts = arrayOfNulls<String>(2)
    private val times = LongArray(2)

    fun load(p: SharedPreferences) {
        styles = listOf(loadStyle(p, 1), loadStyle(p, 2))
        udpMode = p.getString("udp_mode", "same") ?: "same"
        separator = p.getString("udp_separator", ";") ?: ";"
        trim = p.getBoolean("udp_trim", true)
        timeoutMs = ((p.getString("udp_timeout", "0") ?: "0").trim().toLongOrNull() ?: 0L) * 1000L
        maxLen = (p.getString("udp_max_len", "0") ?: "0").trim().toIntOrNull() ?: 0
    }

    private fun fromDefaults(n: Int): OverlayStyle {
        val d = Defaults.overlay(n)
        return OverlayStyle(
            d.enabled, d.prefix, d.suffix, d.defaultText, d.textSize.toFloat(), d.textColor,
            d.bgColor, d.bgOpacity, d.posX / 100f, d.posY / 100f, d.font, d.bold, d.outline,
            d.padding.toFloat(), d.corner.toFloat()
        )
    }

    private fun loadStyle(p: SharedPreferences, n: Int): OverlayStyle {
        val d = Defaults.overlay(n)
        fun k(s: String) = Keys.ov(n, s)
        return OverlayStyle(
            enabled = p.getBoolean(k("enabled"), d.enabled),
            prefix = unescape(p.getString(k("prefix"), d.prefix) ?: ""),
            suffix = unescape(p.getString(k("suffix"), d.suffix) ?: ""),
            defaultText = unescape(p.getString(k("default"), d.defaultText) ?: ""),
            textSizePct = p.getInt(k("text_size"), d.textSize).coerceAtLeast(1).toFloat(),
            textColor = p.getInt(k("text_color"), d.textColor),
            bgColor = p.getInt(k("bg_color"), d.bgColor),
            bgOpacity = p.getInt(k("bg_opacity"), d.bgOpacity).coerceIn(0, 100),
            posX = p.getInt(k("pos_x"), d.posX).coerceIn(0, 100) / 100f,
            posY = p.getInt(k("pos_y"), d.posY).coerceIn(0, 100) / 100f,
            font = p.getString(k("font"), d.font) ?: d.font,
            bold = p.getBoolean(k("bold"), d.bold),
            outline = p.getBoolean(k("outline"), d.outline),
            paddingPct = p.getInt(k("padding"), d.padding).toFloat(),
            cornerPct = p.getInt(k("corner"), d.corner).toFloat()
        )
    }

    /** "\n" napísané v nastaveniach = nový riadok. */
    private fun unescape(s: String) = s.replace("\\n", "\n")

    fun setPosition(i: Int, x: Float, y: Float) {
        val list = styles.toMutableList()
        if (i !in list.indices) return
        list[i] = list[i].copy(posX = x.coerceIn(0f, 1f), posY = y.coerceIn(0f, 1f))
        styles = list
    }

    @Synchronized
    fun setText(i: Int, t: String) {
        var s = t
        if (maxLen > 0 && s.length > maxLen) s = s.substring(0, maxLen)
        texts[i] = s
        times[i] = SystemClock.elapsedRealtime()
    }

    @Synchronized
    private fun rawText(i: Int): String? {
        val t = texts[i] ?: return null
        if (timeoutMs > 0 && SystemClock.elapsedRealtime() - times[i] > timeoutMs) return null
        return t
    }

    /** Výsledný text overlayu: prefix + (UDP text alebo predvolený) + sufix. null = nekresliť. */
    fun displayText(i: Int): String? {
        val st = styles.getOrNull(i) ?: return null
        if (!st.enabled) return null
        val raw = rawText(i)
        val body = if (raw.isNullOrEmpty()) st.defaultText else raw
        val full = st.prefix + body + st.suffix
        return full.ifEmpty { null }
    }

    /** Spracovanie prijatej UDP správy. */
    fun onPacket(raw: String) {
        var m = raw
        if (trim) m = m.trim().replace("\r", "")
        lastPacket = m
        lastPacketAt = SystemClock.elapsedRealtime()
        when (udpMode) {
            "split" -> {
                if (separator.isEmpty()) {
                    setText(0, m); setText(1, m)
                } else {
                    val parts = m.split(separator, limit = 2)
                    val a = parts.getOrElse(0) { "" }
                    val b = parts.getOrElse(1) { "" }
                    setText(0, if (trim) a.trim() else a)
                    setText(1, if (trim) b.trim() else b)
                }
            }
            "prefix" -> when {
                m.startsWith("1:") -> setText(0, m.substring(2))
                m.startsWith("2:") -> setText(1, m.substring(2))
                else -> setText(0, m)
            }
            "only1" -> setText(0, m)
            "only2" -> setText(1, m)
            else -> {
                setText(0, m); setText(1, m)
            }
        }
    }
}
