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
    private lateinit var table: TableLayout
    private lateinit var emptyText: TextView
    private var filter = ALL
    private var bestOnly = false
    private var rows: List<Row> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private data class Row(val file: File, val info: AttemptInfo)

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
        var list = all
            .filter { filter == ALL || VideoStore.eventOf(this, it.first) == filter }
            .map { Row(it.first, it.second) }
        if (bestOnly) {
            list = list.groupBy { it.info.team.ifEmpty { it.file.name } }
                .values.map { g ->
                    g.filter { it.info.note.isEmpty() && it.info.result != null }
                        .minByOrNull { it.info.result ?: Double.MAX_VALUE } ?: g.first()
                }
        }
        rows = list.sortedWith(
            compareBy<Row> { if (it.info.note.isNotEmpty() || it.info.result == null) 1 else 0 }
                .thenBy { it.info.result ?: Double.MAX_VALUE }
                .thenBy { it.info.startEpochMs }
        )
        buildTable()
        supportActionBar?.subtitle = when (filter) {
            ALL -> "Všetky súťaže"
            "" -> "Bez súťaže"
            else -> filter
        } + " • ${rows.size} pokusov"
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
        val head = TableRow(this)
        head.addView(cell("#", true, 0xFFFFAB91.toInt()))
        head.addView(cell("Družstvo", true, 0xFFFFAB91.toInt()))
        head.addView(cell("Pokus", true, 0xFFFFAB91.toInt()))
        for (i in 0 until nf) head.addView(cell(if (nf == 2) (if (i == 0) "Ľavý" else "Pravý") else "Čas ${i + 1}", true, 0xFFFFAB91.toInt()))
        head.addView(cell("Výsledok", true, 0xFFFFAB91.toInt()))
        head.addView(cell("Čas", true, 0xFFFFAB91.toInt()))
        head.addView(cell("Kamera", true, 0xFFFFAB91.toInt()))
        table.addView(head)

        var place = 0
        for ((idx, r) in rows.withIndex()) {
            val valid = r.info.note.isEmpty() && r.info.result != null
            if (valid) place++
            val tr = TableRow(this).apply {
                setBackgroundColor(if (idx % 2 == 0) 0x14FFFFFF else 0x00000000)
                setOnClickListener { play(r.file) }
                setOnLongClickListener {
                    edit(r)
                    true
                }
            }
            tr.addView(cell(if (valid) "$place." else "", bold = true))
            tr.addView(cell(r.info.team.ifEmpty { "(bez družstva)" }, bold = true))
            tr.addView(cell(if (r.info.attemptNo > 0) "${r.info.attemptNo}." else ""))
            for (i in 0 until nf) tr.addView(cell(r.info.finals.getOrNull(i) ?: "", alignEnd = true))
            val res = when {
                r.info.note.isNotEmpty() -> r.info.note
                else -> Times.format(r.info.result) + if (r.info.manualResult != null) " ✎" else ""
            }
            tr.addView(cell(res, bold = true, color = if (valid) 0xFFFFEB3B.toInt() else 0xFFEF9A9A.toInt(), alignEnd = true))
            tr.addView(cell(if (r.info.startEpochMs > 0) timeFmt.format(Date(r.info.startEpochMs)) else "", color = 0xFFB0B0B0.toInt()))
            tr.addView(cell(r.info.camera, color = 0xFFB0B0B0.toInt()))
            table.addView(tr)
        }
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

    /** Ručná oprava: družstvo, výsledný čas, poznámka (NP = neplatný pokus). */
    private fun edit(r: Row) {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun field(hint: String, value: String, type: Int): EditText {
            box.addView(TextView(this).apply { text = hint; alpha = 0.7f })
            return EditText(this).apply {
                setText(value)
                inputType = type
                box.addView(this)
            }
        }
        val team = field("Družstvo", r.info.team, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val result = field(
            "Výsledný čas (prázdne = z časomiery)",
            r.info.manualResult?.let { Times.format(it) } ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val note = field("Poznámka (napr. NP = neplatný pokus)", r.info.note, InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this)
            .setTitle(r.file.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Uložiť") { _, _ ->
                val newInfo = r.info.copy(
                    team = team.text.toString().trim(),
                    manualResult = result.text.toString().replace(',', '.').trim().toDoubleOrNull(),
                    note = note.text.toString().trim()
                )
                Sidecars.saveInfo(r.file, newInfo)
                if (newInfo.team.isNotEmpty()) Teams.add(PreferenceManager.getDefaultSharedPreferences(this), newInfo.team)
                reload()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // ------------------------------------------------------------------ export

    private fun resultLines(): List<List<String>> {
        val nf = maxFinals()
        val out = ArrayList<List<String>>()
        val head = arrayListOf("Poradie", "Družstvo", "Pokus")
        for (i in 0 until nf) head.add(if (nf == 2) (if (i == 0) "Ľavý" else "Pravý") else "Čas ${i + 1}")
        head.addAll(listOf("Výsledok", "Poznámka", "Čas nahrávania", "Kamera", "Video"))
        out.add(head)
        var place = 0
        val dt = SimpleDateFormat("d.M.yyyy HH:mm:ss", Locale.getDefault())
        for (r in rows) {
            val valid = r.info.note.isEmpty() && r.info.result != null
            if (valid) place++
            val line = arrayListOf(if (valid) place.toString() else "", r.info.team, if (r.info.attemptNo > 0) r.info.attemptNo.toString() else "")
            for (i in 0 until nf) line.add(r.info.finals.getOrNull(i) ?: "")
            line.add(if (r.info.result != null) Times.format(r.info.result).replace('.', ',') else "")
            line.add(r.info.note)
            line.add(if (r.info.startEpochMs > 0) dt.format(Date(r.info.startEpochMs)) else "")
            line.add(r.info.camera)
            line.add(r.file.name)
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
            val valid = r.info.note.isEmpty() && r.info.result != null
            if (valid) place++
            val p = if (valid) "$place." else "–"
            val res = if (r.info.note.isNotEmpty()) r.info.note else Times.format(r.info.result)
            val times = r.info.finals.joinToString(" / ")
            "$p ${r.info.team.ifEmpty { "?" }}${if (r.info.attemptNo > 0) " (${r.info.attemptNo}.)" else ""}  $res" +
                if (times.isNotEmpty()) "   [$times]" else ""
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n\n$body")
        }
        startActivity(Intent.createChooser(send, "Zdieľať výsledky"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
