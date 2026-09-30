package sk.firesport.cam

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Výsledková tabuľka súťaže z uložených pokusov.
 * Ťuknutie = prehrať video, podržanie = opraviť údaje.
 */
class ResultsActivity : AppCompatActivity() {

    companion object {
        private const val M_CSV = 1
        private const val M_TEXT = 2
        private const val M_BEST = 3
        private const val ALL = "\u0000all"
    }

    private lateinit var eventBar: LinearLayout
    private lateinit var filterBar: FilterBar
    private lateinit var summary: TextView
    private lateinit var table: TableLayout
    private lateinit var emptyText: TextView
    private var filter = ALL
    private var bestOnly = false
    private var rows: List<Row> = emptyList()
    private val timeFmt = SimpleDateFormat("d.M. HH:mm", Locale.getDefault())
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private data class Row(val file: File?, val info: AttemptInfo, val verdict: String) {
        val ranked get() = verdict == Verdicts.OK && info.result != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Výsledky"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        eventBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        barScroll.addView(eventBar)
        root.addView(barScroll)
        val fScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val fRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        fScroll.addView(fRow)
        root.addView(fScroll)
        filterBar = FilterBar(
            this, fRow,
            listOf("result" to "Poradie (výsledok)", "time" to "Čas nahrávania", "team" to "Družstvo", "attempt" to "Číslo pokusu"),
            allowNa = true
        ) { reload() }
        summary = TextView(this).apply {
            textSize = 13f
            setPadding((12 * dp).toInt(), (2 * dp).toInt(), (12 * dp).toInt(), (2 * dp).toInt())
        }
        root.addView(summary)

        emptyText = TextView(this).apply {
            text = "Zatiaľ žiadne pokusy.\nVyber družstvo v kamere a nahraj pokus – čas sa doplní z časomiery."
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (40 * dp).toInt(), (24 * dp).toInt(), 0)
            visibility = View.GONE
        }
        root.addView(emptyText)

