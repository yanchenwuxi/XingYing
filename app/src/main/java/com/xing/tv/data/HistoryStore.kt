package com.xing.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 播放历史与收藏：以 JSON 数组形式存 SharedPreferences，
 * 单条数据量小、条数有限，无需引入数据库。
 */
object HistoryStore {

    data class Record(
        val siteKey: String,
        val vodId: String,
        val name: String,
        val pic: String,
        val episodeName: String,
        val playUrl: String,
        val lineIndex: Int,
        val episodeIndex: Int,
        val positionMs: Long,
        val updatedAt: Long,
        /** 总时长；用于换算进度百分比。旧记录里该字段缺失，Gson 会给 0 */
        val durationMs: Long = 0
    ) {
        /** 播放进度百分比；总长未知时返回 -1（界面据此隐藏进度条） */
        val progressPercent: Int
            get() = if (durationMs > 0L && positionMs > 0L)
                ((positionMs * 100) / durationMs).toInt().coerceIn(1, 100) else -1
    }

    private const val FILE = "xing_records"
    private const val KEY_HISTORY = "history"
    private const val KEY_FAVORITE = "favorite"
    private const val MAX = 100

    private val gson = Gson()
    private lateinit var sp: android.content.SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ---------- 历史 ----------

    fun history(): List<Record> = load(KEY_HISTORY)

    fun favorites(): List<Record> = load(KEY_FAVORITE)

    fun saveHistory(record: Record) = put(KEY_HISTORY, record)

    fun saveFavorite(record: Record) = put(KEY_FAVORITE, record)

    fun removeFavorite(vodId: String) {
        val list = load(KEY_FAVORITE).filterNot { it.vodId == vodId }
        sp.edit().putString(KEY_FAVORITE, gson.toJson(list)).apply()
    }

    fun removeHistory(vodId: String) {
        val list = load(KEY_HISTORY).filterNot { it.vodId == vodId }
        sp.edit().putString(KEY_HISTORY, gson.toJson(list)).apply()
    }

    fun isFavorite(vodId: String): Boolean = load(KEY_FAVORITE).any { it.vodId == vodId }

    fun clearHistory() = sp.edit().remove(KEY_HISTORY).apply()

    fun clearFavorites() = sp.edit().remove(KEY_FAVORITE).apply()

    fun positionOf(vodId: String): Long =
        load(KEY_HISTORY).firstOrNull { it.vodId == vodId }?.positionMs ?: 0L

    /** 继续观看：按最近观看时间倒序，只保留有播放进度的点播记录 */
    fun continueWatching(limit: Int = 10): List<Record> =
        history().asSequence()
            .filter { it.playUrl.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .toList()

    // ---------- 迁移 ----------

    /** 导出为 JSON 文本 */
    fun exportJson(): String =
        gson.toJson(mapOf("history" to history(), "favorite" to favorites()))

    /**
     * 从 JSON 文本导入，与现有记录合并去重。
     * @return 导入条数（历史 + 收藏）
     */
    fun importJson(text: String): Int {
        val root = runCatching { gson.fromJson(text, com.google.gson.JsonObject::class.java) }
            .getOrNull() ?: return 0

        fun read(key: String): List<Record> {
            val arr = root.getAsJsonArray(key) ?: return emptyList()
            return runCatching {
                gson.fromJson<List<Record>>(arr, object : TypeToken<List<Record>>() {}.type)
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

        val h = read("history")
        val f = read("favorite")
        h.forEach { saveHistory(it) }
        f.forEach { saveFavorite(it) }
        return h.size + f.size
    }

    private fun load(key: String): List<Record> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<Record>>(raw, object : TypeToken<List<Record>>() {}.type)
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun put(key: String, record: Record) {
        val list = load(key).filterNot {
            it.vodId == record.vodId && it.episodeIndex == record.episodeIndex && it.lineIndex == record.lineIndex
        }
        val next = (listOf(record) + list).take(MAX)
        sp.edit().putString(key, gson.toJson(next)).apply()
    }
}
