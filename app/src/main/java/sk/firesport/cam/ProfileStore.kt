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

    fun save(ctx: Context, p: SharedPreferences, name: String): Boolean {
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
}
