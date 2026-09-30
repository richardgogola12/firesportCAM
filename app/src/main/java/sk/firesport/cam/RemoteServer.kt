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
                "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8))
                "/obs" -> send(out, 200, "text/html; charset=utf-8", obsPage(query["pin"] ?: "").toByteArray(Charsets.UTF_8))
                "/status" -> if (authorized) send(out, 200, "application/json; charset=utf-8", handler.statusJson().toByteArray(Charsets.UTF_8))
                else send(out, 403, "application/json", "{\"error\":\"pin\"}".toByteArray())
                "/stream.mjpg" -> if (authorized) stream(s, out)
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
            val interval = 1000L / handler.streamFps().coerceIn(1, 30)
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
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error"
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
    private fun obsPage(pinValue: String): String {
        val q = if (pinValue.isEmpty()) "" else "pin=" + java.net.URLEncoder.encode(pinValue, "UTF-8") + "&"
        return """
<!doctype html>
<html><head><meta charset="utf-8"><title>Firesport Cam – OBS</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img{width:100%;height:100%;object-fit:contain;display:block}</style></head>
<body><img id="v" alt="">
<script>
 const v=document.getElementById('v');
 function start(){v.src='/stream.mjpg?${q}t='+Date.now();}
 v.onerror=()=>setTimeout(start,1000);
 start();
</script></body></html>
""".trimIndent()
    }

    private fun page(): String = """
<!doctype html>
<html lang="sk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Firesport Cam – ovládanie</title>
<style>
 body{margin:0;font-family:system-ui,sans-serif;background:#111;color:#eee}
 header{padding:10px 14px;background:#222;font-weight:600}
 main{max-width:900px;margin:auto;padding:12px}
 img{width:100%;background:#000;border-radius:8px;min-height:120px}
 .row{display:flex;gap:10px;flex-wrap:wrap;margin:12px 0}
 button{flex:1;min-width:120px;font-size:20px;padding:16px;border:0;border-radius:10px;color:#fff;background:#444}
 #rec{background:#c62828} #stop{background:#555} #mark{background:#1565c0}
 #state{font-size:18px;margin:8px 0} .small{opacity:.7;font-size:14px}
 input{font-size:16px;padding:8px;border-radius:6px;border:0;width:120px}
</style></head><body>
<header>🔥 Firesport Cam – diaľkové ovládanie</header>
<main>
 <div id="state">Pripájam…</div>
 <img id="img" alt="náhľad">
 <div class="row">
  <button id="rec" onclick="cmd('rec')">⏺ Nahrávať</button>
  <button id="stop" onclick="cmd('stop')">⏹ Stop</button>
  <button id="mark" onclick="cmd('mark')">📍 Značka</button>
  <button id="clr" onclick="cmd('clear')">✕ Vymazať čas</button>
 </div>
 <div class="small">PIN (ak je nastavený): <input id="pin" type="password" oninput="savePin()"></div>
 <div class="small" id="info"></div>
</main>
<script>
 const pinEl=document.getElementById('pin');
 try{pinEl.value=localStorage.getItem('fs_pin')||''}catch(e){}
 function savePin(){try{localStorage.setItem('fs_pin',pinEl.value)}catch(e){};img()}
 function q(){return 'pin='+encodeURIComponent(pinEl.value)}
 function cmd(c){fetch('/cmd?c='+c+'&'+q()).then(r=>r.text()).then(t=>{document.getElementById('info').textContent=t})}
 function poll(){
  fetch('/status?'+q()).then(r=>r.json()).then(s=>{
   if(s.error){document.getElementById('state').textContent='Zadaj PIN';return}
   document.getElementById('state').innerHTML=(s.recording?'🔴 NAHRÁVA '+s.duration:'⚪ '+s.state)+'<br><span class="small">'+s.info+'</span>';
  }).catch(()=>{document.getElementById('state').textContent='Bez spojenia'});
 }
 function img(){
  const i=document.getElementById('img');
  i.onerror=()=>setTimeout(img,1500);
  i.src='/stream.mjpg?'+q()+'&t='+Date.now();
 }
 setInterval(poll,1000);poll();img();
</script>
</body></html>
""".trimIndent()
}
