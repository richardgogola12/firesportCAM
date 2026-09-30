package sk.firesport.cam

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Jeden snímok náhľadu pripravený na zakódovanie: [matrix] mapuje bitmapu na výstup outW × outH. */
class LiveFrame(val bitmap: Bitmap, val matrix: Matrix, val outW: Int, val outH: Int)

/** Zakódovaný snímok H.264 (Annex-B). */
class EncodedFrame(val data: ByteArray, val ptsUs: Long, val key: Boolean)

/**
 * Živý obraz v plnej kvalite: náhľad kamery (aj s overlaymi) → hardvérový H.264 kodér.
 * Kodér beží len vtedy, keď sa niekto pozerá (prehliadač / OBS).
 */
class LiveEncoder(private val host: Host) {

    interface Host {
        /** Snímok náhľadu do bitmapy s indexom [slot] (0/1). Volá sa z pomocného vlákna. */
        fun liveCapture(slot: Int): LiveFrame?
        fun liveFps(): Int
        /** Dátový tok v bitoch/s (0 = automaticky podľa rozlíšenia a fps). */
        fun liveBitrate(): Int
    }

    /** Divák – fronta snímok. */
    inner class Client {
        val queue = LinkedBlockingQueue<EncodedFrame>(180)
        @Volatile var needKey = true
        @Volatile var closed = false
        /** Zmenilo sa rozlíšenie – klient sa musí pripojiť znova. */
        @Volatile var reset = false
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val lock = Object()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var codec: MediaCodec? = null

    @Volatile var sps: ByteArray? = null
        private set
    @Volatile var pps: ByteArray? = null
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    fun addClient(): Client {
        val c = Client()
        clients.add(c)
        synchronized(lock) {
            if (!running) start()
        }
        requestKeyFrame()
        return c
    }

    fun removeClient(c: Client) {
        c.closed = true
        clients.remove(c)
    }

    /** Počká na SPS/PPS (potrebné pre hlavičku MP4). */
    fun awaitConfig(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (sps != null && pps != null && width > 0) return true
            Thread.sleep(20)
        }
        return false
    }

    fun shutdown() {
        synchronized(lock) { running = false }
        thread?.interrupt()
        clients.forEach { it.reset = true }
    }

