package sk.firesport.cam

import android.content.Context
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Nastavenia"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val screen = preferenceScreen

        numberInput("udp_port")
        numberInput("udp_timeout")
        numberInput("udp_max_len")

        findPreference<Preference>("reset_camera")?.setOnPreferenceClickListener {
            val ed = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            Keys.CAMERA_STATE.forEach { ed.remove(it) }
            ed.apply()
            android.widget.Toast.makeText(requireContext(), "Nastavenia kamery resetované", android.widget.Toast.LENGTH_SHORT).show()
            true
        }

        for (n in 1..2) addOverlayCategory(screen, n)
        addInfoCategory(screen)
    }

    private fun numberInput(key: String) {
        findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.setSelection(it.text.length)
        }
    }

    private fun addOverlayCategory(screen: PreferenceScreen, n: Int) {
        val ctx = preferenceManager.context
        val d = Defaults.overlay(n)
        val cat = PreferenceCategory(ctx).apply {
            title = "Overlay $n"
            isIconSpaceReserved = false
        }
        screen.addPreference(cat)

        fun add(p: Preference) {
            p.isIconSpaceReserved = false
            cat.addPreference(p)
        }
        fun k(s: String) = Keys.ov(n, s)

        add(SwitchPreferenceCompat(ctx).apply {
            key = k("enabled"); title = "Zobraziť overlay $n"
            summary = "Text sa kreslí do náhľadu aj do nahraného videa. Pozíciu zmeníš aj potiahnutím prstom v kamere."
            setDefaultValue(d.enabled)
        })
        add(textPref(ctx, k("prefix"), "Prefix (text pred UDP hodnotou)", d.prefix))
        add(textPref(ctx, k("suffix"), "Sufix (text za UDP hodnotou)", d.suffix))
        add(textPref(ctx, k("default"), "Predvolený text (kým nepríde UDP)", d.defaultText))
        add(seekPref(ctx, k("text_size"), "Veľkosť textu (% výšky videa)", 1, 30, d.textSize))
        add(ColorPreference(ctx).apply {
            key = k("text_color"); title = "Farba textu"; setDefaultValue(d.textColor)
        })
        add(ColorPreference(ctx).apply {
            key = k("bg_color"); title = "Farba pozadia"; setDefaultValue(d.bgColor)
        })
        add(seekPref(ctx, k("bg_opacity"), "Krytie pozadia % (0 = priehľadné, 100 = plné)", 0, 100, d.bgOpacity))
        add(seekPref(ctx, k("pos_x"), "Pozícia X % (0 = vľavo, 100 = vpravo)", 0, 100, d.posX))
        add(seekPref(ctx, k("pos_y"), "Pozícia Y % (0 = hore, 100 = dole)", 0, 100, d.posY))
        add(ListPreference(ctx).apply {
            key = k("font"); title = "Písmo"; dialogTitle = "Písmo"
            entries = arrayOf<CharSequence>("Bezpätkové", "Neproporcionálne (čísla)", "Pätkové", "Zúžené")
            entryValues = arrayOf<CharSequence>("sans", "mono", "serif", "condensed")
            setDefaultValue(d.font)
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        })
        add(SwitchPreferenceCompat(ctx).apply {
            key = k("bold"); title = "Tučné písmo"; setDefaultValue(d.bold)
        })
        add(SwitchPreferenceCompat(ctx).apply {
            key = k("outline"); title = "Obrys textu"
            summary = "Obrys vo farbe pozadia – dobre čitateľné aj bez pozadia"
            setDefaultValue(d.outline)
        })
        add(seekPref(ctx, k("padding"), "Okraj okolo textu (% veľkosti textu)", 0, 150, d.padding))
        add(seekPref(ctx, k("corner"), "Zaoblenie rohov (% veľkosti textu)", 0, 100, d.corner))
    }

    private fun textPref(ctx: Context, key: String, title: String, def: String) =
        EditTextPreference(ctx).apply {
            this.key = key
            this.title = title
            dialogTitle = title
            dialogMessage = "Tip: \\n = nový riadok"
            setDefaultValue(def)
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val t = p.text
                if (t.isNullOrEmpty()) "(prázdne)" else "„$t“"
            }
        }

    private fun seekPref(ctx: Context, key: String, title: String, min: Int, max: Int, def: Int) =
        SeekBarPreference(ctx).apply {
            this.key = key
            this.title = title
            this.max = max
            this.min = min
            showSeekBarValue = true
            updatesContinuously = true
            setDefaultValue(def)
        }

    private fun addInfoCategory(screen: PreferenceScreen) {
        val ctx = preferenceManager.context
        val cat = PreferenceCategory(ctx).apply {
            title = "Informácie"
            isIconSpaceReserved = false
        }
        screen.addPreference(cat)

        val ips = NetUtil.ipAddresses()
        cat.addPreference(Preference(ctx).apply {
            title = "IP adresa telefónu (pre UDP)"
            summary = if (ips.isEmpty()) "Nie je pripojená sieť" else ips.joinToString(", ")
            isIconSpaceReserved = false
            isSelectable = false
        })
        cat.addPreference(Preference(ctx).apply {
            title = "Priečinok s videami"
            summary = VideoStore.dir(ctx).absolutePath
            isIconSpaceReserved = false
            isSelectable = false
        })
        cat.addPreference(Preference(ctx).apply {
            title = "Obnoviť všetky predvolené nastavenia"
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Obnoviť nastavenia?")
                    .setMessage("Všetky nastavenia (aj overlayov) sa vrátia na predvolené hodnoty. Videá ostanú.")
                    .setPositiveButton("Obnoviť") { _, _ ->
                        val c = requireContext()
                        PreferenceManager.getDefaultSharedPreferences(c).edit().clear().commit()
                        PreferenceManager.setDefaultValues(c, R.xml.preferences, true)
                        requireActivity().recreate()
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
                true
            }
        })
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        cat.addPreference(Preference(ctx).apply {
            title = "Verzia"
            summary = "Firesport Cam $version"
            isIconSpaceReserved = false
            isSelectable = false
        })
    }
}
