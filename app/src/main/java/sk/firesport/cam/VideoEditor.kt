package sk.firesport.cam

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Strihanie a spájanie videí (Media3 Transformer).
 * Musí sa volať z hlavného vlákna.
 */
@OptIn(UnstableApi::class)
object VideoEditor {

    class Clip(val file: File, val startMs: Long = 0L, val endMs: Long = Long.MIN_VALUE)

    private fun edited(c: Clip): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(c.startMs.coerceAtLeast(0L))
        if (c.endMs != Long.MIN_VALUE) clipping.setEndPositionMs(c.endMs)
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(c.file))
            .setClippingConfiguration(clipping.build())
            .build()
        return EditedMediaItem.Builder(item).build()
    }

    /**
     * Spojí úseky za sebou do [out].
     * @param onProgress percento 0..100 (volané priebežne)
     */
    fun export(
        ctx: Context,
        clips: List<Clip>,
        out: File,
        onProgress: ((Int) -> Unit)? = null,
        onDone: (Boolean, String?) -> Unit
    ): Transformer {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val progress = ProgressHolder()
        lateinit var poll: Runnable
        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(poll)
                    onDone(true, null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacks(poll)
                    out.delete()
                    onDone(false, exportException.message)
                }
            })
            .build()
        poll = Runnable {
            if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress?.invoke(progress.progress)
            }
            handler.postDelayed(poll, 500)
        }
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        val items = clips.map { edited(it) }
        if (items.size == 1) {
            transformer.start(items[0], out.absolutePath)
        } else {
            val seq = EditedMediaItemSequence.Builder(items).build()
            transformer.start(Composition.Builder(seq).build(), out.absolutePath)
        }
        handler.postDelayed(poll, 500)
        return transformer
    }
}
