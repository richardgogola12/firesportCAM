package sk.firesport.cam

import android.content.SharedPreferences
import java.util.Locale

/**
 * Protokol časomiery ESP32 (repozitár casomiera, zhodný s originálnou časomierou).
 *
 * Binárny rámec 40 bajtov, 10× za sekundu (UDP aj RS232):
 *   [0..2] "RW:"   [3] režim 1 = stopky, 2 = odpočet   [4] počet kanálov 2 = LP+PP, 1 = LP
 *   [5] zobrazovaný čas   [6] 0x01 = resetnutá (pripravená)
 *   [7..10] T1 = LP, [11..14] T2 = PP: čas v ms (3 bajty little-endian) + stav
 *           0x81 beží, 0x82 zastavený senzorom, 0x84 ukončený ručne / koniec odpočtu, 0x00 nulový
 *   [39] 0x0D
 * Textový variant (udp_text=1): $FT,<režim>,<R/B/S/M>,<hlavný>,<LP>,<PP>,<zobrazenie>*<XOR>
 *
 * LP sa zobrazí v overlayi 1, PP v overlayi 2 (nastaviteľné), vo formáte min:sek:stotiny (5:58:98).
 * Zmeny času idú rovnakou cestou ako texty z UDP, takže značky, automatický stop,
 * výsledok pokusu aj výsledková tabuľka fungujú ako doteraz.
 * Udalosti „čas odštartoval“ / „všetky časy zastavené“ idú do automatiky (karta Automatika),
 * ktorá rozhodne o spustení a zastavení nahrávania.
 */
object CasomieraDecoder {
    const val FRAME_LEN = 40
    /** Minimum, ktoré treba z rámca prečítať (hlavička + T1 + T2 + T3 + T4). */
    const val FRAME_MIN = 23

    const val ST_RUN = 0x81
    const val ST_STOP = 0x82
    const val ST_FINISH = 0x84

    // nastavenia
    @Volatile var enabledUdp = false
    @Volatile var lpIndex = 0
    @Volatile var ppIndex = 1
    @Volatile var format = "min"
    @Volatile var showReady = false

    // stav
    private var wasRunning = false
    private var wasReady = false
    private var wasStopped = false
    private var cleared = BooleanArray(TEXT_OVERLAYS)
    private var lastText = arrayOfNulls<String>(TEXT_OVERLAYS)
    @Volatile var lastSummary = ""
        private set
    @Volatile var lastAt = 0L
        private set

    fun load(p: SharedPreferences) {
        enabledUdp = p.getBoolean("cas_protocol", false)
        lpIndex = (Prefs.int(p, "cas_lp_overlay", 1) - 1).coerceIn(0, TEXT_OVERLAYS - 1)
        ppIndex = (Prefs.int(p, "cas_pp_overlay", 2) - 1).coerceIn(0, TEXT_OVERLAYS - 1)
        format = Prefs.str(p, "cas_format", "min")
        showReady = p.getBoolean("cas_show_ready", false)
    }

    /** 358980 ms → "5:58:98" (alebo "358.98"). Stotiny sa orezávajú, nie zaokrúhľujú – ako na časomiere. */
    fun formatMs(ms: Long): String {
        val cs = (ms.coerceAtLeast(0L)) / 10
        return if (format == "sec") String.format(Locale.ROOT, "%d.%02d", cs / 100, cs % 100)
        else {
            val s = cs / 100
            String.format(Locale.ROOT, "%d:%02d:%02d", s / 60, s % 60, cs % 100)
        }
    }

    fun isFrame(d: ByteArray, off: Int, len: Int): Boolean =
        len >= FRAME_MIN && d[off] == 'R'.code.toByte() && d[off + 1] == 'W'.code.toByte() && d[off + 2] == ':'.code.toByte() &&
            // príkazy RW:RST / RW:CHNG z PC programu nie sú rámce
            !(d[off + 3] == 'R'.code.toByte() || d[off + 3] == 'C'.code.toByte())

    private fun isFt(d: ByteArray, off: Int, len: Int): Boolean =
        len >= 8 && d[off] == '$'.code.toByte() && d[off + 1] == 'F'.code.toByte() && d[off + 2] == 'T'.code.toByte() && d[off + 3] == ','.code.toByte()

    /**
     * UDP paket – ak je to rámec časomiery (a protokol je zapnutý), spracuje ho.
     * @return true = paket spracovaný, ďalej sa nespracúva ako text
     */
    fun onPacket(d: ByteArray, off: Int, len: Int, from: String): Boolean {
        if (!enabledUdp) return false
        return when {
            isFrame(d, off, len) -> {
                onFrame(d, off)
                if (from.isNotEmpty()) OverlayState.lastSender = from
                true
            }
            isFt(d, off, len) -> {
                val ok = onFt(String(d, off, len, Charsets.US_ASCII))
                if (ok && from.isNotEmpty()) OverlayState.lastSender = from
                ok
            }
            else -> false
        }
    }

