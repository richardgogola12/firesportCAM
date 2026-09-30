package sk.firesport.cam

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Upratovanie aplikácie bez odinštalovania: dočasné súbory, staré alebo neplatné videá,
 * prázdne súťaže, družstvá, nastavenia, prípadne úplné vyčistenie.
 */
object Cleanup {

    private val main = Handler(Looper.getMainLooper())

    private fun size(f: File?): Long = when {
        f == null || !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { size(it) } ?: 0L
    }

    private fun wipeDir(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        var n = 0L
        f.listFiles()?.forEach { c ->
            n += if (c.isDirectory) wipeDir(c).also { c.delete() } else c.length().also { c.delete() }
        }
        return n
    }

    /** Veľkosť dočasných súborov (cache, exporty, zvyšky predstihu). */
    fun tempBytes(ctx: Context) = size(ctx.cacheDir) + size(ctx.externalCacheDir) + size(VideoStore.bufferDir(ctx))

    /** Vymaže dočasné súbory; vráti uvoľnené bajty. */
    fun clearTemp(ctx: Context): Long =
        wipeDir(ctx.cacheDir) + wipeDir(ctx.externalCacheDir) + wipeDir(VideoStore.bufferDir(ctx))

    /** Súbory .markers/.info/.splits bez videa a prázdne priečinky súťaží. */
    fun clearOrphans(ctx: Context): Int {
        var n = 0
        val root = VideoStore.root(ctx)
        val dirs = listOf(root) + (root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
        for (d in dirs) {
            d.listFiles()?.forEach { f ->
                val suffix = Sidecars.SUFFIXES.firstOrNull { f.name.endsWith(it) } ?: return@forEach
                val video = File(d, f.name.removeSuffix(suffix) + ".mp4")
                if (!video.exists() && f.delete()) n++
            }
            if (d != root && (d.listFiles()?.isEmpty() != false) && d.delete()) n++
        }
        return n
    }

    fun summary(ctx: Context): String {
        val videos = VideoStore.list(ctx)
        val vb = videos.sumOf { it.length() }
        val events = VideoStore.events(ctx).size
        return "Videá: ${videos.size} (${Formatter.formatShortFileSize(ctx, vb)}), súťaže: $events\n" +
            "Dočasné súbory: ${Formatter.formatShortFileSize(ctx, tempBytes(ctx))}\n" +
            "Profily nastavení: ${ProfileStore.list(ctx).size}"
    }

    // ------------------------------------------------------------------ UI

    fun showDialog(act: Activity, onDone: () -> Unit = {}) {
        val items = arrayOf(
            "🗑 Vymazať dočasné súbory",
            "🎬 Vymazať videá…",
            "📁 Vymazať prázdne súťaže a osirelé súbory",
            "👥 Vymazať zoznam družstiev a počítadlá pokusov",
            "🗂 Vymazať všetky profily nastavení",
            "⚙ Obnoviť predvolené nastavenia",
            "⚠ Úplne vyčistiť aplikáciu (ako nová inštalácia)"
        )
        val dp = act.resources.displayMetrics.density
        val head = android.widget.TextView(act).apply {
            text = "🧹 Upratovanie\n" + summary(act)
            textSize = 14f
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        AlertDialog.Builder(act)
            .setCustomTitle(head)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> confirm(act, "Vymazať dočasné súbory?", "Vymažú sa exporty, vyrovnávacia pamäť a zvyšky predstihu nahrávania. Videá ostanú.") {
                        background(act, { clearTemp(it) }) { b -> toast(act, "Uvoľnené ${Formatter.formatShortFileSize(act, b)}"); onDone() }
                    }
                    1 -> pickVideos(act, onDone)
                    2 -> background(act, { clearOrphans(it) }) { n -> toast(act, "Odstránené položky: $n"); onDone() }
                    3 -> confirm(act, "Vymazať družstvá?", "Vymaže sa zoznam družstiev, aktuálne družstvo a vynulujú sa počítadlá pokusov. Videá a výsledky ostanú.") {
                        val p = PreferenceManager.getDefaultSharedPreferences(act)
                        val ed = p.edit().putString("teams_list", "").putString("team_name", "")
                        p.all.keys.filter { it.startsWith("attempt_reset") }.forEach { ed.remove(it) }
                        ed.putLong("attempt_reset_at", System.currentTimeMillis()).apply()
                        OverlayState.teamName = ""
                        toast(act, "Družstvá vymazané")
                        onDone()
                    }
                    4 -> confirm(act, "Vymazať profily?", "Vymažú sa všetky uložené profily nastavení (${ProfileStore.list(act).size}).") {
                        ProfileStore.list(act).forEach { ProfileStore.delete(act, it) }
                        PreferenceManager.getDefaultSharedPreferences(act).edit().putString("profile_name", "").apply()
                        toast(act, "Profily vymazané")
                        onDone()
                    }
                    5 -> confirm(act, "Obnoviť nastavenia?", "Všetky nastavenia (aj overlayov) sa vrátia na predvolené hodnoty. Videá, družstvá a profily ostanú.", "Obnoviť") {
                        resetSettings(act)
                        toast(act, "Nastavenia obnovené")
                        act.recreate()
                    }
                    6 -> wipeAll(act)
                }
            }
            .setNegativeButton("Zavrieť", null)
            .show()
    }

