package sk.firesport.cam

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Údaje o jednom pokuse (súbor *.info.json vedľa videa):
 * družstvo, súťaž, kamera, čas začiatku, finálne časy.
 */
data class AttemptInfo(
    val team: String = "",
    val event: String = "",
    val camera: String = "",
    val attemptNo: Int = 0,
    val startEpochMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Finálne texty overlayov (napr. "L 16.84", "P 17.02"). */
    val finals: List<String> = emptyList(),
    /** Ručne opravený výsledný čas (null = vypočítať z finals). */
    val manualResult: Double? = null,
    /** Poznámka k pokusu. */
    val note: String = "",
    /** Verdikt: OK, NP, D alebo "" = automaticky (s časom OK, bez času NP). */
    val verdict: String = ""
) {
    /** Výsledný čas: horší (vyšší) z časov terčov, alebo ručne zadaný. */
    val result: Double?
        get() = manualResult ?: finals.mapNotNull { Times.parse(it) }.maxOrNull()

    fun toJson(): JSONObject = JSONObject().apply {
        put("team", team)
        put("event", event)
        put("camera", camera)
        put("attemptNo", attemptNo)
        put("startEpochMs", startEpochMs)
        put("durationMs", durationMs)
        put("finals", JSONArray(finals))
        if (manualResult != null) put("manualResult", manualResult)
        put("note", note)
        put("verdict", verdict)
    }

    companion object {
        fun fromJson(o: JSONObject): AttemptInfo {
            val arr = o.optJSONArray("finals")
            val finals = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
            return AttemptInfo(
                team = o.optString("team"),
                event = o.optString("event"),
                camera = o.optString("camera"),
                attemptNo = o.optInt("attemptNo"),
                startEpochMs = o.optLong("startEpochMs"),
                durationMs = o.optLong("durationMs"),
                finals = finals,
                manualResult = if (o.has("manualResult")) o.optDouble("manualResult") else null,
                note = o.optString("note"),
                verdict = o.optString("verdict")
            )
        }
    }
}

/**
 * Verdikty pokusov: OK = úspešný, NP = nedokončený, D = diskvalifikovaný,
 * NA = družstvo ešte nebežalo (nemá žiadny pokus).
 * Skratky a popisy sa dajú zmeniť v Nastavenia → Ostatné.
 */
object Verdicts {
    const val OK = "OK"
    const val NP = "NP"
    const val D = "D"
    const val NA = "NA"

    /** Verdikty, ktoré môže mať nahraný pokus. */
    val ATTEMPT = listOf(OK, NP, D)
    val ALL = listOf(OK, NP, D, NA)

    private val DEF_LABEL = mapOf(OK to "pokus úspešný", NP to "nedokončený pokus", D to "diskvalifikovaný", NA to "ešte nebežali")

    fun short(p: android.content.SharedPreferences, code: String): String =
        Prefs.str(p, "verdict_${code.lowercase()}_short", code).ifEmpty { code }

    fun label(p: android.content.SharedPreferences, code: String): String =
        Prefs.str(p, "verdict_${code.lowercase()}_label", DEF_LABEL[code] ?: code).ifEmpty { DEF_LABEL[code] ?: code }

    /** „OK – pokus úspešný“ */
    fun full(p: android.content.SharedPreferences, code: String) = "${short(p, code)} – ${label(p, code)}"

    fun color(code: String): Int = when (code) {
        OK -> 0xFF81C784.toInt()
        NP -> 0xFFFFB74D.toInt()
        D -> 0xFFE57373.toInt()
        else -> 0xFFB0B0B0.toInt()
    }

    fun emoji(code: String): String = when (code) {
        OK -> "✅"
        NP -> "⚠"
        D -> "⛔"
        else -> "⏳"
    }

    /** Text z UDP / od používateľa → kód verdiktu ("" = automaticky, null = neznámy). */
    fun parse(p: android.content.SharedPreferences, s: String): String? {
        val t = s.trim()
        if (t.isEmpty() || t.equals("AUTO", true)) return ""
        for (c in ATTEMPT) if (t.equals(c, true) || t.equals(short(p, c), true)) return c
        return when (t.uppercase()) {
            "DQ", "DIS", "DISK" -> D
            "N", "NEDOKONCENY", "NEPLATNY" -> NP
            "U", "USPESNY" -> OK
            else -> null
        }
    }

    /** Verdikt pokusu – nastavený ručne, inak automaticky podľa času. */
    fun of(p: android.content.SharedPreferences, info: AttemptInfo): String {
        if (info.verdict in ATTEMPT) return info.verdict
        // staršie pokusy mali NP / D zapísané v poznámke
        val legacy = info.note.trim().uppercase()
        if (legacy == NP || legacy == D || legacy == "DQ") return if (legacy == NP) NP else D
        return if (info.result != null) OK else NP
    }

    /** Pokus sa počíta do poradia: úspešný a má čas. */
    fun ranked(p: android.content.SharedPreferences, info: AttemptInfo) = of(p, info) == OK && info.result != null
}

