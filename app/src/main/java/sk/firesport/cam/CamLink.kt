package sk.firesport.cam

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Viac kamier: hlavný telefón posiela príkazy (START / STOP / TEAM:…)
 * ostatným telefónom v sieti ako UDP broadcast.
 */
object CamLink {
    private val exec = Executors.newSingleThreadExecutor()

    /** Broadcast adresy všetkých sietí telefónu (Wi-Fi, hotspot) + 255.255.255.255. */
    private fun broadcastAddresses(): List<InetAddress> {
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

    /** Predpona textov z časomiery, ktoré hlavná kamera preposiela vedľajším. */
    const val RELAY = "FSCAM:TXT:"

    fun send(port: Int, text: String, times: Int = 2) {
        exec.execute {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    val data = text.toByteArray(Charsets.UTF_8)
                    // 2× pre istotu – UDP môže paket stratiť
                    repeat(times) {
                        for (addr in broadcastAddresses()) {
                            try {
                                s.send(DatagramPacket(data, data.size, addr, port))
                            } catch (_: Exception) {
                            }
                        }
                        Thread.sleep(40)
                    }
                }
            } catch (e: Exception) {
                Log.e("FiresportCam", "CamLink send", e)
            }
        }
    }
}
