package sk.firesport.cam

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.preference.PreferenceManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Počet textových overlayov. */
const val TEXT_OVERLAYS = 3

/** Index loga v poliach boxov (za textovými overlaymi). */
const val LOGO_INDEX = TEXT_OVERLAYS

object Prefs {
    /** XML súbory kariet nastavení. */
    val XML_FILES = intArrayOf(
        R.xml.prefs_video, R.xml.prefs_camera, R.xml.prefs_competition, R.xml.prefs_udp,
        R.xml.prefs_auto, R.xml.prefs_remote, R.xml.prefs_other
    )

    /** Doplní chýbajúce predvolené hodnoty (existujúce neprepíše). */
    fun initDefaults(ctx: Context) {
        for (x in XML_FILES) PreferenceManager.setDefaultValues(ctx, x, true)
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!p.getBoolean("migrated_v2", false)) {
            p.edit().putString("orientation", "auto").putBoolean("migrated_v2", true).apply()
        }
        if (!p.getBoolean("migrated_live", false)) {
            // staré predvolené hodnoty náhľadu (640 px, 15 fps) → Full HD 30 fps video
            val ed = p.edit().putBoolean("migrated_live", true)
            if (p.getString("remote_width", "640") == "640") ed.putString("remote_width", "1920")
            if (p.getString("remote_fps", "15") == "15") ed.putString("remote_fps", "30")
            ed.apply()
        }
    }

    fun str(p: SharedPreferences, key: String, def: String) = (p.getString(key, def) ?: def).trim()
    fun int(p: SharedPreferences, key: String, def: Int) = str(p, key, def.toString()).toIntOrNull() ?: def
}

/** Kľúče nastavení. */
object Keys {
    fun ov(n: Int, name: String) = "ov${n}_$name"

    /** Stav kamery, ktorý sa ukladá priebežne a dá sa resetovať. */
    val CAMERA_STATE = listOf(
        "zoom", "torch", "ev", "manual_focus", "focus", "manual_exposure",
        "iso", "shutter_den", "wb_mode", "ae_lock", "awb_lock"
    )
}

data class OverlayDefaults(
    val enabled: Boolean,
    val prefix: String,
    val suffix: String,
    val defaultText: String,
    val textSize: Int,
    val textColor: Int,
    val bgColor: Int,
    val bgOpacity: Int,
    val posX: Int,
    val posY: Int,
    val font: String,
    val bold: Boolean,
    val outline: Boolean,
    val padding: Int,
    val corner: Int
)

object Defaults {
    fun overlay(n: Int): OverlayDefaults = when (n) {
        1 -> OverlayDefaults(
            enabled = true, prefix = "", suffix = "", defaultText = "Overlay 1",
            textSize = 6, textColor = Color.WHITE, bgColor = Color.BLACK, bgOpacity = 60,
            posX = 3, posY = 4, font = "sans", bold = true, outline = false, padding = 30, corner = 20
        )
        2 -> OverlayDefaults(
            enabled = true, prefix = "", suffix = "", defaultText = "Overlay 2",
            textSize = 6, textColor = Color.YELLOW, bgColor = Color.BLACK, bgOpacity = 60,
            posX = 97, posY = 94, font = "mono", bold = true, outline = false, padding = 30, corner = 20
        )
        else -> OverlayDefaults(
            enabled = false, prefix = "", suffix = "", defaultText = "{sutaz}  {datum} {cas}",
            textSize = 4, textColor = Color.WHITE, bgColor = Color.BLACK, bgOpacity = 45,
            posX = 3, posY = 96, font = "sans", bold = false, outline = false, padding = 30, corner = 20
        )
    }
}

data class OverlayStyle(
    val enabled: Boolean,
    val prefix: String,
    val suffix: String,
    val defaultText: String,
    val textSizePct: Float,
    val textColor: Int,
    val bgColor: Int,
    val bgOpacity: Int,
    val posX: Float,
    val posY: Float,
    val font: String,
    val bold: Boolean,
    val outline: Boolean,
    val paddingPct: Float,
    val cornerPct: Float
)

/** Logo (obrázok) vpálené do videa. */
data class LogoStyle(
    val enabled: Boolean,
    val path: String,
    val sizePct: Float,  // výška loga v % výšky videa
    val posX: Float,
    val posY: Float,
    val opacity: Int     // 0..100
) {
    companion object {
        fun load(p: SharedPreferences) = LogoStyle(
            enabled = p.getBoolean("logo_enabled", false),
            path = p.getString("logo_path", "") ?: "",
            sizePct = p.getInt("logo_size", 12).coerceAtLeast(1).toFloat(),
            posX = p.getInt("logo_pos_x", 97).coerceIn(0, 100) / 100f,
            posY = p.getInt("logo_pos_y", 4).coerceIn(0, 100) / 100f,
            opacity = p.getInt("logo_opacity", 90).coerceIn(0, 100)
        )
    }
}

