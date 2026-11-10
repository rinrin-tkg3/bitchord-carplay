package com.music.bitchord.carplay

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.music.bitchord.BitChordApplication
import com.music.bitchord.download.Downloads
import com.music.bitchord.data.settings.AudioQuality
import java.io.File
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Embedded HTTP/1.1 server for the iPhone 7 / Apple CarPlay Web Companion.
 *
 * Serves:
 * - `/`                   : Synchronized BitChord Web UI (Home, Search, Library, Now Playing, Lyrics, Queue)
 * - `/api/state`          : Live state polling endpoint (with version hashes to minimize Bluetooth/Hotspot bandwidth)
 * - `/api/report`         : iPhone `<audio>` clock & state reporter
 * - `/api/action`         : Bidirectional transport, queue, library, and theme actions
 * - `/api/search`         : Proxied YouTube Music search
 * - `/api/browse`         : Proxied album / playlist / artist detail loader
 * - `/api/artwork`        : Proxied artwork with LRU cache (works even when iPhone has no internet)
 * - `/api/audio/{videoId}`: Range-capable M4A (`audio/mp4`) stream proxy for iOS Safari & Apple CarPlay
 */
object CarPlayWebServer {

    private const val TAG = "CarPlayWebServer"
    private const val STREAM_TTL_MS = 25 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    private class CachedStream(
        val url: String,
        val mimeType: String,
        val headers: Map<String, String>,
        val durationMs: Long,
        val patchedPrefix: ByteArray?,
        val resolvedAt: Long,
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val streamInFlight = ConcurrentHashMap<String, Deferred<CachedStream?>>()
    private val resolvedDurationsMs = ConcurrentHashMap<String, Long>()

    private class CachedArtwork(val bytes: ByteArray, val contentType: String)

    private val artworkCache: MutableMap<String, CachedArtwork> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedArtwork>(48, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedArtwork>?): Boolean =
                size > 40
        },
    )

    fun getResolvedDurationMs(videoId: String?): Long? {
        if (videoId.isNullOrBlank()) return null
        return resolvedDurationsMs[videoId]?.takeIf { it > 0L }
            ?: streamCache[videoId]?.durationMs?.takeIf { it > 0L }
    }

    fun start(preferredPort: Int, onState: (Int, Boolean) -> Unit) {
        if (serverSocket?.isClosed == false && acceptJob?.isActive == true) {
            onState(serverSocket?.localPort ?: preferredPort, true)
            return
        }
        stop()
        acceptJob = scope.launch {
            var boundSocket: ServerSocket? = null
            var chosenPort = preferredPort

            // Attempt strict binding to preferredPort (8765) first with persistent retries
            // SO_REUSEADDR allows instant rebinding even after app restart or network toggle
            for (attempt in 0..15) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress("0.0.0.0", preferredPort), 64)
                    boundSocket = ss
                    chosenPort = preferredPort
                    break
                } catch (e: Exception) {
                    if (attempt < 15) {
                        kotlinx.coroutines.delay(150L)
                    }
                }
            }

            // Only fallback to another port if preferredPort is genuinely occupied by another app after 2.5s
            if (boundSocket == null) {
                for (candidate in (preferredPort + 1)..(preferredPort + 3)) {
                    try {
                        val ss = ServerSocket()
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress("0.0.0.0", candidate), 64)
                        boundSocket = ss
                        chosenPort = candidate
                        break
                    } catch (_: Exception) {
                    }
                }
            }
            if (boundSocket == null) {
                Log.e(TAG, "Could not bind CarPlayWebServer on port $preferredPort")
                mainHandler.post { onState(preferredPort, false) }
                return@launch
            }
            serverSocket = boundSocket
            Log.i(TAG, "CarPlayWebServer listening strictly on 0.0.0.0:$chosenPort")
            startUdpBeacon(chosenPort)
            mainHandler.post { onState(chosenPort, true) }

            try {
                while (!boundSocket.isClosed) {
                    val client = try {
                        boundSocket.accept()
                    } catch (e: Exception) {
                        if (boundSocket.isClosed) break
                        continue
                    }
                    scope.launch {
                        handleClient(client)
                    }
                }
            } finally {
                try {
                    boundSocket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null

    private fun startUdpBeacon(port: Int) {
        stopUdpBeacon()
        udpJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(port).apply {
                    broadcast = true
                    reuseAddress = true
                }
                udpSocket = socket
                val buf = ByteArray(512)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        break
                    }
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                    if (msg.contains("DISCOVER_BITCHORD") || msg.contains("bitchord")) {
                        val reply = "BITCHORD_PORT:$port".toByteArray(StandardCharsets.UTF_8)
                        val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                        try {
                            socket.send(replyPacket)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP Beacon stopped: ${e.message}")
            }
        }
    }

    private fun stopUdpBeacon() {
        udpJob?.cancel()
        udpJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
    }

    fun stop() {
        stopUdpBeacon()
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    /** Pre-resolves an iOS-compatible M4A stream in the background so playback starts instantly. */
    fun warmUpTrack(videoId: String) {
        if (videoId.isBlank() || videoId.startsWith("content:") || videoId.startsWith("file:")) return
        scope.launch {
            try {
                resolveIosStream(videoId)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun resolveIosStream(videoId: String): CachedStream? {
        val now = System.currentTimeMillis()
        streamCache[videoId]?.takeIf { now - it.resolvedAt < STREAM_TTL_MS }?.let { return it }

        streamInFlight[videoId]?.let { return it.await() }
        val deferred = scope.async {
            try {
                // Prefer M4A (audio/mp4, AAC) because iOS 15 Safari on iPhone 7 & CarPlay
                // natively decode MP4/AAC in hardware and do not support WebM/Opus in <audio>.
                val effectiveMaxKbps = when (AppSettings.effectiveAudioQuality) {
                    AudioQuality.LOW -> 64
                    AudioQuality.MEDIUM -> 160
                    AudioQuality.HIGH -> 256
                    AudioQuality.LOSSLESS -> 512
                }
                val m4aStream = runCatching {
                    StreamResolver.resolveForDownload(videoId, maxKbps = effectiveMaxKbps, requireM4a = true)
                }.getOrNull()

                val url = m4aStream?.url ?: StreamResolver.resolve(videoId)
                val mime = when {
                    m4aStream != null -> "audio/mp4"
                    url.contains("mime=audio%2Fmp4") || url.contains("mime=audio/mp4") -> "audio/mp4"
                    url.contains("mime=audio%2Fmpeg") || url.contains(".mp3") -> "audio/mpeg"
                    else -> "audio/mp4"
                }
                val headers = StreamResolver.mediaHeadersFor(url)
                val (headerDurMs, patchedPrefix) = if (mime == "audio/mp4") {
                    inspectAndPatchMp4Header(url, headers)
                } else {
                    0L to null
                }
                if (headerDurMs > 0L) {
                    resolvedDurationsMs[videoId] = headerDurMs
                }
                CachedStream(
                    url = url,
                    mimeType = mime,
                    headers = headers,
                    durationMs = headerDurMs,
                    patchedPrefix = patchedPrefix,
                    resolvedAt = System.currentTimeMillis(),
                ).also { streamCache[videoId] = it }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve iOS stream for $videoId: ${e.message}")
                null
            }
        }
        val existing = streamInFlight.putIfAbsent(videoId, deferred)
        if (existing != null) {
            deferred.cancel()
            return existing.await()
        }
        deferred.invokeOnCompletion { streamInFlight.remove(videoId, deferred) }
        return deferred.await()
    }

    /**
     * Fetches the first 8 KB of a YouTube DASH M4A (`itag=140`) stream, extracts its exact
     * single-pass duration in milliseconds from `mvhd`/`mdhd`/`sidx`, and patches `moov`
     * in-place (`mvhd.duration = 0`, `tkhd.duration = 0`, `mdhd.duration = 0`, `edts -> free`)
     * without changing any box size or byte offset.
     *
     * Why this is needed:
     * YouTube's DASH fragmented MP4 sets the full track duration in BOTH `moov` (`mvhd`, `tkhd`,
     * `mdhd`, `edts/elst`) AND `sidx`/`moof`. Apple's CoreMedia parser on iOS sums the unfragmented
     * `moov` duration and the fragmented `sidx` duration together (`D + D = 2 * D`), causing a
     * 4-minute song to show as 8+ minutes with a silent second half. Zeroing the unfragmented
     * duration fields in `moov` while keeping `sidx` intact makes CoreMedia report the exact 1x duration.
     */
    private fun inspectAndPatchMp4Header(url: String, headers: Map<String, String>): Pair<Long, ByteArray?> {
        return try {
            val reqBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            reqBuilder.header("Range", "bytes=0-8191")
            Http.client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return 0L to null
                val raw = resp.body?.bytes() ?: return 0L to null
                if (raw.size < 64) return 0L to null
                patchMp4InitBoxesInPlace(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MP4 header inspection failed: ${e.message}")
            0L to null
        }
    }

    private fun patchMp4InitBoxesInPlace(raw: ByteArray): Pair<Long, ByteArray?> {
        val buf = raw.copyOf()
        var durationMs = 0L
        var moovEnd = 0
        var pos = 0
        while (pos + 8 <= buf.size) {
            val boxSize = readU32(buf, pos).toInt()
            if (boxSize < 8) break
            val type = String(buf, pos + 4, 4, StandardCharsets.US_ASCII)
            val boxEnd = pos + boxSize
            if (type == "moov" && boxEnd <= buf.size) {
                val durFromMoov = parseAndPatchMoovChildren(buf, pos + 8, boxEnd)
                if (durFromMoov > 0L) durationMs = durFromMoov
                moovEnd = boxEnd
            } else if (type == "sidx" && boxEnd <= buf.size) {
                val durFromSidx = parseSidxDurationMs(buf, pos + 8, boxEnd)
                if (durationMs <= 0L && durFromSidx > 0L) {
                    durationMs = durFromSidx
                }
                break
            } else if (type == "moof") {
                break
            }
            if (boxEnd <= pos || boxEnd > buf.size) break
            pos = boxEnd
        }
        val prefix = if (moovEnd > 0) buf.copyOfRange(0, moovEnd) else null
        return durationMs to prefix
    }

    private fun parseAndPatchMoovChildren(buf: ByteArray, start: Int, end: Int): Long {
        var foundDurationMs = 0L
        var pos = start
        while (pos + 8 <= end) {
            val size = readU32(buf, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(buf, pos + 4, 4, StandardCharsets.US_ASCII)
            val boxEnd = pos + size
            val payload = pos + 8
            when (type) {
                "mvhd", "mdhd" -> {
                    if (payload + 20 <= boxEnd) {
                        val ver = buf[payload].toInt() and 0xFF
                        if (ver == 0 && payload + 20 <= boxEnd) {
                            val timescale = readU32(buf, payload + 12)
                            val ticks = readU32(buf, payload + 16)
                            if (timescale > 0L && ticks > 0L && foundDurationMs <= 0L) {
                                foundDurationMs = (ticks * 1000L) / timescale
                            }
                            writeU32(buf, payload + 16, 0L)
                        } else if (ver == 1 && payload + 32 <= boxEnd) {
                            val timescale = readU32(buf, payload + 20)
                            val ticks = readU64(buf, payload + 24)
                            if (timescale > 0L && ticks > 0L && foundDurationMs <= 0L) {
                                foundDurationMs = (ticks * 1000L) / timescale
                            }
                            writeU64(buf, payload + 24, 0L)
                        }
                    }
                }
                "tkhd" -> {
                    val ver = buf[payload].toInt() and 0xFF
                    if (ver == 0 && payload + 24 <= boxEnd) {
                        writeU32(buf, payload + 20, 0L)
                    } else if (ver == 1 && payload + 36 <= boxEnd) {
                        writeU64(buf, payload + 28, 0L)
                    }
                }
                "edts" -> {
                    // Neutralize the Edit Box (`edts`/`elst`) into a `free` padding box of identical size
                    buf[pos + 4] = 'f'.code.toByte()
                    buf[pos + 5] = 'r'.code.toByte()
                    buf[pos + 6] = 'e'.code.toByte()
                    buf[pos + 7] = 'e'.code.toByte()
                    for (i in payload until boxEnd) {
                        buf[i] = 0
                    }
                }
                "trak", "mdia", "minf", "stbl", "mvex" -> {
                    val childDur = parseAndPatchMoovChildren(buf, payload, boxEnd)
                    if (foundDurationMs <= 0L && childDur > 0L) {
                        foundDurationMs = childDur
                    }
                }
            }
            pos = boxEnd
        }
        return foundDurationMs
    }

    private fun parseSidxDurationMs(buf: ByteArray, payload: Int, boxEnd: Int): Long {
        if (payload + 24 > boxEnd) return 0L
        val ver = buf[payload].toInt() and 0xFF
        val timescale = readU32(buf, payload + 8)
        if (timescale <= 0L) return 0L
        val refCountOffset = if (ver == 0) payload + 22 else payload + 30
        val refsStart = if (ver == 0) payload + 24 else payload + 32
        if (refsStart > boxEnd) return 0L
        val count = ((buf[refCountOffset].toInt() and 0xFF) shl 8) or (buf[refCountOffset + 1].toInt() and 0xFF)
        var totalTicks = 0L
        for (i in 0 until count) {
            val rp = refsStart + i * 12
            if (rp + 12 > boxEnd) break
            totalTicks += readU32(buf, rp + 4)
        }
        return if (totalTicks > 0L) (totalTicks * 1000L) / timescale else 0L
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)

    private fun readU64(b: ByteArray, off: Int): Long =
        (readU32(b, off) shl 32) or (readU32(b, off + 4) and 0xFFFFFFFFL)

    private fun writeU32(b: ByteArray, off: Int, value: Long) {
        b[off] = ((value ushr 24) and 0xFFL).toByte()
        b[off + 1] = ((value ushr 16) and 0xFFL).toByte()
        b[off + 2] = ((value ushr 8) and 0xFFL).toByte()
        b[off + 3] = (value and 0xFFL).toByte()
    }

    private fun writeU64(b: ByteArray, off: Int, value: Long) {
        writeU32(b, off, value ushr 32)
        writeU32(b, off + 4, value and 0xFFFFFFFFL)
    }

    private fun parseRangeStartOffset(contentRangeHeader: String?, requestRangeHeader: String?): Long {
        if (!contentRangeHeader.isNullOrBlank()) {
            // Format: "bytes 0-667/4017099"
            val afterBytes = contentRangeHeader.substringAfter("bytes", "").trim()
            val startStr = afterBytes.substringBefore('-').trim()
            startStr.toLongOrNull()?.let { return it }
        }
        if (!requestRangeHeader.isNullOrBlank()) {
            val afterEq = requestRangeHeader.substringAfter("bytes=", "").trim()
            val startStr = afterEq.substringBefore('-').trim()
            startStr.toLongOrNull()?.let { return it }
        }
        return 0L
    }

    private fun readHeaderLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("UTF-8")
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                baos.write(b)
                if (baos.size() > 16_384) break
            }
        }
        return baos.toString("UTF-8")
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 8_000
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val requestLine = readHeaderLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawUri = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHeaderLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            // HTTPS Tunnel Proxy (CONNECT host:port) so iPhone 7 can use Google Maps / Apple Maps
            // over Galaxy S25's 5G/4G connection even when connected via Local Direct Wi-Fi
            if (method == "CONNECT") {
                handleConnectTunnel(rawUri, sock, input, sock.getOutputStream())
                return
            }

            // Plain HTTP Proxy forwarding when rawUri is an external absolute http:// URL
            val hostHeader = headers["host"].orEmpty()
            val isExternalHttpProxy = (rawUri.startsWith("http://") || rawUri.startsWith("https://")) &&
                !rawUri.contains("192.168.49.1") &&
                !rawUri.contains("127.0.0.1") &&
                !hostHeader.startsWith("192.168.49.1")
            if (isExternalHttpProxy) {
                val out = BufferedOutputStream(sock.getOutputStream())
                handleHttpProxyRequest(method, rawUri, headers, input, out)
                return
            }

            val normalizedUri = if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
                val afterScheme = rawUri.substringAfter("://")
                val slashIdx = afterScheme.indexOf('/')
                if (slashIdx >= 0) afterScheme.substring(slashIdx) else "/"
            } else {
                rawUri
            }

            val path = normalizedUri.substringBefore('?')
            val query = if ('?' in normalizedUri) normalizedUri.substringAfter('?') else ""
            val queryParams = parseQuery(query)

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyText = if (contentLength in 1..262_144) {
                val bytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = input.read(bytes, readTotal, contentLength - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                String(bytes, 0, readTotal, Charsets.UTF_8)
            } else {
                ""
            }

            val clientId = queryParams["cid"]
                ?: headers["x-client-id"]
                ?: sock.inetAddress?.hostAddress
                ?: "iphone"
            CarPlayWebBridge.recordClientHeartbeat(clientId)

            val out = BufferedOutputStream(sock.getOutputStream())
            when {
                path == "/proxy.pac" -> {
                    val activePort = serverSocket?.localPort ?: CarPlayWebBridge.port.value
                    val hostIp = hostHeader.substringBefore(':').ifBlank { "192.168.49.1" }
                    val pacScript = """
                        function FindProxyForURL(url, host) {
                            if (isPlainHostName(host) ||
                                shExpMatch(host, "192.168.*") ||
                                shExpMatch(host, "10.*") ||
                                shExpMatch(host, "172.20.*") ||
                                host === "127.0.0.1" ||
                                host === "localhost") {
                                return "DIRECT";
                            }
                            return "PROXY $hostIp:$activePort";
                        }
                    """.trimIndent()
                    writeResponse(
                        out,
                        "200 OK",
                        "application/x-ns-proxy-autoconfig; charset=utf-8",
                        pacScript.toByteArray(Charsets.UTF_8),
                    )
                }
                path == "/" || path == "/index.html" -> {
                    val statusHtml = """
                        <!DOCTYPE html><html><head><meta charset="utf-8"><title>BitChord Host</title>
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <style>body{font-family:-apple-system,system-ui;background:#0d0e12;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;}
                        .card{background:rgba(255,255,255,0.08);padding:32px 28px;border-radius:20px;border:1px solid rgba(255,255,255,0.12);max-width:340px;}
                        h2{margin:0 0 8px;font-size:22px;}p{color:rgba(255,255,255,0.6);font-size:14px;margin:0 0 16px;}</style>
                        </head><body><div class="card"><h2>BitChord CarPlay Host</h2><p>iPhone専用アプリと接続中</p><a href="/api/state" style="color:#30d158;text-decoration:none;font-weight:600;font-size:13px;">JSON API 確認</a></div></body></html>
                    """.trimIndent()
                    writeResponse(out, "200 OK", "text/html; charset=utf-8", statusHtml.toByteArray(Charsets.UTF_8))
                }
                path == "/api/state" -> {
                    val clientHomeHash = queryParams["hh"]?.toIntOrNull() ?: 0
                    val clientQueueHash = queryParams["qh"]?.toIntOrNull() ?: 0
                    val clientLyricsId = queryParams["lid"].orEmpty()
                    val clientDetailId = queryParams["did"].orEmpty()
                    val json = buildStateJson(clientHomeHash, clientQueueHash, clientLyricsId, clientDetailId)
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
                path == "/api/report" && method == "POST" -> {
                    handleReport(bodyText)
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", """{"ok":true}""".toByteArray())
                }
                path == "/api/action" && method == "POST" -> {
                    handleAction(bodyText)
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", """{"ok":true}""".toByteArray())
                }
                path == "/api/control" -> {
                    val action = queryParams["action"].orEmpty().ifBlank { queryParams["type"].orEmpty() }
                    val param = queryParams["param"].orEmpty()
                    val posParam = queryParams["positionMs"].orEmpty().ifBlank { queryParams["pos"].orEmpty() }
                    val payload = JSONObject().apply {
                        put("type", action)
                        put("param", param)
                        if (posParam.isNotBlank()) {
                            put("positionMs", posParam.toLongOrNull() ?: -1L)
                        } else if (param.isNotBlank() && action == "seek") {
                            put("positionMs", param.toLongOrNull() ?: -1L)
                        }
                        if (action == "play-index" || action == "playQueueIndex") {
                            put("index", param.toIntOrNull() ?: -1)
                        }
                    }
                    handleAction(payload.toString())
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", """{"ok":true}""".toByteArray())
                }
                path == "/api/search" -> {
                    val q = queryParams["q"].orEmpty().trim()
                    val json = handleSearch(q)
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
                path == "/api/browse" -> {
                    val id = queryParams["id"].orEmpty().trim()
                    val json = handleBrowse(id)
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
                path == "/api/artwork" -> {
                    val url = queryParams["url"].orEmpty()
                    handleArtwork(url, out)
                }
                path.startsWith("/api/direct-stream/") -> {
                    val videoId = URLDecoder.decode(path.removePrefix("/api/direct-stream/"), "UTF-8").substringBefore('?')
                    val stream = runBlocking { resolveIosStream(videoId) }
                    if (stream != null) {
                        val json = JSONObject().apply {
                            put("videoId", videoId)
                            put("url", stream.url)
                            put("durationMs", stream.durationMs)
                            put("mimeType", stream.mimeType)
                        }.toString()
                        writeResponse(out, "200 OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                    } else {
                        writeResponse(out, "502 Bad Gateway", "application/json; charset=utf-8", """{"error":"Failed to resolve stream"}""".toByteArray())
                    }
                }
                path.startsWith("/api/audio/") -> {
                    val videoId = URLDecoder.decode(path.removePrefix("/api/audio/"), "UTF-8").substringBefore('?')
                    val rangeHeader = headers["range"]
                    handleAudioStream(method, videoId, rangeHeader, out)
                }
                else -> {
                    writeResponse(out, "404 Not Found", "text/plain; charset=utf-8", "Not Found".toByteArray())
                }
            }
        }
    }

    private fun buildStateJson(
        clientHomeHash: Int,
        clientQueueHash: Int,
        clientLyricsId: String,
        clientDetailId: String,
    ): String {
        var pb = CarPlayWebBridge.PlaybackSnapshot()
        CarPlayWebBridge.getPlaybackSnapshot { pb = it }

        val root = JSONObject()
        root.put("activeOnIphone", pb.activeOnIphone)
        root.put("mediaId", pb.mediaId ?: JSONObject.NULL)
        root.put("title", pb.title)
        root.put("artist", pb.artist)
        root.put("album", pb.album ?: "")
        root.put("thumbnailUrl", pb.thumbnailUrl ?: "")
        root.put("isPlaying", pb.isPlaying)
        root.put("playWhenReady", pb.playWhenReady)
        root.put("buffering", pb.buffering)
        root.put("positionMs", pb.positionMs)
        root.put("durationMs", pb.durationMs)
        root.put("commandSeq", pb.commandSeq)
        root.put("seekSeq", pb.seekSeq)
        root.put("wantedSeekMs", pb.wantedSeekMs)
        root.put("shuffle", pb.shuffle)
        root.put("repeatMode", pb.repeatMode)
        root.put("currentIndex", pb.currentIndex)
        root.put("likeStatus", CarPlayWebBridge.currentLikeStatusSnapshot.name)
        root.put("themeMode", AppSettings.themeMode.value.name)
        root.put("liquidGlass", AppSettings.liquidGlass.value)
        root.put("keepLyricsControlsVisible", AppSettings.keepLyricsControlsVisible.value)
        root.put("syncScreen", CarPlayWebBridge.syncScreenNavigation.value)
        root.put("listenerName", CarPlayWebBridge.listenerNameSnapshot ?: "")
        root.put("listenerAvatarUrl", CarPlayWebBridge.listenerAvatarUrlSnapshot ?: "")

        val screen = CarPlayWebBridge.androidScreenState
        root.put(
            "androidScreen",
            JSONObject().apply {
                put("selectedTab", screen.selectedTab)
                put("showNowPlaying", screen.showNowPlaying)
                put("lyricsOpen", screen.lyricsOpen)
                put("queueOpen", screen.queueOpen)
                put("detailBrowseId", screen.detailBrowseId ?: "")
                put("detailTitle", screen.detailTitle ?: "")
            },
        )

        var queueHash = 17
        for (entry in pb.queue) {
            queueHash = queueHash * 31 + entry.index
            queueHash = queueHash * 31 + entry.videoId.hashCode()
            queueHash = queueHash * 31 + (entry.thumbnailUrl?.hashCode() ?: 0)
            queueHash = queueHash * 31 + entry.durationMs.hashCode()
        }
        if (queueHash == 0) queueHash = 1
        root.put("queueHash", queueHash)
        if (queueHash != clientQueueHash) {
            val qArr = JSONArray()
            for (entry in pb.queue) {
                qArr.put(
                    JSONObject().apply {
                        put("index", entry.index)
                        put("videoId", entry.videoId)
                        put("title", entry.title)
                        put("artist", entry.artist)
                        put("album", entry.album ?: "")
                        put("thumbnailUrl", entry.thumbnailUrl ?: "")
                        put("durationMs", entry.durationMs)
                    },
                )
            }
            root.put("queue", qArr)
        }

        // Lyrics payload (only sent when track or availability changes)
        val lyrics = CarPlayWebBridge.currentLyricsSnapshot
        val lyricsKey = "${pb.mediaId.orEmpty()}:${lyrics?.size ?: -1}:${CarPlayWebBridge.currentLyricsSourceSnapshot.orEmpty()}"
        root.put("lyricsId", lyricsKey)
        if (lyricsKey != clientLyricsId) {
            root.put("lyricsSource", CarPlayWebBridge.currentLyricsSourceSnapshot ?: "")
            val lArr = JSONArray()
            lyrics?.forEachIndexed { lineIdx, line ->
                val wArr = JSONArray()
                val fullText = line.text
                if (line.words.isNotEmpty()) {
                    val spans = line.wordSpans
                    var cursor = 0
                    line.words.forEachIndexed { wIdx, w ->
                        val span = spans.getOrNull(wIdx)
                        val startInLine = (span?.first ?: cursor).coerceIn(0, fullText.length)
                        val endInLine = ((span?.last ?: (startInLine + w.text.length - 1)) + 1).coerceIn(startInLine, fullText.length)
                        if (startInLine > cursor) {
                            val gapStr = fullText.substring(cursor, startInLine)
                            wArr.put(
                                JSONObject().apply {
                                    put("text", gapStr)
                                    put("startMs", w.startMs)
                                    put("endMs", w.startMs)
                                },
                            )
                        }
                        if (w.syllables.isNotEmpty()) {
                            w.syllables.forEach { syl ->
                                val sChar = syl.startChar.coerceIn(0, w.text.length)
                                val eChar = syl.endChar.coerceIn(sChar, w.text.length)
                                val sylText = w.text.substring(sChar, eChar)
                                if (sylText.isNotEmpty()) {
                                    wArr.put(
                                        JSONObject().apply {
                                            put("text", sylText)
                                            put("startMs", syl.startMs)
                                            put("endMs", maxOf(syl.startMs + 1L, syl.endMs))
                                        },
                                    )
                                }
                            }
                        } else {
                            wArr.put(
                                JSONObject().apply {
                                    put("text", w.text)
                                    put("startMs", w.startMs)
                                    put("endMs", maxOf(w.startMs + 1L, w.endMs))
                                },
                            )
                        }
                        cursor = endInLine
                    }
                    if (cursor < fullText.length) {
                        val tailStr = fullText.substring(cursor)
                        val lastEnd = line.words.last().endMs
                        wArr.put(
                            JSONObject().apply {
                                put("text", tailStr)
                                put("startMs", lastEnd)
                                put("endMs", lastEnd)
                            },
                        )
                    }
                }
                val nextLineStartMs = lyrics.getOrNull(lineIdx + 1)?.timeMs ?: (line.timeMs + 4000L)
                val effectiveEndMs = line.sungUntilMs?.takeIf { it > line.timeMs }
                    ?: line.words.lastOrNull()?.endMs?.takeIf { it > line.timeMs }
                    ?: maxOf(line.timeMs + 500L, nextLineStartMs)
                lArr.put(
                    JSONObject().apply {
                        put("timeMs", line.timeMs)
                        put("endTimeMs", effectiveEndMs)
                        put("text", line.text)
                        put("translation", "")
                        put("words", wArr)
                    },
                )
            }
            root.put("lyrics", lArr)
        }

        // Home shelves & Library shelves (only sent when hash changes)
        val homeShelves = CarPlayWebBridge.homeShelvesSnapshot
        val libShelves = CarPlayWebBridge.libraryShelvesSnapshot
        val homeHash = (homeShelves.hashCode() * 31) + libShelves.hashCode()
        root.put("homeHash", homeHash)
        if (homeHash != clientHomeHash) {
            root.put("home", buildHomeDataJson(homeShelves))
            root.put("library", shelvesToJsonArray(libShelves))
        }

        // Currently open detail page on Android
        val detail = CarPlayWebBridge.currentDetailSnapshot
        val detailSongs = (detail?.songs as? UiState.Success<List<Song>>)?.data
        val detailKey = if (detail != null && detailSongs != null) "${detail.browseId}:${detailSongs.size}" else ""
        root.put("detailId", detailKey)
        if (detail != null && detailSongs != null && detailKey != clientDetailId) {
            root.put(
                "detail",
                JSONObject().apply {
                    put("browseId", detail.browseId)
                    put("title", detail.title)
                    put("subtitle", detail.subtitle)
                    put("thumbnailUrl", detail.thumbnailUrl ?: "")
                    val sArr = JSONArray()
                    detailSongs.forEach { s -> sArr.put(songToJson(s)) }
                    put("songs", sArr)
                },
            )
        }

        return root.toString()
    }

    private fun buildHomeDataJson(shelves: List<HomeShelf>): JSONObject {
        val obj = JSONObject()
        if (shelves.isEmpty()) {
            obj.put("recommended", JSONArray())
            obj.put("quickAccess", JSONArray())
            obj.put("remaining", JSONArray())
            return obj
        }
        val recommendedShelf = shelves.firstOrNull { isQuickPicksTitle(it.title) }
            ?: shelves.firstOrNull { it.layout == ShelfLayout.LIST && !it.title.equals("Recents", ignoreCase = true) }
            ?: shelves.firstOrNull { shelf -> shelf.items.any { it.videoId != null } }
            ?: shelves.first()

        val recArr = JSONArray()
        recommendedShelf.items.forEach { recArr.put(shelfItemToJson(it)) }
        obj.put("recommended", recArr)

        val quickItems = buildQuickAccessItems(shelves, recommendedShelf)
        val qaArr = JSONArray()
        quickItems.forEach { qaArr.put(shelfItemToJson(it)) }
        obj.put("quickAccess", qaArr)

        val remaining = shelves.filter { it !== recommendedShelf && !it.title.equals("Recents", ignoreCase = true) }
        obj.put("remaining", shelvesToJsonArray(remaining))
        return obj
    }

    private fun isQuickPicksTitle(title: String): Boolean {
        val t = title.trim()
        return t.equals("Quick picks", ignoreCase = true) ||
            t.equals("クイック ミックス", ignoreCase = true) ||
            t.equals("おすすめ", ignoreCase = true) ||
            t.equals("あなたへのおすすめ", ignoreCase = true) ||
            t.equals("For you", ignoreCase = true) ||
            t.equals("Lựa chọn nhanh", ignoreCase = true) ||
            t.equals("Chọn nhanh đài phát", ignoreCase = true)
    }

    private fun buildQuickAccessItems(
        shelves: List<HomeShelf>,
        recommendedShelf: HomeShelf?,
    ): List<ShelfItem> {
        val recentsShelf = shelves.firstOrNull { it.title.equals("Recents", ignoreCase = true) }
        val otherShelves = shelves.filter { it !== recommendedShelf && it !== recentsShelf }
        val songs = buildList {
            recentsShelf?.items?.forEach(::add)
            otherShelves.forEach { shelf ->
                shelf.items.filter { it.videoId != null && !it.isVideo }.forEach(::add)
            }
            recommendedShelf?.items?.drop(4)?.forEach(::add)
            recommendedShelf?.items?.take(4)?.forEach(::add)
        }
        val collections = buildList {
            otherShelves.forEach { shelf ->
                shelf.items.filter { it.browseId != null && !it.isVideo }.forEach(::add)
            }
        }
        val seen = HashSet<String>()
        val result = ArrayList<ShelfItem>(18)
        var sIdx = 0
        var cIdx = 0
        val pattern = booleanArrayOf(true, false, false, true, true, false, false, false, false)
        var step = 0
        while (result.size < 18 && (sIdx < songs.size || cIdx < collections.size)) {
            val preferSong = pattern[step % pattern.size]
            step++
            val candidate = if (preferSong && sIdx < songs.size) {
                songs[sIdx++]
            } else if (!preferSong && cIdx < collections.size) {
                collections[cIdx++]
            } else if (sIdx < songs.size) {
                songs[sIdx++]
            } else {
                collections[cIdx++]
            }
            val key = candidate.videoId ?: candidate.browseId ?: candidate.title
            if (seen.add(key)) result.add(candidate)
        }
        return result
    }

    private fun shelvesToJsonArray(shelves: List<HomeShelf>): JSONArray {
        val arr = JSONArray()
        for (shelf in shelves) {
            val itemsArr = JSONArray()
            shelf.items.forEach { itemsArr.put(shelfItemToJson(it)) }
            arr.put(
                JSONObject().apply {
                    put("title", shelf.title)
                    put("subtitle", shelf.subtitle)
                    put("layout", shelf.layout.name)
                    put("items", itemsArr)
                },
            )
        }
        return arr
    }

    private fun shelfItemToJson(item: ShelfItem): JSONObject = JSONObject().apply {
        put("title", item.title)
        put("subtitle", item.subtitle)
        put("thumbnailUrl", item.thumbnailUrl ?: "")
        put("videoId", item.videoId ?: "")
        put("browseId", item.browseId ?: "")
        put("isVideo", item.isVideo)
    }

    private fun songToJson(song: Song): JSONObject = JSONObject().apply {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.albumName ?: "")
        put("thumbnailUrl", song.thumbnailUrl ?: "")
        put("durationSeconds", (song.durationMillis() / 1000L).toInt())
        put("isVideo", song.isVideo)
    }

    private fun handleReport(bodyText: String) {
        if (bodyText.isBlank()) return
        try {
            val json = JSONObject(bodyText)
            val mediaId = json.optString("mediaId", "")
            val playing = json.optBoolean("playing", false)
            val buffering = json.optBoolean("buffering", false)
            val positionMs = json.optLong("positionMs", 0L)
            val durationMs = json.optLong("durationMs", 0L)
            val ended = json.optBoolean("ended", false)
            val seekSeq = json.optLong("seekSeq", -1L)
            mainHandler.post {
                CarPlayWebBridge.speaker?.onClientReport(
                    mediaId = mediaId,
                    playing = playing,
                    buffering = buffering,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    ended = ended,
                    clientSeekSeq = seekSeq,
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun handleAction(bodyText: String) {
        if (bodyText.isBlank()) return
        try {
            val json = JSONObject(bodyText)
            val type = json.optString("type", "")
            mainHandler.post {
                val controller = CarPlayWebBridge.uiController
                val speaker = CarPlayWebBridge.speaker
                when (type) {
                    "enableIphoneAudio" -> {
                        speaker?.start()
                    }
                    "disableIphoneAudio" -> {
                        speaker?.stop(resumeHere = true)
                    }
                    "play" -> {
                        if (speaker?.active == true) {
                            val pos = json.optLong("positionMs", -1L).takeIf { it >= 0L }
                            speaker.onClientDirectTransport(play = true, currentPositionMs = pos)
                        } else {
                            val player = CarPlayWebBridge.localPlayerProvider?.invoke()
                            if (player != null) {
                                player.play()
                            } else {
                                controller?.onTogglePlay()
                            }
                            CarPlayWebBridge.updatePlaybackSnapshot()
                        }
                    }
                    "pause" -> {
                        if (speaker?.active == true) {
                            val pos = json.optLong("positionMs", -1L).takeIf { it >= 0L }
                            speaker.onClientDirectTransport(play = false, currentPositionMs = pos)
                        } else {
                            val player = CarPlayWebBridge.localPlayerProvider?.invoke()
                            if (player != null) {
                                player.pause()
                            } else {
                                controller?.onTogglePlay()
                            }
                            CarPlayWebBridge.updatePlaybackSnapshot()
                        }
                    }
                    "togglePlay" -> {
                        controller?.onTogglePlay()
                    }
                    "next" -> {
                        controller?.onNext()
                    }
                    "prev", "previous" -> {
                        controller?.onPrevious()
                    }
                    "seek" -> {
                        val pos = when {
                            json.has("positionMs") -> json.optLong("positionMs", -1L)
                            json.has("param") -> json.optString("param", "").toLongOrNull() ?: -1L
                            else -> -1L
                        }
                        if (pos >= 0L) {
                            mainHandler.post {
                                CarPlayWebBridge.speaker?.takeIf { it.active }?.seekTo(pos)
                                controller?.onSeekTo(pos)
                            }
                        }
                    }
                    "playQueueIndex", "play-index" -> {
                        val idx = json.optInt("index", json.optString("param", "-1").toIntOrNull() ?: -1)
                        if (idx >= 0) controller?.onPlayQueueIndex(idx)
                    }
                    "playSong" -> {
                        val videoId = json.optString("videoId", "")
                        if (videoId.isNotBlank()) {
                            val song = Song(
                                videoId = videoId,
                                title = json.optString("title", ""),
                                artist = json.optString("artist", ""),
                                albumName = json.optString("album", "").ifBlank { null },
                                thumbnailUrl = json.optString("thumbnailUrl", "").ifBlank { null },
                                isVideo = json.optBoolean("isVideo", false),
                            )
                            val shelfTitle = json.optString("shelfTitle", "おすすめ")
                            controller?.onPlaySong(song, shelfTitle)
                        }
                    }
                    "playShelfItem" -> {
                        val item = ShelfItem(
                            title = json.optString("title", ""),
                            subtitle = json.optString("subtitle", ""),
                            thumbnailUrl = json.optString("thumbnailUrl", "").ifBlank { null },
                            videoId = json.optString("videoId", "").ifBlank { null },
                            browseId = json.optString("browseId", "").ifBlank { null },
                            isVideo = json.optBoolean("isVideo", false),
                        )
                        val shelfTitle = json.optString("shelfTitle", "おすすめ")
                        controller?.onPlayShelfItem(item, shelfTitle)
                    }
                    "openDetail" -> {
                        val browseId = json.optString("browseId", "")
                        if (browseId.isNotBlank()) {
                            controller?.onOpenDetail(
                                browseId = browseId,
                                title = json.optString("title", ""),
                                subtitle = json.optString("subtitle", ""),
                                thumbnailUrl = json.optString("thumbnailUrl", "").ifBlank { null },
                            )
                        }
                    }
                    "toggleShuffle" -> controller?.onToggleShuffle()
                    "cycleRepeat" -> controller?.onCycleRepeat()
                    "toggleLike" -> {
                        CarPlayWebBridge.currentSongSnapshot?.let { controller?.onToggleLike(it) }
                    }
                    "setTheme" -> {
                        val modeStr = json.optString("mode", "")
                        val mode = runCatching { ThemeMode.valueOf(modeStr) }.getOrNull()
                        if (mode != null) AppSettings.setThemeMode(mode)
                    }
                    "selectTab" -> {
                        val tab = json.optInt("tab", 0)
                        controller?.onSelectTab(tab)
                    }
                    "setNowPlaying" -> {
                        val open = json.optBoolean("open", false)
                        controller?.onSetNowPlayingOpen(open)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleSearch(query: String): String {
        if (query.isBlank()) return """{"songs":[],"collections":[]}"""
        return runBlocking(Dispatchers.IO) {
            try {
                val results = YtMusicRepository.search(query, SearchFilter.ALL).getOrNull().orEmpty()
                val songsArr = JSONArray()
                val colsArr = JSONArray()
                results.forEach { row ->
                    when (row) {
                        is SearchResult.TopTrack -> {
                            if (songsArr.length() < 25) songsArr.put(songToJson(row.song))
                        }
                        is SearchResult.Track -> {
                            if (songsArr.length() < 25) songsArr.put(songToJson(row.song))
                        }
                        is SearchResult.Browse -> {
                            val item = row.item
                            colsArr.put(
                                shelfItemToJson(
                                    ShelfItem(
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                        videoId = null,
                                        browseId = item.browseId,
                                        isVideo = false,
                                    ),
                                ),
                            )
                        }
                    }
                }
                JSONObject().apply {
                    put("songs", songsArr)
                    put("collections", colsArr)
                }.toString()
            } catch (e: Exception) {
                """{"songs":[],"collections":[],"error":${JSONObject.quote(e.message ?: "error")}}"""
            }
        }
    }

    private fun handleBrowse(browseId: String): String {
        if (browseId.isBlank()) return """{"songs":[],"sections":[]}"""
        return runBlocking(Dispatchers.IO) {
            try {
                val page = YtMusicRepository.browseSongs(browseId).getOrNull()
                val songsArr = JSONArray()
                page?.songs?.forEach { s -> songsArr.put(songToJson(s)) }
                val sectionsArr = JSONArray()
                JSONObject().apply {
                    put("browseId", browseId)
                    put("description", page?.description ?: "")
                    put("songs", songsArr)
                    put("sections", sectionsArr)
                }.toString()
            } catch (e: Exception) {
                """{"songs":[],"sections":[]}"""
            }
        }
    }

    private fun handleArtwork(url: String, out: OutputStream) {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            writeResponse(out, "404 Not Found", "text/plain", ByteArray(0))
            return
        }
        val cached = artworkCache[url]
        if (cached != null) {
            writeResponse(out, "200 OK", cached.contentType, cached.bytes, extraHeaders = mapOf("Cache-Control" to "public, max-age=86400"))
            return
        }
        try {
            val req = Request.Builder().url(url).get().build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    writeResponse(out, "404 Not Found", "text/plain", ByteArray(0))
                    return
                }
                val contentType = resp.header("Content-Type") ?: "image/jpeg"
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.isNotEmpty() && bytes.size < 2_000_000) {
                    artworkCache[url] = CachedArtwork(bytes, contentType)
                }
                writeResponse(out, "200 OK", contentType, bytes, extraHeaders = mapOf("Cache-Control" to "public, max-age=86400"))
            }
        } catch (_: Exception) {
            writeResponse(out, "404 Not Found", "text/plain", ByteArray(0))
        }
    }

    private fun handleLocalAudioStream(
        method: String,
        savedUriStr: String,
        rangeHeader: String?,
        out: OutputStream,
    ): Boolean {
        try {
            val uri = Uri.parse(savedUriStr)
            val context = BitChordApplication.instance
            val afd = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")
            }.getOrNull()

            val file = if (uri.scheme == "file" || afd == null) {
                val p = uri.path
                if (!p.isNullOrBlank()) File(p).takeIf { it.exists() && it.isFile } else null
            } else null

            if (afd == null && file == null) {
                return false
            }

            val totalLength = file?.length() ?: (afd?.length ?: -1L)
            val mimeType = runCatching {
                context.contentResolver.getType(uri)
            }.getOrNull() ?: when {
                savedUriStr.endsWith(".m4a", ignoreCase = true) || savedUriStr.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
                savedUriStr.endsWith(".flac", ignoreCase = true) -> "audio/flac"
                savedUriStr.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                savedUriStr.endsWith(".ogg", ignoreCase = true) || savedUriStr.endsWith(".opus", ignoreCase = true) -> "audio/ogg"
                else -> "audio/mp4"
            }

            var start = 0L
            var end = if (totalLength > 0L) totalLength - 1L else Long.MAX_VALUE
            var isRange = false

            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.substringAfter("bytes=").trim()
                val parts = spec.split("-", limit = 2)
                val sStr = parts.getOrNull(0)?.trim()
                val eStr = parts.getOrNull(1)?.trim()

                if (!sStr.isNullOrEmpty()) {
                    start = sStr.toLongOrNull() ?: 0L
                    if (!eStr.isNullOrEmpty()) {
                        val pe = eStr.toLongOrNull()
                        if (pe != null && pe < end) {
                            end = pe
                        }
                    }
                    isRange = true
                } else if (!eStr.isNullOrEmpty()) {
                    val suffix = eStr.toLongOrNull() ?: 0L
                    if (totalLength > 0L) {
                        start = (totalLength - suffix).coerceAtLeast(0L)
                        end = totalLength - 1L
                        isRange = true
                    }
                }
            }

            if (totalLength > 0L && start >= totalLength) {
                val errHeaders = mapOf("Content-Range" to "bytes */$totalLength")
                writeResponse(out, "416 Range Not Satisfiable", "text/plain", ByteArray(0), errHeaders)
                afd?.close()
                return true
            }

            val contentLength = if (totalLength > 0L) (end - start + 1L) else null
            val statusText = if (isRange && totalLength > 0L) "206 Partial Content" else "200 OK"

            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(statusText).append("\r\n")
            sb.append("Content-Type: ").append(mimeType).append("\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Cache-Control: no-cache\r\n")
            if (contentLength != null) {
                sb.append("Content-Length: ").append(contentLength).append("\r\n")
            }
            if (isRange && totalLength > 0L) {
                sb.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(totalLength).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()

            if (method != "HEAD") {
                val inputStream: InputStream = if (file != null) {
                    java.io.FileInputStream(file)
                } else {
                    afd!!.createInputStream()
                }

                inputStream.use { stream ->
                    if (start > 0L) {
                        var toSkip = start
                        while (toSkip > 0L) {
                            val skipped = stream.skip(toSkip)
                            if (skipped <= 0) break
                            toSkip -= skipped
                        }
                    }
                    val buf = ByteArray(32 * 1024)
                    var bytesRemaining = if (totalLength > 0L) (end - start + 1L) else Long.MAX_VALUE
                    while (bytesRemaining > 0L) {
                        val toRead = min(buf.size.toLong(), bytesRemaining).toInt()
                        val read = stream.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        bytesRemaining -= read
                    }
                    out.flush()
                }
            }
            afd?.close()
            return true
        } catch (_: Exception) {
            return true
        }
    }

    private fun handleAudioStream(
        method: String,
        videoId: String,
        rangeHeader: String?,
        out: OutputStream,
    ) {
        if (videoId.isBlank()) {
            writeResponse(out, "400 Bad Request", "text/plain", "Missing videoId".toByteArray())
            return
        }

        // Check if track is downloaded locally: zero cellular data usage!
        val savedUriStr = Downloads.verifiedSavedUri(videoId)
        if (!savedUriStr.isNullOrBlank()) {
            if (handleLocalAudioStream(method, savedUriStr, rangeHeader, out)) {
                return
            }
        }

        val resolved = runBlocking(Dispatchers.IO) { resolveIosStream(videoId) }
        if (resolved == null) {
            writeResponse(out, "502 Bad Gateway", "text/plain", "Could not resolve audio stream".toByteArray())
            return
        }

        try {
            val reqBuilder = Request.Builder().url(resolved.url)
            resolved.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }
            if (method == "HEAD") {
                reqBuilder.head()
            } else {
                reqBuilder.get()
            }

            Http.client.newCall(reqBuilder.build()).execute().use { upstream ->
                val code = upstream.code
                val statusText = when (code) {
                    206 -> "206 Partial Content"
                    200 -> "200 OK"
                    416 -> "416 Range Not Satisfiable"
                    else -> "$code ${upstream.message.ifBlank { "OK" }}"
                }
                val contentType = upstream.header("Content-Type")?.takeIf { it.isNotBlank() } ?: resolved.mimeType
                val contentLength = upstream.header("Content-Length")
                val contentRange = upstream.header("Content-Range")

                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(statusText).append("\r\n")
                sb.append("Content-Type: ").append(contentType).append("\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Access-Control-Allow-Origin: *\r\n")
                sb.append("Cache-Control: no-cache\r\n")
                if (!contentLength.isNullOrBlank()) {
                    sb.append("Content-Length: ").append(contentLength).append("\r\n")
                }
                if (!contentRange.isNullOrBlank()) {
                    sb.append("Content-Range: ").append(contentRange).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                out.flush()

                if (method != "HEAD") {
                    val patchedPrefix = resolved.patchedPrefix
                    var fileOffset = if (code == 206) {
                        parseRangeStartOffset(contentRange, rangeHeader)
                    } else {
                        0L
                    }
                    upstream.body?.byteStream()?.use { bodyStream ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val read = bodyStream.read(buf)
                            if (read <= 0) break
                            if (patchedPrefix != null && fileOffset < patchedPrefix.size) {
                                val startInPrefix = fileOffset.toInt()
                                val countToPatch = min(read, patchedPrefix.size - startInPrefix)
                                System.arraycopy(patchedPrefix, startInPrefix, buf, 0, countToPatch)
                            }
                            out.write(buf, 0, read)
                            fileOffset += read
                        }
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {
            // Client closed connection mid-stream (normal during iOS Safari range probes or track skips)
        }
    }

    private fun writeResponse(
        out: OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        try {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            if ("Cache-Control" !in extraHeaders) {
                sb.append("Cache-Control: no-store\r\n")
            }
            for ((k, v) in extraHeaders) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) {
                out.write(body)
            }
            out.flush()
        } catch (_: Exception) {
        }
    }

    /**
     * HTTPS TCP Tunnel (`CONNECT host:port HTTP/1.1`) so iPhone 7 / CarPlay can access
     * Google Maps, Apple Maps, and HTTPS websites through Galaxy S25's 5G/4G connection
     * even when connected via Local Direct Wi-Fi (`DIRECT-BC-BitChord`).
     */
    private fun handleConnectTunnel(
        targetAuthority: String,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        val hostPort = targetAuthority.trim()
        val colonIdx = hostPort.lastIndexOf(':')
        val host = if (colonIdx > 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx > 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443
        if (host.isBlank() || host == "127.0.0.1" || host == "localhost") return

        try {
            Socket().use { remote ->
                remote.tcpNoDelay = true
                remote.soTimeout = 60_000
                clientSocket.soTimeout = 60_000
                remote.connect(InetSocketAddress(host, port), 10_000)

                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
                clientOut.flush()

                val remoteIn = remote.getInputStream()
                val remoteOut = remote.getOutputStream()

                val upstreamJob = scope.launch(Dispatchers.IO) {
                    try {
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = clientIn.read(buf)
                            if (r <= 0) break
                            remoteOut.write(buf, 0, r)
                            remoteOut.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { remote.shutdownOutput() }
                    }
                }

                try {
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = remoteIn.read(buf)
                        if (r <= 0) break
                        clientOut.write(buf, 0, r)
                        clientOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { clientSocket.shutdownOutput() }
                    upstreamJob.cancel()
                }
            }
        } catch (_: Exception) {
            runCatching {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                clientOut.flush()
            }
        }
    }

    /**
     * Plain HTTP Proxy handler for non-TLS HTTP requests (`GET http://example.com/... HTTP/1.1`),
     * including iOS captive portal connectivity checks (`http://captive.apple.com/hotspot-detect.html`).
     */
    private fun handleHttpProxyRequest(
        method: String,
        fullUrl: String,
        headers: Map<String, String>,
        clientIn: InputStream,
        out: OutputStream,
    ) {
        try {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength in 1..2_097_152) {
                val buf = ByteArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val r = clientIn.read(buf, total, contentLength - total)
                    if (r <= 0) break
                    total += r
                }
                if (total == contentLength) buf else buf.copyOf(total)
            } else {
                null
            }

            val reqBuilder = Request.Builder().url(fullUrl)
            for ((k, v) in headers) {
                if (k != "host" && k != "proxy-connection" && k != "connection" && k != "content-length") {
                    reqBuilder.header(k, v)
                }
            }
            when (method) {
                "POST", "PUT", "PATCH" -> {
                    val mediaType = headers["content-type"]
                    val reqBody = okhttp3.RequestBody.create(
                        null,
                        bodyBytes ?: ByteArray(0),
                    )
                    if (!mediaType.isNullOrBlank()) {
                        reqBuilder.header("Content-Type", mediaType)
                    }
                    reqBuilder.method(method, reqBody)
                }
                "HEAD" -> reqBuilder.head()
                else -> reqBuilder.get()
            }

            Http.client.newCall(reqBuilder.build()).execute().use { resp ->
                val statusLine = "HTTP/1.1 ${resp.code} ${resp.message.ifBlank { "OK" }}\r\n"
                val sb = StringBuilder(statusLine)
                val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                sb.append("Content-Type: ").append(contentType).append("\r\n")
                val respBytes = resp.body?.bytes() ?: ByteArray(0)
                sb.append("Content-Length: ").append(respBytes.size).append("\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                if (method != "HEAD" && respBytes.isNotEmpty()) {
                    out.write(respBytes)
                }
                out.flush()
            }
        } catch (_: Exception) {
            writeResponse(out, "502 Bad Gateway", "text/plain; charset=utf-8", "Bad Gateway".toByteArray())
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault(pair.substring(0, eq))
                val v = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))
                map[k] = v
            } else {
                map[pair] = ""
            }
        }
        return map
    }
}
