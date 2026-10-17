package com.xing.tv.player

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 播放器内核工厂与热切换管理器。
 * - 记录当前 URL / 进度，切换内核后 seek 恢复
 * - ExoPlayer 失败时可自动回落到 Ijk（若未集成再回落系统播放器）
 */
class PlayerKernelManager(private val context: Context) {

    companion object {
        private const val TAG = "PlayerKernelManager"
    }

    private var currentKernelType: PlayerKernelType = PlayerKernelType.EXOPLAYER
    private var currentPlayer: IMediaPlayer? = null
    private var currentContainer: ViewGroup? = null

    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String>? = null
    private var currentMime: String? = null
    private var lastPlaybackPosition: Long = 0L
    private var fallingBack = false

    private val listeners = CopyOnWriteArrayList<PlayerEventListener>()

    fun attachContainer(container: ViewGroup) {
        currentContainer = container
        currentPlayer?.setDisplayContainer(container)
    }

    fun loadMedia(
        url: String,
        headers: Map<String, String>? = null,
        mimeType: String? = null,
        type: PlayerKernelType = PlayerKernelType.EXOPLAYER,
        startPosition: Long = 0L
    ) {
        currentUrl = url
        currentHeaders = headers
        currentMime = mimeType
        lastPlaybackPosition = startPosition.coerceAtLeast(0L)
        fallingBack = false
        switchKernelInternal(type, lastPlaybackPosition)
    }

    fun switchKernel(newType: PlayerKernelType) {
        if (currentKernelType == newType && currentPlayer != null) {
            Log.i(TAG, "Already using $newType, skip")
            return
        }
        val url = currentUrl ?: run {
            Log.w(TAG, "Cannot switch: no URL")
            return
        }
        lastPlaybackPosition = currentPlayer?.getCurrentPosition()?.coerceAtLeast(0L) ?: lastPlaybackPosition
        Log.i(TAG, "Switch $currentKernelType → $newType @ ${lastPlaybackPosition}ms url=$url")
        releaseCurrentPlayer()
        switchKernelInternal(newType, lastPlaybackPosition)
    }

    private fun switchKernelInternal(type: PlayerKernelType, startPosition: Long) {
        // Ijk 未集成时直接降级，避免空转
        val resolved = when {
            type == PlayerKernelType.IJKPLAYER && !IjkPlayerImpl.AVAILABLE -> {
                Log.w(TAG, "Ijk not available, use ExoPlayer")
                PlayerKernelType.EXOPLAYER
            }
            else -> type
        }
        currentKernelType = resolved

        val player: IMediaPlayer = when (resolved) {
            PlayerKernelType.EXOPLAYER -> ExoPlayerImpl(context)
            PlayerKernelType.IJKPLAYER -> IjkPlayerImpl(context)
            PlayerKernelType.SYSTEM_MEDIA -> SystemMediaPlayerImpl(context)
        }

        listeners.forEach { player.addListener(it) }
        currentContainer?.let { player.setDisplayContainer(it) }

        player.addListener(object : PlayerEventListener {
            override fun onPrepared() {
                if (startPosition > 0L) player.seekTo(startPosition)
                player.start()
            }

            override fun onError(errorCode: Int, message: String) {
                Log.e(TAG, "Kernel $resolved error: $message ($errorCode)")
                if (fallingBack) return
                val next = nextFallback(resolved) ?: return
                fallingBack = true
                Log.w(TAG, "Fallback $resolved → $next")
                lastPlaybackPosition = player.getCurrentPosition().coerceAtLeast(startPosition)
                releaseCurrentPlayer()
                switchKernelInternal(next, lastPlaybackPosition)
                fallingBack = false
            }
        })

        currentUrl?.let { player.setDataSource(it, currentHeaders, currentMime) }
        player.prepareAsync()
        currentPlayer = player
    }

    private fun nextFallback(from: PlayerKernelType): PlayerKernelType? = when (from) {
        PlayerKernelType.EXOPLAYER ->
            if (IjkPlayerImpl.AVAILABLE) PlayerKernelType.IJKPLAYER else PlayerKernelType.SYSTEM_MEDIA
        PlayerKernelType.IJKPLAYER -> PlayerKernelType.SYSTEM_MEDIA
        PlayerKernelType.SYSTEM_MEDIA -> null
    }

    fun getActiveKernelType(): PlayerKernelType = currentKernelType
    fun getPlayer(): IMediaPlayer? = currentPlayer

    fun addListener(listener: PlayerEventListener) {
        listeners.addIfAbsent(listener)
        currentPlayer?.addListener(listener)
    }

    fun removeListener(listener: PlayerEventListener) {
        listeners.remove(listener)
        currentPlayer?.removeListener(listener)
    }

    private fun releaseCurrentPlayer() {
        currentPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        currentPlayer = null
    }

    fun release() {
        releaseCurrentPlayer()
        listeners.clear()
        currentContainer = null
        currentUrl = null
    }
}