    /** Obnoví predvolené nastavenia; zoznam družstiev a súťaž ponechá. */
    fun resetSettings(ctx: Context) {
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        val keep = listOf("teams_list", "team_name", "event_name", "attempt_reset_at")
        val saved = keep.associateWith { p.all[it] }
        p.edit().clear().commit()
        val ed = p.edit()
        for ((k, v) in saved) when (v) {
            is String -> ed.putString(k, v)
            is Long -> ed.putLong(k, v)
        }
        ed.commit()
        Prefs.initDefaults(ctx)
    }

    private fun pickVideos(act: Activity, onDone: () -> Unit) {
        val p = PreferenceManager.getDefaultSharedPreferences(act)
        val day = 24L * 3600 * 1000
        val now = System.currentTimeMillis()
        val events = VideoStore.events(act)
        val options = ArrayList<Pair<String, (File, AttemptInfo?) -> Boolean>>()
        options.add("Neúspešné pokusy (${Verdicts.short(p, Verdicts.NP)} a ${Verdicts.short(p, Verdicts.D)})" to { _, i ->
            i != null && Verdicts.of(p, i) != Verdicts.OK
        })
        options.add("Videá bez družstva a času (skúšobné)" to { _, i -> i == null || (i.team.isEmpty() && i.result == null) })
        options.add("Staršie ako 7 dní" to { f, _ -> now - f.lastModified() > 7 * day })
        options.add("Staršie ako 30 dní" to { f, _ -> now - f.lastModified() > 30 * day })
        options.add("Bez súťaže" to { f, _ -> VideoStore.eventOf(act, f).isEmpty() })
        for (e in events) options.add("Súťaž „$e“" to { f, _ -> VideoStore.eventOf(act, f) == e })
        options.add("VŠETKY videá" to { _, _ -> true })
        AlertDialog.Builder(act)
            .setTitle("Ktoré videá vymazať?")
            .setItems(options.map { it.first }.toTypedArray()) { _, w ->
                val (label, pred) = options[w]
                val list = VideoStore.list(act).filter { pred(it, Sidecars.loadInfo(it)) }
                if (list.isEmpty()) {
                    toast(act, "Žiadne také videá")
                    return@setItems
                }
                val bytes = list.sumOf { it.length() }
                confirm(
                    act, "Vymazať ${list.size} videí?",
                    "$label – ${Formatter.formatShortFileSize(act, bytes)}.\nVymažú sa aj ich značky, údaje a medzičasy. Nedá sa to vrátiť."
                ) {
                    background(act, { list.count { f -> VideoStore.deleteVideo(f) } }) { n ->
                        toast(act, "Vymazané videá: $n (${Formatter.formatShortFileSize(act, bytes)})")
                        onDone()
                    }
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    /** Vymaže všetko ako pri odinštalovaní – aplikácia sa zavrie. */
    private fun wipeAll(act: Activity) {
        confirm(
            act, "Úplne vyčistiť aplikáciu?",
            "Vymažú sa VŠETKY videá, výsledky, družstvá, profily a nastavenia – ako pri novej inštalácii.\n\n" + summary(act)
        ) {
            confirm(act, "Naozaj všetko vymazať?", "Toto sa nedá vrátiť. Aplikácia sa potom zavrie, spusti ju znova.") {
                wipeDir(VideoStore.root(act))
                val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                if (!am.clearApplicationUserData()) {
                    // záloha, ak systém vyčistenie odmietne
                    clearTemp(act)
                    ProfileStore.list(act).forEach { ProfileStore.delete(act, it) }
                    PreferenceManager.getDefaultSharedPreferences(act).edit().clear().commit()
                    Prefs.initDefaults(act)
                    toast(act, "Vyčistené")
                    act.finishAffinity()
                }
            }
        }
    }

    // ------------------------------------------------------------------ pomocné

    private fun confirm(act: Activity, title: String, msg: String, yes: String = "Vymazať", ok: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> ok() }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun <T> background(act: Activity, work: (Context) -> T, done: (T) -> Unit) {
        val app = act.applicationContext
        Thread {
            val r = work(app)
            main.post { if (!act.isFinishing) done(r) }
        }.start()
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}
