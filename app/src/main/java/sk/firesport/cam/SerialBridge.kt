package sk.firesport.cam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * RS232 (USB-sériový prevodník cez OTG) → UDP.
 *
 * Tri nezávislé vlákna, aby sa navzájom nebrzdili:
 *  - čítanie z USB a skladanie správ (riadkov),
 *  - odosielanie UDP (fronta, každá správa samostatný paket),
 *  - zobrazenie v tejto aplikácii (texty sa zlučujú – overlay potrebuje len posledný čas; príkazy idú všetky).
 * Navrhnuté na stovky až tisíce správ za sekundu.
 */
object SerialHub {
    private const val TAG = "FiresportSerial"
    private const val ACTION_PERMISSION = "sk.firesport.cam.USB_PERMISSION"

    /** Spracovanie správy v aplikácii (nastaví CameraActivity). */
    @Volatile var localSink: ((String) -> Unit)? = null

    /** Zdrojový port UDP soketu mostu – vlastné broadcasty sa na tomto telefóne druhýkrát nespracujú. */
    @Volatile var localUdpPort = -1
        private set

    @Volatile var status = "RS232 vypnuté"
        private set
    @Volatile var deviceName = ""
        private set
    @Volatile var lastLine = ""
        private set

    // počítadlá
    val rxCount = AtomicLong()
    val txCount = AtomicLong()
    val ignoredCount = AtomicLong()
    val droppedCount = AtomicLong()
    val errorCount = AtomicLong()
    @Volatile var rxRate = 0
        private set
    @Volatile var txRate = 0
        private set

    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())
    private var bridge: Bridge? = null
    private var configKey = ""
    private val requested = HashSet<String>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("rs_")) {
            main.removeCallbacks(applyRunnable)
            main.postDelayed(applyRunnable, 400)
        }
    }
    private val applyRunnable = Runnable { applySettings() }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION, UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    bridge?.wake()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    bridge?.deviceDetached()
                }
            }
        }
    }

    /** Raz pri štarte aplikácie. */
    fun init(ctx: Context) {
        if (appCtx != null) return
        val c = ctx.applicationContext
        appCtx = c
        val p = PreferenceManager.getDefaultSharedPreferences(c)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(c, usbReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        // UsbManager.ACTION_USB_DEVICE_* sú systémové – na niektorých verziách chodia len exportovaným prijímačom
        try {
            val sys = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(c, usbReceiver, sys, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Exception) {
        }
        applySettings()
    }

    /** Zariadenie bolo pripojené (intent v aktivite) – skús hneď otvoriť. */
    fun deviceAttached() {
        bridge?.wake()
    }

    /** Znova požiada o povolenie a pripojí prevodník. */
    fun reconnect() {
        requested.clear()
        val b = bridge
        if (b != null) b.reopen() else applySettings()
    }

    /** Podľa nastavení spustí, reštartuje alebo zastaví most. */
    fun applySettings() {
        val c = appCtx ?: return
        val p = prefs ?: return
        val cfg = Config.load(p)
        val key = cfg.toString()
        if (!cfg.enabled) {
            stop()
            status = "RS232 vypnuté"
            configKey = key
            return
        }
        if (bridge != null && key == configKey) return
        stop()
        configKey = key
        requested.clear()
        bridge = Bridge(c, cfg).also { it.start() }
    }

    fun stop() {
        bridge?.stop()
        bridge = null
        localUdpPort = -1
        rxRate = 0
        txRate = 0
    }

    fun isRunning() = bridge != null

    fun resetCounters() {
        rxCount.set(0); txCount.set(0); ignoredCount.set(0); droppedCount.set(0); errorCount.set(0)
    }

    /** Skúška výkonu bez prevodníka: [perSecond] správ za sekundu počas [seconds] s celou cestou (filter → UDP → aplikácia). */
    fun runTest(perSecond: Int, seconds: Int): Boolean {
        val b = bridge ?: return false
        Thread({
            val start = System.nanoTime()
            val total = perSecond * seconds
            for (i in 0 until total) {
                val target = start + i * 1_000_000_000L / perSecond
                val wait = target - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                val t = i / perSecond.toDouble()
                b.onLine(String.format(java.util.Locale.ROOT, "%.2f", t))
            }
        }, "rs-test").start()
        return true
    }

    /** Zoznam pripojených USB-sériových zariadení: (kľúč "vid:pid", popis). */
    fun devices(ctx: Context): List<Pair<String, String>> {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return try {
            UsbSerialProber.getDefaultProber().findAllDrivers(um).map { d -> keyOf(d.device) to describe(d) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun keyOf(d: UsbDevice) = String.format("%04X:%04X", d.vendorId, d.productId)

    fun describe(d: UsbSerialDriver): String {
        val dev = d.device
        val name = d.javaClass.simpleName.removeSuffix("SerialDriver")
        val prod = try {
            dev.productName
        } catch (_: Exception) {
            null
        }
        return "${prod ?: name} ($name, ${keyOf(dev)})"
    }

    internal fun requestPermission(ctx: Context, um: UsbManager, d: UsbDevice) {
        val k = keyOf(d)
        if (!requested.add(k)) return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, flags)
        main.post {
            try {
                um.requestPermission(d, pi)
            } catch (e: Exception) {
                Log.e(TAG, "permission", e)
            }
        }
    }

    internal fun setStatus(s: String) {
        status = s
    }

    internal fun setDevice(s: String) {
        deviceName = s
    }

    internal fun setLast(s: String) {
        lastLine = s
    }

    internal fun setRates(rx: Int, tx: Int) {
        rxRate = rx
        txRate = tx
    }

    internal fun setLocalPort(p: Int) {
        localUdpPort = p
    }

    /** Zhrnutie do stavového riadku. */
    fun summary(): String {
        if (bridge == null) return status
        return "$status • $rxRate spr/s → UDP $txRate/s" +
            (if (droppedCount.get() > 0) " • zahodené ${droppedCount.get()}" else "")
    }

    // ------------------------------------------------------------------ nastavenia

    data class Config(
        val enabled: Boolean,
        val device: String,
        val portIndex: Int,
        val baud: Int,
        val dataBits: Int,
        val stopBits: Int,
        val parity: Int,
        val flow: String,
        val dtr: Boolean,
        val rts: Boolean,
        val framing: String,
        val terminator: String,
        val gapMs: Int,
        val maxLen: Int,
        val charset: String,
        val trim: Boolean,
        val stripCtrl: Boolean,
        val skipEmpty: Boolean,
        val dedupe: Boolean,
        val ignore: String,
        val ignoreCase: Boolean,
        val local: Boolean,
        val udpEnabled: Boolean,
        val udpTarget: String,
        val udpIps: String,
        val udpPort: Int,
        val udpPrefix: String,
        val udpSuffix: String,
        val udpCharset: String
    ) {
        companion object {
            fun load(p: SharedPreferences) = Config(
                enabled = p.getBoolean("rs_enabled", false),
                device = Prefs.str(p, "rs_device", "auto"),
                portIndex = Prefs.int(p, "rs_port_index", 0).coerceIn(0, 7),
                baud = Prefs.int(p, "rs_baud", 9600).coerceIn(50, 4_000_000),
                dataBits = Prefs.int(p, "rs_databits", 8).coerceIn(5, 8),
                stopBits = when (Prefs.str(p, "rs_stopbits", "1")) {
                    "1.5" -> UsbSerialPort.STOPBITS_1_5
                    "2" -> UsbSerialPort.STOPBITS_2
                    else -> UsbSerialPort.STOPBITS_1
                },
                parity = when (Prefs.str(p, "rs_parity", "none")) {
                    "odd" -> UsbSerialPort.PARITY_ODD
                    "even" -> UsbSerialPort.PARITY_EVEN
                    "mark" -> UsbSerialPort.PARITY_MARK
                    "space" -> UsbSerialPort.PARITY_SPACE
                    else -> UsbSerialPort.PARITY_NONE
                },
                flow = Prefs.str(p, "rs_flow", "none"),
                dtr = p.getBoolean("rs_dtr", true),
                rts = p.getBoolean("rs_rts", true),
                framing = Prefs.str(p, "rs_framing", "any"),
                terminator = p.getString("rs_terminator", ";") ?: ";",
                gapMs = Prefs.int(p, "rs_gap_ms", 20).coerceIn(2, 2000),
                maxLen = Prefs.int(p, "rs_max_len", 256).coerceIn(8, 8192),
                charset = Prefs.str(p, "rs_encoding", "UTF-8"),
                trim = p.getBoolean("rs_trim", true),
                stripCtrl = p.getBoolean("rs_strip_ctrl", true),
                skipEmpty = p.getBoolean("rs_skip_empty", true),
                dedupe = p.getBoolean("rs_dedupe", false),
                ignore = p.getString("rs_ignore", "") ?: "",
                ignoreCase = p.getBoolean("rs_ignore_case", true),
                local = p.getBoolean("rs_local", true),
                udpEnabled = p.getBoolean("rs_udp_enabled", true),
                udpTarget = Prefs.str(p, "rs_udp_target", "broadcast"),
                udpIps = p.getString("rs_udp_ips", "") ?: "",
                udpPort = Prefs.int(p, "rs_udp_port", 5000).coerceIn(1, 65535),
                udpPrefix = p.getString("rs_udp_prefix", "") ?: "",
                udpSuffix = Prefs.str(p, "rs_udp_suffix", "none"),
                udpCharset = Prefs.str(p, "rs_udp_encoding", "UTF-8")
            )
        }
    }

    /** Zoznam ignorovaných textov: text = presne, *text* obsahuje, text* začína, *text končí, re:… regulárny výraz. */
    class IgnoreList(spec: String, private val ignoreCase: Boolean) {
        private val exact = HashSet<String>()
        private val contains = ArrayList<String>()
        private val starts = ArrayList<String>()
        private val ends = ArrayList<String>()
        private val regex = ArrayList<Regex>()
        val empty: Boolean

        init {
            for (raw in spec.lines()) {
                val l = raw.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                when {
                    l.startsWith("re:", true) -> try {
                        regex.add(Regex(l.substring(3).trim(), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()))
                    } catch (_: Exception) {
                    }
                    l.length > 2 && l.startsWith("*") && l.endsWith("*") -> contains.add(norm(l.substring(1, l.length - 1)))
                    l.length > 1 && l.endsWith("*") -> starts.add(norm(l.dropLast(1)))
                    l.length > 1 && l.startsWith("*") -> ends.add(norm(l.drop(1)))
                    else -> exact.add(norm(l))
                }
            }
            empty = exact.isEmpty() && contains.isEmpty() && starts.isEmpty() && ends.isEmpty() && regex.isEmpty()
        }

        private fun norm(s: String) = if (ignoreCase) s.lowercase() else s

        fun matches(text: String): Boolean {
            if (empty) return false
            val t = norm(text)
            if (t in exact) return true
            for (c in contains) if (t.contains(c)) return true
            for (s in starts) if (t.startsWith(s)) return true
            for (e in ends) if (t.endsWith(e)) return true
            for (r in regex) if (r.containsMatchIn(text)) return true
            return false
        }
    }

    // ------------------------------------------------------------------ most

    private class Bridge(private val ctx: Context, private val cfg: Config) {
        @Volatile private var running = false
        private val lock = Object()
        private var port: UsbSerialPort? = null
        private val udpQueue = ArrayBlockingQueue<ByteArray>(8192)
        private val localQueue = ArrayBlockingQueue<String>(4096)
        private val ignore = IgnoreList(cfg.ignore, cfg.ignoreCase)
        private val inCharset = charset(cfg.charset)
        private val outCharset = charset(cfg.udpCharset)
        private val suffix = when (cfg.udpSuffix) {
            "lf" -> "\n"; "cr" -> "\r"; "crlf" -> "\r\n"; else -> ""
        }
        private val term: ByteArray = unescape(cfg.terminator).toByteArray(inCharset)
        private var lastSent = ""
        private val threads = ArrayList<Thread>()
        private var wifiLock: WifiManager.WifiLock? = null

        private fun charset(n: String): Charset = try {
            Charset.forName(n)
        } catch (_: Exception) {
            Charsets.UTF_8
        }

        fun start() {
            running = true
            SerialHub.setStatus("RS232 hľadá prevodník…")
            SerialHub.setDevice("")
            try {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm?.createWifiLock(mode, "firesport-rs232")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
            }
            spawn("rs-read") { readLoop() }
            if (cfg.udpEnabled) spawn("rs-udp") { udpLoop() }
            if (cfg.local) spawn("rs-local") { localLoop() }
            spawn("rs-stats") { statsLoop() }
        }

        private fun spawn(name: String, body: () -> Unit) {
            val t = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                } catch (_: Exception) {
                }
                body()
            }, name)
            t.isDaemon = true
            threads.add(t)
            t.start()
        }

        fun stop() {
            running = false
            closePort()
            threads.forEach { it.interrupt() }
            threads.clear()
            try {
                wifiLock?.release()
            } catch (_: Exception) {
            }
            wifiLock = null
        }

        fun wake() = synchronized(lock) { lock.notifyAll() }

        fun deviceDetached() {
            closePort()
            wake()
        }

        fun reopen() {
            closePort()
            wake()
        }

        private fun closePort() {
            val p = port
            port = null
            try {
                p?.close()
            } catch (_: Exception) {
            }
        }

        // ---------------- USB

        private fun findDriver(um: UsbManager): UsbSerialDriver? {
            val all = try {
                UsbSerialProber.getDefaultProber().findAllDrivers(um)
            } catch (_: Exception) {
                emptyList()
            }
            if (all.isEmpty()) return null
            if (cfg.device == "auto" || cfg.device.isBlank()) return all[0]
            return all.firstOrNull { keyOf(it.device) == cfg.device } ?: all[0]
        }

        private fun open(): Boolean {
            val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val drv = findDriver(um)
            if (drv == null) {
                SerialHub.setStatus("RS232: prevodník nie je pripojený")
                SerialHub.setDevice("")
                return false
            }
            if (!um.hasPermission(drv.device)) {
                SerialHub.setStatus("RS232: povoľ prístup k USB zariadeniu")
                requestPermission(ctx, um, drv.device)
                return false
            }
            val conn = um.openDevice(drv.device)
            if (conn == null) {
                SerialHub.setStatus("RS232: zariadenie sa nedá otvoriť")
                return false
            }
            val p = drv.ports.getOrNull(cfg.portIndex) ?: drv.ports[0]
            return try {
                p.open(conn)
                p.setParameters(cfg.baud, cfg.dataBits, cfg.stopBits, cfg.parity)
                val fc = when (cfg.flow) {
                    "rtscts" -> UsbSerialPort.FlowControl.RTS_CTS
                    "dtrdsr" -> UsbSerialPort.FlowControl.DTR_DSR
                    "xonxoff" -> UsbSerialPort.FlowControl.XON_XOFF
                    else -> UsbSerialPort.FlowControl.NONE
                }
                var flowNote = ""
                try {
                    p.setFlowControl(fc)
                } catch (_: Exception) {
                    if (fc != UsbSerialPort.FlowControl.NONE) flowNote = " (riadenie toku nepodporované)"
                }
                if (fc != UsbSerialPort.FlowControl.RTS_CTS) try {
                    p.setRTS(cfg.rts)
                } catch (_: Exception) {
                }
                if (fc != UsbSerialPort.FlowControl.DTR_DSR) try {
                    p.setDTR(cfg.dtr)
                } catch (_: Exception) {
                }
                port = p
                SerialHub.setDevice(describe(drv))
                SerialHub.setStatus("RS232: ${drv.javaClass.simpleName.removeSuffix("SerialDriver")} ${cfg.baud} Bd$flowNote")
                true
            } catch (e: Exception) {
                Log.e(TAG, "open", e)
                SerialHub.errorCount.incrementAndGet()
                SerialHub.setStatus("RS232 chyba: ${e.message}")
                try {
                    p.close()
                } catch (_: Exception) {
                }
                false
            }
        }

        private fun readLoop() {
            val buf = ByteArray(16384)
            val line = ByteArrayOutputStream(512)
            var lastByteAt = 0L
            while (running) {
                if (port == null && !open()) {
                    synchronized(lock) {
                        try {
                            lock.wait(2000)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    continue
                }
                val p = port ?: continue
                val n = try {
                    p.read(buf, if (cfg.framing == "gap") cfg.gapMs.coerceAtMost(50) else 100)
                } catch (e: Exception) {
                    if (!running) return
                    SerialHub.errorCount.incrementAndGet()
                    SerialHub.setStatus("RS232 odpojené: ${e.message ?: "chyba čítania"}")
                    closePort()
                    line.reset()
                    continue
                }
                val now = System.nanoTime()
                if (n <= 0) {
                    // „gap“: správa = bajty do prestávky
                    if (cfg.framing == "gap" && line.size() > 0 && now - lastByteAt >= cfg.gapMs * 1_000_000L) {
                        emit(line)
                    }
                    continue
                }
                if (cfg.framing == "gap" && line.size() > 0 && now - lastByteAt >= cfg.gapMs * 1_000_000L) emit(line)
                lastByteAt = now
                for (i in 0 until n) {
                    val b = buf[i]
                    when (cfg.framing) {
                        "lf" -> if (b.toInt() == 10) { emit(line); continue }
                        "cr" -> if (b.toInt() == 13) { emit(line); continue }
                        "any" -> if (b.toInt() == 10 || b.toInt() == 13) { emit(line); continue }
                        "custom" -> if (term.isNotEmpty() && b == term[term.size - 1]) {
                            line.write(b.toInt())
                            if (endsWith(line, term)) {
                                val bytes = line.toByteArray()
                                line.reset()
                                line.write(bytes, 0, bytes.size - term.size)
                                emit(line)
                            }
                            continue
                        }
                    }
                    line.write(b.toInt())
                    if (line.size() >= cfg.maxLen) emit(line)
                }
            }
        }

        private fun endsWith(o: ByteArrayOutputStream, t: ByteArray): Boolean {
            if (o.size() < t.size) return false
            val a = o.toByteArray()
            for (i in t.indices) if (a[a.size - t.size + i] != t[i]) return false
            return true
        }

        private fun emit(line: ByteArrayOutputStream) {
            val bytes = line.toByteArray()
            line.reset()
            onLine(String(bytes, inCharset))
        }

        /** Jedna prijatá správa → filter → UDP + aplikácia. */
        fun onLine(raw: String) {
            var t = raw
            if (cfg.stripCtrl) t = stripControl(t)
            if (cfg.trim) t = t.trim()
            if (t.isEmpty() && cfg.skipEmpty) return
            SerialHub.rxCount.incrementAndGet()
            if (ignore.matches(t)) {
                SerialHub.ignoredCount.incrementAndGet()
                return
            }
            if (cfg.dedupe && t == lastSent) {
                SerialHub.ignoredCount.incrementAndGet()
                return
            }
            lastSent = t
            SerialHub.setLast(t)
            if (cfg.udpEnabled) {
                val data = (cfg.udpPrefix + t + suffix).toByteArray(outCharset)
                if (!udpQueue.offer(data)) {
                    udpQueue.poll()
                    udpQueue.offer(data)
                    SerialHub.droppedCount.incrementAndGet()
                }
            }
            if (cfg.local) {
                if (!localQueue.offer(t)) {
                    localQueue.poll()
                    localQueue.offer(t)
                }
            }
        }

        private fun stripControl(s: String): String {
            var ok = true
            for (c in s) if (c < ' ' && c != '\t') { ok = false; break }
            if (ok) return s
            val sb = StringBuilder(s.length)
            for (c in s) if (c >= ' ' || c == '\t') sb.append(c)
            return sb.toString()
        }

        // ---------------- UDP

        private fun targets(): List<InetAddress> {
            val out = LinkedHashSet<InetAddress>()
            if (cfg.udpTarget == "broadcast" || cfg.udpTarget == "both") out.addAll(NetUtil.broadcastAddresses())
            if (cfg.udpTarget == "ips" || cfg.udpTarget == "both") {
                for (s in cfg.udpIps.split(',', ';', '\n', ' ')) {
                    val h = s.trim()
                    if (h.isEmpty()) continue
                    try {
                        out.add(InetAddress.getByName(h))
                    } catch (_: Exception) {
                    }
                }
            }
            return out.toList()
        }

        private fun udpLoop() {
            var sock: DatagramSocket? = null
            var addrs: List<InetAddress> = emptyList()
            var addrsAt = 0L
            try {
                while (running) {
                    try {
                        if (sock == null) {
                            sock = DatagramSocket().apply {
                                broadcast = true
                                sendBufferSize = 256 * 1024
                            }
                            SerialHub.setLocalPort(sock.localPort)
                        }
                        val now = System.currentTimeMillis()
                        if (now - addrsAt > 5000) {
                            addrs = targets()
                            addrsAt = now
                        }
                        val data = udpQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        val s = sock
                        for (a in addrs) {
                            try {
                                s.send(DatagramPacket(data, data.size, a, cfg.udpPort))
                            } catch (_: Exception) {
                                SerialHub.errorCount.incrementAndGet()
                            }
                        }
                        SerialHub.txCount.incrementAndGet()
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        SerialHub.errorCount.incrementAndGet()
                        try {
                            sock?.close()
                        } catch (_: Exception) {
                        }
                        sock = null
                        Thread.sleep(500)
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
            }
        }

        // ---------------- aplikácia

        private fun localLoop() {
            val batch = ArrayList<String>(256)
            try {
                while (running) {
                    val first = localQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    batch.clear()
                    batch.add(first)
                    localQueue.drainTo(batch)
                    val sink = localSink ?: continue
                    // príkazy všetky a v poradí; z textov (časov) medzi nimi len posledný
                    for (i in batch.indices) {
                        val t = batch[i]
                        val cmd = OverlayState.isCommand(t)
                        if (!cmd && i + 1 < batch.size && !OverlayState.isCommand(batch[i + 1])) continue
                        try {
                            sink(t)
                        } catch (e: Exception) {
                            Log.e(TAG, "local", e)
                        }
                    }
                    // overlay sa prekresľuje s obrazom – rýchlejšie ako ~100×/s netreba
                    Thread.sleep(8)
                }
            } catch (_: InterruptedException) {
            }
        }

        private fun statsLoop() {
            var lastRx = SerialHub.rxCount.get()
            var lastTx = SerialHub.txCount.get()
            try {
                while (running) {
                    Thread.sleep(1000)
                    val rx = SerialHub.rxCount.get()
                    val tx = SerialHub.txCount.get()
                    SerialHub.setRates((rx - lastRx).toInt(), (tx - lastTx).toInt())
                    lastRx = rx
                    lastTx = tx
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    /** "\n", "\r", "\t", "\x03" → znaky */
    fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    'x' -> if (i + 4 <= s.length) {
                        val hex = s.substring(i + 2, i + 4).toIntOrNull(16)
                        if (hex != null) { sb.append(hex.toChar()); i += 4; continue }
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
