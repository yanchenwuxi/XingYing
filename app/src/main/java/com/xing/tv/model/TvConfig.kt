package com.xing.tv.model

/**
 * TVBox 配置协议模型（依据公开的配置格式实现）：
 * 顶层字段 sites / parses / lives / wallpaper / urls(多仓)
 */
data class TvConfig(
    val sites: List<Site> = emptyList(),
    val parses: List<Parse> = emptyList(),
    val lives: List<Live> = emptyList(),
    val wallpaper: String = "",
    val urls: List<String> = emptyList(),
    val diag: Diag = Diag.EMPTY
) {
    /**
     * 本应用真正能驱动的点播站点。
     * 支持 macCMS（0/1）、T4（4）、Python（3+py_*）、jar 爬虫（csp_*）与 js 源。
     */
    val usableSites: List<Site> get() = sites.filter { it.supported }

    /**
     * @param type 0=XML采集 1=JSON采集 2=js源 3=脚本源(py/csp) 4=T4聚合
     * @param header 站点级自定义请求头（JSON 字符串，如 {"Cookie":"...","Referer":"..."}）
     */
    data class Site(
        val key: String,
        val name: String,
        val type: Int,
        val api: String,
        val ext: String = "",
        val jar: String = "",
        val searchable: Int = 1,
        val quickSearch: Int = 0,
        val filterable: Int = 0,
        val categories: List<String> = emptyList(),
        val playerType: Int = -1,
        val header: String = ""
    ) {
        /**
         * Python 源判定：
         * - 经典写法 api=py_xxx + ext=脚本URL
         * - api 直接是 .py 地址
         * - type=3 且 api 非 csp_*，ext 指向 .py
         */
        val isPy: Boolean
            get() {
                if (type != 3) return false
                if (api.startsWith("csp_")) return false
                if (api.startsWith("py_")) return true
                if (api.endsWith(".py") || api.contains(".py?")) return true
                if (api.startsWith("http") && api.contains(".py")) return true
                if (ext.endsWith(".py") || ext.contains(".py?") ||
                    (ext.startsWith("http") && ext.contains(".py"))
                ) return true
                return false
            }

        /** drpy / 规则 js：type=2，或 type=3 且 api/ext 指向 .js */
        val isJs: Boolean
            get() {
                if (type == 2) return true
                if (api.startsWith("csp_")) return false
                if (isPy) return false
                fun looksJs(s: String): Boolean {
                    val x = s.lowercase()
                    return x.endsWith(".js") || x.contains(".js?") || x.contains("drpy")
                }
                if (looksJs(api)) return true
                if (type == 3 && looksJs(ext)) return true
                return false
            }

        val isScript: Boolean get() = type == 3

        /** api 是否是可直接请求的 http(s) 地址；`csp_Xxx` 这类爬虫类名不是 */
        private val isHttpApi: Boolean
            get() = api.startsWith("http://") || api.startsWith("https://")

        /**
         * 站点类型判定。
         * type=3：py → Python；js/drpy → JS；csp_* 及其它 → jar 爬虫。
         */
        val kind: SiteKind
            get() = when {
                type == 0 -> SiteKind.CMS_XML
                type == 1 -> SiteKind.CMS_JSON
                type == 4 -> SiteKind.T4
                type == 2 -> SiteKind.JS
                type == 3 -> when {
                    isPy -> SiteKind.PY
                    isJs -> SiteKind.JS
                    else -> SiteKind.SPIDER_JAR
                }
                isHttpApi -> SiteKind.CMS_JSON
                else -> SiteKind.UNKNOWN
            }

        /** 本应用能否驱动该站点 */
        val supported: Boolean get() = kind.supported

        /** 解析 header 字段；格式非法时返回空表，不影响主流程 */
        val headerMap: Map<String, String>
            get() = parseHeader(header)

        companion object {
            private val headerCache =
                java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

            fun parseHeader(raw: String): Map<String, String> {
                if (raw.isBlank()) return emptyMap()
                return headerCache.getOrPut(raw) {
                    runCatching {
                        val o = com.google.gson.JsonParser.parseString(raw).asJsonObject
                        o.entrySet().mapNotNull { (k, v) ->
                            if (v.isJsonPrimitive) k to v.asString else null
                        }.toMap()
                    }.getOrDefault(emptyMap())
                }
            }
        }
    }

    /** 解析口：type 0=webview嗅探 1=json接口 2=json扩展 3=聚合 */
    data class Parse(
        val name: String,
        val url: String,
        val type: Int = 0
    )

    data class Live(val name: String, val url: String, val type: Int = 0) {
        /**
         * 地址指向本机回环。
         *
         * 不少公开配置把直播源写成 `http://127.0.0.1:xxxx/iptv.m3u`，
         * 那是 TVBox 客户端自己起的内置代理，本应用不提供该服务，必然连不上。
         * 提前识别出来，才能把「解析失败」换成准确的说明。
         */
        val isLoopback: Boolean get() = isLoopbackUrl(url)
    }

    /**
     * 配置可用性诊断。
     *
     * 很多公开配置是纯 jar 爬虫源（上百个 `csp_*` 站点）。这类配置对本应用
     * 无法使用点播，但其中的直播、解析口仍然可用。与其让用户面对空白页面
     * 反复重试，不如把「为什么不能用、还剩什么能用」一次说清楚。
     */
    data class Diag(
        val totalSites: Int = 0,
        val supportedSites: Int = 0,
        val spiderJar: Int = 0,
        val jsSource: Int = 0,
        val unknownKind: Int = 0,
        val lives: Int = 0,
        val localLives: Int = 0,
        val parses: Int = 0
    ) {
        /** 点播完全不可用 */
        val noUsableSite: Boolean get() = supportedSites == 0 && totalSites > 0

        /** 真正能连上的直播源数；地址指向回环地址的不算（依赖客户端内置代理） */
        val usableLives: Int get() = lives - localLives

        /** 直播源全部依赖客户端内置代理 */
        val livesAllLocal: Boolean get() = lives > 0 && localLives == lives

        /** 人话摘要，用于接口源列表与提示弹窗 */
        fun brief(): String = buildString {
            append("$totalSites 个站点")
            if (spiderJar > 0) append(" · $spiderJar 个 jar 爬虫")
            if (jsSource > 0) append(" · $jsSource 个 js 源")
            if (lives > 0) {
                append(" · $lives 个直播源")
                if (livesAllLocal) append("（依赖内置代理）")
            }
        }

        /** 点播不可用时的完整解释 */
        fun explain(): String = buildString {
            append("该配置共 $totalSites 个站点")
            if (unknownKind > 0) append("，其中 $unknownKind 个类型未知无法驱动")
            if (spiderJar > 0) append("，jar 爬虫 $spiderJar 个")
            if (jsSource > 0) append("，js 源 $jsSource 个")
            append("。\n\n可用部分：")
            append(
                when {
                    usableLives > 0 -> "直播源 $usableLives 个（直播页可正常使用）。"
                    livesAllLocal ->
                        "直播源 $lives 个，但地址指向客户端内置代理（127.0.0.1），本版本不提供该服务。"
                    else -> "无可用点播站点与直播源。"
                }
            )
            if (parses > 0) append("\n另含解析口 $parses 个。")
        }

        companion object {
            val EMPTY = Diag()
        }
    }
}

/** URL 是否指向本机回环地址 */
internal fun isLoopbackUrl(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
    // URI.getHost() 对 IPv6 字面量返回带方括号的 [::1]，先去掉再比较
    val h = host.removePrefix("[").removeSuffix("]")
    return h.equals("127.0.0.1", ignoreCase = true) ||
        h.equals("localhost", ignoreCase = true) ||
        h == "::1"
}

/** 站点类型；决定能否被本应用驱动 */
enum class SiteKind(val label: String, val supported: Boolean) {
    CMS_XML("XML 采集", true),
    CMS_JSON("JSON 采集", true),
    T4("T4 聚合", true),
    PY("Python 源", true),
    SPIDER_JAR("jar 爬虫", true),
    JS("js 源", true),
    UNKNOWN("未知类型", false)
}