/** Práca s časmi v texte ("L 16.84" → 16.84). */
object Times {
    private val re = Regex("(\\d+)[.,:](\\d+)")

    fun parse(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val m = re.find(text) ?: return text.trim().toDoubleOrNull()
        return "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull()
    }

    fun format(v: Double?): String = if (v == null) "–" else String.format(Locale.ROOT, "%.2f", v)

    /** Časť názvu súboru z času: 16.84 → 16-84 */
    fun forFileName(v: Double?): String = if (v == null) "" else format(v).replace('.', '-')
}

/** Súbory, ktoré patria k videu (presúvajú a mažú sa spolu s ním). */
object Sidecars {
    val SUFFIXES = listOf(".markers.txt", ".info.json", ".splits.txt")

    fun of(video: File): List<File> = SUFFIXES.map { File(video.parentFile, video.nameWithoutExtension + it) }

    fun infoFile(video: File) = File(video.parentFile, video.nameWithoutExtension + ".info.json")

    fun loadInfo(video: File): AttemptInfo? {
        val f = infoFile(video)
        if (!f.exists()) return null
        return try {
            AttemptInfo.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    fun saveInfo(video: File, info: AttemptInfo) {
        try {
            infoFile(video).writeText(info.toJson().toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}

/** Medzičasy – fázy pokusu označené v prehrávači (súbor *.splits.txt). */
object Splits {
    fun fileFor(video: File) = File(video.parentFile, video.nameWithoutExtension + ".splits.txt")

    /** Zoznam (názov fázy, čas vo videu v ms). */
    fun load(video: File): List<Pair<String, Long>> {
        val f = fileFor(video)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines(Charsets.UTF_8).mapNotNull { line ->
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).trim().toLongOrNull() ?: return@mapNotNull null)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(video: File, list: List<Pair<String, Long>>) {
        val f = fileFor(video)
        if (list.isEmpty()) {
            f.delete()
            return
        }
        try {
            f.writeText(list.joinToString("\n") { "${it.first}\t${it.second}" } + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /** Časy fáz od prvej označenej (v s): pre každú fázu (názov, trvanie fázy). */
    fun durations(list: List<Pair<String, Long>>): List<Pair<String, Double>> {
        if (list.size < 2) return emptyList()
        return (1 until list.size).map { i -> list[i].first to (list[i].second - list[i - 1].second) / 1000.0 }
    }
}

/** Zoznam družstiev a číslovanie pokusov. */
object Teams {
    fun list(p: android.content.SharedPreferences): List<String> =
        (p.getString("teams_list", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun add(p: android.content.SharedPreferences, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val l = list(p)
        if (l.none { it.equals(n, ignoreCase = true) }) {
            p.edit().putString("teams_list", (l + n).joinToString("\n")).apply()
        }
    }

    /** Režim číslovania: "day" = každý deň od 1, "reset" = od posledného vynulovania, "event" = celá súťaž. */
    fun numberingMode(p: android.content.SharedPreferences): String =
        p.getString("attempt_numbering", "day") ?: "day"

    private fun teamKey(team: String) = "attempt_reset_team_" + team.trim().lowercase()

    /** Vynuluje počítadlo – pre jedno družstvo alebo (team == null) pre všetky. */
    fun resetCounter(p: android.content.SharedPreferences, team: String? = null) {
        val now = System.currentTimeMillis()
        val ed = p.edit()
        if (team.isNullOrBlank()) {
            ed.putLong("attempt_reset_at", now)
            // globálne vynulovanie ruší staršie vynulovania jednotlivých družstiev
            p.all.keys.filter { it.startsWith("attempt_reset_team_") }.forEach { ed.remove(it) }
        } else {
            ed.putLong(teamKey(team), now)
        }
        ed.apply()
    }

    private fun startOfToday(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Od kedy sa rátajú pokusy družstva (epoch ms). */
    fun countFrom(p: android.content.SharedPreferences, team: String): Long {
        var from = maxOf(p.getLong("attempt_reset_at", 0L), p.getLong(teamKey(team), 0L))
        if (numberingMode(p) == "day") from = maxOf(from, startOfToday())
        return from
    }

    /** Ďalšie číslo pokusu družstva v danej súťaži (od vynulovania / od začiatku dňa). */
    fun nextAttemptNo(ctx: android.content.Context, event: String, team: String): Int {
        if (team.isEmpty()) return 0
        val p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val from = countFrom(p, team)
        val count = VideoStore.list(ctx)
            .filter { VideoStore.eventOf(ctx, it) == VideoStore.safeName(event) }
            .count { f ->
                val info = Sidecars.loadInfo(f) ?: return@count false
                val t = if (info.startEpochMs > 0) info.startEpochMs else f.lastModified()
                info.team.equals(team, ignoreCase = true) && t >= from
            }
        return count + 1
    }
}
