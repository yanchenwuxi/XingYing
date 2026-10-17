package com.xing.tv

import android.app.Application
import com.xing.tv.data.HistoryStore
import com.xing.tv.data.LocalConfigStore
import com.xing.tv.data.Prefs
import com.xing.tv.data.SearchHistory
import com.xing.tv.data.SourceStore
import com.xing.tv.net.Http

/**
 * 全局入口：初始化网络、配置仓库、历史收藏存储。
 * Python 运行时（Chaquopy）按需启动，见 py.PyBridge。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Http.init(this)
        Prefs.init(this)
        HistoryStore.init(this)
        SourceStore.init(this)
        SearchHistory.init(this)
        LocalConfigStore.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