    /** Rámec → textová správa $FT (na preposlanie vedľajším kamerám a do UDP ako text). */
    fun toFt(d: ByteArray, off: Int, len: Int): String? {
        if (!isFrame(d, off, len)) return null
        val mode = d[off + 3].toInt() and 0xFF
        val channels = d[off + 4].toInt() and 0xFF
        val ready = (d[off + 6].toInt() and 0xFF) == 0x01
        val t1 = le24(d, off + 7)
        val s1 = d[off + 10].toInt() and 0xFF
        val t2 = le24(d, off + 11)
        val s2 = d[off + 14].toInt() and 0xFF
        val ftMode = if (mode == 2) 3 else if (channels >= 2) 1 else 2
        val running = s1 == ST_RUN || (ftMode == 1 && s2 == ST_RUN)
        val st = if (ready) 'R' else if (running) 'B' else 'S'
        fun sec(ms: Long) = String.format(Locale.ROOT, "%d.%02d", ms / 1000, (ms % 1000) / 10)
        val main = if (ftMode == 1) maxOf(t1, t2) else t1
        val body = "FT,$ftMode,$st,${sec(main)},${sec(t1)},${if (ftMode == 1) sec(t2) else "-"},${d[off + 5].toInt() and 0xFF}"
        var x = 0
        for (c in body) x = x xor c.code
        return "$" + body + "*" + String.format(Locale.ROOT, "%02X", x and 0xFF)
    }

    private fun le24(d: ByteArray, i: Int): Long =
        (d[i].toLong() and 0xFF) or ((d[i + 1].toLong() and 0xFF) shl 8) or ((d[i + 2].toLong() and 0xFF) shl 16)

    /** Binárny rámec (aspoň [FRAME_MIN] bajtov od [off]). */
    @Synchronized
    fun onFrame(d: ByteArray, off: Int) {
        val mode = d[off + 3].toInt() and 0xFF
        val channels = d[off + 4].toInt() and 0xFF
        val ready = (d[off + 6].toInt() and 0xFF) == 0x01
        val t1 = le24(d, off + 7)
        val s1 = d[off + 10].toInt() and 0xFF
        val t2 = le24(d, off + 11)
        val s2 = d[off + 14].toInt() and 0xFF
        val countdown = mode == 2
        val two = channels >= 2 && !countdown
        val running = s1 == ST_RUN || (two && s2 == ST_RUN)
        fun stopped(st: Int) = st == ST_STOP || st == ST_FINISH
        val allStopped = !ready && !running && stopped(s1) && (!two || stopped(s2))
        update(ready, running, allStopped, countdown, formatMs(t1), if (two) formatMs(t2) else null)
    }

    /** Textový variant $FT,… */
    @Synchronized
    fun onFt(line: String): Boolean {
        val body = line.trim().removePrefix("$").substringBefore('*')
        val p = body.split(',')
        if (p.size < 7 || p[0] != "FT") return false
        val mode = p[1].toIntOrNull() ?: return false
        val st = p[2].firstOrNull() ?: return false
        val main = p[3]
        fun ms(s: String): Long? = Times.parse(s)?.let { Math.round(it * 1000) }
        val mainMs = ms(main) ?: 0L
        val lp = if (p[4] == "-") mainMs else ms(p[4]) ?: mainMs
        val pp = if (p[5] == "-") mainMs else ms(p[5]) ?: mainMs
        val countdown = mode == 3
        update(st == 'R', st == 'B', st == 'S', countdown, formatMs(if (countdown) mainMs else lp), if (mode == 1) formatMs(pp) else null)
        return true
    }

    private fun update(ready: Boolean, running: Boolean, allStopped: Boolean, countdown: Boolean, lp: String, pp: String?) {
        lastAt = android.os.SystemClock.elapsedRealtime()
        lastSummary = when {
            ready -> "časomiera pripravená"
            pp != null -> "LP $lp • PP $pp" + if (running) " (beží)" else ""
            else -> (if (countdown) "odpočet " else "LP ") + lp + if (running) " (beží)" else ""
        }
        OverlayState.lastPacket = "RW: $lastSummary"
        OverlayState.lastPacketAt = lastAt

        // reset časomiery → vymazať čas (podľa nastavenia „Po vymazaní“)
        if (ready && !wasReady) {
            if (!showReady) {
                OverlayState.clear(lpIndex)
                if (ppIndex != lpIndex) OverlayState.clear(ppIndex)
                lastText.fill(null)
            }
        }
        // udalosti pre automatiku (odpočet nie – to je čas na prípravu, nie pokus)
        val fireStart = !countdown && running && !wasRunning
        val fireStop = !countdown && allStopped && !wasStopped
        wasReady = ready
        wasRunning = running
        wasStopped = allStopped

        // najprv časy (aby značky a výsledok mali posledný čas), potom udalosti
        if (!(ready && !showReady)) {
            put(lpIndex, lp)
            if (pp != null && ppIndex != lpIndex) put(ppIndex, pp)
            else if (pp == null && ppIndex != lpIndex && !cleared[ppIndex]) {
                OverlayState.clear(ppIndex)
                lastText[ppIndex] = null
                cleared[ppIndex] = true
            }
        }
        if (fireStart) OverlayState.listener?.onCommand("timestart", "časomiera RW:")
        if (fireStop) OverlayState.listener?.onCommand("timestop", "časomiera RW:")
    }

    private fun put(i: Int, text: String) {
        cleared[i] = false
        if (lastText[i] == text && OverlayState.lastText(i) == text) {
            // rovnaký čas – len obnoví „čerstvosť“ (timeout overlayu)
            OverlayState.setText(i, text)
            return
        }
        lastText[i] = text
        OverlayState.protocolText(i, text)
    }
}
