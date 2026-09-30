package sk.firesport.cam

import android.os.SystemClock
import java.io.File
import java.util.Locale

/** Čítanie a zápis značiek k videu (súbor vedľa videa: *.markers.txt). */
object Markers {

    fun fileFor(video: File) = File(video.parentFile, video.nameWithoutExtension + ".markers.txt")

    fun format(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = (t % 3_600_000) / 60_000
        val s = (t % 60_000) / 1000
        val milli = t % 1000
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%03d", h, m, s, milli)
        else String.format(Locale.ROOT, "%02d:%02d.%03d", m, s, milli)
    }

    private fun parse(time: String): Long? {
        val parts = time.trim().split(":")
        return try {
            when (parts.size) {
                2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
                3 -> parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + (parts[2].toDouble() * 1000).toLong()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun load(video: File): List<Marker> {
        val f = fileFor(video)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    val time = if (tab >= 0) line.substring(0, tab) else line
                    val text = if (tab >= 0) line.substring(tab + 1) else ""
                    parse(time)?.let { Marker(it, text) }
                }
                .sortedBy { it.ms }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(video: File, markers: List<Marker>) {
        val f = fileFor(video)
        if (markers.isEmpty()) {
            f.delete()
            return
        }
        val sb = StringBuilder()
        sb.append("# Firesport Cam – značky k videu ").append(video.name).append('\n')
        sb.append("# čas vo videu<TAB>text\n")
        for (m in markers.sortedBy { it.ms }) {
            sb.append(format(m.ms)).append('\t').append(m.text.replace('\n', ' ')).append('\n')
        }
        try {
            f.writeText(sb.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /** Presunie/premenuje súbor značiek spolu s videom. */
    fun moveWith(from: File, to: File) {
        val src = fileFor(from)
        if (src.exists()) src.renameTo(fileFor(to))
    }

    fun deleteFor(video: File) {
        fileFor(video).delete()
    }
}

/**
 * Zbiera značky počas nahrávania z UDP textov.
 * Režim "stable" = značka, keď sa čas zastaví (hodnota sa nemení [stableMs]).
 */
class MarkerCollector {
    private class Track {
        var text = ""
        var changedVideoMs = 0L
        var changedAt = 0L
        var pending = false
    }

    private val tracks = Array(TEXT_OVERLAYS) { Track() }
    val markers = ArrayList<Marker>()

    /** Posledný finálny (ustálený) text každého overlayu v tomto pokuse. */
    val finals = arrayOfNulls<String>(TEXT_OVERLAYS)

    var mode = "stable"
    var stableMs = 1000L
    var ignoreZero = true

    /** Volané pri ustálení času (finálny čas). */
    var onFinal: ((Marker) -> Unit)? = null

    fun reset(currentTexts: List<String?>) {
        markers.clear()
        finals.fill(null)
        for (i in tracks.indices) {
            tracks[i].text = currentTexts.getOrNull(i)?.trim() ?: ""
            tracks[i].pending = false
        }
    }

    private fun isZero(t: String) = t.none { it in '1'..'9' }

    fun onText(i: Int, text: String, videoMs: Long) {
        if (mode == "off" || i !in tracks.indices) return
        val t = text.trim()
        val tr = tracks[i]
        if (t == tr.text) return
        tr.text = t
        tr.changedVideoMs = videoMs
        tr.changedAt = SystemClock.elapsedRealtime()
        tr.pending = t.isNotEmpty() && !(ignoreZero && isZero(t))
        if (mode == "every" && tr.pending) {
            add(Marker(videoMs, t))
            finals[i] = t
            tr.pending = false
        }
    }

    /** Volať pravidelne – vyhodnotí ustálené hodnoty. */
    fun tick() {
        if (mode != "stable") return
        val now = SystemClock.elapsedRealtime()
        for ((i, tr) in tracks.withIndex()) {
            if (tr.pending && now - tr.changedAt >= stableMs) {
                tr.pending = false
                finals[i] = tr.text
                val m = Marker(tr.changedVideoMs, tr.text)
                if (add(m)) onFinal?.invoke(m)
            }
        }
    }

    /** Pridá značku, ak nejde o duplikát. @return true ak bola pridaná */
    fun add(m: Marker): Boolean {
        val dup = markers.any { it.text == m.text && kotlin.math.abs(it.ms - m.ms) < 1500 }
        if (dup) return false
        markers.add(m)
        return true
    }
}
