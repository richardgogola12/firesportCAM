package sk.firesport.cam

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Prehľad videí nahraných touto aplikáciou. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = VideoAdapter(onClick = { openPlayer(it) }, onLongClick = { showOptions(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Moje videá"
        recycler = findViewById(R.id.recycler)
        emptyText = findViewById(R.id.emptyText)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val span = (widthDp / 180f).toInt().coerceIn(2, 6)
        recycler.layoutManager = GridLayoutManager(this, span)
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        val files = VideoStore.list(this)
        adapter.submit(files)
        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val total = files.sumOf { it.length() }
        supportActionBar?.subtitle = "${files.size} videí • ${Formatter.formatShortFileSize(this, total)}"
    }

    private fun openPlayer(f: File) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun showOptions(f: File) {
        val items = arrayOf("Prehrať", "Zdieľať", "Uložiť do galérie telefónu", "Premenovať", "Vymazať")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openPlayer(f)
                    1 -> share(f)
                    2 -> copyToGallery(f)
                    3 -> rename(f)
                    4 -> delete(f)
                }
            }
            .show()
    }

    private fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Zdieľať video"))
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

    private fun rename(f: File) {
        val input = EditText(this).apply {
            setText(f.nameWithoutExtension)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Premenovať")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isEmpty()) return@setPositiveButton
                val target = File(f.parentFile, "$name.mp4")
                when {
                    target.exists() -> toast("Súbor s týmto názvom už existuje")
                    f.renameTo(target) -> reload()
                    else -> toast("Premenovanie zlyhalo")
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun delete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Vymazať video?")
            .setMessage(f.name)
            .setPositiveButton("Vymazať") { _, _ ->
                if (f.delete()) reload() else toast("Vymazanie zlyhalo")
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

class VideoAdapter(
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private var items: List<File> = emptyList()
    private val dateFmt = SimpleDateFormat("d.M.yyyy HH:mm", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        val ctx = holder.itemView.context
        holder.name.text = f.name
        holder.info.text = "${dateFmt.format(Date(f.lastModified()))} • ${Formatter.formatShortFileSize(ctx, f.length())}"
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
