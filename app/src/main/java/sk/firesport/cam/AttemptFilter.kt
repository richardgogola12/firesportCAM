package sk.firesport.cam

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jeden pokus = video + jeho údaje. */
data class AttemptRow(val file: File?, val info: AttemptInfo, val verdict: String)

/**
 * Filter pokusov (galéria aj výsledky): družstvo, verdikt, deň, číslo pokusu,
 * rozsah výsledného času a hľadaný text. Plus zoradenie.
 */
data class AttemptFilter(
    val team: String? = null,
    val verdicts: Set<String> = emptySet(),
    val day: String? = null,
    val attemptNo: Int? = null,
    val minTime: Double? = null,
    val maxTime: Double? = null,
    val text: String = "",
    val sort: String = ""
) {
    val active: Boolean
        get() = team != null || verdicts.isNotEmpty() || day != null || attemptNo != null ||
            minTime != null || maxTime != null || text.isNotBlank()

    fun matches(r: AttemptRow): Boolean {
        if (team != null && !r.info.team.equals(team, ignoreCase = true)) return false
        if (verdicts.isNotEmpty() && r.verdict !in verdicts) return false
        if (day != null && dayOf(r.info) != day) return false
        if (attemptNo != null && r.info.attemptNo != attemptNo) return false
        if (minTime != null || maxTime != null) {
            val t = r.info.result ?: return false
            if (minTime != null && t < minTime) return false
            if (maxTime != null && t > maxTime) return false
        }
        if (text.isNotBlank()) {
            val hay = listOfNotNull(
                r.file?.name, r.info.team, r.info.note, r.info.camera, r.info.event,
                r.info.finals.joinToString(" "), Times.format(r.info.result), r.verdict
            ).joinToString(" ").lowercase(Locale.getDefault())
            if (text.lowercase(Locale.getDefault()).split(' ').filter { it.isNotBlank() }.any { it !in hay }) return false
        }
        return true
    }

    companion object {
        private val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val dayLabel = SimpleDateFormat("EEE d.M.yyyy", Locale("sk"))

        fun dayOf(info: AttemptInfo): String? = if (info.startEpochMs > 0) dayKey.format(Date(info.startEpochMs)) else null

        fun dayLabel(key: String): String = try {
            dayLabel.format(dayKey.parse(key)!!)
        } catch (_: Exception) {
            key
        }

        /** Riadky zo súborov videí. */
        fun rows(p: SharedPreferences, list: List<Pair<File, AttemptInfo>>): List<AttemptRow> =
            list.map { (f, i) -> AttemptRow(f, i, Verdicts.of(p, i)) }
    }
}

/**
 * Pás „čipov“ s filtrami nad zoznamom. Hostiteľ dodá dostupné riadky (pre ponuky)
 * a možnosti zoradenia; pri zmene sa zavolá [onChange].
 */
