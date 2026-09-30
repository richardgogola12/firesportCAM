package sk.firesport.cam

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Prehľad videí nahraných touto aplikáciou – podľa súťaží, s hromadným výberom. */
class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val M_SHARE = 1
        private const val M_COMPARE = 2
        private const val M_MOVE = 3
        private const val M_DELETE = 4
        private const val M_MORE = 5
        private const val M_CLEAR = 6
        private const val M_SELECT_ALL = 7
        private const val M_HELP = 8
        private const val M_RESULTS = 9
        private const val M_IMPORT = 10
        private const val M_VERDICT = 11
        private const val M_CLEANUP = 12

        /** null = všetky, "" = bez súťaže */
        private const val ALL = "\u0000all"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var eventBar: LinearLayout
    private val selected = LinkedHashSet<String>()
    private var filter = ALL
    private var files: List<File> = emptyList()
    private lateinit var filterBar: FilterBar
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val importLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importFiles(uris)
        }

    private val adapter = VideoAdapter(
        onClick = { onItemClick(it) },
        onLongClick = { toggleSelect(it) },
        isSelected = { selected.contains(it.absolutePath) },
        verdictOf = { f ->
            Sidecars.loadInfo(f)?.let { i -> Verdicts.of(prefs, i) to i }
        },
        shortOf = { Verdicts.short(prefs, it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        recycler = findViewById(R.id.recycler)
        emptyText = findViewById(R.id.emptyText)
        eventBar = findViewById(R.id.eventBar)
        filterBar = FilterBar(
            this, findViewById(R.id.filterBar),
            listOf("new" to "Najnovšie", "old" to "Najstaršie", "result" to "Najlepší čas", "team" to "Družstvo"),
            allowNa = false
        ) { clearSelection(); reload() }
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val span = (widthDp / 180f).toInt().coerceIn(2, 6)
        recycler.layoutManager = GridLayoutManager(this, span)
        recycler.adapter = adapter
        filter = savedInstanceState?.getString("filter") ?: ALL

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selected.isNotEmpty()) clearSelection()
                else finish()
            }
        })
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
        if (selected.isNotEmpty()) clearSelection() else finish()
        return true
    }

    // ------------------------------------------------------------------ zoznam

    private fun reload() {
        val all = VideoStore.list(this)
        val inEvent = when (filter) {
            ALL -> all
            else -> all.filter { VideoStore.eventOf(this, it) == filter }
        }
        val rows = inEvent.map { f ->
            val i = Sidecars.loadInfo(f) ?: AttemptInfo(startEpochMs = f.lastModified())
            AttemptRow(f, i, Verdicts.of(prefs, i))
        }
        filterBar.build(rows, Teams.list(prefs))
        val fl = filterBar.filter
        val shown = rows.filter { fl.matches(it) }
        files = when (fl.sort) {
            "old" -> shown.sortedBy { it.file!!.lastModified() }
            "result" -> shown.sortedWith(compareBy<AttemptRow> { if (it.verdict == Verdicts.OK && it.info.result != null) 0 else 1 }
                .thenBy { it.info.result ?: Double.MAX_VALUE })
            "team" -> shown.sortedWith(compareBy<AttemptRow> { it.info.team.lowercase(Locale.getDefault()).ifEmpty { "\uffff" } }
                .thenBy { it.info.attemptNo }.thenBy { it.info.startEpochMs })
            else -> shown
        }.map { it.file!! }
        selected.retainAll(files.map { it.absolutePath }.toSet())
        adapter.submit(files)
        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (inEvent.isNotEmpty() && files.isEmpty()) "Filtru nevyhovuje žiadne video." else "Zatiaľ žiadne videá.\nNahraj prvé video v kamere."
        buildEventBar(all)
        updateTitle()
    }

    private fun buildEventBar(all: List<File>) {
        eventBar.removeAllViews()
        val dp = resources.displayMetrics.density
        val counts = all.groupingBy { VideoStore.eventOf(this, it) }.eachCount()
        val entries = ArrayList<Pair<String, String>>()
        entries.add(ALL to "Všetko (${all.size})")
        counts[""]?.let { entries.add("" to "Bez súťaže ($it)") }
        for (e in VideoStore.events(this)) entries.add(e to "🏆 $e (${counts[e] ?: 0})")
        for ((key, label) in entries) {
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setBackgroundResource(if (key == filter) R.drawable.chip_bg_selected else R.drawable.chip_bg)
                setOnClickListener {
                    filter = key
                    clearSelection()
                    reload()
                }
                if (key != ALL && key.isNotEmpty()) setOnLongClickListener {
                    confirmDeleteEvent(key)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * dp).toInt()
            eventBar.addView(tv, lp)
        }
    }

    private fun updateTitle() {
        if (selected.isNotEmpty()) {
            title = "Vybrané: ${selected.size}"
            supportActionBar?.subtitle = null
        } else {
            title = if (filter == ALL) "Moje videá" else if (filter.isEmpty()) "Bez súťaže" else filter
            val total = files.sumOf { it.length() }
            supportActionBar?.subtitle = "${files.size} videí • ${Formatter.formatShortFileSize(this, total)}"
        }
        invalidateOptionsMenu()
    }

    private fun onItemClick(f: File) {
        if (selected.isNotEmpty()) toggleSelect(f) else openPlayer(f)
    }

    private fun toggleSelect(f: File) {
        val p = f.absolutePath
        if (!selected.remove(p)) selected.add(p)
        adapter.refresh()
        updateTitle()
    }

    private fun clearSelection() {
        selected.clear()
        adapter.refresh()
        updateTitle()
    }

    private fun selectedFiles() = files.filter { selected.contains(it.absolutePath) }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (selected.isNotEmpty()) {
            menu.add(0, M_SHARE, 1, "Zdieľať").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (selected.size == 2) menu.add(0, M_COMPARE, 2, "Porovnať").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, M_DELETE, 3, "Vymazať").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, M_VERDICT, 4, "Verdikt…")
            menu.add(0, M_MOVE, 4, "Presunúť do súťaže…")
            if (selected.size == 1) menu.add(0, M_MORE, 5, "Ďalšie možnosti…")
            menu.add(0, M_SELECT_ALL, 6, "Vybrať všetko")
            menu.add(0, M_CLEAR, 7, "Zrušiť výber")
        } else {
            menu.add(0, M_RESULTS, 1, "Výsledky").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, M_SELECT_ALL, 2, "Vybrať viac")
            menu.add(0, M_IMPORT, 3, "Importovať videá z iného telefónu…")
            menu.add(0, M_CLEANUP, 4, "🧹 Upratovanie…")
            menu.add(0, M_HELP, 5, "Návod")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_SHARE -> share(selectedFiles())
            M_COMPARE -> selectedFiles().let { if (it.size == 2) compare(it[0], it[1]) }
            M_DELETE -> deleteFiles(selectedFiles())
            M_MOVE -> moveFiles(selectedFiles())
            M_MORE -> selectedFiles().firstOrNull()?.let { showOptions(it) }
            M_CLEAR -> clearSelection()
            M_SELECT_ALL -> {
                files.forEach { selected.add(it.absolutePath) }
                adapter.refresh()
                updateTitle()
            }
            M_HELP -> startActivity(Intent(this, HelpActivity::class.java))
            M_VERDICT -> AttemptEditor.pickVerdict(this, selectedFiles()) { clearSelection(); reload() }
            M_CLEANUP -> Cleanup.showDialog(this) { reload() }
            M_RESULTS -> startActivity(Intent(this, ResultsActivity::class.java))
            M_IMPORT -> importLauncher.launch(arrayOf("video/*", "text/plain", "application/json", "application/octet-stream"))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ akcie

    private fun openPlayer(f: File) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun compare(a: File, b: File) {
        startActivity(
            Intent(this, CompareActivity::class.java)
                .putExtra(CompareActivity.EXTRA_A, a.absolutePath)
                .putExtra(CompareActivity.EXTRA_B, b.absolutePath)
        )
    }

    private fun showOptions(f: File) {
        val others = files.filter { it != f }
        val items = arrayListOf("Prehrať", "Verdikt…", "Upraviť údaje pokusu…", "Zdieľať", "Uložiť do galérie telefónu", "Premenovať", "Značky…", "Presunúť do súťaže…", "Vymazať")
        if (others.isNotEmpty()) items.add(1, "Porovnať s iným videom…")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Prehrať" -> openPlayer(f)
                    "Verdikt…" -> AttemptEditor.pickVerdict(this, listOf(f)) { reload() }
                    "Upraviť údaje pokusu…" -> AttemptEditor.show(this, f) { reload() }
                    "Porovnať s iným videom…" -> pickOther(f, others)
                    "Zdieľať" -> share(listOf(f))
                    "Uložiť do galérie telefónu" -> copyToGallery(f)
                    "Premenovať" -> rename(f)
                    "Značky…" -> showMarkers(f)
                    "Presunúť do súťaže…" -> moveFiles(listOf(f))
                    "Vymazať" -> deleteFiles(listOf(f))
                }
            }
            .show()
    }

    private fun pickOther(f: File, others: List<File>) {
        AlertDialog.Builder(this)
            .setTitle("Porovnať s…")
            .setItems(others.map { it.name }.toTypedArray()) { _, w -> compare(f, others[w]) }
            .show()
    }

    private fun share(list: List<File>) {
        if (list.isEmpty()) return
        try {
            val uris = ArrayList<Uri>(list.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "video/mp4"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Zdieľať videá"))
        } catch (e: Exception) {
            toast("Zdieľanie zlyhalo: ${e.message}")
        }
    }

    private fun copyToGallery(f: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("Kopírovanie do galérie je podporované od Androidu 10. Použi Zdieľať.")
            return
        }
        toast("Kopírujem…")
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = VideoStore.copyToGallery(applicationContext, f)
            main.post { toast(if (ok) "Uložené do Filmy/FiresportCam" else "Kopírovanie zlyhalo") }
        }.start()
    }

    private fun textInput(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun rename(f: File) {
        textInput("Premenovať", f.nameWithoutExtension) { raw ->
            val name = VideoStore.safeName(raw)
            if (name.isEmpty()) return@textInput
            val target = File(f.parentFile, "$name.mp4")
            when {
                target.exists() -> toast("Súbor s týmto názvom už existuje")
                VideoStore.moveVideo(f, target) -> reload()
                else -> toast("Premenovanie zlyhalo")
            }
        }
    }

    private fun showMarkers(f: File) {
        val markers = Markers.load(f)
        if (markers.isEmpty()) {
            toast("Video nemá žiadne značky")
            return
        }
        val text = markers.joinToString("\n") { "${Markers.format(it.ms)}   ${it.text}" }
        AlertDialog.Builder(this)
            .setTitle("Značky – ${f.name}")
            .setMessage(text)
            .setPositiveButton("Zavrieť", null)
            .setNeutralButton("Zdieľať text") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Značky – ${f.name}")
                    putExtra(Intent.EXTRA_TEXT, "${f.name}\n$text")
                }
                startActivity(Intent.createChooser(send, "Zdieľať značky"))
            }
            .show()
    }

    private fun moveFiles(list: List<File>) {
        if (list.isEmpty()) return
        val events = VideoStore.events(this)
        val labels = ArrayList<String>()
        labels.add("➕ Nová súťaž…")
        labels.add("Bez súťaže")
        labels.addAll(events.map { "🏆 $it" })
        AlertDialog.Builder(this)
            .setTitle("Presunúť ${list.size} videí do…")
            .setItems(labels.toTypedArray()) { _, w ->
                when (w) {
                    0 -> textInput("Názov súťaže", "") { name -> if (name.isNotEmpty()) doMove(list, name) }
                    1 -> doMove(list, "")
                    else -> doMove(list, events[w - 2])
                }
            }
            .show()
    }

    private fun doMove(list: List<File>, event: String) {
        val dir = VideoStore.dir(this, event)
        var ok = 0
        for (f in list) {
            var target = File(dir, f.name)
            var i = 1
            while (target.exists() && target.absolutePath != f.absolutePath) {
                target = File(dir, "${f.nameWithoutExtension}_$i.mp4")
                i++
            }
            if (VideoStore.moveVideo(f, target)) ok++
        }
        toast("Presunuté: $ok")
        clearSelection()
        reload()
    }

    private fun deleteFiles(list: List<File>) {
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (list.size == 1) "Vymazať video?" else "Vymazať ${list.size} videí?")
            .setMessage(list.joinToString("\n") { it.name }.take(600))
            .setPositiveButton("Vymazať") { _, _ ->
                var n = 0
                for (f in list) if (VideoStore.deleteVideo(f)) n++
                toast("Vymazané: $n")
                clearSelection()
                reload()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun confirmDeleteEvent(event: String) {
        val dir = VideoStore.dir(this, event)
        val count = dir.listFiles()?.count { it.extension.equals("mp4", true) } ?: 0
        if (count > 0) {
            toast("Súťaž „$event“ obsahuje $count videí – najprv ich presuň alebo vymaž.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Odstrániť prázdnu súťaž „$event“?")
            .setPositiveButton("Odstrániť") { _, _ ->
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
                if (filter == event) filter = ALL
                val p = PreferenceManager.getDefaultSharedPreferences(this)
                if (p.getString("event_name", "") == event) p.edit().putString("event_name", "").apply()
                reload()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    /**
     * Import videí (a ich súborov .markers.txt / .info.json / .splits.txt) z iného telefónu
     * do aktuálne zobrazenej súťaže.
     */
    private fun importFiles(uris: List<Uri>) {
        val event = if (filter == ALL) {
            PreferenceManager.getDefaultSharedPreferences(this).getString("event_name", "") ?: ""
        } else filter
        val dir = VideoStore.dir(this, event)
        val main = Handler(Looper.getMainLooper())
        toast("Importujem ${uris.size} súborov…")
        val appCtx = applicationContext
        Thread {
            var ok = 0
            for (u in uris) {
                try {
                    var name = "import_${System.currentTimeMillis()}.mp4"
                    appCtx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { name = it }
                    }
                    name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val lower = name.lowercase(Locale.ROOT)
                    val allowed = lower.endsWith(".mp4") || Sidecars.SUFFIXES.any { lower.endsWith(it) }
                    if (!allowed) continue
                    var target = File(dir, name)
                    if (target.exists() && lower.endsWith(".mp4")) {
                        target = File(dir, name.removeSuffix(".mp4").removeSuffix(".MP4") + "_import.mp4")
                    }
                    appCtx.contentResolver.openInputStream(u)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    ok++
                } catch (_: Exception) {
                }
            }
            main.post {
                toast("Importované: $ok")
                reload()
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

class VideoAdapter(
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit,
    private val isSelected: (File) -> Boolean,
    private val verdictOf: (File) -> Pair<String, AttemptInfo>? = { null },
    private val shortOf: (String) -> String = { it }
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private var items: List<File> = emptyList()
    private val dateFmt = SimpleDateFormat("d.M.yyyy HH:mm", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val markers: TextView = v.findViewById(R.id.markers)
        val check: TextView = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
        val verdict: TextView = v.findViewById(R.id.verdict)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        val ctx = holder.itemView.context
        val vi = verdictOf(f)
        val ai = vi?.second
        if (ai != null && ai.team.isNotEmpty()) {
            holder.name.text = ai.team + (if (ai.attemptNo > 0) " • ${ai.attemptNo}. pokus" else "") +
                (ai.result?.let { " • ${Times.format(it)}" } ?: "")
        } else holder.name.text = f.name
        holder.info.text = "${dateFmt.format(Date(f.lastModified()))} • ${Formatter.formatShortFileSize(ctx, f.length())}"
        if (vi != null) {
            holder.verdict.visibility = View.VISIBLE
            holder.verdict.text = "${Verdicts.emoji(vi.first)} ${shortOf(vi.first)}"
            holder.verdict.setTextColor(Verdicts.color(vi.first))
        } else holder.verdict.visibility = View.GONE
        val mc = Markers.load(f).size
        holder.markers.text = if (mc > 0) "📍$mc" else ""
        holder.markers.visibility = if (mc > 0) View.VISIBLE else View.GONE
        val sel = isSelected(f)
        holder.check.visibility = if (sel) View.VISIBLE else View.GONE
        holder.itemView.alpha = 1f
        holder.thumb.alpha = if (sel) 0.6f else 1f
        ThumbLoader.load(f, holder.thumb, holder.duration)
        holder.itemView.setOnClickListener { onClick(f) }
        holder.itemView.setOnLongClickListener {
            onLongClick(f)
            true
        }
    }
}

/** Načítanie náhľadov a dĺžky videí na pozadí. */
object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val durations = ConcurrentHashMap<String, Long>()
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun formatMs(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun load(file: File, target: ImageView, durView: TextView) {
        val key = file.absolutePath + ":" + file.lastModified()
        target.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            target.setImageBitmap(cached)
            durView.text = formatMs(durations[key])
            return
        }
        target.setImageDrawable(null)
        durView.text = ""
        exec.execute {
            var bmp: Bitmap? = null
            var dur = 0L
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frame = mmr.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.frameAtTime
                if (frame != null && frame.width > 0) {
                    val w = 320
                    val h = (frame.height * w / frame.width.toFloat()).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
                    if (scaled !== frame) frame.recycle()
                    bmp = scaled
                }
            } catch (_: Exception) {
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
            durations[key] = dur
            val result = bmp
            if (result != null) cache.put(key, result)
            main.post {
                if (target.tag == key) {
                    target.setImageBitmap(result)
                    durView.text = formatMs(dur)
                }
            }
        }
    }
}
