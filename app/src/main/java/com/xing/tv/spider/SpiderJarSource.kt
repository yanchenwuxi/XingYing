package com.xing.tv.spider

import android.util.Log
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.Source
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodFilter
import com.xing.tv.net.Http
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class SpiderJarSource(
    override val site: TvConfig.Site,
    private val globalSpider: String = ""
) : Source {

    private val lock = Any()
    private var spider: Any? = null
    private var homeCache: String? = null

    override val supportsFilter: Boolean get() = site.filterable == 1

    override fun home(page: Int): PageResult {
        val json = if (page <= 1) invokeHome(true).also { homeCache = it }
        else invokeCategory("", page.toString(), false, "")
        val list = SpiderJson.parseList(json, page)
        if (list.items.isEmpty() && page <= 1) {
            val cats = SpiderJson.parseCategories(json)
            if (cats.isNotEmpty()) return category(cats.first().id, 1)
        }
        return list
    }

    override fun category(categoryId: String, page: Int): PageResult =
        categoryFiltered(categoryId, page, VodFilter())

    override fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult {
        val json = invokeCategory(categoryId, page.toString(), !filter.isEmpty(), buildExtend(filter))
        return SpiderJson.parseList(json, page)
    }

    override fun categories(): List<Category> {
        val text = homeCache ?: invokeHome(true).also { homeCache = it }
        val fromHome = SpiderJson.parseCategories(text)
        if (fromHome.isNotEmpty()) return fromHome
        return site.categories.map { Category(it, it) }
    }

    override fun detail(vodId: String): VodDetail? =
        SpiderJson.parseDetail(invokeDetail(listOf(vodId)))

    override fun search(keyword: String, page: Int): PageResult =
        SpiderJson.parseList(invokeSearch(keyword, false), page)

    fun player(flag: String, id: String): String =
        SpiderJson.parsePlayer(invokePlayer(flag, id))

    private fun invokeHome(filter: Boolean): String =
        call("homeContent", arrayOf(Boolean::class.javaPrimitiveType), arrayOf(filter))
            ?: call("homeVideoContent", emptyArray(), emptyArray()) ?: "{}"

    private fun invokeCategory(tid: String, pg: String, filter: Boolean, extend: String): String =
        call(
            "categoryContent",
            arrayOf(String::class.java, String::class.java, Boolean::class.javaPrimitiveType, String::class.java),
            arrayOf(tid, pg, filter, extend)
        ) ?: "{}"

    private fun invokeDetail(ids: List<String>): String =
        call("detailContent", arrayOf(List::class.java), arrayOf(ids)) ?: "{}"

    private fun invokeSearch(key: String, quick: Boolean): String =
        call("searchContent", arrayOf(String::class.java, Boolean::class.javaPrimitiveType), arrayOf(key, quick))
            ?: call(
                "searchContent",
                arrayOf(String::class.java, Boolean::class.javaPrimitiveType, String::class.java),
                arrayOf(key, quick, "")
            ) ?: "{}"

    private fun invokePlayer(flag: String, id: String): String =
        call(
            "playerContent",
            arrayOf(String::class.java, String::class.java, List::class.java),
            arrayOf(flag, id, emptyList<String>())
        ) ?: "{}"

    private fun call(name: String, types: Array<Class<*>?>, args: Array<Any?>): String? =
        synchronized(lock) {
            val inst = ensureSpider() ?: return null
            try {
                val method = findMethod(inst.javaClass, name, types) ?: return null
                method.invoke(inst, *args)?.toString()
            } catch (t: Throwable) {
                Log.w(TAG, "spider.$name failed: ${t.message}")
                null
            }
        }

    private fun findMethod(cls: Class<*>, name: String, types: Array<Class<*>?>): Method? {
        runCatching { cls.getMethod(name, *types) }.getOrNull()?.let { return it }
        return cls.methods.firstOrNull { it.name == name && it.parameterTypes.size == types.size }
    }

    private fun ensureSpider(): Any? {
        spider?.let { return it }
        val jarUrl = resolveJarUrl()
        if (jarUrl.isEmpty()) {
            Log.w(TAG, "no spider jar for ${site.key}")
            return null
        }
        val loader = loadJar(jarUrl) ?: return null
        val clazz = tryLoadClass(loader, resolveClassName()) ?: return null
        val inst = runCatching { clazz.getDeclaredConstructor().newInstance() }.getOrNull()
            ?: runCatching { @Suppress("DEPRECATION") clazz.newInstance() }.getOrNull()
            ?: return null
        runCatching {
            // 部分爬虫需要 siteKey
            findMethod(clazz, "siteKey", arrayOf(String::class.java))
                ?.invoke(inst, site.key)
            findMethod(clazz, "setSiteKey", arrayOf(String::class.java))
                ?.invoke(inst, site.key)
            findMethod(clazz, "init", arrayOf(String::class.java))
                ?.invoke(inst, site.ext.ifEmpty { "{}" })
            // 双参 init(ext, siteKey) 兜底
            findMethod(clazz, "init", arrayOf(String::class.java, String::class.java))
                ?.invoke(inst, site.ext.ifEmpty { "{}" }, site.key)
        }.onFailure { Log.w(TAG, "spider init failed ${site.key}: ${it.message}") }
        spider = inst
        return inst
    }

    private fun resolveJarUrl(): String {
        fun clean(u: String): String = u.substringBefore(";").trim()
        val fromSite = clean(site.jar)
        if (fromSite.isNotEmpty()) return fromSite
        if (globalSpider.isNotEmpty()) return clean(globalSpider)
        return clean(ConfigHolder.spiderJar.orEmpty())
    }

    private fun resolveClassName(): String {
        val api = site.api.trim()
        return when {
            api.startsWith("csp_") -> api.removePrefix("csp_")
            api.contains('.') -> api
            else -> api
        }
    }

    private fun tryLoadClass(loader: ClassLoader, shortName: String): Class<*>? {
        val candidates = listOf(
            shortName,
            "com.github.catvod.spider.$shortName",
            "com.github.catvod.$shortName",
            "com.github.catvod.spider.merger.$shortName"
        )
        for (name in candidates) {
            runCatching { Class.forName(name, true, loader) }.getOrNull()?.let { return it }
        }
        Log.w(TAG, "class not found: $shortName")
        return null
    }

    private fun loadJar(url: String): ClassLoader? = runCatching {
        cacheLoaders[url]?.let { return it }
        val dir = Http.cacheDir("spider_jar")
        val file = File(dir, Integer.toHexString(url.hashCode()).toString() + ".jar")
        if (!file.exists() || file.length() == 0L) Http.download(url, file, api = true)
        val optimized = File(dir, "opt_${file.nameWithoutExtension}").apply { mkdirs() }
        val loader = DexClassLoader(file.absolutePath, optimized.absolutePath, null, javaClass.classLoader)
        cacheLoaders[url] = loader
        loader
    }.onFailure { Log.w(TAG, "load jar failed: $url → ${it.message}") }.getOrNull()

    /**
     * 筛选条件 → 爬虫的 extend JSON。
     * 必须走 JSON 序列化：手工拼串在筛选项带引号/反斜杠时会产出非法 JSON，
     * 爬虫解析直接抛异常，表现就是「点了筛选没反应」。
     */
    private fun buildExtend(filter: VodFilter): String {
        if (filter.isEmpty()) return ""
        val obj = com.google.gson.JsonObject()
        if (filter.area.isNotEmpty()) obj.addProperty("area", filter.area)
        if (filter.year.isNotEmpty()) obj.addProperty("year", filter.year)
        if (filter.cls.isNotEmpty()) obj.addProperty("class", filter.cls)
        return obj.toString()
    }

    companion object {
        private const val TAG = "SpiderJar"
        private val cacheLoaders = ConcurrentHashMap<String, ClassLoader>()
    }
}
