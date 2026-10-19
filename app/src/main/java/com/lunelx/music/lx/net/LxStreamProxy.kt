package com.lunelx.music.lx.net

import com.lunelx.music.lx.engine.LxLog
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 本地 HTTP 流代理。
 *
 * ## 为什么需要它
 * 音源脚本返回的是各平台直链，直接交给 ExoPlayer 会遇到三个问题：
 * 1. **防盗链**：需要特定 Referer / User-Agent / Cookie，ExoPlayer 无法按曲设置；
 * 2. **URL 过期**：直链通常几十分钟失效，长播单曲或暂停后继续会 403；
 * 3. **跳转链**：部分音源返回 302，且跳转目标仍需带 header。
 *
 * 因此这里起一个 127.0.0.1 上的极简 HTTP 服务，ExoPlayer 只认
 * `http://127.0.0.1:<port>/stream/<token>`，真实地址与 header 全部由本代理持有。
 *
 * ## 工作方式
 * - [register] 登记一个 token → 解析器。解析器是挂起函数，返回真实 URL 与请求头。
 * - ExoPlayer 发 GET 时，代理调用解析器，把上游响应**边读边转发**（不落盘、不全量缓冲），
 *   并透传 `Content-Type` / `Content-Length` / `Content-Range`。
 * - 支持 `Range` 请求（ExoPlayer 拖进度条必需）。
 * - 上游 403/410 时自动重新解析一次（应对直链过期）。
 *
 * ## 生命周期
 * 由 [com.lunelx.music.lx.LxRepository] 在首次使用时启动，进程存活期间常驻。
 */
