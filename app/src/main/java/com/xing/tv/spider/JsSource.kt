package com.xing.tv.spider

import android.util.Log
import com.xing.tv.api.JsonCmsSource
import com.xing.tv.api.Source
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodFilter
import com.xing.tv.model.VodItem
import com.xing.tv.net.Http
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * JS / drpy 源（type=2，或 type=3 + api/ext 为 .js）。
 *
 * 完整 drpy 运行时依赖浏览器级 JS 引擎与大量宿主 API，本应用不内嵌整套运行时。
 * 可行兜底：
 * 1. api 本身是非 .js 的 http 采集口 → 走 JsonCms
 * 2. 规则脚本（ext）里能解析出 macCMS `api.php/provide/vod` 类 host → 按 JSON 采集驱动
 * 3. 下载到的正文已是 spider 风格 JSON → 直接解析
 * 否则给出明确错误，避免静默空白。
 */
class JsSource(override val site: TvConfig.Site) : Source {

    private val cmsFallback: JsonCmsSource? by lazy { resolveCmsFallback() }
    private var ruleCache: String? = null

    override val supportsFilter: Boolean
        get() = cmsFallback?.supportsFilter == true || site.filterable == 1

    override fun home(page: Int): PageResult {
        cmsFallback?.let { return it.home(page) }
        val text = fetchPrimary()
        val list = SpiderJson.parseList(text, page)
        if (list.items.isNotEmpty()) return list
        throw failHint()
    }

    override fun category(categoryId: String, page: Int): PageResult {
        cmsFallback?.let { return it.category(categoryId, page) }
        return home(page)
    }

    override fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult {
        cmsFallback?.let { return it.categoryFiltered(categoryId, page, filter) }
        return category(categoryId, page)
    }

    override fun categories(): List<Category> {
        cmsFallback?.let { return it.categories() }
        return runCatching { SpiderJson.parseCategories(fetchPrimary()) }.getOrDefault(emptyList())
            .ifEmpty { site.categories.map { Category(it, it) } }
    }

    override fun detail(vodId: String): VodDetail? {
        cmsFallback?.let { return it.detail(vodId) }
        return null
    }

    override fun search(keyword: String, page: Int): PageResult {
        cmsFallback?.let { return it.search(keyword, page) }
        return PageResult(emptyList(), page, page)
    }

    private fun failHint(): IllegalStateException =
        IllegalStateException(
            "「${site.name}」是 drpy/JS 规则源，需要完整 JS 运行时。" +
                "请改用配置中的 macCMS（type=0/1）站点，例如非凡、爱坤、量子等采集源。"
        )

    private fun fetchPrimary(): String {
        val urls = listOf(site.ext, site.api).map { it.trim() }.filter {
            it.startsWith("http://") || it.startsWith("https://")
        }
        for (u in urls) {
            val body = runCatching {
                Http.get(u, headers = site.headerMap.ifEmpty { null }, api = true)
            }.getOrNull() ?: continue
            if (body.isBlank()) continue
            // 脚本文件：缓存规则文本，尝试抽出 JSON 片段
            if (u.endsWith(".js", true) || body.trimStart().startsWith("//") ||
                body.contains("var rule") || body.contains("export default")
            ) {
                ruleCache = body
                extractJsonBlob(body)?.let { return it }
                continue
            }
            if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) return body
        }
        return ""
    }

    private fun resolveCmsFallback(): JsonCmsSource? {
        // 1) api 直接是采集口
        val api = site.api.trim()
        if ((api.startsWith("http://") || api.startsWith("https://")) &&
            !api.endsWith(".js", true) && !api.contains(".js?")
        ) {
            return JsonCmsSource(site)
        }
        // 2) 从规则脚本解析 host / 采集地址
        val rule = ruleText() ?: return null
        val host = extractHost(rule) ?: return null
        val cmsApi = when {
            host.contains("api.php") || host.contains("provide/vod") -> host
            host.endsWith("/") -> host + "api.php/provide/vod/"
            else -> "$host/api.php/provide/vod/"
        }
        if (!cmsApi.startsWith("http")) return null
        Log.i(TAG, "js rule → cms fallback: ${site.key} → $cmsApi")
        val proxy = site.copy(api = cmsApi, type = 1)
        return JsonCmsSource(proxy)
    }

    private fun ruleText(): String? {
        ruleCache?.let { return it }
        val u = site.ext.trim().ifEmpty { site.api.trim() }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null
        return runCatching {
            Http.get(u, headers = site.headerMap.ifEmpty { null }, api = true).also { ruleCache = it }
        }.getOrNull()
    }

    private fun extractHost(rule: String): String? {
        val patterns = listOf(
            Pattern.compile("""host\s*[:=]\s*['"](https?://[^'"]+)['"]""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""['"]url['"]\s*:\s*['"](https?://[^'"]*api\.php[^'"]*)['"]""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(https?://[^\s'"]+/api\.php/provide/vod[^\s'"]*)""", Pattern.CASE_INSENSITIVE)
        )
        for (p in patterns) {
            val m = p.matcher(rule)
            if (m.find()) return m.group(1)?.trim()?.trimEnd(',')
        }
        return null
    }

    private fun extractJsonBlob(js: String): String? {
        val i = js.indexOf('{')
        if (i < 0) return null
        // 仅当看起来像列表结果时才返回
        val slice = js.substring(i).take(200_000)
        if ("\"list\"" in slice || "\"class\"" in slice || "\"data\"" in slice) {
            return slice
        }
        return null
    }

    companion object {
        private const val TAG = "JsSource"
    }
}
