package com.xing.tv.spider

import android.util.Log
import com.xing.tv.App
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.Source
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodFilter
import com.xing.tv.spider.js.JarLoader
import com.xing.tv.spider.js.JsHttp
import com.xing.tv.spider.js.JsRuntime
import com.xing.tv.spider.js.LocalProxy
import java.io.File

/**
 * TVBox / 影视仓体系的 JS 源（type=2，或 type=3 且 api/ext 指向 .js）。
 *
 * 与旧兜底实现的区别：这里是真的跑 JS——内嵌 QuickJS 引擎，宿主函数按
 * TVBox 约定注入（req / joinUrl / pdfh / pdfa / pd / md5 / base64 / local …），
 * 源的 `homeContent / categoryContent / detailContent / searchContent` 直接调用。
 *
 * 脚本来源三种写法都认：
 * 1. `http(s)://…/x.js` 直链下载
 * 2. `js:./js/x.js`、`./js/x.js` 从爬虫包（jar）里取
 * 3. api 只写名字（如 `x.js`），按爬虫包里的 `js/<name>`、`<name>` 逐个碰
 */
class TvJsSource(override val site: TvConfig.Site) : Source {

    private val lock = Any()
    private var rt: JsRuntime? = null

    /** 装载失败原因：给界面一句人话，不静默空白 */
    @Volatile
    var lastError: String = ""
        private set

    private var homeCache: String? = null

    override val supportsFilter: Boolean
        get() = site.filterable == 1

    // ---------------------------------------------------------------- 生命周期

    private fun runtime(): JsRuntime? {
        rt?.let { return it }
        synchronized(lock) {
            rt?.let { return it }
            val ctx = App.instance
            val runtime = JsRuntime(ctx, site.key)
            return try {
                runtime.prepare()
                val code = loadScript()
                if (code.isBlank()) {
                    lastError = "找不到 JS 脚本（api=${site.api}）"
                    runtime.close()
                    return null
                }
                val shape = runtime.loadSpider(code)
                if (shape == "none") {
                    lastError = runtime.lastError.ifBlank { "JS 脚本装载失败" }
                    runtime.close()
                    return null
                }
                // 部分源的 init 用来设 host / token，失败不影响后续调用
                runCatching { runtime.invoke("init", initExt()) }
                rt = runtime
                proxyRegistry[site.key] = this
                ensureProxyService()
                runtime
            } catch (t: Throwable) {
                lastError = t.message ?: "JS 引擎启动失败"
                Log.e(TAG, "js runtime failed: ${site.key}", t)
                runCatching { runtime.close() }
                null
            }
        }
    }

    private fun initExt(): String {
        // ext 若是脚本地址本身，就别当参数塞给 init
        val ext = site.ext.trim()
        return if (ext.startsWith("http") || ext.endsWith(".js") || ext.startsWith("./")) "" else ext
    }

