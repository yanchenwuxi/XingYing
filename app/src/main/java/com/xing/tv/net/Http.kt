package com.xing.tv.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 统一网络层：TVBox 接口/采集站常见 http/https 请求。
 * - 自动处理 gzip、常见编码（UTF-8 / GBK）
 * - 统一 UA，避免部分采集站拒收默认 UA
 * - 持久化 Cookie：不少采集站/播放接口依赖 Cookie 做鉴权或防盗链
 */
object Http {

    private const val TAG = "Http"

    /**
     * 浏览器 UA：用于抓取网页（嗅探播放地址时需要伪装成浏览器）。
     */
    private const val UA_WEB =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /**
     * TVBox 接口 UA。
     *
     * 相当多的配置站与采集站按 UA 分流：浏览器 UA 回一个 HTML 落地页，
     * 只有 UA 里带 `okhttp` 才回真正的 JSON/XML 数据——TVBox 客户端本身就是
     * okhttp 发的请求，这些站点就是照着它做的识别。
     *
     * 之前统一用浏览器 UA，结果这类站点的配置被当成 HTML 解析失败，
     * 表现为「接口无法识别」。所以配置与采集接口一律走 [get] 的 `api = true`，
     * 抓网页的嗅探仍用 [UA_WEB]。
     */
    private const val UA_TVBOX = "okhttp/4.12.0"

    lateinit var client: OkHttpClient
        private set

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cookieJar(PersistentCookieJar(appContext))
            .build()
    }

    /** 缓存目录；供配置磁盘缓存复用 */
    fun cacheDir(name: String): File =
        File(appContext.cacheDir, name).apply { mkdirs() }

    /**
     * 同步 GET，返回解码后的文本；失败抛异常。
     * @param api true 表示这是 TVBox 配置/采集接口请求，用 [UA_TVBOX]；
     *            false 表示抓网页，用 [UA_WEB]
     */
    @Throws(Exception::class)
    fun get(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        api: Boolean = false
    ): String {
        val builder = Request.Builder().url(normalizeUrl(url)).header("User-Agent", userAgent(api))
        if (referer != null) builder.header("Referer", referer)
        headers?.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            return decode(body, resp.header("Content-Type"))
        }
    }

    /** GET 原始字节（代理转发 / 二进制资源）。 */
    @Throws(Exception::class)
    fun getBytes(
        url: String,
        headers: Map<String, String>? = null,
        api: Boolean = false
    ): ByteArray {
        val builder = Request.Builder().url(normalizeUrl(url)).header("User-Agent", userAgent(api))
        headers?.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            return body
        }
    }

    /** GET 下载到缓存文件。 */
    @Throws(Exception::class)
    fun download(url: String, target: File, api: Boolean = false) {
        val builder = Request.Builder().url(normalizeUrl(url)).header("User-Agent", userAgent(api))
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                val buf = ByteArray(8192)
                val stream = resp.body?.byteStream() ?: return
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun normalizeUrl(url: String): String =
        runCatching { url.toHttpUrl().toString() }.getOrDefault(url)

    private fun userAgent(api: Boolean) = if (api) UA_TVBOX else UA_WEB

    private fun decode(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) return ""
        val fromHeader = contentType
            ?.substringAfter("charset=", "")
            ?.substringBefore(";")
            ?.trim()
            ?.uppercase()
        val declared = runCatching { Charset.forName(fromHeader ?: "UTF-8") }.getOrNull()
        // 采集站有大量 GBK 站点：先按声明/UTF-8 解码，出现替换字符则回退 GBK。
        val utf8 = declared ?: Charsets.UTF_8
        val text = String(bytes, utf8)
        if (fromHeader == null && text.contains('�')) {
            return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(text)
        }
        return text
    }

    /**
     * 简易持久化 CookieJar。
     *
     * 只按 host 保存 name=value，不记录过期时间与安全属性：
     * 采集站的 Cookie 多为短期会话标识，粗粒度保存即可满足鉴权/防盗链需求，
     * 同时避免引入复杂的状态同步问题。
     */
    private class PersistentCookieJar(context: Context) : CookieJar {

        private val prefs: SharedPreferences =
            context.getSharedPreferences("http_cookies", Context.MODE_PRIVATE)

        /** 内存镜像，避免每次请求都读盘 */
        private val memory = ConcurrentHashMap<String, MutableSet<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            val set = memory.getOrPut(host) { mutableSetOf() }
            val editor = prefs.edit()
            cookies.forEach { c ->
                set.removeAll { it.name == c.name }
                set.add(c)
                editor.putString("$host#${c.name}", c.toString())
            }
            editor.apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val set = memory.getOrPut(host) {
                // 首次从磁盘恢复
                val restored = mutableSetOf<Cookie>()
                prefs.all.forEach { (k, v) ->
                    if (k.startsWith("$host#") && v is String) {
                        runCatching { Cookie.parse(url, v) }.getOrNull()?.let { restored.add(it) }
                    }
                }
                restored
            }
            // 会话 Cookie 的 expiresAt 是远期常量，直接按时间比较即可
            val now = System.currentTimeMillis()
            return set.filter { it.expiresAt > now }
        }
    }
}
