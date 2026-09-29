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

/** Prijíma UDP správy na pozadí a posiela ich ako text do [onMessage]. */
class UdpReceiver(
    ctx: Context,
    private val port: Int,
    private val charset: Charset,
    private val multicastGroup: String,
    private val onMessage: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val appCtx = ctx.applicationContext
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var lock: WifiManager.MulticastLock? = null

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
                    onMessage(String(p.data, p.offset, p.length, charset))
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

    @Suppress("DEPRECATION")
    private fun openSocket(): DatagramSocket {
        val group = multicastGroup.trim()
        if (group.isNotEmpty()) {
            val ms = MulticastSocket(null as SocketAddress?)
            ms.reuseAddress = true
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
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
    }
}

object NetUtil {
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