        table = TableLayout(this).apply {
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt())
            isStretchAllColumns = false
        }
        val hs = HorizontalScrollView(this).apply { addView(table) }
        val vs = ScrollView(this).apply { addView(hs) }
        root.addView(vs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val ev = PreferenceManager.getDefaultSharedPreferences(this).getString("event_name", "") ?: ""
        filter = savedInstanceState?.getString("filter") ?: VideoStore.safeName(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filter", filter)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_CSV, 1, "Export do Excelu (CSV)").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_TEXT, 2, "Zdieľať ako text")
        menu.add(0, M_BEST, 3, if (bestOnly) "Zobraziť všetky pokusy" else "Iba najlepší pokus družstva")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_CSV -> exportCsv()
            M_TEXT -> shareText()
            M_BEST -> {
                bestOnly = !bestOnly
                invalidateOptionsMenu()
                reload()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ dáta

    private fun reload() {
        val all = VideoStore.attempts(this, null)
        buildEventBar(all.map { VideoStore.eventOf(this, it.first) })
        val inEvent = all
            .filter { filter == ALL || VideoStore.eventOf(this, it.first) == filter }
            .map { Row(it.first, it.second, Verdicts.of(prefs, it.second)) }
        val f = filterBar.filter
        // družstvá zo zoznamu, ktoré v tejto súťaži (a vybranom dni) ešte nebežali
        val dayRows = if (f.day == null) inEvent else inEvent.filter { AttemptFilter.dayOf(it.info) == f.day }
        val ran = dayRows.map { it.info.team.lowercase(Locale.getDefault()) }.toSet()
        val notRun = Teams.list(prefs).filter { it.lowercase(Locale.getDefault()) !in ran }
            .map { Row(null, AttemptInfo(team = it), Verdicts.NA) }
        filterBar.build(inEvent.map { AttemptRow(it.file, it.info, it.verdict) }, Teams.list(prefs))

        var list = (inEvent + notRun).filter { f.matches(AttemptRow(it.file, it.info, it.verdict)) || (it.verdict == Verdicts.NA && naMatches(it, f)) }
        if (bestOnly) {
            list = list.groupBy { it.info.team.lowercase(Locale.getDefault()).ifEmpty { it.file?.name ?: "" } }
                .values.map { g -> g.filter { it.ranked }.minByOrNull { it.info.result ?: Double.MAX_VALUE } ?: g.maxBy { it.info.startEpochMs } }
        }
        val byGroup = compareBy<Row> { if (it.ranked) 0 else if (it.verdict == Verdicts.NA) 2 else 1 }
        rows = list.sortedWith(
            when (f.sort) {
                "time" -> compareBy<Row> { it.file == null }.thenByDescending { it.info.startEpochMs }
                "team" -> compareBy<Row> { it.info.team.lowercase(Locale.getDefault()) }.thenBy { it.info.attemptNo }.thenBy { it.info.startEpochMs }
                "attempt" -> compareBy<Row> { it.file == null }.thenBy { it.info.attemptNo }.thenBy { it.info.result ?: Double.MAX_VALUE }
                else -> byGroup.thenBy { it.info.result ?: Double.MAX_VALUE }.thenBy { it.info.startEpochMs }
            }
        )
        buildTable()
        val counts = rows.groupingBy { it.verdict }.eachCount()
        summary.text = Verdicts.ALL.joinToString("   ") { "${Verdicts.emoji(it)} ${Verdicts.short(prefs, it)}: ${counts[it] ?: 0}" }
        supportActionBar?.subtitle = when (filter) {
            ALL -> "Všetky súťaže"
            "" -> "Bez súťaže"
            else -> filter
        } + " • ${rows.count { it.file != null }} pokusov"
    }

    /** Riadok „ešte nebežali“ prejde filtrom len podľa družstva, verdiktu a textu. */
    private fun naMatches(r: Row, f: AttemptFilter): Boolean {
        if (f.verdicts.isNotEmpty() && Verdicts.NA !in f.verdicts) return false
        if (f.attemptNo != null || f.minTime != null || f.maxTime != null) return false
        if (f.team != null && !r.info.team.equals(f.team, true)) return false
        if (f.text.isNotBlank() && !r.info.team.contains(f.text.trim(), true)) return false
        return true
    }

    private fun buildEventBar(events: List<String>) {
        eventBar.removeAllViews()
        val dp = resources.displayMetrics.density
        val entries = ArrayList<Pair<String, String>>()
        entries.add(ALL to "Všetko")
        if (events.contains("")) entries.add("" to "Bez súťaže")
        for (e in events.filter { it.isNotEmpty() }.distinct().sorted()) entries.add(e to "🏆 $e")
        for ((key, label) in entries) {
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setBackgroundResource(if (key == filter) R.drawable.chip_bg_selected else R.drawable.chip_bg)
                setOnClickListener {
                    filter = key
                    reload()
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * dp).toInt()
            eventBar.addView(tv, lp)
        }
    }

    private fun cell(text: String, bold: Boolean = false, color: Int = Color.WHITE, alignEnd: Boolean = false): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = if (alignEnd) Gravity.END else Gravity.START
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
    }

    private fun maxFinals() = rows.maxOfOrNull { it.info.finals.size }?.coerceAtLeast(1) ?: 1

    private fun buildTable() {
        table.removeAllViews()
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) return
        val nf = maxFinals()
        val hc = 0xFFFFAB91.toInt()
        val head = TableRow(this)
        head.addView(cell("#", true, hc))
        head.addView(cell("Družstvo", true, hc))
        head.addView(cell("Pokus", true, hc))
        head.addView(cell("Verdikt", true, hc))
        for (i in 0 until nf) head.addView(cell(if (nf == 2) (if (i == 0) "Ľavý" else "Pravý") else "Čas ${i + 1}", true, hc))
        head.addView(cell("Výsledok", true, hc))
        head.addView(cell("Nahraté", true, hc))
        head.addView(cell("Kamera", true, hc))
        head.addView(cell("Poznámka", true, hc))
        table.addView(head)

        var place = 0
        for ((idx, r) in rows.withIndex()) {
            val valid = r.ranked
            if (valid) place++
            val tr = TableRow(this).apply {
                setBackgroundColor(if (idx % 2 == 0) 0x14FFFFFF else 0x00000000)
                val f = r.file
                if (f != null) {
                    setOnClickListener { play(f) }
                    setOnLongClickListener {
                        rowMenu(f)
                        true
                    }
                } else setOnClickListener { toast("${r.info.team}: ${Verdicts.label(prefs, Verdicts.NA)}") }
            }
            val vc = Verdicts.color(r.verdict)
            tr.addView(cell(if (valid && filterBar.filter.sort.let { it == "" || it == "result" }) "$place." else "", bold = true))
            tr.addView(cell(r.info.team.ifEmpty { "(bez družstva)" }, bold = true, color = if (r.file == null) 0xFFB0B0B0.toInt() else Color.WHITE))
            tr.addView(cell(if (r.info.attemptNo > 0) "${r.info.attemptNo}." else ""))
            tr.addView(cell(Verdicts.short(prefs, r.verdict), bold = true, color = vc))
            for (i in 0 until nf) tr.addView(cell(r.info.finals.getOrNull(i) ?: "", alignEnd = true))
            val res = if (r.info.result != null) Times.format(r.info.result) + if (r.info.manualResult != null) " ✎" else "" else "–"
            tr.addView(cell(res, bold = true, color = if (valid) 0xFFFFEB3B.toInt() else vc, alignEnd = true))
            tr.addView(cell(if (r.info.startEpochMs > 0) timeFmt.format(Date(r.info.startEpochMs)) else "", color = 0xFFB0B0B0.toInt()))
            tr.addView(cell(r.info.camera, color = 0xFFB0B0B0.toInt()))
            tr.addView(cell(r.info.note, color = 0xFFB0B0B0.toInt()))
            table.addView(tr)
        }
    }

    private fun rowMenu(f: File) {
        val items = arrayOf("▶ Prehrať", "✔ Verdikt…", "✎ Upraviť údaje…")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> play(f)
                    1 -> AttemptEditor.pickVerdict(this, listOf(f)) { reload() }
                    2 -> AttemptEditor.show(this, f) { reload() }
                }
            }
            .show()
    }

    private fun play(f: File) {
        val markers = Markers.load(f)
        val start = markers.firstOrNull()?.let { (it.ms - 3000).coerceAtLeast(0L) } ?: 0L
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath)
                .putExtra(PlayerActivity.EXTRA_START_MS, start)
        )
    }

    // ------------------------------------------------------------------ export

    private fun resultLines(): List<List<String>> {
        val nf = maxFinals()
        val out = ArrayList<List<String>>()
        val head = arrayListOf("Poradie", "Družstvo", "Pokus", "Verdikt")
        for (i in 0 until nf) head.add(if (nf == 2) (if (i == 0) "Ľavý" else "Pravý") else "Čas ${i + 1}")
        head.addAll(listOf("Výsledok", "Poznámka", "Čas nahrávania", "Kamera", "Video"))
        out.add(head)
        var place = 0
        val dt = SimpleDateFormat("d.M.yyyy HH:mm:ss", Locale.getDefault())
        for (r in rows) {
            val valid = r.ranked
            if (valid) place++
            val line = arrayListOf(
                if (valid) place.toString() else "", r.info.team,
                if (r.info.attemptNo > 0) r.info.attemptNo.toString() else "", Verdicts.short(prefs, r.verdict)
            )
            for (i in 0 until nf) line.add(r.info.finals.getOrNull(i) ?: "")
            line.add(if (r.info.result != null) Times.format(r.info.result).replace('.', ',') else "")
            line.add(r.info.note)
            line.add(if (r.info.startEpochMs > 0) dt.format(Date(r.info.startEpochMs)) else "")
            line.add(r.info.camera)
            line.add(r.file?.name ?: "")
            out.add(line)
        }
        return out
    }

    private fun exportCsv() {
        if (rows.isEmpty()) {
            toast("Nie je čo exportovať")
            return
        }
        // bodkočiarka + BOM = Excel v slovenskom nastavení otvorí správne aj s diakritikou
        val csv = "﻿" + resultLines().joinToString("\r\n") { line ->
            line.joinToString(";") { v -> "\"" + v.replace("\"", "\"\"") + "\"" }
        }
        val name = "vysledky_" + VideoStore.safeName(if (filter == ALL) "vsetko" else filter.ifEmpty { "bez_sutaze" }) + ".csv"
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val f = File(dir, name)
        try {
            f.writeText(csv, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Výsledky")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Uložiť / poslať výsledky"))
        } catch (e: Exception) {
            toast("Export zlyhal: ${e.message}")
        }
    }

    private fun shareText() {
        if (rows.isEmpty()) {
            toast("Nie je čo zdieľať")
            return
        }
        val title = if (filter == ALL) "Výsledky" else "Výsledky – ${filter.ifEmpty { "bez súťaže" }}"
        var place = 0
        val body = rows.joinToString("\n") { r ->
            val valid = r.ranked
            if (valid) place++
            val p = if (valid) "$place." else "–"
            val res = if (valid) Times.format(r.info.result) else Verdicts.short(prefs, r.verdict) +
                if (r.info.result != null && r.verdict != Verdicts.NA) " (${Times.format(r.info.result)})" else ""
            val times = r.info.finals.joinToString(" / ")
            "$p ${r.info.team.ifEmpty { "?" }}${if (r.info.attemptNo > 0) " (${r.info.attemptNo}.)" else ""}  $res" +
                if (times.isNotEmpty()) "   [$times]" else ""
        } + "\n\n" + Verdicts.ALL.joinToString(", ") { Verdicts.full(prefs, it) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n\n$body")
        }
        startActivity(Intent.createChooser(send, "Zdieľať výsledky"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
