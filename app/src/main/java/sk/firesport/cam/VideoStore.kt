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

/** Videá tejto aplikácie sa ukladajú do jej vlastného priečinka. */
object VideoStore {

    fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "videos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun newFile(ctx: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var f = File(dir(ctx), "FS_$stamp.mp4")
        var i = 1
        while (f.exists()) {
            f = File(dir(ctx), "FS_${stamp}_$i.mp4")
            i++
        }
        return f
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

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