class LxStreamProxy(
    private val resolve: suspend (LxStreamSpec) -> LxResolvedStream?,
    /**
     * 丢弃某个 token 的直链缓存。
     *
     * 403/410 重试时必须先失效缓存，否则 [resolve] 命中 10 分钟 TTL 直接返回
     * **同一条已经死掉的 URL**，重试等于没做。默认空实现仅为兼容测试替身。
     */
    private val invalidate: (String) -> Unit = {},
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val running = AtomicBoolean(false)

    private val registry = ConcurrentHashMap<String, LxStreamSpec>()

    private val worker = Executors.newCachedThreadPool { r ->
        thread(start = false, name = "lx-stream", isDaemon = true) { r.run() }
    }

    /** 代理是否已启动。 */
    val isRunning: Boolean get() = running.get()

    /** 代理监听的端口；未启动时为 0。 */
    val listenPort: Int get() = port

    /**
     * 启动代理。幂等，重复调用无副作用。
     * 绑定到回环地址，不对外暴露。
     */
    @Synchronized
    fun start() {
        if (running.get()) return
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        thread(name = "lx-stream-accept", isDaemon = true) {
            LxLog.i("LxStreamProxy: listening on 127.0.0.1:$port")
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running.get()) LxLog.w(e, "LxStreamProxy: accept failed")
                    break
                }
                worker.execute { handleClient(client) }
            }
        }
    }

    /** 停止代理并释放端口。 */
    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        registry.clear()
        worker.shutdownNow()
    }

    /**
     * 登记一个可播放条目，返回可直接交给 ExoPlayer 的本地 URL。
     *
     * @param spec 描述这次播放的来源（音源、songmid、期望音质等）
     */
    fun register(spec: LxStreamSpec): String {
        start()
        registry[spec.token] = spec
        return urlFor(spec.token)
    }

    /** 注销条目，释放内存。播放结束时调用。 */
    fun unregister(token: String) {
        registry.remove(token)
    }

    /** 拼出交给 ExoPlayer 的本地地址。 */
    fun urlFor(token: String): String = "http://127.0.0.1:$port/stream/$token"

    // ---------------------------------------------------------------- HTTP

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return writeStatus(socket, 400, "Bad Request")

                val method = parts[0].uppercase()
                val path = parts[1]

                // 读完请求头（我们只关心 Range）
                var rangeHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    return writeStatus(socket, 405, "Method Not Allowed")
                }

                val token = path.substringAfterLast('/').substringBefore('?')
                val spec = registry[token]
                if (spec == null) {
                    LxLog.w("LxStreamProxy: unknown token $token")
                    return writeStatus(socket, 404, "Not Found")
                }

                val resolved = try {
                    runBlocking { resolve(spec) }
                } catch (e: Exception) {
                    LxLog.e(e, "LxStreamProxy: resolve failed for $token")
                    null
                }
                if (resolved == null) {
                    return writeStatus(socket, 502, "Resolve Failed")
                }

                runBlocking { forward(socket, resolved, spec, rangeHeader, headOnly = method == "HEAD") }
            }
        } catch (e: Exception) {
            LxLog.w(e, "LxStreamProxy: client handler error")
        }
    }

    /**
     * 向上游发起请求并把响应转发给客户端。
     * 直链过期（403/410）时重新解析一次。
     */
    private suspend fun forward(
        socket: Socket,
        initial: LxResolvedStream,
        spec: LxStreamSpec,
        rangeHeader: String?,
        headOnly: Boolean,
    ) {
        var resolved = initial
        var attempt = 0
        var redirects = 0
        while (true) {
            val conn = try {
                java.net.URL(resolved.url).openConnection()
            } catch (e: Exception) {
                LxLog.e(e, "LxStreamProxy: bad url ${resolved.url.take(120)}")
                return writeStatus(socket, 502, "Bad Upstream URL")
            }

            (conn as? java.net.HttpURLConnection)?.let { http ->
                // 重定向由下面手动跟随。
                // Android 的 HttpURLConnection **不跟随跨协议重定向**（http → https），
                // 而音源脚本给的直链第一跳往往就是 301 到 https
                // （实测：http://mcp.../kg.php →301→ https://mcp... →302→ kuwo CDN →200 audio/mpeg）。
                // 交给它自动跟就会停在 301，表现为「upstream returned 301」+ 502。
                http.instanceFollowRedirects = false
                http.connectTimeout = CONNECT_TIMEOUT_MS
                http.readTimeout = READ_TIMEOUT_MS
                resolved.headers.forEach { (k, v) -> runCatching { http.setRequestProperty(k, v) } }
                rangeHeader?.let { runCatching { http.setRequestProperty("Range", it) } }
            }

            val status = try {
                conn.connect()
                (conn as? java.net.HttpURLConnection)?.responseCode ?: 200
            } catch (e: Exception) {
                LxLog.w(e, "LxStreamProxy: connect failed attempt=$attempt")
                return writeStatus(socket, 502, "Upstream Connect Failed")
            }

            // 手动跟随重定向（含 http → https，以及相对 Location）
            if (status in 301..308 && status != 304) {
                val location = (conn as? java.net.HttpURLConnection)?.getHeaderField("Location")
                val next = if (!location.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                    runCatching { java.net.URL(java.net.URL(resolved.url), location).toString() }.getOrNull()
                } else null

                runCatching { conn.getInputStream().close() }
                runCatching { (conn as? java.net.HttpURLConnection)?.disconnect() }

                if (next != null) {
                    redirects++
                    LxLog.d("LxStreamProxy: redirect $status -> ${next.take(100)}")
                    // 只换地址，请求头（Referer/Cookie/Range）保留，重定向后仍然需要
                    resolved = LxResolvedStream(next, resolved.headers)
                    continue
                }
                LxLog.w("LxStreamProxy: unreachable redirect status=$status location=$location")
            }

            // 直链过期，重新解析一次
            if ((status == 403 || status == 410 || status == 404) && attempt == 0) {
                LxLog.w("LxStreamProxy: upstream $status, re-resolving ${resolved.url.take(80)}")
                attempt++
                val again = runCatching { resolveForRetry(spec) }.getOrNull()
                if (again != null) {
                    resolved = again
                    continue
                }
            }

            if (status !in 200..299 && status != 206) {
                LxLog.w("LxStreamProxy: upstream returned $status")
                return writeStatus(socket, 502, "Upstream $status")
            }

            val out = socket.getOutputStream()
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(if (status == 206) "206 Partial Content" else "200 OK").append("\r\n")

            val contentType = conn.contentType?.substringBefore(';')?.trim()
                ?: guessContentType(resolved.url)
            sb.append("Content-Type: ").append(contentType).append("\r\n")

            val length = conn.contentLengthLong
            if (length > 0) sb.append("Content-Length: ").append(length).append("\r\n")

            // 透传 206 必需的头部
            (conn as? java.net.HttpURLConnection)?.let { http ->
                http.getHeaderField("Content-Range")?.let {
                    sb.append("Content-Range: ").append(it).append("\r\n")
                }
                http.getHeaderField("Accept-Ranges")?.let {
                    sb.append("Accept-Ranges: ").append(it).append("\r\n")
                } ?: sb.append("Accept-Ranges: bytes\r\n")
            }

            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()

            if (headOnly) {
                runCatching { conn.getInputStream().close() }
                return
            }

            // 边读边转发，不缓冲整首歌
            var stream: InputStream? = null
            try {
                stream = conn.getInputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                LxLog.d("LxStreamProxy: streamed $total bytes for ${resolved.url.take(60)}")
            } catch (e: Exception) {
                // 客户端主动断开（用户切歌）属正常，不记错误
                LxLog.d("LxStreamProxy: stream interrupted after error=${e.message}")
            } finally {
                runCatching { stream?.close() }
                runCatching { conn.getInputStream().close() }
                runCatching { (conn as? java.net.HttpURLConnection)?.disconnect() }
            }
            return
        }
    }

    /**
     * 直链过期后重新解析**当前这首**。
     *
     * 曾经的 bug：这里忽略了传入的 spec，改从 `registry.firstOrNull()` 随便取一个，
     * 于是 B 歌的直链 403 时会去解析 A 歌，拿到 200 后把 **A 的音频** 当成 B 播出去
     * （registry 只增不减，`releaseStream` 没有调用点，所以 firstOrNull 几乎总是别人）。
     */
    private suspend fun resolveForRetry(spec: LxStreamSpec): LxResolvedStream? {
        // 必须先失效缓存：否则 resolve() 命中 10 分钟 TTL，原样返回那条已死的 URL。
        invalidate(spec.token)
        return resolve(spec)
    }

    private fun writeStatus(socket: Socket, code: Int, text: String) {
        runCatching {
            val out: OutputStream = socket.getOutputStream()
            val body = text.toByteArray()
            val header = "HTTP/1.1 $code $text\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(body)
            out.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > MAX_LINE) return null
            sb.append(c.toChar())
        }
    }

    private fun guessContentType(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".flac") -> "audio/flac"
            clean.endsWith(".mp3") -> "audio/mpeg"
            clean.endsWith(".m4a") || clean.endsWith(".aac") -> "audio/mp4"
            clean.endsWith(".ogg") || clean.endsWith(".opus") -> "audio/ogg"
            clean.endsWith(".wav") -> "audio/wav"
            clean.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            else -> "audio/mpeg"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_LINE = 8192

        /** 手动跟随重定向的最大跳数，防止脚本/上游给出循环重定向。 */
        private const val MAX_REDIRECTS = 5

        /** URL 解码辅助，供调用方处理脚本返回的转义地址。 */
        fun decode(url: String): String = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    }
}

/**
 * 一次播放请求的描述。token 是稳定的（由 uid + 音质决定），
 * 这样同一首歌重复播放时 ExoPlayer 缓存也能命中。
 */
data class LxStreamSpec(
    val token: String,
    val uid: String,
    val source: String,
    val songmid: String,
    val quality: String,
    val extra: Map<String, String> = emptyMap(),
)

/** 解析结果：真实地址 + 需要附加的请求头。 */
data class LxResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
