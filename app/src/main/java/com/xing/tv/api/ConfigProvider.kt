package com.xing.tv.api

import com.xing.tv.data.Prefs
import com.xing.tv.model.TvConfig

/**
 * 配置获取的唯一入口。
 *
 * 首页与直播页都需要配置，若各写一份「asset:// 与 http:// 的分支处理」迟早会分叉，
 * 所以集中到这里：入口负责地址解析、缓存刷新与回退，落盘与解析交给 [ConfigLoader]。
 */
object ConfigProvider {

    const val ASSET_PREFIX = "asset://"
    const val ASSET_DEMO = "asset://demo.json"

    /** 当前生效地址；空表示尚未配置接口 */
    fun activeUrl(): String = Prefs.configUrl

    /**
     * 拉取配置。
     * @param url 为空时取当前生效地址
     * @param force 忽略磁盘缓存重新拉取（下拉刷新时用）
     */
    fun fetch(url: String = activeUrl(), force: Boolean = true): TvConfig {
        val cfg = if (url.startsWith(ASSET_PREFIX)) {
            val text = com.xing.tv.App.instance.assets
                .open(url.removePrefix(ASSET_PREFIX))
                .bufferedReader().use { it.readText() }
            ConfigLoader.loadFromText(text)
        } else {
            ConfigLoader.load(url, force = force)
        }
        ConfigHolder.update(cfg, url, ConfigLoader.lastSpiderJar)
        return cfg
    }
}
