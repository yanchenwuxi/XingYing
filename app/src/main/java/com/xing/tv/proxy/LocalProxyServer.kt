package com.xing.tv.proxy

import android.util.Log
import com.google.gson.JsonObject
import com.xing.tv.api.ConfigHolder
import com.xing.tv.py.PyBridge
import com.xing.tv.py.PySource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 本地代理服务：为 py 源的 localProxy 约定提供 HTTP 入口。
 *
 * 不少 py 源的 playerContent 返回 parse=1，url 形如
 * `http://127.0.0.1:9978/local?ids=xxx&flag=yyy`，期望应用侧在本机开一个服务，
 * 再由脚本的 localProxy() 动态产出内容（m3u8 / JSON / 二进制均可）。
 * 缺少这个服务时，这类源完全无法播放。
 *
 * 仅监听回环地址，不对外网开放。
 */
object LocalProxyServer {

    private const val TAG = "LocalProxy"

    /** TVBox 生态常用的本地代理端口（优先尝试） */
    const val DEFAULT_PORT = 9978

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** 实际监听端口；0 表示未启动。偏好端口被占时会回落到系统分配的随机端口 */
    @Volatile
    var actualPort: Int = 0
        private set

    /** 当前正在播放的 py 站点，localProxy 请求优先分发到它 */
    @Volatile
    private var siteKey: String = ""

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "local-proxy-worker").apply { isDaemon = true }
    }

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    /** 记录当前播放的 py 站点，供 localProxy 请求分发使用 */
    fun setSiteKey(key: String) {
        siteKey = key
    }

    /**
     * 启动本机代理。
     * @return 实际监听端口；0 表示失败。
     *
     * 设备上 9978 常被其他应用占用（双栈通配会同时占掉 IPv4 loopback），
     * 因此先试偏好端口，失败则回落到系统分配的随机端口，由调用方改写 URL。
     */
    @Synchronized
    fun ensureStarted(preferred: Int = DEFAULT_PORT): Int {
        if (isRunning) return actualPort
        for (candidate in intArrayOf(preferred, 0)) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                // 只监听回环地址，避免暴露到局域网
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate))
                serverSocket = ss
                actualPort = ss.localPort
                Thread({ acceptLoop(ss) }, "local-proxy-accept").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "started on 127.0.0.1:${ss.localPort}")
                return actualPort
            } catch (t: Throwable) {
                Log.w(TAG, "bind $candidate failed: $t")
            }
        }
        actualPort = 0
        return 0
    }

    /**
     * 把指向本机代理的 URL 改写到实际端口。
     * 脚本里通常硬编码 9978，但实际可能落在随机端口。
     */
    fun rewriteToActual(url: String): String {
        if (!isRunning || actualPort == 0 || actualPort == DEFAULT_PORT) return url
        return url.replace(
            Regex("""(?i)(https?://(?:127\.0\.0\.1|localhost)):\d+"""),
            "$1:$actualPort"
        )
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        actualPort = 0
    }

    private fun acceptLoop(ss: ServerSocket) {
        try {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { handle(socket) }
            }
        } catch (t: Throwable) {
            if (!ss.isClosed) Log.w(TAG, "accept loop ended: $t")
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            runCatching {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                if (requestLine.isEmpty()) return
                // 跳过请求头；localProxy 只用 URL，不解析 body
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    line = reader.readLine()
                }
                respond(s.getOutputStream(), requestLine)
            }.onFailure {
                Log.w(TAG, "handle failed: $it")
                runCatching { writeText(s.getOutputStream(), 502, "text/plain", "bad request") }
            }
        }
    }

    private fun respond(out: OutputStream, requestLine: String) {
        // 例：GET /local?ids=1&flag=a HTTP/1.1
        val parts = requestLine.split(" ")
        if (parts.size < 2) return writeText(out, 400, "text/plain", "bad request")
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")

        val params = JsonObject()
        params.addProperty("path", path)
        if (query.isNotEmpty()) {
            query.split("&").forEach { kv ->
                if (kv.isEmpty()) return@forEach
                val i = kv.indexOf('=')
                val k = URLDecoder.decode(if (i < 0) kv else kv.substring(0, i), "UTF-8")
                val v = if (i < 0) "" else URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                params.addProperty(k, v)
            }
        }

        val result = dispatch(params.toString())
            ?: return writeText(out, 404, "text/plain", "localProxy not available")
        if (result.status == 404 && result.body.isEmpty()) {
            return writeText(out, 404, "text/plain", "not found")
        }
        writeBinary(out, result.status, result.contentType, result.body, result.headers)
    }

    private fun dispatch(paramsJson: String): com.xing.tv.py.LocalProxyResult? {
        // 优先当前播放站点；未设置时遍历已加载的 py 源
        val keys = buildList {
            if (siteKey.isNotEmpty()) add(siteKey)
            addAll(PyBridge.loadedKeys().filter { it != siteKey })
        }
        for (key in keys) {
            val site = ConfigHolder.siteOf(key) ?: continue
            val r = runCatching { PySource(site).localProxy(paramsJson) }.getOrNull()
            if (r != null && !(r.status == 404 && r.body.isEmpty())) return r
        }
        return null
    }

    private fun writeText(out: OutputStream, status: Int, type: String, body: String) {
        writeBinary(out, status, type, body.toByteArray(Charsets.UTF_8), emptyMap())
    }

    private fun writeBinary(
        out: OutputStream,
        status: Int,
        type: String,
        body: ByteArray,
        headers: Map<String, String>
    ) {
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
