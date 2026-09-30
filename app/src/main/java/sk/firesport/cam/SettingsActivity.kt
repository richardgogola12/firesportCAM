package sk.firesport.cam

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.FileOutputStream

/** Nastavenia rozdelené na karty. */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** (názov karty, stránka) */
        val TABS = listOf(
            "🎬 Video" to SettingsPageFragment.PAGE_VIDEO,
            "📷 Kamera" to SettingsPageFragment.PAGE_CAMERA,
            "🏆 Súťaž" to SettingsPageFragment.PAGE_COMPETITION,
            "🅰 Overlay 1" to SettingsPageFragment.PAGE_OVERLAY1,
            "🅱 Overlay 2" to SettingsPageFragment.PAGE_OVERLAY2,
            "ℹ Overlay 3" to SettingsPageFragment.PAGE_OVERLAY3,
            "🖼 Logo" to SettingsPageFragment.PAGE_LOGO,
            "📡 UDP" to SettingsPageFragment.PAGE_UDP,
            "⚡ Automatika" to SettingsPageFragment.PAGE_AUTO,
            "🎥 Kamery a OBS" to SettingsPageFragment.PAGE_REMOTE,
            "⚙ Ostatné" to SettingsPageFragment.PAGE_OTHER
        )
        private const val M_HELP = 1
    }

    private lateinit var tabs: TabLayout
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Nastavenia"

        tabs = findViewById(R.id.tabs)
        for (t in TABS) tabs.addTab(tabs.newTab().setText(t.first))

        currentPage = savedInstanceState?.getInt("page", 0) ?: 0
        tabs.getTabAt(currentPage)?.select()
        if (savedInstanceState == null) showPage(currentPage)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showPage(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", currentPage)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_HELP, 0, "Návod").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == M_HELP) {
            startActivity(Intent(this, HelpActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showPage(page: Int) {
        currentPage = page
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsPageFragment.newInstance(TABS.getOrNull(page)?.second ?: 0))
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsPageFragment : PreferenceFragmentCompat() {

    companion object {
        const val PAGE_VIDEO = 0
        const val PAGE_CAMERA = 1
        const val PAGE_OVERLAY1 = 2
        const val PAGE_OVERLAY2 = 3
        const val PAGE_OVERLAY3 = 4
        const val PAGE_LOGO = 5
        const val PAGE_UDP = 6
        const val PAGE_AUTO = 7
        const val PAGE_REMOTE = 8
        const val PAGE_OTHER = 9
        const val PAGE_COMPETITION = 10

        fun newInstance(page: Int) = SettingsPageFragment().apply {
            arguments = bundleOf("page" to page)
        }
    }

    private val prefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(requireContext()) }

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importLogo(uri)
    }

    /** Keď sa prvok posunie v náhľade, aktualizujú sa aj posuvníky X/Y. */
    private val posListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key != null && (key.endsWith("_pos_x") || key.endsWith("_pos_y"))) {
            val p = findPreference<SeekBarPreference>(key) ?: return@OnSharedPreferenceChangeListener
            val v = sp.getInt(key, p.value)
            if (p.value != v) p.value = v
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        when (arguments?.getInt("page") ?: 0) {
            PAGE_VIDEO -> {
                setPreferencesFromResource(R.xml.prefs_video, rootKey)
                numberInput("preroll_seconds")
            }
            PAGE_CAMERA -> {
                setPreferencesFromResource(R.xml.prefs_camera, rootKey)
                findPreference<Preference>("reset_camera")?.setOnPreferenceClickListener {
                    val ed = prefs.edit()
                    Keys.CAMERA_STATE.forEach { ed.remove(it) }
                    ed.apply()
                    toast("Nastavenia kamery resetované")
                    true
                }
            }
            PAGE_COMPETITION -> {
                setPreferencesFromResource(R.xml.prefs_competition, rootKey)
                findPreference<EditTextPreference>("teams_list")?.apply {
                    setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        it.minLines = 4
                        it.isSingleLine = false
                    }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                        val n = (p.text ?: "").lines().count { it.isNotBlank() }
                        if (n == 0) "Jedno družstvo na riadok – zatiaľ prázdne" else "$n družstiev (jedno na riadok)"
                    }
                }
                findPreference<EditTextPreference>("team_name")?.summaryProvider =
                    Preference.SummaryProvider<EditTextPreference> { p -> p.text?.ifEmpty { null } ?: "(žiadne)" }
                findPreference<Preference>("attempt_reset")?.apply {
                    fun refresh() {
                        val at = preferenceManager.sharedPreferences?.getLong("attempt_reset_at", 0L) ?: 0L
                        summary = "Ďalší pokus každého družstva bude opäť 1." +
                            if (at > 0) "\nPosledné vynulovanie: " +
                                java.text.SimpleDateFormat("d.M.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
                            else ""
                    }
                    refresh()
                    setOnPreferenceClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Vynulovať počítadlo pokusov?")
                            .setMessage("Pokusy všetkých družstiev sa budú rátať znova od 1. Nahrané videá ostanú zachované.")
                            .setPositiveButton("Vynulovať") { _, _ ->
                                preferenceManager.sharedPreferences?.let { Teams.resetCounter(it, null) }
                                refresh()
                                android.widget.Toast.makeText(requireContext(), "Počítadlo pokusov vynulované", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Zrušiť", null)
                            .show()
                        true
                    }
                }
                val cat = category(preferenceScreen, "Výsledky")
                cat.add(Preference(preferenceManager.context).apply {
                    title = "📊 Otvoriť výsledkovú tabuľku"
                    summary = "Poradie družstiev z nahraných pokusov, export do Excelu"
                    setOnPreferenceClickListener {
                        startActivity(Intent(requireContext(), ResultsActivity::class.java))
                        true
                    }
                })
            }
            PAGE_OVERLAY1 -> buildOverlayPage(1)
            PAGE_OVERLAY2 -> buildOverlayPage(2)
            PAGE_OVERLAY3 -> buildOverlayPage(3)
            PAGE_LOGO -> buildLogoPage()
            PAGE_UDP -> {
                setPreferencesFromResource(R.xml.prefs_udp, rootKey)
                numberInput("udp_port")
                numberInput("udp_timeout")
                numberInput("udp_max_len")
                numberInput("udp_announce_port")
                addUdpInfo()
            }
            PAGE_AUTO -> {
                setPreferencesFromResource(R.xml.prefs_auto, rootKey)
                numberInput("auto_stop_after_final")
                numberInput("auto_stop_after")
                numberInput("marker_stable_ms")
                numberInput("replay_before_s")
            }
            PAGE_REMOTE -> {
                setPreferencesFromResource(R.xml.prefs_remote, rootKey)
                numberInput("remote_port")
                addRemoteInfo()
            }
            else -> {
                setPreferencesFromResource(R.xml.prefs_other, rootKey)
                addProfilesCategory(preferenceScreen)
                addCleanupCategory(preferenceScreen)
                addInfoCategory(preferenceScreen)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(posListener)
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(posListener)
        super.onPause()
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun numberInput(key: String) {
        findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.setSelection(it.text.length)
        }
    }

    private fun category(group: PreferenceGroup, title: String): PreferenceCategory {
        val cat = PreferenceCategory(preferenceManager.context).apply {
            this.title = title
            isIconSpaceReserved = false
        }
        group.addPreference(cat)
        return cat
    }

    private fun PreferenceGroup.add(p: Preference) {
        p.isIconSpaceReserved = false
        addPreference(p)
    }

    private fun info(title: String, summary: String) = Preference(preferenceManager.context).apply {
        this.title = title
        this.summary = summary
        isSelectable = false
    }

    // ------------------------------------------------------------------ overlay

    private fun buildOverlayPage(n: Int) {
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        val d = Defaults.overlay(n)
        fun k(s: String) = Keys.ov(n, s)

        screen.add(OverlayPreviewPreference(ctx, n - 1))

        screen.add(SwitchPreferenceCompat(ctx).apply {
            key = k("enabled"); title = "Zobraziť overlay $n"
            summary = if (n == 3) "Informačný riadok – napr. súťaž, dátum a čas" else "Kreslí sa do náhľadu aj do nahraného videa"
            setDefaultValue(d.enabled)
        })

        val text = category(screen, "Text")
        text.add(textPref(ctx, k("prefix"), "Prefix – text pred hodnotou z UDP", d.prefix))
        text.add(textPref(ctx, k("suffix"), "Sufix – text za hodnotou z UDP", d.suffix))
        text.add(textPref(ctx, k("default"), "Predvolený text (kým nepríde UDP)", d.defaultText))
        text.add(info(
            "Zástupné texty",
            "{datum}  dnešný dátum\n{cas}  aktuálny čas (s sekundami)\n{hhmm}  čas bez sekúnd\n" +
                "{sutaz}  názov súťaže\n{profil}  názov profilu\n{rec}  dĺžka nahrávania\n\\n  nový riadok"
        ))

        val look = category(screen, "Písmo")
        look.add(seekPref(ctx, k("text_size"), "Veľkosť textu (% výšky videa)", 1, 30, d.textSize))
        look.add(ColorPreference(ctx).apply {
            key = k("text_color"); title = "Farba textu"; setDefaultValue(d.textColor)
        })
        look.add(ListPreference(ctx).apply {
            key = k("font"); title = "Typ písma"; dialogTitle = "Typ písma"
            entries = arrayOf<CharSequence>("Bezpätkové", "Neproporcionálne (vhodné na čísla)", "Pätkové", "Zúžené")
            entryValues = arrayOf<CharSequence>("sans", "mono", "serif", "condensed")
            setDefaultValue(d.font)
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        })
        look.add(SwitchPreferenceCompat(ctx).apply {
            key = k("bold"); title = "Tučné písmo"; setDefaultValue(d.bold)
        })
        look.add(SwitchPreferenceCompat(ctx).apply {
            key = k("outline"); title = "Obrys textu"
            summary = "Obrys vo farbe pozadia – čitateľné aj bez pozadia"
            setDefaultValue(d.outline)
        })

        val bg = category(screen, "Pozadie")
        bg.add(ColorPreference(ctx).apply {
            key = k("bg_color"); title = "Farba pozadia"; setDefaultValue(d.bgColor)
        })
        bg.add(seekPref(ctx, k("bg_opacity"), "Krytie pozadia % (0 = priehľadné, 100 = plné)", 0, 100, d.bgOpacity))
        bg.add(seekPref(ctx, k("padding"), "Okraj okolo textu (% veľkosti textu)", 0, 150, d.padding))
        bg.add(seekPref(ctx, k("corner"), "Zaoblenie rohov (% veľkosti textu)", 0, 100, d.corner))

        val pos = category(screen, "Pozícia")
        pos.add(seekPref(ctx, k("pos_x"), "Vodorovne % (0 = vľavo, 100 = vpravo)", 0, 100, d.posX))
        pos.add(seekPref(ctx, k("pos_y"), "Zvisle % (0 = hore, 100 = dole)", 0, 100, d.posY))
    }

    private fun buildLogoPage() {
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen

        screen.add(OverlayPreviewPreference(ctx, LOGO_INDEX))
        screen.add(SwitchPreferenceCompat(ctx).apply {
            key = "logo_enabled"; title = "Zobraziť logo"
            summary = "Obrázok (napr. logo klubu) vpálený do videa"
            setDefaultValue(false)
        })
        screen.add(Preference(ctx).apply {
            title = "Vybrať obrázok…"
            summary = if (File(prefs.getString("logo_path", "") ?: "").exists()) "Obrázok je nastavený – ťukni pre zmenu"
            else "Najlepšie PNG s priehľadným pozadím"
            setOnPreferenceClickListener {
                pickLogo.launch("image/*")
                true
            }
        })
        screen.add(seekPref(ctx, "logo_size", "Veľkosť (% výšky videa)", 2, 60, 12))
        screen.add(seekPref(ctx, "logo_opacity", "Krytie %", 5, 100, 90))
        screen.add(seekPref(ctx, "logo_pos_x", "Vodorovne % (0 = vľavo, 100 = vpravo)", 0, 100, 97))
        screen.add(seekPref(ctx, "logo_pos_y", "Zvisle % (0 = hore, 100 = dole)", 0, 100, 4))
    }

    private fun importLogo(uri: Uri) {
        val ctx = requireContext()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalStateException("Obrázok sa nedá načítať")
            val max = 1024
            val scaled = if (bmp.width > max || bmp.height > max) {
                val k = max.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
            } else bmp
            val out = File(ctx.filesDir, "logo.png")
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prefs.edit()
                .putString("logo_path", out.absolutePath)
                .putBoolean("logo_enabled", true)
                .putLong("logo_ver", System.currentTimeMillis())
                .apply()
            findPreference<SwitchPreferenceCompat>("logo_enabled")?.isChecked = true
            toast("Logo nastavené")
        } catch (e: Exception) {
            toast("Logo sa nepodarilo načítať: ${e.message}")
        }
    }

    private fun textPref(ctx: Context, key: String, title: String, def: String) =
        EditTextPreference(ctx).apply {
            this.key = key
            this.title = title
            dialogTitle = title
            dialogMessage = "Tip: \\n = nový riadok, {datum} {cas} {sutaz} …"
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

    // ------------------------------------------------------------------ info

    private fun addUdpInfo() {
        val ips = NetUtil.ipAddresses()
        val port = prefs.getString("udp_port", "5000") ?: "5000"
        val cat = category(preferenceScreen, "Kam posielať")
        cat.add(info(
            "Adresa telefónu",
            if (ips.isEmpty()) "Nie je pripojená sieť (Wi-Fi / hotspot)" else ips.joinToString("\n") { "$it : $port" }
        ))
        cat.add(info(
            "Príklady správ",
            "Rovnaký text:  12.34\n" +
                "Rozdelenie:  L 12.34;P 13.01;Družstvo A\n" +
                "Podľa predpony:  1:12.34  alebo  2:13.01\n" +
                "Príkazy:  START  STOP  MARK  MARK:text  CLEAR  CLEAR:1  TEAM:názov"
        ))
    }

    private fun addRemoteInfo() {
        val ips = NetUtil.ipAddresses()
        val port = Prefs.int(prefs, "remote_port", 8080)
        val cat = category(preferenceScreen, "Ako sa pripojiť")
        cat.add(info(
            "Adresa v prehliadači",
            if (ips.isEmpty()) "Telefón nie je pripojený k sieti" else ips.joinToString("\n") { "http://$it:$port" }
        ))
        cat.add(info(
            "OBS – najlepšia kvalita (Full HD, 30/60 fps)",
            if (ips.isEmpty()) "Telefón nie je pripojený k sieti"
            else "Zdroj → Zdroj médií (Media Source) → zruš „Lokálny súbor“, Vstup:\n" +
                ips.joinToString("\n") { "http://$it:$port/stream.ts" } +
                "\nFormát vstupu nechaj prázdny, Sieťové vyrovnávanie 0–1 MB. Oneskorenie cca 0,5–1 s."
        ))
        cat.add(info(
            "OBS – cez prehliadač",
            if (ips.isEmpty()) "Telefón nie je pripojený k sieti"
            else "Zdroj → Prehliadač (Browser Source), URL:\n" + ips.joinToString("\n") { "http://$it:$port/obs" } +
                "\nŠírka 1920, výška 1080. Iba obraz – zvuk pridaj v OBS z mikrofónu."
        ))
        cat.add(info(
            "60 fps",
            "Živý obraz má 60 fps len vtedy, keď kamera beží na 60 fps (🎬 Video → Snímky za sekundu: 60). " +
                "Full HD 60 potrebuje silnejší telefón a dobrú Wi-Fi (5 GHz alebo hotspot)."
        ))
        cat.add(info(
            "Postup",
            "1. Druhý mobil alebo PC pripoj na rovnakú Wi-Fi (alebo na hotspot tohto telefónu).\n" +
                "2. V prehliadači otvor adresu vyššie.\n" +
                "3. Ovládanie funguje, kým je Firesport Cam otvorená."
        ))
    }

    private fun addProfilesCategory(screen: PreferenceScreen) {
        val ctx = preferenceManager.context
        val cat = category(screen, "Profily nastavení")
        val current = prefs.getString("profile_name", "") ?: ""
        cat.add(info("Aktívny profil", current.ifEmpty { "(žiadny)" }))
        cat.add(Preference(ctx).apply {
            title = "Uložiť aktuálne nastavenia ako profil…"
            setOnPreferenceClickListener {
                textDialog("Názov profilu", current) { name ->
                    if (name.isEmpty()) return@textDialog
                    if (ProfileStore.save(requireContext(), prefs, name)) {
                        toast("Profil „$name“ uložený")
                        requireActivity().recreate()
                    } else toast("Uloženie zlyhalo")
                }
                true
            }
        })
        cat.add(Preference(ctx).apply {
            title = "Načítať profil…"
            setOnPreferenceClickListener {
                val list = ProfileStore.list(requireContext())
                if (list.isEmpty()) toast("Zatiaľ nemáš uložené žiadne profily")
                else AlertDialog.Builder(requireContext())
                    .setTitle("Načítať profil")
                    .setItems(list.toTypedArray()) { _, w ->
                        if (ProfileStore.load(requireContext(), prefs, list[w])) {
                            toast("Profil „${list[w]}“ načítaný")
                            requireActivity().recreate()
                        } else toast("Načítanie zlyhalo")
                    }
                    .show()
                true
            }
        })
        cat.add(Preference(ctx).apply {
            title = "Vymazať profil…"
            setOnPreferenceClickListener {
                val list = ProfileStore.list(requireContext())
                if (list.isEmpty()) toast("Žiadne profily")
                else AlertDialog.Builder(requireContext())
                    .setTitle("Vymazať profil")
                    .setItems(list.toTypedArray()) { _, w ->
                        ProfileStore.delete(requireContext(), list[w])
                        if (current == list[w]) prefs.edit().putString("profile_name", "").apply()
                        toast("Profil vymazaný")
                        requireActivity().recreate()
                    }
                    .show()
                true
            }
        })
    }

    private fun textDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun addCleanupCategory(screen: PreferenceScreen) {
        val ctx = preferenceManager.context
        val cat = category(screen, "🧹 Upratovanie")
        cat.add(Preference(ctx).apply {
            title = "Upratať aplikáciu…"
            summary = Cleanup.summary(ctx) + "\nDočasné súbory, staré alebo neúspešné videá, družstvá, nastavenia – bez odinštalovania."
            setOnPreferenceClickListener {
                Cleanup.showDialog(requireActivity()) { requireActivity().recreate() }
                true
            }
        })
    }

    private fun addInfoCategory(screen: PreferenceScreen) {
        val ctx = preferenceManager.context
        val cat = category(screen, "Informácie")

        cat.add(Preference(ctx).apply {
            title = "📖 Návod"
            summary = "Ako aplikáciu používať"
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), HelpActivity::class.java))
                true
            }
        })
        cat.add(info("Priečinok s videami", VideoStore.root(ctx).absolutePath))
        val version = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        cat.add(info("Verzia", "Firesport Cam $version"))
    }
}
