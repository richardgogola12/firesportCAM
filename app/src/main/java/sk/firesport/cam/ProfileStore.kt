package sk.firesport.cam

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/** Uložené sady nastavení (profily), napr. „Súťaž večer“, „Tréning“. */
object ProfileStore {

    /** Kľúče, ktoré sa do profilu neukladajú. */
    private val SKIP = setOf("migrated_v2", "migrated_live", "profile_name", "_has_set_default_values")

    private fun dir(ctx: Context) = File(ctx.filesDir, "profiles").apply { mkdirs() }

    private fun fileFor(ctx: Context, name: String) = File(dir(ctx), VideoStore.safeName(name) + ".json")

    fun list(ctx: Context): List<String> =
        dir(ctx).listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    /** Nastavenia ako JSON {kľúč: {t, v}}. */
    fun prefsJson(p: SharedPreferences): JSONObject {
        val json = JSONObject()
        for ((k, v) in p.all) {
            if (k in SKIP || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                else -> continue
            }
            json.put(k, o)
        }
        return json
    }

    fun save(ctx: Context, p: SharedPreferences, name: String): Boolean {
        val json = prefsJson(p)
        return try {
            fileFor(ctx, name).writeText(json.toString(2), Charsets.UTF_8)
            p.edit().putString("profile_name", name).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(ctx: Context, p: SharedPreferences, name: String): Boolean {
        return try {
            val json = JSONObject(fileFor(ctx, name).readText(Charsets.UTF_8))
            val ed = p.edit()
            for (k in p.all.keys) if (k !in SKIP) ed.remove(k)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = json.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> ed.putBoolean(k, o.getBoolean("v"))
                    "i" -> ed.putInt(k, o.getInt("v"))
                    "l" -> ed.putLong(k, o.getLong("v"))
                    "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> ed.putString(k, o.getString("v"))
                }
            }
            ed.putString("profile_name", name)
            ed.commit()
            Prefs.initDefaults(ctx)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun delete(ctx: Context, name: String) = fileFor(ctx, name).delete()

    // ------------------------------------------------------------------ export / import

    class Imported(val name: String, val prefs: JSONObject, val logo: ByteArray?) {
        val count get() = prefs.length()
    }

    /** Súbor na export: hlavička + nastavenia + logo (base64). [name] null = aktuálne nastavenia. */
    fun exportJson(ctx: Context, p: SharedPreferences, name: String?): JSONObject {
        val prefsObj = if (name == null) prefsJson(p) else JSONObject(fileFor(ctx, name).readText(Charsets.UTF_8))
        val out = JSONObject()
        out.put("format", "firesportcam-profile")
        out.put("version", 1)
        out.put("name", name ?: (p.getString("profile_name", "") ?: "").ifEmpty { "Nastavenia" })
        out.put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        try {
            out.put("app", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "")
        } catch (_: Exception) {
        }
        out.put("prefs", prefsObj)
        val logoPath = prefsObj.optJSONObject("logo_path")?.optString("v") ?: ""
        val logo = File(logoPath)
        if (logoPath.isNotEmpty() && logo.isFile && logo.length() < 3_000_000) {
            out.put("logo_png", android.util.Base64.encodeToString(logo.readBytes(), android.util.Base64.NO_WRAP))
        }
        return out
    }

    /** Prečíta exportovaný súbor (aj starý formát = len nastavenia). */
    fun parseImport(text: String): Imported {
        val root = JSONObject(text)
        val prefsObj = if (root.has("prefs")) root.getJSONObject("prefs") else root
        // kontrola formátu: každá položka {t, v}
        val keys = prefsObj.keys()
        var n = 0
        while (keys.hasNext()) {
            val k = keys.next()
            val o = prefsObj.optJSONObject(k) ?: throw IllegalArgumentException("nie je to profil Firesport Cam")
            if (!o.has("t") || !o.has("v")) throw IllegalArgumentException("nie je to profil Firesport Cam")
            n++
        }
        if (n == 0) throw IllegalArgumentException("profil je prázdny")
        val logo = root.optString("logo_png", "").takeIf { it.isNotEmpty() }?.let {
            try {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }
        return Imported(root.optString("name", ""), prefsObj, logo)
    }

    /** Uloží importovaný profil; logo dostane vlastný súbor a cestu v tomto telefóne. */
    fun saveImported(ctx: Context, name: String, imp: Imported): Boolean = try {
        val prefsObj = JSONObject(imp.prefs.toString())
        for (k in SKIP) prefsObj.remove(k)
        if (imp.logo != null) {
            val dir = File(ctx.filesDir, "logos").apply { mkdirs() }
            val f = File(dir, VideoStore.safeName(name).replace(' ', '_') + ".png")
            f.writeBytes(imp.logo)
            prefsObj.put("logo_path", JSONObject().put("t", "s").put("v", f.absolutePath))
            prefsObj.put("logo_ver", JSONObject().put("t", "l").put("v", System.currentTimeMillis()))
        } else if (prefsObj.has("logo_path")) {
            // cesta z iného telefónu tu neplatí
            val path = prefsObj.getJSONObject("logo_path").optString("v")
            if (path.isNotEmpty() && !File(path).isFile) prefsObj.put("logo_path", JSONObject().put("t", "s").put("v", ""))
        }
        fileFor(ctx, name).writeText(prefsObj.toString(2), Charsets.UTF_8)
        true
    } catch (_: Exception) {
        false
    }
}
