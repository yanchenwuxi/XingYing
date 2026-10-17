package com.xing.tv.api

import com.xing.tv.model.TvConfig

object ConfigHolder {

    @Volatile
    var config: TvConfig = TvConfig()
        private set

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var spiderJar: String? = null
        private set

    fun update(config: TvConfig, url: String, spider: String? = null) {
        if (url != currentUrl) {
            // 切换接口后丢弃已加载的源实例（JS 引擎 / Python 脚本 / jar 类加载器），避免串站与泄漏
            runCatching { com.xing.tv.api.SourceFactory.clear() }
        }
        this.config = config
        this.currentUrl = url
        if (spider != null) this.spiderJar = spider.ifBlank { null }
    }

    fun siteOf(key: String): TvConfig.Site? = config.sites.firstOrNull { it.key == key }

    val sites: List<TvConfig.Site> get() = config.usableSites
    val allSites: List<TvConfig.Site> get() = config.sites
    val diag: TvConfig.Diag get() = config.diag
}