class FilterBar(
    private val act: Activity,
    private val container: LinearLayout,
    private val sortOptions: List<Pair<String, String>>,
    private val allowNa: Boolean,
    private val onChange: () -> Unit
) {
    var filter = AttemptFilter(sort = sortOptions.firstOrNull()?.first ?: "")
    private val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(act)

    fun build(rows: List<AttemptRow>, extraTeams: List<String> = emptyList()) {
        container.removeAllViews()
        val f = filter
        chip("↕ " + (sortOptions.firstOrNull { it.first == f.sort }?.second ?: "Zoradiť"), false) { pickSort() }
        chip("👥 " + (f.team ?: "Družstvo"), f.team != null) { pickTeam(rows, extraTeams) }
        chip(
            "✔ " + if (f.verdicts.isEmpty()) "Verdikt" else f.verdicts.joinToString(",") { Verdicts.short(p, it) },
            f.verdicts.isNotEmpty()
        ) { pickVerdicts() }
        chip("📅 " + (f.day?.let { AttemptFilter.dayLabel(it) } ?: "Deň"), f.day != null) { pickDay(rows) }
        chip("# " + (f.attemptNo?.let { "$it. pokus" } ?: "Pokus"), f.attemptNo != null) { pickAttempt(rows) }
        val timeLabel = when {
            f.minTime != null && f.maxTime != null -> "${Times.format(f.minTime)}–${Times.format(f.maxTime)} s"
            f.maxTime != null -> "do ${Times.format(f.maxTime)} s"
            f.minTime != null -> "od ${Times.format(f.minTime)} s"
            else -> "Čas"
        }
        chip("⏱ $timeLabel", f.minTime != null || f.maxTime != null) { pickTime() }
        chip("🔍 " + f.text.ifBlank { "Hľadať" }, f.text.isNotBlank()) { pickText() }
        if (f.active) chip("✖ Zrušiť filtre", false) {
            filter = AttemptFilter(sort = filter.sort)
            onChange()
        }
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit) {
        val dp = act.resources.displayMetrics.density
        val tv = TextView(act).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            setBackgroundResource(if (on) R.drawable.chip_bg_selected else R.drawable.chip_bg)
            setOnClickListener { click() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = (6 * dp).toInt()
        container.addView(tv, lp)
    }

    private fun set(f: AttemptFilter) {
        filter = f
        onChange()
    }

    private fun pickSort() {
        AlertDialog.Builder(act)
            .setTitle("Zoradiť podľa")
            .setItems(sortOptions.map { it.second }.toTypedArray()) { _, w -> set(filter.copy(sort = sortOptions[w].first)) }
            .show()
    }

    private fun pickTeam(rows: List<AttemptRow>, extra: List<String>) {
        val teams = (rows.map { it.info.team } + extra).filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }.sortedBy { it.lowercase(Locale.getDefault()) }
        val labels = listOf("Všetky družstvá") + teams.map { t ->
            val n = rows.count { it.info.team.equals(t, true) && it.file != null }
            "$t ($n)"
        }
        AlertDialog.Builder(act)
            .setTitle("Družstvo")
            .setItems(labels.toTypedArray()) { _, w -> set(filter.copy(team = if (w == 0) null else teams[w - 1])) }
            .show()
    }

    private fun pickVerdicts() {
        val codes = if (allowNa) Verdicts.ALL else Verdicts.ATTEMPT
        val checked = BooleanArray(codes.size) { codes[it] in filter.verdicts }
        AlertDialog.Builder(act)
            .setTitle("Verdikt")
            .setMultiChoiceItems(codes.map { "${Verdicts.emoji(it)} ${Verdicts.full(p, it)}" }.toTypedArray(), checked) { _, w, c ->
                checked[w] = c
            }
            .setPositiveButton("OK") { _, _ -> set(filter.copy(verdicts = codes.filterIndexed { i, _ -> checked[i] }.toSet())) }
            .setNeutralButton("Všetky") { _, _ -> set(filter.copy(verdicts = emptySet())) }
            .show()
    }

    private fun pickDay(rows: List<AttemptRow>) {
        val days = rows.mapNotNull { AttemptFilter.dayOf(it.info) }.groupingBy { it }.eachCount().toSortedMap(reverseOrder())
        val keys = days.keys.toList()
        val today = AttemptFilter.dayOf(AttemptInfo(startEpochMs = System.currentTimeMillis()))
        val labels = listOf("Všetky dni") + keys.map { k ->
            (if (k == today) "Dnes – " else "") + AttemptFilter.dayLabel(k) + " (${days[k]})"
        }
        AlertDialog.Builder(act)
            .setTitle("Deň")
            .setItems(labels.toTypedArray()) { _, w -> set(filter.copy(day = if (w == 0) null else keys[w - 1])) }
            .show()
    }

    private fun pickAttempt(rows: List<AttemptRow>) {
        val nums = rows.map { it.info.attemptNo }.filter { it > 0 }.distinct().sorted()
        val labels = listOf("Všetky pokusy") + nums.map { "$it. pokus" }
        AlertDialog.Builder(act)
            .setTitle("Číslo pokusu")
            .setItems(labels.toTypedArray()) { _, w -> set(filter.copy(attemptNo = if (w == 0) null else nums[w - 1])) }
            .show()
    }

    private fun pickTime() {
        val dp = act.resources.displayMetrics.density
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun field(hint: String, v: Double?): EditText {
            box.addView(TextView(act).apply { text = hint; alpha = 0.7f })
            return EditText(act).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(v?.let { Times.format(it) } ?: "")
                box.addView(this)
            }
        }
        val min = field("Od (s) – prázdne = bez obmedzenia", filter.minTime)
        val max = field("Do (s) – napr. 20", filter.maxTime)
        AlertDialog.Builder(act)
            .setTitle("Výsledný čas")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                set(
                    filter.copy(
                        minTime = min.text.toString().replace(',', '.').trim().toDoubleOrNull(),
                        maxTime = max.text.toString().replace(',', '.').trim().toDoubleOrNull()
                    )
                )
            }
            .setNeutralButton("Bez obmedzenia") { _, _ -> set(filter.copy(minTime = null, maxTime = null)) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun pickText() {
        val input = EditText(act).apply {
            setText(filter.text)
            hint = "družstvo, čas, názov súboru, poznámka…"
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = FrameLayout(act).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(act)
            .setTitle("Hľadať")
            .setView(box)
            .setPositiveButton("Hľadať") { _, _ -> set(filter.copy(text = input.text.toString().trim())) }
            .setNeutralButton("Vymazať") { _, _ -> set(filter.copy(text = "")) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }
}

/** Úprava údajov pokusu: družstvo, verdikt, výsledný čas, poznámka. */
object AttemptEditor {
    fun show(act: Activity, file: File, onSaved: () -> Unit) {
        val p = PreferenceManager.getDefaultSharedPreferences(act)
        val info = Sidecars.loadInfo(file) ?: AttemptInfo(startEpochMs = file.lastModified())
        val dp = act.resources.displayMetrics.density
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun field(hint: String, value: String, type: Int): EditText {
            box.addView(TextView(act).apply { text = hint; alpha = 0.7f })
            return EditText(act).apply {
                setText(value)
                inputType = type
                box.addView(this)
            }
        }
        val team = field("Družstvo", info.team, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        box.addView(TextView(act).apply { text = "Verdikt"; alpha = 0.7f })
        val codes = listOf("") + Verdicts.ATTEMPT
        val group = android.widget.RadioGroup(act)
        val auto = Verdicts.of(p, info.copy(verdict = ""))
        codes.forEachIndexed { i, c ->
            group.addView(android.widget.RadioButton(act).apply {
                id = 1000 + i
                text = if (c.isEmpty()) "Automaticky (teraz ${Verdicts.short(p, auto)})" else "${Verdicts.emoji(c)} ${Verdicts.full(p, c)}"
            })
        }
        group.check(1000 + codes.indexOf(if (info.verdict in Verdicts.ATTEMPT) info.verdict else ""))
        box.addView(group)
        val result = field(
            "Výsledný čas (prázdne = z časomiery)",
            info.manualResult?.let { Times.format(it) } ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val no = field("Číslo pokusu", if (info.attemptNo > 0) info.attemptNo.toString() else "", InputType.TYPE_CLASS_NUMBER)
        val note = field("Poznámka", info.note, InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(act)
            .setTitle(file.name)
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Uložiť") { _, _ ->
                val v = codes.getOrElse(group.checkedRadioButtonId - 1000) { "" }
                val newInfo = info.copy(
                    team = team.text.toString().trim(),
                    manualResult = result.text.toString().replace(',', '.').trim().toDoubleOrNull(),
                    attemptNo = no.text.toString().trim().toIntOrNull() ?: 0,
                    note = note.text.toString().trim().let { if (it.equals(Verdicts.NP, true) || it.equals(Verdicts.D, true)) "" else it },
                    verdict = v
                )
                Sidecars.saveInfo(file, newInfo)
                if (newInfo.team.isNotEmpty()) Teams.add(p, newInfo.team)
                onSaved()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    /** Rýchle nastavenie verdiktu jednému alebo viacerým videám. */
    fun pickVerdict(act: Activity, files: List<File>, onSaved: () -> Unit) {
        if (files.isEmpty()) return
        val p = PreferenceManager.getDefaultSharedPreferences(act)
        val codes = Verdicts.ATTEMPT + ""
        val labels = codes.map { if (it.isEmpty()) "Automaticky (podľa času)" else "${Verdicts.emoji(it)} ${Verdicts.full(p, it)}" }
        AlertDialog.Builder(act)
            .setTitle(if (files.size == 1) "Verdikt pokusu" else "Verdikt pre ${files.size} pokusov")
            .setItems(labels.toTypedArray()) { _, w ->
                for (f in files) setVerdict(f, codes[w])
                onSaved()
            }
            .show()
    }

    fun setVerdict(file: File, code: String) {
        val info = Sidecars.loadInfo(file) ?: AttemptInfo(startEpochMs = file.lastModified())
        val note = if (info.note.equals(Verdicts.NP, true) || info.note.equals(Verdicts.D, true)) "" else info.note
        Sidecars.saveInfo(file, info.copy(verdict = code, note = note))
    }
}