/** Jedna značka v čase videa. */
data class Marker(val ms: Long, val text: String)

/** Príjemca UDP príkazov (nahrávanie, značky). */
interface PacketListener {
    fun onCommand(cmd: String, arg: String)

    /** Text overlayu [index] sa zmenil (nie príkaz). */
    fun onText(index: Int, text: String)
}

/**
 * Zdieľaný stav overlayov – číta ho vlákno, ktoré kreslí do videa,
 * zapisuje UDP vlákno a UI.
 */
object OverlayState {
    @Volatile var styles: List<OverlayStyle> = (1..TEXT_OVERLAYS).map { fromDefaults(it) }
    @Volatile var logo: LogoStyle = LogoStyle(false, "", 12f, 0.97f, 0.04f, 90)
    @Volatile var logoBitmap: Bitmap? = null
    private var logoLoadedPath = ""

    @Volatile var udpMode = "same"
    @Volatile var separator = ";"
    @Volatile var trim = true
    @Volatile var timeoutMs = 0L
    @Volatile var maxLen = 0

    // príkazy
    @Volatile var commandsEnabled = true
    @Volatile var cmdStart = "START"
    @Volatile var cmdStop = "STOP"
    @Volatile var cmdMark = "MARK"
    @Volatile var cmdTeam = "TEAM"
    @Volatile var cmdClear = "CLEAR"
    @Volatile var cmdReset = "RESET"
    @Volatile var cmdVerdict = "VERDIKT"
    /** "hide" = po vymazaní overlay skryť, "default" = zobraziť predvolený text. */
    @Volatile var clearMode = "hide"

    /** IP adresa, z ktorej prišla posledná správa (napr. ESP01). */
    @Volatile var lastSender = ""

    // zástupné texty
    @Volatile var eventName = ""
    @Volatile var profileName = ""
    @Volatile var teamName = ""
    @Volatile var cameraName = ""
    /** Číslo pokusu, ktorý sa práve nahráva (alebo bude nahrávať). */
    @Volatile var attemptNo = 0
    /** elapsedRealtime začiatku nahrávania (0 = nenahráva sa) – pre {rec}. */
    @Volatile var recStartedAt = 0L

    @Volatile var lastPacket = ""
    @Volatile var lastPacketAt = 0L
    @Volatile var listener: PacketListener? = null

    private val texts = arrayOfNulls<String>(TEXT_OVERLAYS)
    private val times = LongArray(TEXT_OVERLAYS)
    /** Overlay je po vymazaní skrytý, kým nepríde nový text. */
    private val hidden = BooleanArray(TEXT_OVERLAYS)

