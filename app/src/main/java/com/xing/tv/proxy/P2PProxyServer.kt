package com.xing.tv.proxy

import android.util.Log
import com.xing.tv.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机 P2P/旁路代理：把播放地址改写为 127.0.0.1，便于统一加头、嗅探或后续接 P2P 分片。
 * 与 [LocalProxyServer]（py localProxy）互补，不互相替代。
 */
class P2PProxyServer(private val preferredPort: Int = 9090) {

    companion object {
        private const val TAG = "P2PProxyServer"
        @Volatile
        private var instance: P2PProxyServer? = null

        fun get(): P2PProxyServer = instance ?: synchronized(this) {
            instance ?: P2PProxyServer().also { instance = it }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    var actualPort: Int = preferredPort
        private set

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort))
                serverSocket = ss
                actualPort = ss.localPort
                Log.i(TAG, "P2P proxy listening on 127.0.0.1:$actualPort")
                while (isRunning.get()) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "P2P proxy failed", e)
            } finally {
                stop()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return@launch
                // 读完请求头
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }
                val target = parseTarget(requestLine)
                val output = BufferedOutputStream(socket.getOutputStream())
                if (target.isNullOrBlank()) {
                    writeText(output, 400, "text/plain", "missing target")
                    return@launch
                }
                try {
                    val bytes = Http.getBytes(target, api = false)
                    writeBytes(output, 200, guessContentType(target), bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "proxy fetch failed: ${e.message}")
                    writeText(output, 502, "text/plain", "upstream: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "client error", e)
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun parseTarget(requestLine: String): String? {
        // GET /proxy?target=... HTTP/1.1
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val path = parts[1]
        val q = path.substringAfter("target=", "").substringBefore(" ").substringBefore("&")
        if (q.isEmpty()) return null
        return URLDecoder.decode(q, "UTF-8")
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun writeText(out: BufferedOutputStream, code: Int, type: String, body: String) {
        writeBytes(out, code, type, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(out: BufferedOutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun guessContentType(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            u.contains(".mp4") -> "video/mp4"
            u.contains(".ts") -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        Log.i(TAG, "P2P proxy stopped")
    }

    fun isRunning(): Boolean = isRunning.get()

    fun getProxyUrl(originalUrl: String): String {
        if (!isRunning.get()) return originalUrl
        val encoded = java.net.URLEncoder.encode(originalUrl, "UTF-8")
        return "http://127.0.0.1:$actualPort/proxy?target=$encoded"
    }
}
