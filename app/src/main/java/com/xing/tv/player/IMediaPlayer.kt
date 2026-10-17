package com.xing.tv.player

import android.view.ViewGroup

/** 播放器事件监听 */
interface PlayerEventListener {
    fun onPrepared() {}
    fun onVideoSizeChanged(width: Int, height: Int) {}
    fun onError(errorCode: Int, message: String) {}
    fun onCompletion() {}
    fun onBufferingUpdate(percent: Int) {}
    fun onIsPlayingChanged(isPlaying: Boolean) {}
}

/** 播放器内核统一抽象 */
interface IMediaPlayer {
    fun setDisplayContainer(container: ViewGroup)
    fun setDataSource(url: String, headers: Map<String, String>? = null, mimeType: String? = null)
    fun prepareAsync()
    fun start()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun isPlaying(): Boolean
    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun release()
    fun setSpeed(speed: Float)
    fun addListener(listener: PlayerEventListener)
    fun removeListener(listener: PlayerEventListener)
}

/** 支持的播放器内核 */
enum class PlayerKernelType(val label: String) {
    EXOPLAYER("ExoPlayer"),
    IJKPLAYER("IjkPlayer"),
    SYSTEM_MEDIA("系统播放器");

    companion object {
        fun fromName(name: String?): PlayerKernelType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: EXOPLAYER
    }
}