    private fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    private fun start() {
        running = true
        thread = Thread({ loop() }, "live-encoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun publish(f: EncodedFrame) {
        for (c in clients) {
            if (c.needKey && !f.key) continue
            c.needKey = false
            if (!c.queue.offer(f)) {
                // divák nestíha – zahodiť a počkať na ďalší kľúčový snímok
                c.queue.clear()
                c.needKey = true
                requestKeyFrame()
            }
        }
    }

    private fun loop() {
        val capture = Executors.newSingleThreadExecutor()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var enc: MediaCodec? = null
        var surface: Surface? = null
        var drain: Thread? = null
        var idleSince = 0L
        var slot = 0
        var pending: Future<LiveFrame?> = capture.submit<LiveFrame?> { host.liveCapture(slot) }
        val t0 = System.nanoTime()

        fun stopCodec() {
            try {
                enc?.signalEndOfInputStream()
            } catch (_: Exception) {
            }
            drain?.interrupt()
            try {
                drain?.join(500)
            } catch (_: Exception) {
            }
            try {
                enc?.stop()
            } catch (_: Exception) {
            }
            try {
                enc?.release()
            } catch (_: Exception) {
            }
            try {
                surface?.release()
            } catch (_: Exception) {
            }
            enc = null
            surface = null
            drain = null
            codec = null
            sps = null
            pps = null
            width = 0
            height = 0
        }

        try {
            while (running) {
                val fps = host.liveFps().coerceIn(5, 60)
                val frameNs = 1_000_000_000L / fps
                val start = System.nanoTime()

                if (clients.isEmpty()) {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince > 5000) {
                        synchronized(lock) {
                            if (clients.isEmpty()) running = false
                        }
                        if (!running) break
                    }
                } else idleSince = 0L

                val fr = try {
                    pending.get(1, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
                slot = 1 - slot
                val s = slot
                pending = capture.submit<LiveFrame?> { host.liveCapture(s) }

                if (fr != null) {
                    // rozlíšenie sa zmenilo (otočenie telefónu) → nový kodér
                    if (enc == null || fr.outW != width || fr.outH != height) {
                        if (enc != null) {
                            stopCodec()
                            clients.forEach { it.reset = true }
                        }
                        val created = createCodec(fr.outW, fr.outH, fps)
                        if (created == null) {
                            Thread.sleep(1000)
                            continue
                        }
                        enc = created.first
                        surface = created.second
                        codec = enc
                        width = fr.outW
                        height = fr.outH
                        val e = enc!!
                        drain = Thread({ drainLoop(e) }, "live-drain").apply {
                            isDaemon = true
                            start()
                        }
                    }
                    val sf = surface
                    if (sf != null) {
                        try {
                            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sf.lockHardwareCanvas() else sf.lockCanvas(null)
                            canvas.drawColor(Color.BLACK)
                            canvas.drawBitmap(fr.bitmap, fr.matrix, paint)
                            sf.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            Log.w("FiresportCam", "live draw", e)
                        }
                    }
                }
                val wait = frameNs - (System.nanoTime() - start)
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e("FiresportCam", "live encoder", e)
        } finally {
            stopCodec()
            capture.shutdownNow()
            running = false
            clients.forEach { it.reset = true }
        }
        Log.i("FiresportCam", "live encoder stopped after ${(System.nanoTime() - t0) / 1_000_000_000}s")
    }

    private fun createCodec(w: Int, h: Int, fps: Int): Pair<MediaCodec, Surface>? {
        val auto = when {
            w * h >= 1920 * 1080 -> if (fps > 30) 12_000_000 else 8_000_000
            w * h >= 1280 * 720 -> if (fps > 30) 7_000_000 else 5_000_000
            else -> 3_000_000
        }
        val bitrate = host.liveBitrate().takeIf { it > 0 } ?: auto
        val long = maxOf(w, h)
        val sizes = arrayListOf(w to h)
        if (long > 1280) sizes.add(w * 1280 / long to h * 1280 / long)
        for ((cw, ch) in sizes) {
            val ew = cw and 1.inv()
            val eh = ch and 1.inv()
            var mc: MediaCodec? = null
            try {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ew, eh)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) f.setInteger(MediaFormat.KEY_LATENCY, 1)
                mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                mc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val sf = mc.createInputSurface()
                mc.start()
                if (ew != w || eh != h) Log.w("FiresportCam", "live: ${w}x$h nepodporované, použité ${ew}x$eh")
                return mc to sf
            } catch (e: Exception) {
                Log.e("FiresportCam", "live codec ${ew}x$eh", e)
                try {
                    mc?.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun drainLoop(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val idx = mc.dequeueOutputBuffer(info, 20_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = mc.outputFormat
                        fmt.getByteBuffer("csd-0")?.let { b -> Nal.split(ByteArray(b.remaining()).also { b.get(it) }).firstOrNull { Nal.type(it) == 7 }?.let { sps = it } }
                        fmt.getByteBuffer("csd-1")?.let { b -> Nal.split(ByteArray(b.remaining()).also { b.get(it) }).firstOrNull { Nal.type(it) == 8 }?.let { pps = it } }
                    }
                    idx >= 0 -> {
                        val buf = mc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                for (n in Nal.split(data)) {
                                    if (Nal.type(n) == 7) sps = n
                                    if (Nal.type(n) == 8) pps = n
                                }
                            } else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                publish(EncodedFrame(data, info.presentationTimeUs, key))
                            }
                        }
                        mc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}

/** Práca s NAL jednotkami H.264. */
object Nal {
    fun type(nal: ByteArray) = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Rozdelí Annex-B (00 00 01 / 00 00 00 01) na NAL jednotky bez štartovacích kódov. */
    fun split(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        val n = data.size
        while (i + 2 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var end = i
                    while (end > start && data[end - 1].toInt() == 0) end--
                    if (end > start) out.add(data.copyOfRange(start, end))
                }
                i += 3
                start = i
            } else i++
        }
        if (start >= 0 && start < n) out.add(data.copyOfRange(start, n))
        if (start < 0 && n > 0) out.add(data) // bez štartovacích kódov
        return out
    }

    val START = byteArrayOf(0, 0, 0, 1)
}

/** MPEG-TS pre OBS (Zdroj médií), VLC, ffmpeg. */
class TsMuxer {
    private companion object {
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    }

    private val cc = HashMap<Int, Int>()
    private var t0 = -1L

    private fun nextCc(pid: Int): Int {
        val v = cc[pid] ?: 0
        cc[pid] = (v + 1) and 0x0F
        return v
    }

    fun frame(f: EncodedFrame, sps: ByteArray?, pps: ByteArray?): ByteArray {
        if (t0 < 0) t0 = f.ptsUs
        val rel = ((f.ptsUs - t0).coerceAtLeast(0L) * 9 / 100)
        val pts = rel + 27_000 // 0,3 s rezerva dekodéra
        val out = ByteArrayOutputStream(f.data.size + 4096)
        if (f.key) {
            out.write(psi(0, pat()))
            out.write(psi(PID_PMT, pmt()))
        }
        val es = ByteArrayOutputStream(f.data.size + 64)
        es.write(AUD)
        if (f.key && sps != null && pps != null) {
            es.write(Nal.START); es.write(sps)
            es.write(Nal.START); es.write(pps)
        }
        es.write(f.data)
        val body = es.toByteArray()
        val pes = ByteArray(14 + body.size)
        pes[0] = 0; pes[1] = 0; pes[2] = 1; pes[3] = 0xE0.toByte()
        pes[4] = 0; pes[5] = 0 // neobmedzená dĺžka (video)
        pes[6] = 0x80.toByte(); pes[7] = 0x80.toByte(); pes[8] = 5
        pes[9] = (0x20 or ((pts shr 29).toInt() and 0x0E) or 1).toByte()
        pes[10] = (pts shr 22).toInt().toByte()
        pes[11] = (((pts shr 14).toInt() and 0xFE) or 1).toByte()
        pes[12] = (pts shr 7).toInt().toByte()
        pes[13] = (((pts shl 1).toInt() and 0xFE) or 1).toByte()
        System.arraycopy(body, 0, pes, 14, body.size)

        var pos = 0
        var first = true
        val pkt = ByteArray(188)
        while (pos < pes.size) {
            val minAf = if (first) 8 else 0
            val payload = minOf(pes.size - pos, 184 - minAf)
            val af = 184 - payload
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or ((PID_VIDEO shr 8) and 0x1F)).toByte()
            pkt[2] = (PID_VIDEO and 0xFF).toByte()
            pkt[3] = ((if (af > 0) 0x30 else 0x10) or nextCc(PID_VIDEO)).toByte()
            var p = 4
            if (af > 0) {
                pkt[p++] = (af - 1).toByte()
                if (af >= 2) {
                    pkt[p++] = (if (first) 0x10 or (if (f.key) 0x40 else 0) else 0).toByte()
                    if (first) {
                        val base = rel
                        pkt[p++] = (base shr 25).toInt().toByte()
                        pkt[p++] = (base shr 17).toInt().toByte()
                        pkt[p++] = (base shr 9).toInt().toByte()
                        pkt[p++] = (base shr 1).toInt().toByte()
                        pkt[p++] = (((base and 1L).toInt() shl 7) or 0x7E).toByte()
                        pkt[p++] = 0
                    }
                    while (p < 4 + af) pkt[p++] = 0xFF.toByte()
                }
            }
            System.arraycopy(pes, pos, pkt, p, payload)
            pos += payload
            out.write(pkt)
            first = false
        }
        return out.toByteArray()
    }

    private fun pat(): ByteArray = section(
        0x00,
        byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte())
    )

