package com.xing.tv.player

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import java.util.concurrent.CopyOnWriteArrayList

/**
 * IjkPlayer 占位实现。
 * 未引入 ijk 原生依赖时：prepare 后上报错误，由 [PlayerKernelManager] 回落到 ExoPlayer。
 * 接入 bibili/ijkplayer AAR 后在此补全 Surface / IjkMediaPlayer 绑定即可。
 */
class IjkPlayerImpl(private val context: Context) : IMediaPlayer {

    companion object {
        private const val TAG = "IjkPlayerImpl"
        /** 是否已集成真实 Ijk 库；当前工程未引入，恒为 false */
        const val AVAILABLE = false
    }

    private val listeners = CopyOnWriteArrayList<PlayerEventListener>()
    private var url: String? = null

    override fun setDisplayContainer(container: ViewGroup) {}

    override fun setDataSource(url: String, headers: Map<String, String>?, mimeType: String?) {
        this.url = url
    }

    override fun prepareAsync() {
        Log.w(TAG, "IjkPlayer not linked; signal error for fallback. url=$url")
        listeners.forEach {
            it.onError(-1, "IjkPlayer 未集成，请改用 ExoPlayer 或接入 ijk 依赖")
        }
    }

    override fun start() {}
    override fun pause() {}
    override fun stop() {}
    override fun seekTo(positionMs: Long) {}
    override fun isPlaying(): Boolean = false
    override fun getCurrentPosition(): Long = 0L
    override fun getDuration(): Long = 0L
    override fun release() { listeners.clear() }
    override fun setSpeed(speed: Float) {}
    override fun addListener(listener: PlayerEventListener) { listeners.addIfAbsent(listener) }
    override fun removeListener(listener: PlayerEventListener) { listeners.remove(listener) }
}