    private val dateFmt = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.ROOT)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val timeShortFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun load(p: SharedPreferences) {
        styles = loadStyles(p)
        logo = LogoStyle.load(p)
        loadLogoBitmap()
        udpMode = p.getString("udp_mode", "same") ?: "same"
        separator = p.getString("udp_separator", ";") ?: ";"
        trim = p.getBoolean("udp_trim", true)
        timeoutMs = Prefs.int(p, "udp_timeout", 0) * 1000L
        maxLen = Prefs.int(p, "udp_max_len", 0)
        commandsEnabled = p.getBoolean("udp_commands", true)
        cmdStart = Prefs.str(p, "cmd_start", "START")
        cmdStop = Prefs.str(p, "cmd_stop", "STOP")
        cmdMark = Prefs.str(p, "cmd_mark", "MARK")
        cmdTeam = Prefs.str(p, "cmd_team", "TEAM")
        cmdClear = Prefs.str(p, "cmd_clear", "CLEAR")
        cmdReset = Prefs.str(p, "cmd_reset", "RESET")
        cmdVerdict = Prefs.str(p, "cmd_verdict", "VERDIKT")
        clearMode = p.getString("clear_mode", "hide") ?: "hide"
        teamName = Prefs.str(p, "team_name", "")
        cameraName = Prefs.str(p, "camera_name", "")
        eventName = Prefs.str(p, "event_name", "")
        profileName = Prefs.str(p, "profile_name", "")
    }

    @Synchronized
    private fun loadLogoBitmap() {
        val l = logo
        if (!l.enabled || l.path.isEmpty()) {
            logoBitmap = null
            logoLoadedPath = ""
            return
        }
        val f = File(l.path)
        val key = l.path + ":" + f.lastModified()
        if (key == logoLoadedPath && logoBitmap != null) return
        logoBitmap = try {
            BitmapFactory.decodeFile(l.path)
        } catch (_: Exception) {
            null
        }
        logoLoadedPath = key
    }

    private fun fromDefaults(n: Int): OverlayStyle {
        val d = Defaults.overlay(n)
        return OverlayStyle(
            d.enabled, d.prefix, d.suffix, d.defaultText, d.textSize.toFloat(), d.textColor,
            d.bgColor, d.bgOpacity, d.posX / 100f, d.posY / 100f, d.font, d.bold, d.outline,
            d.padding.toFloat(), d.corner.toFloat()
        )
    }

    fun loadStyles(p: SharedPreferences) = (1..TEXT_OVERLAYS).map { loadStyle(p, it) }

    fun loadStyle(p: SharedPreferences, n: Int): OverlayStyle {
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

    private fun unescape(s: String) = s.replace("\\n", "\n")

    fun setPosition(i: Int, x: Float, y: Float) {
        if (i == LOGO_INDEX) {
            logo = logo.copy(posX = x.coerceIn(0f, 1f), posY = y.coerceIn(0f, 1f))
            return
        }
        val list = styles.toMutableList()
        if (i !in list.indices) return
        list[i] = list[i].copy(posX = x.coerceIn(0f, 1f), posY = y.coerceIn(0f, 1f))
        styles = list
    }

    /** Aktuálna pozícia prvku (0..1) – pre uloženie po potiahnutí. */
    fun positionOf(i: Int): Pair<Float, Float>? =
        if (i == LOGO_INDEX) logo.posX to logo.posY
        else styles.getOrNull(i)?.let { it.posX to it.posY }

    /** Kľúče nastavení pozície prvku [i]. */
    fun positionKeys(i: Int): Pair<String, String> =
        if (i == LOGO_INDEX) "logo_pos_x" to "logo_pos_y"
        else Keys.ov(i + 1, "pos_x") to Keys.ov(i + 1, "pos_y")

    @Synchronized
    fun setText(i: Int, t: String) {
        if (i !in 0 until TEXT_OVERLAYS) return
        var s = t
        if (maxLen > 0 && s.length > maxLen) s = s.substring(0, maxLen)
        texts[i] = s
        times[i] = SystemClock.elapsedRealtime()
        hidden[i] = false
    }

    /**
     * Vymaže text z UDP. [index] = 0..2 jeden overlay, null = všetky.
     * Podľa nastavenia sa overlay skryje alebo ukáže predvolený text.
     */
    fun clear(index: Int? = null) {
        val range = if (index == null) 0 until TEXT_OVERLAYS else index..index
        synchronized(this) {
            for (i in range) {
                if (i !in 0 until TEXT_OVERLAYS) continue
                texts[i] = null
                hidden[i] = clearMode == "hide"
            }
        }
        for (i in range) if (i in 0 until TEXT_OVERLAYS) listener?.onText(i, "")
    }

    @Synchronized
    private fun isHidden(i: Int) = hidden.getOrElse(i) { false }

    /** Posledný prijatý text overlayu (bez ohľadu na timeout). */
    @Synchronized
    fun lastText(i: Int): String? = texts.getOrNull(i)

    @Synchronized
    private fun rawText(i: Int): String? {
        val t = texts[i] ?: return null
        if (timeoutMs > 0 && SystemClock.elapsedRealtime() - times[i] > timeoutMs) return null
        return t
    }

    /** Doplní zástupné texty {datum}, {cas}, {hhmm}, {sutaz}, {profil}, {rec}. */
    fun expand(s: String): String {
        if (s.indexOf('{') < 0) return s
        val now = LocalDateTime.now()
        var r = s
        if (r.contains("{datum}")) r = r.replace("{datum}", now.format(dateFmt))
        if (r.contains("{cas}")) r = r.replace("{cas}", now.format(timeFmt))
        if (r.contains("{hhmm}")) r = r.replace("{hhmm}", now.format(timeShortFmt))
        if (r.contains("{sutaz}")) r = r.replace("{sutaz}", eventName)
        if (r.contains("{profil}")) r = r.replace("{profil}", profileName)
        if (r.contains("{druzstvo}")) r = r.replace("{druzstvo}", teamName)
        if (r.contains("{kamera}")) r = r.replace("{kamera}", cameraName)
        if (r.contains("{pokus}")) r = r.replace("{pokus}", if (attemptNo > 0) attemptNo.toString() else "")
        if (r.contains("{rec}")) {
            val start = recStartedAt
            val sec = if (start > 0) (SystemClock.elapsedRealtime() - start) / 1000 else 0
            r = r.replace("{rec}", String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60))
        }
        return r
    }

    /** Výsledný text overlayu: prefix + (UDP text alebo predvolený) + sufix. null = nekresliť. */
    fun displayText(i: Int): String? {
        val st = styles.getOrNull(i) ?: return null
        if (!st.enabled) return null
        if (isHidden(i)) return null
        val raw = rawText(i)
        val body = if (raw.isNullOrEmpty()) st.defaultText else raw
        return displayFor(st, body)
    }

    fun displayFor(st: OverlayStyle, body: String): String? {
        val full = expand(st.prefix) + expand(body) + expand(st.suffix)
        return full.trim().ifEmpty { null }?.let { full }
    }

    /** Je text príkaz (nie čas)? Príkazy sa pri rýchlom toku nikdy nezlučujú. */
    fun isCommand(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("FSCAM:")) return true
        if (matches(t, cmdStart) || matches(t, cmdStop) || matches(t, cmdMark) || matches(t, cmdClear) || matches(t, cmdReset)) return true
        for (c in arrayOf(cmdMark, cmdClear, cmdReset, cmdTeam, cmdVerdict)) {
            if (c.isNotEmpty() && t.length > c.length && t[c.length] == ':' && t.startsWith(c, ignoreCase = true)) return true
        }
        return false
    }

    private fun matches(m: String, cmd: String) = cmd.isNotEmpty() && m.equals(cmd, ignoreCase = true)

    private fun setAndNotify(i: Int, text: String) {
        setText(i, text)
        listener?.onText(i, text)
    }

    /** Spracovanie prijatej UDP správy. */
    fun onPacket(raw: String) {
        // servisné správy vyhľadávania (FSCAM:…) sa nezobrazujú
        if (raw.startsWith("FSCAM:")) return
        var m = raw
        if (trim) m = m.trim().replace("\r", "")
        lastPacket = m
        lastPacketAt = SystemClock.elapsedRealtime()

        if (commandsEnabled) {
            val t = m.trim()
            when {
                matches(t, cmdStart) -> { listener?.onCommand("start", ""); return }
                matches(t, cmdStop) -> { listener?.onCommand("stop", ""); return }
                matches(t, cmdMark) -> { listener?.onCommand("mark", ""); return }
                matches(t, cmdClear) -> { clear(null); return }
                cmdClear.isNotEmpty() && t.startsWith("$cmdClear:", ignoreCase = true) -> {
                    val n = t.substring(cmdClear.length + 1).trim().toIntOrNull()
                    clear(if (n != null && n in 1..TEXT_OVERLAYS) n - 1 else null); return
                }
                cmdVerdict.isNotEmpty() && t.startsWith("$cmdVerdict:", ignoreCase = true) -> {
                    listener?.onCommand("verdict", t.substring(cmdVerdict.length + 1).trim()); return
                }
                matches(t, cmdReset) -> { listener?.onCommand("reset", ""); return }
                cmdReset.isNotEmpty() && t.startsWith("$cmdReset:", ignoreCase = true) -> {
                    listener?.onCommand("reset", t.substring(cmdReset.length + 1).trim()); return
                }
                cmdTeam.isNotEmpty() && t.startsWith("$cmdTeam:", ignoreCase = true) -> {
                    listener?.onCommand("team", t.substring(cmdTeam.length + 1).trim()); return
                }
                cmdMark.isNotEmpty() && t.startsWith("$cmdMark:", ignoreCase = true) -> {
                    listener?.onCommand("mark", t.substring(cmdMark.length + 1).trim()); return
                }
            }
        }

        when (udpMode) {
            "split" -> {
                if (separator.isEmpty()) {
                    for (i in 0 until TEXT_OVERLAYS) setAndNotify(i, m)
                } else {
                    val parts = m.split(separator, limit = TEXT_OVERLAYS)
                    for (i in parts.indices) {
                        val part = parts[i]
                        setAndNotify(i, if (trim) part.trim() else part)
                    }
                }
            }
            "prefix" -> {
                val c = m.firstOrNull()
                if (m.length >= 2 && m[1] == ':' && c != null && c in '1'..('0' + TEXT_OVERLAYS)) {
                    setAndNotify(c - '1', m.substring(2))
                } else {
                    setAndNotify(0, m)
                }
            }
            "only1" -> setAndNotify(0, m)
            "only2" -> setAndNotify(1, m)
            "only3" -> setAndNotify(2, m)
            else -> {
                // rovnaký text do overlayov 1 a 2 (tretí je informačný)
                setAndNotify(0, m)
                setAndNotify(1, m)
            }
        }
    }
}
