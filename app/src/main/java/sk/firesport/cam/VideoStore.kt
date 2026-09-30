package sk.firesport.cam

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Videá tejto aplikácie sa ukladajú do jej vlastného priečinka.
 * Každá súťaž / udalosť má vlastný podpriečinok.
 */
object VideoStore {

    /** Koreňový priečinok videí. */
    fun root(ctx: Context): File {
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "videos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Dočasný priečinok pre predstih nahrávania (rovnaký disk → rýchly presun). */
    fun bufferDir(ctx: Context): File = File(root(ctx), ".buffer").apply { if (!exists()) mkdirs() }

    fun safeName(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)

    /** Priečinok aktuálnej súťaže (prázdny názov = koreň). */
    fun dir(ctx: Context, event: String = ""): File {
        val name = safeName(event)
        val d = if (name.isEmpty()) root(ctx) else File(root(ctx), name)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * Nový súbor videa. [base] = začiatok názvu (napr. "Dolany_16-84_3.pokus"),
     * prázdny = "FS". Vždy sa pridá dátum a čas.
     */
    fun newFile(ctx: Context, event: String = "", base: String = ""): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val d = dir(ctx, event)
        val prefix = safeName(base).replace(' ', '_').ifEmpty { "FS" }
        var f = File(d, "${prefix}_$stamp.mp4")
        var i = 1
        while (f.exists()) {
            f = File(d, "${prefix}_${stamp}_$i.mp4")
            i++
        }
        return f
    }

    private fun isVideo(f: File) = f.isFile && f.extension.equals("mp4", ignoreCase = true) && f.length() > 0

    /** Všetky videá (koreň + priečinky súťaží), najnovšie prvé. */
    fun list(ctx: Context): List<File> {
        val r = root(ctx)
        val out = ArrayList<File>()
        r.listFiles()?.forEach { f ->
            if (isVideo(f)) out.add(f)
            else if (f.isDirectory && !f.name.startsWith(".")) f.listFiles()?.filter { isVideo(it) }?.let { out.addAll(it) }
        }
        return out.sortedByDescending { it.lastModified() }
    }

    /** Názvy priečinkov súťaží. */
    fun events(ctx: Context): List<String> =
        root(ctx).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** Súťaž, do ktorej video patrí ("" = bez súťaže). */
    fun eventOf(ctx: Context, f: File): String {
        val parent = f.parentFile ?: return ""
        return if (parent.absolutePath == root(ctx).absolutePath) "" else parent.name
    }

    /** Presun súboru (aj medzi diskami). */
    fun move(from: File, to: File): Boolean {
        if (from.absolutePath == to.absolutePath) return true
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        return try {
            from.inputStream().use { i -> to.outputStream().use { o -> i.copyTo(o) } }
            from.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Presunie video aj s pridruženými súbormi (značky, údaje o pokuse, medzičasy). */
    fun moveVideo(from: File, to: File): Boolean {
        val side = Sidecars.of(from)
        val ok = move(from, to)
        if (ok) {
            val target = Sidecars.of(to)
            for (i in side.indices) if (side[i].exists()) move(side[i], target[i])
        }
        return ok
    }

    fun deleteVideo(f: File): Boolean {
        Sidecars.of(f).forEach { it.delete() }
        return f.delete()
    }

    /** Pokusy danej súťaže so svojimi údajmi (len videá, ktoré majú .info.json alebo značky). */
    fun attempts(ctx: Context, event: String?): List<Pair<File, AttemptInfo>> =
        list(ctx)
            .filter { event == null || eventOf(ctx, it) == event }
            .map { f -> f to (Sidecars.loadInfo(f) ?: AttemptInfo(event = eventOf(ctx, f), startEpochMs = f.lastModified())) }

    /** Vymaže zvyšky predstihu (napr. po páde aplikácie). */
    fun clearBuffer(ctx: Context) {
        bufferDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Skopíruje video do verejnej galérie (Filmy/FiresportCam). Android 10+. */
    fun copyToGallery(ctx: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FiresportCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Nedá sa zapisovať")
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            false
        }
    }
}
