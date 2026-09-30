package sk.firesport.cam

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Jednoduchý webový server na diaľkové ovládanie z iného mobilu / PC
 * (prehliadač → http://IP_TELEFONU:PORT).
 */
class RemoteServer(
    private val port: Int,
    private val pin: String,
    private val handler: Handler
) {
    interface Handler {
        /** Stav vo formáte JSON. */
        fun statusJson(): String

        /** JPEG náhľad kamery alebo null. */
        fun snapshot(): ByteArray?

        /** Počet snímok za sekundu pre video stream. */
        fun streamFps(): Int

        /** Príkaz: rec, stop, toggle, mark. @return odpoveď pre používateľa */
        fun command(cmd: String, arg: String): String

        /** "h264" = video Full HD (odporúčané), "mjpeg" = obrázky. */
        fun streamMode(): String

        /** Kodér živého obrazu H.264 alebo null (kamera nebeží). */
        fun liveEncoder(): LiveEncoder?

        fun liveFps(): Int

        /** Súbor webovej stránky z assets/web (index.html, player.js). */
        fun asset(path: String): ByteArray?
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    // posledný snímok – zdieľaný všetkými divákmi, aby sa kamera nezaťažovala viac
    private val frameLock = Object()
    private var lastFrame: ByteArray? = null
    private var lastFrameAt = 0L

    private fun frame(intervalMs: Long): ByteArray? {
        synchronized(frameLock) {
            val now = System.currentTimeMillis()
            if (lastFrame == null || now - lastFrameAt >= intervalMs * 8 / 10) {
                val f = handler.snapshot()
                if (f != null) lastFrame = f
                lastFrameAt = now
            }
            return lastFrame
        }
    }

    fun start() {
        running = true
        Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                while (running) {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                }
            } catch (e: Exception) {
                if (running) Log.e("FiresportCam", "remote server", e)
            }
        }, "remote-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5000
            s.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // hlavičky preskočíme
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            val target = parts.getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val out = s.getOutputStream()

            val authorized = pin.isEmpty() || query["pin"] == pin
            when (path) {
                "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", page(query["mode"] ?: handler.streamMode()).toByteArray(Charsets.UTF_8))
            "/player.js" -> {
                val js = handler.asset("player.js")
                if (js != null) send(out, 200, "application/javascript; charset=utf-8", js)
                else send(out, 404, "text/plain", "not found".toByteArray())
            }
                "/obs" -> send(out, 200, "text/html; charset=utf-8", obsPage(query["pin"] ?: "", if ((query["mode"] ?: handler.streamMode()) == "mjpeg") "mjpeg" else "h264").toByteArray(Charsets.UTF_8))
                "/status" -> if (authorized) send(out, 200, "application/json; charset=utf-8", handler.statusJson().toByteArray(Charsets.UTF_8))
                else send(out, 403, "application/json", "{\"error\":\"pin\"}".toByteArray())
                "/stream.mjpg" -> if (authorized) stream(s, out)
                else send(out, 403, "text/plain", "pin".toByteArray())
                "/stream.ts", "/live.ts" -> if (authorized) liveTs(s, out)
                else send(out, 403, "text/plain", "pin".toByteArray())
                "/live.mp4" -> if (authorized) liveMp4(s, out)
                else send(out, 403, "text/plain", "pin".toByteArray())
                "/snapshot.jpg" -> {
                    val img = if (authorized) frame(100) else null
                    if (img != null) send(out, 200, "image/jpeg", img)
                    else send(out, 404, "text/plain", "no image".toByteArray())
                }
                "/cmd" -> {
                    val msg = if (authorized) handler.command(query["c"] ?: "", query["t"] ?: "") else "Nesprávny PIN"
                    send(out, if (authorized) 200 else 403, "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
                }
                else -> send(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Súvislý MJPEG stream – prehliadač ho zobrazí ako video v obyčajnom <img>. */
    private fun stream(s: Socket, out: OutputStream) {
        s.soTimeout = 0
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=fsframe\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.flush()
        var lastSent: ByteArray? = null
        while (running) {
            val t0 = System.currentTimeMillis()
            val interval = 1000L / handler.streamFps().coerceIn(1, 60)
            val img = frame(interval)
            if (img != null && img !== lastSent) {
                out.write(
                    ("--fsframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.size}\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                )
                out.write(img)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                lastSent = img
            }
            val wait = interval - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
        }
    }

    private fun head(out: OutputStream, type: String, extra: String = "") {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: $type\r\n" +
                "Cache-Control: no-store\r\n" + extra +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.flush()
    }

    /** Živý obraz H.264 v MPEG-TS – OBS „Zdroj médií“, VLC. */
    private fun liveTs(s: Socket, out: OutputStream) {
        val enc = handler.liveEncoder() ?: return send(out, 503, "text/plain", "camera off".toByteArray())
        s.soTimeout = 0
        val c = enc.addClient()
        try {
            head(out, "video/mp2t")
            val mux = TsMuxer()
            while (running && !c.reset) {
                val f = c.queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                out.write(mux.frame(f, enc.sps, enc.pps))
                out.flush()
            }
        } finally {
            enc.removeClient(c)
        }
    }

    /** Živý obraz H.264 vo fragmentovanom MP4 – prehliadač (Media Source Extensions). */
    private fun liveMp4(s: Socket, out: OutputStream) {
        val enc = handler.liveEncoder() ?: return send(out, 503, "text/plain", "camera off".toByteArray())
        s.soTimeout = 0
        val c = enc.addClient()
        try {
            if (!enc.awaitConfig(5000)) {
                send(out, 503, "text/plain", "encoder".toByteArray())
                return
            }
            val sps = enc.sps ?: return
            val pps = enc.pps ?: return
            val mux = Fmp4Muxer(sps, pps, enc.width, enc.height, handler.liveFps())
            head(out, "video/mp4", "X-Codec: ${mux.codec}\r\nX-Size: ${enc.width}x${enc.height}\r\nAccess-Control-Expose-Headers: X-Codec, X-Size\r\n")
            out.write(mux.init())
            out.flush()
            while (running && !c.reset) {
                val f = c.queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                out.write(mux.fragment(f))
                out.flush()
            }
        } finally {
            enc.removeClient(c)
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split("&").mapNotNull {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            try {
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }




    /** Čistý obraz na celú plochu – pre OBS (Zdroj prehliadača / Browser Source). */
    private fun obsPage(pinValue: String, mode: String): String {
        val q = if (pinValue.isEmpty()) "" else "pin=" + java.net.URLEncoder.encode(pinValue, "UTF-8") + "&"
        return """
<!doctype html>
<html><head><meta charset="utf-8"><title>Firesport Cam – OBS</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img,video{width:100%;height:100%;object-fit:contain;display:block}</style></head>
<body><video id="vid" muted autoplay playsinline></video><img id="v" alt="" style="display:none">
<script src="/player.js"></script>
<script>
 const v=document.getElementById('v'),vid=document.getElementById('vid');
 function mjpeg(){vid.style.display='none';v.style.display='block';start();}
 function start(){v.src='/stream.mjpg?${q}t='+Date.now();}
 v.onerror=function(){setTimeout(start,1000)};
 if('$mode'==='h264')fsPlay(vid,'${q}x=1',mjpeg);else mjpeg();
</script></body></html>
""".trimIndent()
    }

    /** Ovládacia stránka (assets/web/index.html). */
    private fun page(mode: String): String {
        val m = if (mode == "mjpeg") "mjpeg" else "h264"
        val html = handler.asset("index.html")?.toString(Charsets.UTF_8)
            ?: return "<!doctype html><meta charset=utf-8><p>Stránka ovládania chýba.</p>"
        return html.replace("__MODE__", m)
    }
}
