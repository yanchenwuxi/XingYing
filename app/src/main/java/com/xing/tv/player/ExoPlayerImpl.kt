package com.xing.tv.player

import android.content.Context
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Media3 ExoPlayer 包装，实现 [IMediaPlayer]。
 * 容器内优先复用已有 [PlayerView]，否则动态添加一个。
 */
class ExoPlayerImpl(private val context: Context) : IMediaPlayer {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val listeners = CopyOnWriteArrayList<PlayerEventListener>()
    private var pendingUrl: String? = null
    private var pendingHeaders: Map<String, String>? = null
    private var pendingMime: String? = null
    private var prepared = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    if (!prepared) {
                        prepared = true
                        listeners.forEach { it.onPrepared() }
                    }
                }
                Player.STATE_ENDED -> listeners.forEach { it.onCompletion() }
                Player.STATE_BUFFERING -> {
                    val p = player ?: return
                    val buffered = p.bufferedPercentage
                    listeners.forEach { it.onBufferingUpdate(buffered) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listeners.forEach { it.onError(error.errorCode, error.message ?: error.errorCodeName) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            listeners.forEach { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listeners.forEach { it.onIsPlayingChanged(isPlaying) }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        return ExoPlayer.Builder(context).build().also { exo ->
            player = exo
            exo.addListener(playerListener)
            playerView?.player = exo
        }
    }

    override fun setDisplayContainer(container: ViewGroup) {
        val existing = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<PlayerView>()
            .firstOrNull()
        playerView = existing ?: PlayerView(context).also { pv ->
            pv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            container.removeAllViews()
            container.addView(pv)
        }
        playerView?.player = ensurePlayer()
    }

    override fun setDataSource(url: String, headers: Map<String, String>?, mimeType: String?) {
        pendingUrl = url
        pendingHeaders = headers
        pendingMime = mimeType
        prepared = false
    }

    override fun prepareAsync() {
        val url = pendingUrl ?: return
        val exo = ensurePlayer()
        val builder = MediaItem.Builder().setUri(url)
        pendingMime?.takeIf { it.isNotBlank() }?.let { builder.setMimeType(it) }
        // Media3 对自定义请求头通过 MediaSourceFactory；此处先落地 URI，头信息由上层 Http 层处理
        exo.setMediaItem(builder.build())
        exo.prepare()
    }

    override fun start() {
        ensurePlayer().playWhenReady = true
    }

    override fun pause() {
        player?.playWhenReady = false
    }

    override fun stop() {
        player?.stop()
        prepared = false
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun getCurrentPosition(): Long = player?.currentPosition ?: 0L

    override fun getDuration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L

    override fun release() {
        playerView?.player = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        playerView = null
        listeners.clear()
        prepared = false
    }

    override fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }

    override fun addListener(listener: PlayerEventListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: PlayerEventListener) {
        listeners.remove(listener)
    }

    /** 暴露底层实例，便于 PlayerActivity 渐进迁移（音轨/字幕等） */
    fun exoPlayer(): ExoPlayer? = player
}
