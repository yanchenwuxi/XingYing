package com.xing.tv.api

import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.SiteKind
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodFilter
import com.xing.tv.spider.TvJsSource
import com.xing.tv.spider.SpiderJarSource

interface Source {
    val site: TvConfig.Site
    fun home(page: Int = 1): PageResult
    fun category(categoryId: String, page: Int): PageResult
    fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult =
        category(categoryId, page)
    val supportsFilter: Boolean get() = false
    fun categories(): List<Category>
    fun detail(vodId: String): VodDetail?
    fun search(keyword: String, page: Int): PageResult
    val searchable: Boolean get() = site.searchable == 1
}

object SourceFactory {

    /**
     * 源实例缓存。
     *
     * JS / jar / py 源的重建代价很高：JS 要起 QuickJS 线程并装载 20+ 个预置库，
     * jar 要新建 DexClassLoader。之前每次取数都 new 一个，首页翻一页就重建一次，
     * 内存和 CPU 都扛不住，还会泄漏引擎线程。这里按「站点标识 + 接口指纹」缓存，
     * 换了接口或改了站点地址自然会拿到新实例。
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Source>()

    private fun fingerprint(site: TvConfig.Site): String =
        "${site.key}@${site.type}@${site.api}@${site.ext}@${site.jar}"

    @Throws(UnsupportedOperationException::class)
    fun create(site: TvConfig.Site): Source {
        val fp = fingerprint(site)
        cache[fp]?.let { return it }
        val created = build(site)
        // 并发下可能重复构建一次，保留先入的实例，另一份直接释放
        val prev = cache.putIfAbsent(fp, created)
        if (prev != null) {
            (created as? com.xing.tv.spider.TvJsSource)?.destroy()
            return prev
        }
        return created
    }

    /** 切换接口 / 退出时释放引擎线程与本机代理注册 */
    fun clear() {
        cache.values.forEach { src ->
            runCatching { (src as? com.xing.tv.spider.TvJsSource)?.destroy() }
        }
        cache.clear()
        runCatching { com.xing.tv.py.PyBridge.unloadAll() }
    }

    private fun build(site: TvConfig.Site): Source = when (site.kind) {
        SiteKind.CMS_XML -> XmlCmsSource(site)
        SiteKind.T4 -> T4Source(site)
        SiteKind.PY -> com.xing.tv.py.PySource(site)
        SiteKind.CMS_JSON -> JsonCmsSource(site)
        SiteKind.SPIDER_JAR -> SpiderJarSource(site, ConfigHolder.spiderJar.orEmpty())
        SiteKind.JS -> TvJsSource(site)
        else -> throw UnsupportedOperationException(
            "「${site.name}」是${site.kind.label}，本版本暂不支持"
        )
    }

    fun createOrNull(site: TvConfig.Site): Source? =
        if (site.supported) create(site) else null
}
