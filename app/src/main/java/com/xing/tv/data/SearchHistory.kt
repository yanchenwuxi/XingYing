package com.xing.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 搜索历史：本地保存最近若干条关键词，去重、最新在前。 */
object SearchHistory {

    private const val FILE = "xing_search"
    private const val KEY = "history"
    private const val MAX = 12

    private val gson = Gson()
    private lateinit var sp: android.content.SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun all(): List<String> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun push(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val next = (listOf(kw) + all().filterNot { it == kw }).take(MAX)
        sp.edit().putString(KEY, gson.toJson(next)).apply()
    }

    fun remove(keyword: String) {
        sp.edit().putString(KEY, gson.toJson(all().filterNot { it == keyword })).apply()
    }

    fun clear() = sp.edit().remove(KEY).apply()

    /** 导出为 JSON 文本，便于在设备间迁移 */
    fun export(): String = gson.toJson(all())

    /** 导入并合并（去重），返回合并后条数 */
    fun importFrom(text: String): Int {
        val incoming = runCatching {
            gson.fromJson<List<String>>(text, object : TypeToken<List<String>>() {}.type)
                ?: emptyList<String>()
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        if (incoming.isEmpty()) return all().size
        val merged = (incoming + all()).distinct().take(MAX)
        sp.edit().putString(KEY, gson.toJson(merged)).apply()
        return merged.size
    }
}