    private fun loadScript(): String {
        val raw = site.api.trim()
        val api = raw.removePrefix("js:").trim()

        // 1) 远程脚本
        if (api.startsWith("http://") || api.startsWith("https://")) {
            val body = runCatching { JsHttp.get(api, null, 15000) }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }

        // 2) 爬虫包里的脚本
        val spec = site.jar.ifBlank { ConfigHolder.spiderJar.orEmpty() }
        if (spec.isNotBlank()) {
            val pkg: File = runCatching { JarLoader.ensure(spec) }.getOrNull() ?: return ""
            val candidates = buildList {
                add(api)
                if (api.startsWith("./")) add(api.removePrefix("./"))
                if (api.startsWith("/")) add(api.removePrefix("/"))
                val base = api.substringAfterLast('/')
                add("js/$base")
                add(base)
                add("js/${site.key}.js")
                add("${site.key}.js")
            }.filter { it.isNotBlank() }.distinct()
            for (path in candidates) {
                val text = runCatching { JarLoader.text(pkg, path) }.getOrNull().orEmpty()
                if (text.isNotBlank()) return text
            }
        }

        // 3) 内置资源里同名脚本（配置里偶尔直接写 assets 名）
        if (api.isNotBlank() && !api.startsWith("http")) {
            val name = api.removePrefix("./").substringAfterLast('/')
            runCatching {
                App.instance.assets.open("js/$name").bufferedReader().use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    // ---------------------------------------------------------------- Source

    private fun call(name: String, vararg args: Any?): String {
        val runtime = runtime() ?: return ""
        val r = runtime.invoke(name, *args)
        if (r.isBlank() && runtime.lastError.isNotBlank()) lastError = runtime.lastError
        return r
    }

    override fun home(page: Int): PageResult {
        if (page > 1) return category("", page)
        val json = call("homeContent", true).ifBlank { call("homeVideoContent") }.also { homeCache = it }
        val list = SpiderJson.parseList(json, page)
        if (list.items.isEmpty() && page <= 1) {
            // homeContent 只给分类、列表另走分类接口的源（常见于 drpy）
            val cats = SpiderJson.parseCategories(json)
            if (cats.isNotEmpty()) return category(cats.first().id, 1)
        }
        return list
    }

    override fun category(categoryId: String, page: Int): PageResult =
        categoryFiltered(categoryId, page, VodFilter())

    override fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult {
        val json = call("categoryContent", categoryId, page.toString(), !filter.isEmpty(), filter.params())
        return SpiderJson.parseList(json, page)
    }

    override fun categories(): List<Category> {
        val text = homeCache ?: call("homeContent", true).ifBlank { call("homeVideoContent") }
            .also { homeCache = it }
        val fromJs = SpiderJson.parseCategories(text)
        if (fromJs.isNotEmpty()) return fromJs
        return site.categories.map { Category(it, it) }
    }

    override fun detail(vodId: String): VodDetail? {
        val json = call("detailContent", listOf(vodId))
        return SpiderJson.parseDetail(json)
    }

    override fun search(keyword: String, page: Int): PageResult {
        val json = call("searchContent", keyword, false)
        return SpiderJson.parseList(json, page)
    }

    /** 播放直链：详情里的 playUrl 常常还需要源自己解析一次 */
    fun player(flag: String, id: String): String =
        SpiderJson.parsePlayer(call("playerContent", flag, id, emptyList<String>()))

    // ---------------------------------------------------------------- 本机代理

    /**
     * 脚本自己拼的播放地址（getProxy / js2Proxy）指回 127.0.0.1，
     * 播放器只认 http，所以由本机小服务转一道，把请求交回脚本的 proxy()。
     * 服务只绑回环地址，不对外网开放。
     */
    private fun ensureProxyService() {
        LocalProxy.setHandler { _, params ->
            val key = params["key"].orEmpty()
            val target = proxyRegistry[key] ?: this
            target.proxyResult(params)
        }
        LocalProxy.start()
    }

    /** 把脚本 proxy() 的返回值翻成 HTTP 响应体 */
    fun proxyResult(params: Map<String, String>): LocalProxy.Result? {
        val runtime = rt ?: return null
        val text = runtime.invoke("proxy", params)
        if (text.isBlank()) return null
        return runCatching {
            val el = com.google.gson.JsonParser.parseString(text)
            if (el.isJsonArray) {
                val arr = el.asJsonArray
                fun at(i: Int): com.google.gson.JsonElement? =
                    if (i < arr.size()) arr.get(i) else null
                val mime = at(1)?.takeIf { !it.isJsonNull }?.asString
                    ?: "application/octet-stream"
                val body = at(2)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val base64 = at(4)?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val bytes = if (base64 == 1) {
                    android.util.Base64.decode(body, android.util.Base64.DEFAULT)
                } else {
                    body.toByteArray()
                }
                LocalProxy.Result(mime, bytes)
            } else {
                LocalProxy.Result(
                    params["mime"] ?: "application/octet-stream",
                    text.toByteArray()
                )
            }
        }.getOrNull()
    }

    fun destroy() {
        synchronized(lock) {
            runCatching { rt?.close() }
            rt = null
        }
    }

    companion object {
        private const val TAG = "TvJsSource"

        /** 代理请求按 key 找回对应源：同一份配置里 js 源常有多个 */
        private val proxyRegistry = java.util.concurrent.ConcurrentHashMap<String, TvJsSource>()
    }
}
