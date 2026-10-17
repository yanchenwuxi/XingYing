package com.xing.tv.data

import android.content.Context
import android.content.SharedPreferences

/** 轻量键值存储：接口地址、播放偏好等。 */
object Prefs {

    private const val FILE = "xing_prefs"
    private const val KEY_CONFIG_URL = "config_url"
    private const val KEY_LAST_SITE = "last_site_key"

    // 播放偏好
    private const val KEY_AUTO_SWITCH = "pref_auto_switch"
    private const val KEY_DEFAULT_SPEED = "pref_default_speed"
    private const val KEY_RESUME = "pref_resume"
    private const val KEY_SKIP_INTRO = "pref_skip_intro"
    private const val KEY_SKIP_OUTRO = "pref_skip_outro"
    private const val KEY_SUB_TOKEN = "pref_sub_token"
    private const val KEY_LIVE_SOURCE = "live_source_index"
    private const val KEY_PLAYER_KERNEL = "pref_player_kernel"

    // 个性化（对齐影视仓「个性化设置」页）
    private const val KEY_GRID_COLUMNS = "pref_grid_columns"
    private const val KEY_SEARCH_GRID = "pref_search_grid"
    private const val KEY_SEARCH_EXACT = "pref_search_exact"
    private const val KEY_INCOGNITO = "pref_incognito"
    private const val KEY_CONFIG_CACHE = "pref_config_cache"
    private const val KEY_AUTO_LANDSCAPE = "pref_auto_landscape"
    private const val KEY_MINI_PROGRESS = "pref_mini_progress"
    private const val KEY_FULLSCREEN_STYLE = "pref_fullscreen_style"
    private const val KEY_BOTTOM_HISTORY = "pref_bottom_history"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 当前生效的接口地址（空串表示尚未配置） */
    var configUrl: String
        get() = sp.getString(KEY_CONFIG_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_CONFIG_URL, value).apply()

    var lastSiteKey: String
        get() = sp.getString(KEY_LAST_SITE, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_SITE, value).apply()

    /** 播放失败时自动换源重试 */
    var autoSwitchSource: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()

    /** 默认倍速 */
    var defaultSpeed: Float
        get() = sp.getFloat(KEY_DEFAULT_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(KEY_DEFAULT_SPEED, value).apply()

    /** 续播：记住上次播放进度 */
    var resumeEnabled: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME, value).apply()

    /** 起播后自动跳过的片头秒数（0 表示不跳） */
    var skipIntroSec: Int
        get() = sp.getInt(KEY_SKIP_INTRO, 0)
        set(value) = sp.edit().putInt(KEY_SKIP_INTRO, value).apply()

    /** 距片尾不足该秒数时自动跳到片尾（0 表示不跳） */
    var skipOutroSec: Int
        get() = sp.getInt(KEY_SKIP_OUTRO, 0)
        set(value) = sp.edit().putInt(KEY_SKIP_OUTRO, value).apply()

    /**
     * 在线字幕 Token（ASSRT 免费 API 的个人密钥）。
     * 字幕下载走用户自己的配额，应用不内置任何共享密钥。
     */
    var subtitleToken: String
        get() = sp.getString(KEY_SUB_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_SUB_TOKEN, value.trim()).apply()

    /** 选中的直播源下标 */
    var liveSourceIndex: Int
        get() = sp.getInt(KEY_LIVE_SOURCE, 0)
        set(value) = sp.edit().putInt(KEY_LIVE_SOURCE, value).apply()

    /** 播放器内核：EXOPLAYER / IJKPLAYER / SYSTEM_MEDIA */
    var playerKernel: String
        get() = sp.getString(KEY_PLAYER_KERNEL, "EXOPLAYER") ?: "EXOPLAYER"
        set(value) = sp.edit().putString(KEY_PLAYER_KERNEL, value).apply()

    // ---------- 个性化设置（影视仓那套逐项搬过来，开关都真实生效） ----------

    /** 首页/分类海报列数：2 / 3 / 4 */
    var gridColumns: Int
        get() = sp.getInt(KEY_GRID_COLUMNS, 3).coerceIn(2, 5)
        set(value) = sp.edit().putInt(KEY_GRID_COLUMNS, value.coerceIn(2, 5)).apply()

    /** 搜索结果用网格展示（关掉是单列列表） */
    var searchGrid: Boolean
        get() = sp.getBoolean(KEY_SEARCH_GRID, false)
        set(value) = sp.edit().putBoolean(KEY_SEARCH_GRID, value).apply()

    /** 搜索模式：true=精确匹配（结果片名必须含关键词），false=接口原样返回 */
    var searchExact: Boolean
        get() = sp.getBoolean(KEY_SEARCH_EXACT, false)
        set(value) = sp.edit().putBoolean(KEY_SEARCH_EXACT, value).apply()

    /** 全局无痕：不写入观看历史（收藏不受影响） */
    var incognito: Boolean
        get() = sp.getBoolean(KEY_INCOGNITO, false)
        set(value) = sp.edit().putBoolean(KEY_INCOGNITO, value).apply()

    /** 接口缓存：关掉后每次进首页都重新拉配置 */
    var configCacheEnabled: Boolean
        get() = sp.getBoolean(KEY_CONFIG_CACHE, true)
        set(value) = sp.edit().putBoolean(KEY_CONFIG_CACHE, value).apply()

    /** 播放页自动横屏（影视仓「播放页自动横屏」） */
    var autoLandscape: Boolean
        get() = sp.getBoolean(KEY_AUTO_LANDSCAPE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_LANDSCAPE, value).apply()

    /** 迷你进度条：控制条收起时底部留一条细进度 */
    var miniProgress: Boolean
        get() = sp.getBoolean(KEY_MINI_PROGRESS, true)
        set(value) = sp.edit().putBoolean(KEY_MINI_PROGRESS, value).apply()

    /** 全屏样式：LANDSCAPE / PORTRAIT / AUTO */
    var fullscreenStyle: String
        get() = sp.getString(KEY_FULLSCREEN_STYLE, "AUTO") ?: "AUTO"
        set(value) = sp.edit().putString(KEY_FULLSCREEN_STYLE, value).apply()

    /** 首页底部悬浮播放记录条（继续观看的悬浮入口） */
    var bottomHistory: Boolean
        get() = sp.getBoolean(KEY_BOTTOM_HISTORY, true)
        set(value) = sp.edit().putBoolean(KEY_BOTTOM_HISTORY, value).apply()

    /** 播放偏好导出为一行可读文本，便于排查 */
    fun describe(): String =
        "自动换源=${if (autoSwitchSource) "开" else "关"}  默认倍速=${defaultSpeed}x  " +
            "续播=${if (resumeEnabled) "开" else "关"}  跳片头=${skipIntroSec}s  跳片尾=${skipOutroSec}s  内核=$playerKernel"
}
