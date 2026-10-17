package com.xing.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 多配置源管理。
 *
 * TVBox 客户端普遍支持同时维护多条接口地址（多仓/多配置），本项目也保留这一习惯：
 * 首页站点标签之外，接口本身也可增删切换，切换后立即重新拉取配置。
 *
 * 当前生效的地址仍由 [Prefs.configUrl] 承载，[SourceStore] 只负责「有哪些可选源」。
 */
object SourceStore {

    data class Item(val name: String, val url: String)

    private const val FILE = "xing_sources"
    private const val KEY = "list"
    private const val MAX = 20

    private val gson = Gson()
    private lateinit var sp: android.content.SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun all(): List<Item> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<Item>>(raw, object : TypeToken<List<Item>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 追加或覆盖同地址条目，并把它设为当前生效源 */
    fun add(name: String, url: String): Item {
        val item = Item(name.ifBlank { defaultName(url) }, url.trim())
        val next = (listOf(item) + all().filterNot { it.url == item.url }).take(MAX)
        sp.edit().putString(KEY, gson.toJson(next)).apply()
        Prefs.configUrl = item.url
        return item
    }

    fun remove(url: String) {
        sp.edit().putString(KEY, gson.toJson(all().filterNot { it.url == url })).apply()
    }

    fun clear() = sp.edit().remove(KEY).apply()

    /** 当前生效地址是否已在列表中 */
    fun contains(url: String): Boolean = all().any { it.url == url }

    private fun defaultName(url: String): String = runCatching {
        val host = java.net.URL(url).host
        host.removePrefix("www.")
    }.getOrDefault("接口源")
}
