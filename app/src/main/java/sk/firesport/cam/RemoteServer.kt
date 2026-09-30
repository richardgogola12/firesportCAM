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
                "/obs" -> send(out, 200, "text/html; charset=utf-8", obsPage(query["pin"] ?: "", query["mode"] ?: handler.streamMode()).toByteArray(Charsets.UTF_8))
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


    /** Prehrávač H.264 (MSE) s automatickým návratom na MJPEG. */
    private val playerJs = """
function fsPlay(video,q,onFallback){
 if(!window.MediaSource||!MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01F"')){onFallback();return}
 let played=false,done=false,lastT=-1,stall=0;const ctrl=new AbortController();
 const ms=new MediaSource();video.src=URL.createObjectURL(ms);
 function end(){if(done)return;done=true;clearInterval(wd);try{ctrl.abort()}catch(e){}
  if(played)setTimeout(function(){fsPlay(video,q,onFallback)},800);else onFallback();}
 ms.addEventListener('sourceopen',async function(){
  try{
   const r=await fetch('/live.mp4?'+q+'&t='+Date.now(),{signal:ctrl.signal});
   if(!r.ok){end();return}
   const codec=r.headers.get('X-Codec')||'avc1.42E01F';
   const sb=ms.addSourceBuffer('video/mp4; codecs="'+codec+'"');
   const queue=[];
   function pump(){
    if(sb.updating||!queue.length)return;
    try{if(video.buffered.length&&video.currentTime-video.buffered.start(0)>20){sb.remove(video.buffered.start(0),video.currentTime-5);return}}catch(e){}
    let n=0;for(const c of queue)n+=c.length;const b=new Uint8Array(n);let o=0;
    while(queue.length){const c=queue.shift();b.set(c,o);o+=c.length}
    try{sb.appendBuffer(b)}catch(e){end()}
   }
   sb.addEventListener('updateend',function(){
    if(video.buffered.length){const e=video.buffered.end(video.buffered.length-1);
     if(e-video.currentTime>0.5)video.currentTime=Math.max(e-0.1,0);}
    if(video.paused)video.play().catch(function(){});
    pump();
   });
   const rd=r.body.getReader();
   for(;;){const x=await rd.read();if(x.done)break;queue.push(x.value);pump();}
   end();
  }catch(e){end()}
 });
 const wd=setInterval(function(){
  if(video.currentTime>0.2)played=true;
  if(video.currentTime===lastT){if(++stall>=6)end()}else{stall=0;lastT=video.currentTime}
 },1000);
}
"""

    /** Čistý obraz na celú plochu – pre OBS (Zdroj prehliadača / Browser Source). */
    private fun obsPage(pinValue: String, mode: String): String {
        val q = if (pinValue.isEmpty()) "" else "pin=" + java.net.URLEncoder.encode(pinValue, "UTF-8") + "&"
        return """
<!doctype html>
<html><head><meta charset="utf-8"><title>Firesport Cam – OBS</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img,video{width:100%;height:100%;object-fit:contain;display:block}</style></head>
<body><video id="vid" muted autoplay playsinline></video><img id="v" alt="" style="display:none">
<script>
$playerJs
 const v=document.getElementById('v'),vid=document.getElementById('vid');
 function mjpeg(){vid.style.display='none';v.style.display='block';start();}
 function start(){v.src='/stream.mjpg?${q}t='+Date.now();}
 v.onerror=function(){setTimeout(start,1000)};
 if('$mode'==='h264')fsPlay(vid,'${q}x=1',mjpeg);else mjpeg();
</script></body></html>
""".trimIndent()
    }

    private fun page(mode: String): String = """
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
 <video id="vid" muted autoplay playsinline style="width:100%;background:#000;border-radius:8px;display:block"></video>
 <img id="img" alt="náhľad" style="display:none">
 <div class="small" id="mode"></div>
 <div class="row">
  <button id="rec" onclick="cmd('rec')">⏺ Nahrávať</button>
  <button id="stop" onclick="cmd('stop')">⏹ Stop</button>
  <button id="mark" onclick="cmd('mark')">📍 Značka</button>
  <button id="clr" onclick="cmd('clear')">✕ Vymazať čas</button>
 </div>
 <div class="small">PIN (ak je nastavený): <input id="pin" type="password" onchange="savePin()"></div>
 <div class="small" id="info"></div>
</main>
<script>
 const pinEl=document.getElementById('pin');
 try{pinEl.value=localStorage.getItem('fs_pin')||''}catch(e){}
 function savePin(){try{localStorage.setItem('fs_pin',pinEl.value)}catch(e){};location.reload()}
 function q(){return 'pin='+encodeURIComponent(pinEl.value)}
 function cmd(c){fetch('/cmd?c='+c+'&'+q()).then(r=>r.text()).then(t=>{document.getElementById('info').textContent=t})}
 function poll(){
  fetch('/status?'+q()).then(r=>r.json()).then(s=>{
   if(s.error){document.getElementById('state').textContent='Zadaj PIN';return}
   document.getElementById('state').innerHTML=(s.recording?'🔴 NAHRÁVA '+s.duration:'⚪ '+s.state)+'<br><span class="small">'+s.info+'</span>';
  }).catch(()=>{document.getElementById('state').textContent='Bez spojenia'});
 }
 $playerJs
 let mjpegOn=false;
 function img(){
  if(!mjpegOn&&MODE==='h264'){startVideo();return}
  const i=document.getElementById('img');
  i.onerror=()=>setTimeout(img,1500);
  i.src='/stream.mjpg?'+q()+'&t='+Date.now();
 }
 function startVideo(){
  document.getElementById('mode').innerHTML='Obraz: video H.264 · <a href="/?mode=mjpeg" style="color:#9cf">prepnúť na obrázky (MJPEG)</a>';
  fsPlay(document.getElementById('vid'),q(),function(){
   mjpegOn=true;document.getElementById('vid').style.display='none';
   document.getElementById('img').style.display='block';
   document.getElementById('mode').textContent='Obraz: obrázky (MJPEG) – prehliadač nepodporuje video alebo sa nenačítalo';
   img();
  });
 }
 const MODE='$mode';
 if(MODE!=='h264'){mjpegOn=true;document.getElementById('vid').style.display='none';document.getElementById('img').style.display='block';
  document.getElementById('mode').innerHTML='Obraz: obrázky (MJPEG) · <a href="/?mode=h264" style="color:#9cf">skúsiť video Full HD</a>';}
 setInterval(poll,1000);poll();img();
</script>
</body></html>
""".trimIndent()
}