    private fun pmt(): ByteArray = section(
        0x02,
        byteArrayOf(
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(), 0xF0.toByte(), 0x00,
            0x1B, (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(), 0xF0.toByte(), 0x00
        )
    )

    /** table_id + dĺžka + telo + CRC32. */
    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val len = body.size + 4
        val s = ByteArray(3 + len)
        s[0] = tableId.toByte()
        s[1] = (0xB0 or ((len shr 8) and 0x0F)).toByte()
        s[2] = (len and 0xFF).toByte()
        System.arraycopy(body, 0, s, 3, body.size)
        val crc = crc32(s, 3 + body.size)
        s[3 + body.size] = (crc ushr 24).toByte()
        s[4 + body.size] = (crc ushr 16).toByte()
        s[5 + body.size] = (crc ushr 8).toByte()
        s[6 + body.size] = crc.toByte()
        return s
    }

    private fun psi(pid: Int, section: ByteArray): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or nextCc(pid)).toByte()
        pkt[4] = 0 // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.size)
        return pkt
    }

    private fun crc32(d: ByteArray, len: Int): Int {
        var crc = -1
        for (i in 0 until len) {
            crc = crc xor ((d[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}

/** Fragmentované MP4 pre prehliadač (Media Source Extensions). */
class Fmp4Muxer(private val sps: ByteArray, private val pps: ByteArray, private val w: Int, private val h: Int, private val fps: Int) {
    private var seq = 1
    private var t0 = -1L
    private var lastDts = -1L

    /** Reťazec kodeku pre prehliadač, napr. avc1.640028 */
    private fun sb(i: Int, def: Int) = sps.getOrNull(i)?.toInt()?.and(0xFF) ?: def

    val codec: String = String.format(java.util.Locale.ROOT, "avc1.%02X%02X%02X", sb(1, 0x42), sb(2, 0), sb(3, 0x1E))

    private class Box {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v shr 8); u8(v) }
        fun u32(v: Int) = apply { u16(v ushr 16); u16(v) }
        fun u64(v: Long) = apply { u32((v ushr 32).toInt()); u32(v.toInt()) }
        fun bytes(a: ByteArray) = apply { b.write(a) }
        fun str(s: String) = apply { b.write(s.toByteArray(Charsets.US_ASCII)) }
        fun zeros(n: Int) = apply { repeat(n) { b.write(0) } }
        fun full(v: Int, flags: Int) = apply { u8(v); u8(flags shr 16); u16(flags) }
        fun done(type: String): ByteArray {
            val body = b.toByteArray()
            return Box().u32(body.size + 8).str(type).bytes(body).b.toByteArray()
        }
    }

    private val matrix: ByteArray = Box().u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000).b.toByteArray()

    fun init(): ByteArray {
        val ftyp = Box().str("isom").u32(0x200).str("isom").str("iso6").str("avc1").str("mp41").done("ftyp")
        val mvhd = Box().full(0, 0).u32(0).u32(0).u32(1000).u32(0).u32(0x00010000).u16(0x0100).zeros(10)
            .bytes(matrix).zeros(24).u32(2).done("mvhd")
        val tkhd = Box().full(0, 3).u32(0).u32(0).u32(1).u32(0).u32(0).zeros(8).u16(0).u16(0).u16(0).u16(0)
            .bytes(matrix).u32(w shl 16).u32(h shl 16).done("tkhd")
        val mdhd = Box().full(0, 0).u32(0).u32(0).u32(90000).u32(0).u16(0x55C4).u16(0).done("mdhd")
        val hdlr = Box().full(0, 0).u32(0).str("vide").zeros(12).str("VideoHandler").u8(0).done("hdlr")
        val vmhd = Box().full(0, 1).u16(0).u16(0).u16(0).u16(0).done("vmhd")
        val url = Box().full(0, 1).done("url ")
        val dref = Box().full(0, 0).u32(1).bytes(url).done("dref")
        val dinf = Box().bytes(dref).done("dinf")
        val avcC = Box().u8(1).u8(sb(1, 0x42)).u8(sb(2, 0)).u8(sb(3, 0x1E))
            .u8(0xFF).u8(0xE1).u16(sps.size).bytes(sps).u8(1).u16(pps.size).bytes(pps).done("avcC")
        val avc1 = Box().zeros(6).u16(1).u16(0).u16(0).zeros(12).u16(w).u16(h).u32(0x00480000).u32(0x00480000)
            .u32(0).u16(1).zeros(32).u16(0x0018).u16(0xFFFF).bytes(avcC).done("avc1")
        val stsd = Box().full(0, 0).u32(1).bytes(avc1).done("stsd")
        val stts = Box().full(0, 0).u32(0).done("stts")
        val stsc = Box().full(0, 0).u32(0).done("stsc")
        val stsz = Box().full(0, 0).u32(0).u32(0).done("stsz")
        val stco = Box().full(0, 0).u32(0).done("stco")
        val stbl = Box().bytes(stsd).bytes(stts).bytes(stsc).bytes(stsz).bytes(stco).done("stbl")
        val minf = Box().bytes(vmhd).bytes(dinf).bytes(stbl).done("minf")
        val mdia = Box().bytes(mdhd).bytes(hdlr).bytes(minf).done("mdia")
        val trak = Box().bytes(tkhd).bytes(mdia).done("trak")
        val trex = Box().full(0, 0).u32(1).u32(1).u32(0).u32(0).u32(0).done("trex")
        val mvex = Box().bytes(trex).done("mvex")
        val moov = Box().bytes(mvhd).bytes(trak).bytes(mvex).done("moov")
        return ftyp + moov
    }

    fun fragment(f: EncodedFrame): ByteArray {
        if (t0 < 0) t0 = f.ptsUs
        var dts = (f.ptsUs - t0).coerceAtLeast(0L) * 9 / 100
        if (dts <= lastDts) dts = lastDts + 1
        lastDts = dts
        // dáta vzorky: dĺžka + NAL (bez SPS/PPS/AUD – tie sú v hlavičke)
        val sample = ByteArrayOutputStream(f.data.size + 16)
        for (n in Nal.split(f.data)) {
            val t = Nal.type(n)
            if (t == 7 || t == 8 || t == 9) continue
            sample.write(Box().u32(n.size).b.toByteArray())
            sample.write(n)
        }
        val data = sample.toByteArray()
        val duration = 90000 / fps.coerceAtLeast(1)
        val flags = if (f.key) 0x02000000 else 0x01010000
        val mfhd = Box().full(0, 0).u32(seq++).done("mfhd")
        val tfhd = Box().full(0, 0x020000).u32(1).done("tfhd")
        val tfdt = Box().full(1, 0).u64(dts).done("tfdt")
        // veľkosť trun: 8 + 4 + 4 + 4 + 12 = 32
        val trunSize = 32
        val trafSize = 8 + tfhd.size + tfdt.size + trunSize
        val moofSize = 8 + mfhd.size + trafSize
        val trun = Box().full(0, 0x000701).u32(1).u32(moofSize + 8).u32(duration).u32(data.size).u32(flags).done("trun")
        val traf = Box().bytes(tfhd).bytes(tfdt).bytes(trun).done("traf")
        val moof = Box().bytes(mfhd).bytes(traf).done("moof")
        val mdat = Box().bytes(data).done("mdat")
        return moof + mdat
    }
}
