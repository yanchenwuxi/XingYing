package com.xing.tv.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import java.util.concurrent.CopyOnWriteArrayList

/** 系统 [MediaPlayer] 兜底实现 */
class SystemMediaPlayerImpl(private val context: Context) : IMediaPlayer {

    companion object {
        private const val TAG = "SystemMediaPlayer"
    }

    private var mp: MediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private val listeners = CopyOnWriteArrayList<PlayerEventListener>()
    private var pendingUrl: String? = null
    private var headers: Map<String, String>? = null
    private var surfaceReady = false

    override fun setDisplayContainer(container: ViewGroup) {
        val sv = SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.removeAllViews()
        container.addView(sv)
        surfaceView = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                mp?.setDisplay(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                mp?.setDisplay(null)
            }
        })
    }

    override fun setDataSource(url: String, headers: Map<String, String>?, mimeType: String?) {
        pendingUrl = url
        this.headers = headers
    }

    override fun prepareAsync() {
        val url = pendingUrl ?: return
        releasePlayerOnly()
        try {
            val player = MediaPlayer()
            mp = player
            if (headersOrEmpty().isNotEmpty()) {
                player.setDataSource(context, Uri.parse(url), headersOrEmpty())
            } else {
                player.setDataSource(url)
            }
            surfaceView?.holder?.let { if (surfaceReady) player.setDisplay(it) }
            player.setOnPreparedListener {
                listeners.forEach { l -> l.onPrepared() }
            }
            player.setOnCompletionListener {
                listeners.forEach { l -> l.onCompletion() }
            }
            player.setOnErrorListener { _, what, extra ->
                listeners.forEach { l -> l.onError(what, "MediaPlayer error what=$what extra=$extra") }
                true
            }
            player.setOnBufferingUpdateListener { _, percent ->
                listeners.forEach { l -> l.onBufferingUpdate(percent) }
            }
            player.setOnVideoSizeChangedListener { _, w, h ->
                listeners.forEach { l -> l.onVideoSizeChanged(w, h) }
            }
            player.prepareAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "prepare failed", t)
            listeners.forEach { it.onError(-1, t.message ?: "prepare failed") }
        }
    }


    private fun headersOrEmpty(): Map<String, String> = headers.orEmpty()

    override fun start() { runCatching { mp?.start() } }
    override fun pause() { runCatching { mp?.pause() } }
    override fun stop() { runCatching { mp?.stop() } }
    override fun seekTo(positionMs: Long) { runCatching { mp?.seekTo(positionMs.toInt()) } }
    override fun isPlaying(): Boolean = mp?.isPlaying == true
    override fun getCurrentPosition(): Long = mp?.currentPosition?.toLong() ?: 0L
    override fun getDuration(): Long = mp?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L
    override fun setSpeed(speed: Float) {
        // API 23+ PlaybackParams
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                mp?.playbackParams = mp!!.playbackParams.setSpeed(speed)
            }
        }
    }

    override fun release() {
        releasePlayerOnly()
        surfaceView = null
        listeners.clear()
    }

    private fun releasePlayerOnly() {
        runCatching {
            mp?.reset()
            mp?.release()
        }
        mp = null
    }

    override fun addListener(listener: PlayerEventListener) { listeners.addIfAbsent(listener) }
    override fun removeListener(listener: PlayerEventListener) { listeners.remove(listener) }
}
