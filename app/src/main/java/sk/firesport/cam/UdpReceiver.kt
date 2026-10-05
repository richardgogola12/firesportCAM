package sk.firesport.cam

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.nio.charset.Charset

/**
 * Prijíma UDP správy na pozadí a posiela ich ako text do [onMessage].
 *
 * Automatické vyhľadanie (aby vysielač, napr. ESP01, nemusel poznať IP telefónu):
 *  - telefón každé 2 s pošle broadcast `FSCAM:HELLO:<port>:<názov>` na [announcePort],
 *  - na správu `FSCAM:DISCOVER` odpovie odosielateľovi `FSCAM:HERE:<port>:<názov>`.
 */
class UdpReceiver(
    ctx: Context,
    private val port: Int,
    private val charset: Charset,
    private val multicastGroup: String,
    private val onMessage: (String, String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val announcePort: Int = 0,
    private val deviceName: String = ""
) {
    private val appCtx = ctx.applicationContext
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var announcer: Thread? = null
    private var lock: WifiManager.MulticastLock? = null

    private fun name() = deviceName.replace(":", " ").ifEmpty { android.os.Build.MODEL ?: "telefon" }

    fun start() {
        running = true
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wm?.createMulticastLock("firesportcam")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        thread = Thread({ loop() }, "udp-receiver").apply {
            isDaemon = true
            start()
        }
        if (announcePort in 1..65535) {
            announcer = Thread({ announceLoop() }, "udp-announce").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun announceLoop() {
        while (running) {
            val s = socket
            if (s != null) {
                val data = "FSCAM:HELLO:$port:${name()}".toByteArray(Charsets.UTF_8)
                for (addr in NetUtil.broadcastAddresses()) {
                    try {
                        s.send(DatagramPacket(data, data.size, addr, announcePort))
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun loop() {
        while (running) {
            try {
                val s = openSocket()
                socket = s
                onStatus("UDP počúva na porte $port")
                val buf = ByteArray(8192)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    // vlastný broadcast z mostu RS232 → UDP sa tu druhýkrát nespracuje
                    if (p.port == SerialHub.localUdpPort && isOwnAddress(p.address)) continue
                    val text = String(p.data, p.offset, p.length, charset)
                    val from = p.address?.hostAddress ?: ""
                    if (text.startsWith("FSCAM:DISCOVER")) {
                        // odpoveď na vyhľadávanie – vysielač sa dozvie IP a port telefónu
                        try {
                            val reply = "FSCAM:HERE:$port:${name()}".toByteArray(Charsets.UTF_8)
                            s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                        } catch (_: Exception) {
                        }
                        continue
                    }
                    onMessage(text, from)
                }
            } catch (e: Exception) {
                if (!running) break
                onStatus("UDP chyba (port $port): ${e.message}")
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }
    }

    private var ownAddrs: Set<InetAddress> = emptySet()
    private var ownAddrsAt = 0L

    private fun isOwnAddress(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress) return true
        val now = System.currentTimeMillis()
        if (now - ownAddrsAt > 10_000) {
            ownAddrs = NetUtil.ipAddresses().mapNotNull { try { InetAddress.getByName(it) } catch (_: Exception) { null } }.toSet()
            ownAddrsAt = now
        }
        return a in ownAddrs
    }

    @Suppress("DEPRECATION")
    private fun openSocket(): DatagramSocket {
        val group = multicastGroup.trim()
        if (group.isNotEmpty()) {
            val ms = MulticastSocket(null as SocketAddress?)
            ms.reuseAddress = true
            ms.broadcast = true
            ms.bind(InetSocketAddress(port))
            ms.joinGroup(InetAddress.getByName(group))
            return ms
        }
        val ds = DatagramSocket(null as SocketAddress?)
        ds.reuseAddress = true
        ds.broadcast = true
        ds.bind(InetSocketAddress(port))
        return ds
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        thread?.interrupt()
        thread = null
        announcer?.interrupt()
        announcer = null
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
    }
}

object NetUtil {
    /** Broadcast adresy všetkých sietí telefónu (Wi-Fi, hotspot) + 255.255.255.255. */
    fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .forEach { ni ->
                    ni.interfaceAddresses.forEach { ia ->
                        if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
                    }
                }
        } catch (_: Exception) {
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** IPv4 adresy zariadenia (Wi-Fi, hotspot, ethernet...). */
    fun ipAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }
}
