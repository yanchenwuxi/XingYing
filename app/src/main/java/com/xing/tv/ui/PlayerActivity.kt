package com.xing.tv.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.SourceFactory
import com.xing.tv.api.SubtitleApi
import com.xing.tv.api.T4Source
import com.xing.tv.data.HistoryStore
import com.xing.tv.data.Prefs
import com.xing.tv.player.PlayerKernelType
import com.xing.tv.databinding.ActivityPlayerBinding
import com.xing.tv.databinding.ItemEpisodeBinding
import com.xing.tv.databinding.ItemEpisodeRowBinding
import com.xing.tv.model.Episode
import com.xing.tv.model.PlayLine
import com.xing.tv.py.PySource
import com.xing.tv.proxy.LocalProxyServer
import com.xing.tv.proxy.Sniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器：
 * - Media3 ExoPlayer，支持 http/https 的 mp4/m3u8/flv 等
 * - 续播（历史记录里的进度）
 * - 左右上下手势：亮度/音量；双击暂停
 * - 倍速 / 锁定 / 画中画小窗
 * - 直播模式（isLive）：隐藏进度条、不记录观看历史
 * - 顶栏 + 自定义底栏（播放暂停/进度/时间/下一集/选集）+ 右侧选集面板（按站点取回详情填充）
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var siteKey = ""
    private var vodId = ""
    private var vodName = ""
    private var episodeName = ""
    private var playUrl = ""
    private var lineName = ""
    private var lineIndex = 0
    private var episodeIndex = 0
    private var resumeMs = 0L

    /** 直播：无进度条、不续播、不写历史 */
    private var isLive = false

    /** 锁定屏幕：只保留一个解锁按钮，屏蔽其余控件与手势 */
    private var locked = false

    /** 当前倍速（以设置里的默认倍速为初值） */
    private var speed = 1.0f

    /** 起播的真实流地址与探测到的容器；外挂字幕需要按它重建播放项 */
    private var streamUrl = ""
    private var streamMime: String? = null

    /** 外挂字幕；null 表示没挂，切换/关闭都走重建 */
    private var externalSubUri: String? = null
    private var externalSubMime: String? = null

    /** 本地字幕文件选择器 */
    private val pickSubtitle = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) loadLocalSubtitle(uri) }

    /** 换源重试时已尝试过的站点，避免反复撞同一个坏源 */
    private val triedSites = mutableSetOf<String>()

    /**
     * 当前影片的全线路剧集：详情页进来时播放器只带当集地址，
     * 这里按 siteKey + vodId 取回详情后填充选集面板、支撑「下一集」。
     * 直播与取不到详情时为空，面板显示空提示。
     */
    private var detailLines: List<PlayLine> = emptyList()

    private val panelAdapter = PanelEpisodeAdapter { index -> playEpisodeAt(lineIndex, index) }

    // 进度保存循环：整个播放生命周期只跑一条，onDestroy 统一取消。
    // 之前每次 onIsPlayingChanged(true) 都新建 Handler 并 post 一条自我续期的任务，
    // 暂停/恢复会叠加多条循环，且退出后仍继续写盘。
    private val positionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val positionTask = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying && vodId.isNotEmpty() && !isLive) persistPosition(p.currentPosition)
            positionHandler.postDelayed(this, POSITION_INTERVAL_MS)
        }
    }

    // 跳片头片尾单独一个更快的轮询：进度记录 5s 一次就够，但片头该在 1s 内就跳掉，
    // 复用同一条循环会让用户多看最多 5 秒的片头。
    private val skipHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val skipTask = object : Runnable {
        override fun run() {
            player?.let { maybeSkip(it) }
            updateMiniProgress()
            skipHandler.postDelayed(this, SKIP_INTERVAL_MS)
        }
    }

    /** 控制条收起时显示底部细进度；展开时让位给正式进度条 */
    private fun updateMiniProgress() {
        val bar = runCatching { b.miniProgress }.getOrNull() ?: return
        val p = player
        if (p == null || isLive || !Prefs.miniProgress || locked) {
            bar.visibility = View.GONE
            return
        }
        val visible = runCatching { b.playerView.isControllerFullyVisible }.getOrDefault(true)
        val dur = p.duration
        if (visible || dur <= 0L) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        val ratio = (p.currentPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        bar.pivotX = 0f
        bar.scaleX = ratio
    }

    /** 片尾只跳一次：跳到结尾后若用户往回拖，不应立刻又被拽回去 */
    private var outroSkipped = false

    private val audio: AudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        siteKey = intent.getStringExtra("siteKey") ?: ""
        vodId = intent.getStringExtra("vodId") ?: ""
        vodName = intent.getStringExtra("vodName") ?: ""
        episodeName = intent.getStringExtra("episodeName") ?: ""
        playUrl = intent.getStringExtra("playUrl") ?: ""
        // 线路名是 T4 / py 源播放解析的 flag 参数，缺失会导致解析失败
        lineName = intent.getStringExtra("lineName") ?: ""
        lineIndex = intent.getIntExtra("lineIndex", 0)
        episodeIndex = intent.getIntExtra("episodeIndex", 0)
        resumeMs = intent.getLongExtra("resumeMs", 0L)
        isLive = intent.getBooleanExtra("isLive", false)
        speed = Prefs.defaultSpeed

        b.tvTitle.text = vodName
        // 顶栏第二行：线路名 · 集数（线路是影视仓那套的「画质/线路」入口，放这儿一眼可见）
        b.tvEpisode.text = when {
            lineName.isEmpty() -> episodeName
            episodeName.isEmpty() -> lineName
            else -> "$lineName · $episodeName"
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnSwitchSource.setOnClickListener { switchSource() }
        b.btnErrorBack.setOnClickListener { finish() }
        b.btnSpeed.setOnClickListener { showSpeedPicker() }
        b.btnTrack.setOnClickListener { showTrackPicker() }
        b.btnSub.setOnClickListener { showSubtitleMenu() }
        b.btnSettings.setOnClickListener { showPlayerSettings() }
        b.btnLock.setOnClickListener { setLocked(true) }
        b.btnUnlock.setOnClickListener { setLocked(false) }
        b.btnPip.setOnClickListener { enterPip() }
        b.btnPanelClose.setOnClickListener { showPanel(false) }
        // 自定义控制条里的自有按钮：Media3 只自动接线 exo_*，这两个手动接
        b.playerView.findViewById<View>(R.id.btnAspect)?.setOnClickListener { cycleAspectMode() }
        b.playerView.findViewById<View>(R.id.btnFullscreen)?.setOnClickListener { toggleFullscreen() }
        b.playerView.findViewById<View>(R.id.btnNextEp)?.setOnClickListener { playNext() }
        b.playerView.findViewById<View>(R.id.btnEpisodes)?.setOnClickListener {
            showPanel(b.sidePanel.visibility != View.VISIBLE)
        }
        b.episodeList.layoutManager = LinearLayoutManager(this)
        b.episodeList.adapter = panelAdapter

        applyOrientation()
        if (isLive) applyLiveUi()

        setupGestures()
        resolveAndPlay(playUrl)
        loadPanelEpisodes()
    }

    /**
     * 进播放页的方向，对齐影视仓「播放页自动横屏 / 全屏样式」：
     * 关掉自动横屏就保持系统方向，开了按全屏样式决定横/竖。
     */
    private fun applyOrientation() {
        if (isLive) {
            // 直播允许横屏
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            return
        }
        requestedOrientation = when {
            !Prefs.autoLandscape -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Prefs.fullscreenStyle == "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Prefs.fullscreenStyle == "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** 直播没有进度可言：藏掉进度条、选集入口与选集面板 */
    private fun applyLiveUi() {
        runCatching {
            b.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.visibility = View.GONE
        }
        b.btnSwitchSource.visibility = View.GONE
        b.playerView.findViewById<View>(R.id.btnNextEp)?.visibility = View.GONE
        b.playerView.findViewById<View>(R.id.btnEpisodes)?.visibility = View.GONE
        if (b.sidePanel.visibility == View.VISIBLE) showPanel(false)
    }


    /** 部分源（py/t4）需要经 playerContent 解析出真实地址 */
    private fun resolveAndPlay(rawUrl: String) {
        showPanel(false)
        lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) { resolveUrl(rawUrl) }
            if (isFinishing || isDestroyed) return@launch
            if (url.isNullOrEmpty()) {
                showError("无法解析播放地址")
            } else {
                startPlayer(url)
            }
        }
    }

    /** 嗅探/代理链路可能产出无后缀地址，需探测 Content-Type 交给播放器判断容器 */
    @Volatile
    private var detectedMime: String? = null

    private fun resolveUrl(raw: String): String {
        detectedMime = null
        if (raw.isBlank()) return ""
        // 直链（m3u8/mp4 等常见后缀）直接返回
        if (Sniffer.isDirect(raw)) return raw
        // 指向本机代理：确保服务已启动后交给播放器，内容由 localProxy 动态产出
        if (isLocalProxyUrl(raw)) {
            LocalProxyServer.setSiteKey(siteKey)
            return LocalProxyServer.rewriteToActual(ensureProxy(raw))
        }

        val site = ConfigHolder.siteOf(siteKey) ?: return sniffOrRaw(raw)
        return runCatching {
            when {
                // JS / jar 源的播放地址要经脚本自己解析（playerContent），
                // 直接拿去播会拿到一个网页地址，播放器只能报格式不支持
                site.kind == com.xing.tv.model.SiteKind.JS -> {
                    val src = SourceFactory.create(site) as? com.xing.tv.spider.TvJsSource
                        ?: return raw
                    val url = src.player(flag = lineName, id = raw).ifEmpty { raw }
                    if (isLocalProxyUrl(url)) {
                        LocalProxyServer.setSiteKey(site.key)
                        LocalProxyServer.rewriteToActual(ensureProxy(url))
                    } else sniffOrRaw(url)
                }
                site.kind == com.xing.tv.model.SiteKind.SPIDER_JAR -> {
                    val src = SourceFactory.create(site) as? com.xing.tv.spider.SpiderJarSource
                        ?: return raw
                    val url = src.player(flag = lineName, id = raw).ifEmpty { raw }
                    if (isLocalProxyUrl(url)) {
                        LocalProxyServer.setSiteKey(site.key)
                        LocalProxyServer.rewriteToActual(ensureProxy(url))
                    } else sniffOrRaw(url)
                }
                site.isPy -> {
                    val src = SourceFactory.create(site) as? PySource ?: return raw
                    val (parse, url) = src.player(flag = lineName, id = raw)
                    when {
                        url.isEmpty() -> raw
                        isLocalProxyUrl(url) -> {
                            LocalProxyServer.setSiteKey(site.key)
                            LocalProxyServer.rewriteToActual(ensureProxy(url))
                        }
                        // parse=1 表示还需进一步解析（网页地址）-> 嗅探直链
                        parse == 1 -> sniffOrRaw(url)
                        else -> url
                    }
                }
                site.type == 4 -> {
                    val src = SourceFactory.create(site) as? T4Source ?: return raw
                    val url = src.play(flag = lineName, id = raw).ifEmpty { raw }
                    if (isLocalProxyUrl(url)) {
                        LocalProxyServer.rewriteToActual(ensureProxy(url))
                    } else sniffOrRaw(url)
                }
                else -> sniffOrRaw(raw)
            }
        }.getOrDefault(raw)
    }

    /** 启动本地代理并探测内容类型；返回原地址（端口由 rewriteToActual 改写） */
    private fun ensureProxy(url: String): String {
        LocalProxyServer.ensureStarted()
        detectedMime = probeContentType(LocalProxyServer.rewriteToActual(url))
        return url
    }

    /**
     * 本机代理返回的地址通常不带 .m3u8/.mp4 后缀，ExoPlayer 默认按后缀推断容器，
     * 会把 HLS 内容当作 MP4 走 progressive 解析而失败。这里探一次 Content-Type 显式指定。
     */
    private fun probeContentType(url: String): String? =
        runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val ct = conn.contentType
            val code = conn.responseCode
            // 必须消费掉 body，否则连接不会真正完成
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            normalizeMime(code, ct)
        }.getOrElse {
            android.util.Log.w("Player", "probe failed: $it")
            null
        }

    /**
     * 把响应的 Content-Type 归一化成 Media3 认识的 MIME。
     *
     * Media3 的 Util.inferContentTypeForMimeType 采用精确匹配 `application/x-mpegURL`，
     * 而服务器/脚本常返回 `application/vnd.apple.mpegurl`，不归一化就会落回
     * CONTENT_TYPE_UNKNOWN，最终被当成 MP4 走 progressive 解析而失败。
     * @return null 表示无法判断，交给播放器按后缀推断。
     */
    private fun normalizeMime(code: Int, raw: String?): String? {
        if (code !in 200..299 || raw.isNullOrBlank()) return null
        val t = raw.substringBefore(';').trim().lowercase()
        return when {
            "mpegurl" in t || "m3u8" in t -> "application/x-mpegURL"
            "mp2t" in t || ("mpeg" in t && "2t" in t) -> "video/mp2t"
            "flv" in t -> "video/x-flv"
            "mp4" in t || "m4v" in t || "quicktime" in t -> "video/mp4"
            "webm" in t -> "video/webm"
            "matroska" in t -> "video/x-matroska"
            else -> null
        }
    }

    /** 不是直链时嗅探出真实地址，失败则回退原地址交给播放器自行判断 */
    private fun sniffOrRaw(url: String): String =
        if (Sniffer.isDirect(url)) url else Sniffer.resolve(url).ifEmpty { url }

    /**
     * 是否指向本机代理服务（localProxy 约定）。
     *
     * 仅按 127.0.0.1 前缀判断会误伤用户自建的本机采集服务器（本机跑 macCMS 很常见），
     * 因此必须同时匹配代理端口：脚本约定的 9978，或当前实际监听端口。
     */
    private fun isLocalProxyUrl(url: String): Boolean {
        val l = url.lowercase()
        if (!(l.startsWith("http://127.0.0.1:") || l.startsWith("http://localhost:"))) return false
        val port = runCatching { java.net.URL(url).port }.getOrDefault(-1)
        return port == LocalProxyServer.DEFAULT_PORT ||
                (port > 0 && port == LocalProxyServer.actualPort)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 影视仓那套手势：左 1/3 双击后退 10 秒、右 1/3 前进 10 秒、中间暂停
                val p = player ?: return true
                val w = resources.displayMetrics.widthPixels.toFloat()
                when {
                    e.x < w / 3f -> {
                        if (isLive) return true
                        val t = (p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)
                        p.seekTo(t)
                        showGesture("⏪ 10 秒")
                    }
                    e.x > w * 2f / 3f -> {
                        if (isLive) return true
                        val dur = p.duration.coerceAtLeast(0L)
                        val t = (p.currentPosition + SEEK_STEP_MS)
                            .let { if (dur > 0) it.coerceAtMost(dur) else it }
                        p.seekTo(t)
                        showGesture("10 秒 ⏩")
                    }
                    else -> p.playWhenReady = !p.playWhenReady
                }
                return true
            }
        })

        var downX = 0f
        var downY = 0f
        var startProgress = 0f
        var startVolume = -1
        var startBrightness = -1f
        var mode = 0 // 0=未定 1=水平 2=垂直

        b.playerView.setOnTouchListener { _, event ->
            if (locked) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    mode = 0
                    // 长按 3 倍速（松手恢复）：影视仓播放器的手感，按住不动才触发
                    gestureHandler.postDelayed(longPressBoost, LONG_PRESS_MS)
                    // 起点必须是当前进度：之前误写成 duration，左右滑会从片尾附近起跳
                    startProgress = (player?.currentPosition ?: 0L).toFloat()
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBrightness = window.attributes.screenBrightness
                    // DOWN 也要喂给手势识别：只喂孤立的 UP 的话，
                    // GestureDetector 没有手势起点，单击/双击永远识别不出来
                    tapDetector.onTouchEvent(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val scrW = resources.displayMetrics.widthPixels.toFloat()
                    val scrH = resources.displayMetrics.heightPixels.toFloat()
                    if (mode == 0 && (Math.abs(dx) > 40 || Math.abs(dy) > 40)) {
                        mode = if (Math.abs(dx) > Math.abs(dy)) 1 else 2
                        // 手指一划就走手势，长按倍速到此为止
                        cancelLongPressBoost()
                    }
                    if (mode == 1 && startProgress > 0) {
                        // 水平滑动快进快退：直播没有进度概念，直接忽略
                        if (isLive) return@setOnTouchListener true
                        val delta = dx / scrW * 60_000f // 全屏宽度对应 60 秒
                        // duration 未解析出来时是 C.TIME_UNSET（负数），
                        // 直接拿它当上界会触发 coerceIn 的空区间异常
                        val duration = (player?.duration ?: 0L).coerceAtLeast(0L)
                        val target = (startProgress + delta).toLong().coerceIn(0, duration)
                        player?.seekTo(target)
                        showGesture(
                            "进度 " + formatTime(target) +
                                if (duration > 0) " / " + formatTime(duration) else ""
                        )
                    } else if (mode == 2) {
                        if (downX < scrW / 2f) {
                            // 左侧：亮度
                            val delta = -dy / scrH
                            val lp = window.attributes
                            lp.screenBrightness = (if (startBrightness < 0) 0.5f else startBrightness) + delta
                            window.attributes = lp
                            showGesture("亮度 " + ((lp.screenBrightness * 100).toInt()) + "%")
                        } else {
                            // 右侧：音量
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / scrH * max).toInt()
                            val vol = (startVolume + delta).coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                            showGesture("音量 " + (vol * 100 / max) + "%")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPressBoost()
                    if (mode == 0) tapDetector.onTouchEvent(event)
                    mode = 0
                    true
                }
                else -> tapDetector.onTouchEvent(event)
            }
        }
    }

    // ---------- 长按倍速 ----------

    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var boosting = false

    private val longPressBoost = Runnable {
        if (locked) return@Runnable
        val p = player ?: return@Runnable
        if (p.isPlaying) {
            boosting = true
            p.playbackParameters = PlaybackParameters(BOOST_SPEED)
            showGesture("${BOOST_SPEED.toInt()}x 倍速中，松手恢复")
        }
    }

    private fun cancelLongPressBoost() {
        gestureHandler.removeCallbacks(longPressBoost)
        if (!boosting) return
        boosting = false
        player?.playbackParameters = PlaybackParameters(speed)
    }

    private fun startPlayer(url: String) {
        b.errorPanel.visibility = View.GONE
        if (player == null) {
            player = ExoPlayer.Builder(this)
                .build()
                .also { exo ->
                    b.playerView.player = exo
                    exo.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            onPlaybackFailed(error)
                        }
                    })
                }
        }
        // 播放地址可能不带容器后缀（本机代理/嗅探链路），此时用探测到的 Content-Type
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(detectedMime?.takeIf { it.isNotBlank() })
            .build()
        streamUrl = url
        streamMime = detectedMime
        player?.setMediaItem(item)
        player?.prepare()
        if (resumeMs > 0 && Prefs.resumeEnabled) player?.seekTo(resumeMs)
        player?.playbackParameters = PlaybackParameters(speed)
        player?.playWhenReady = true
        startPositionLoop()
    }

    /** 播放失败：按偏好决定是否自动换源重试 */
    private fun onPlaybackFailed(error: PlaybackException) {
        val msg = "播放失败: ${error.errorCodeName}"
        if (isLive || !Prefs.autoSwitchSource || vodName.isEmpty()) {
            showError(msg)
            return
        }
        // 自动换源：给出提示并直接去其他站点查找同名影片
        b.tvError.text = "$msg\n正在自动换源重试…"
        b.errorPanel.visibility = View.VISIBLE
        b.btnSwitchSource.visibility = View.GONE
        autoSwitchOnce()
    }

    private var autoSwitching = false

    private fun autoSwitchOnce() {
        if (autoSwitching) return
        autoSwitching = true
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { searchOtherSites() }
            autoSwitching = false
            if (isFinishing || isDestroyed) return@launch
            if (found == null) {
                // 没有可换的源了，退回错误面板并保留手动入口
                showError("播放失败，且其他线路没有找到「$vodName」")
                return@launch
            }
            val (site, detail) = found
            siteKey = site.key
            triedSites.add(site.key)
            playDetail(detail)
        }
    }

    // ---------- 倍速 / 锁定 / 小窗 ----------

    private val speedOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private fun showSpeedPicker() {
        val labels = speedOptions.map { "${it}x" }.toTypedArray()
        val checked = speedOptions.withIndex().firstOrNull { it.value == speed }?.index ?: 0
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                speed = speedOptions[which]
                player?.playbackParameters = PlaybackParameters(speed)
                toast("倍速 ${speedOptions[which]}x")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 音轨 / 字幕轨切换。
     *
     * 公开的 m3u8 常带多条音轨（国语/粤语/英语）和内挂字幕，ExoPlayer 默认只选第一条，
     * 而播放器此前没有暴露任何入口，用户根本无从切换。这里从 [Player.getCurrentTracks]
     * 枚举可用轨道，再通过 [Player.setTrackSelectionParameters] 落地选择。
     */
    private fun showTrackPicker() {
        val exo = player ?: return
        val groups = exo.currentTracks.groups
        val sections = buildList {
            val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty()) add("音轨" to audio)
            val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            if (text.isNotEmpty()) add("字幕轨" to text)
        }
        if (sections.isEmpty()) {
            toast("当前内容没有可切换的音轨或字幕")
            return
        }
        if (sections.size == 1) {
            pickTrack(sections[0].first, sections[0].second)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("音轨与字幕")
            .setItems(sections.map { it.first }.toTypedArray()) { d, w ->
                d.dismiss()
                pickTrack(sections[w].first, sections[w].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 展开某个分组下所有可选轨道供用户挑选 */
    private fun pickTrack(kind: String, groups: List<Tracks.Group>) {
        val isText = kind.startsWith("字幕")
        val labels = mutableListOf<String>()
        // 字幕多一个「关闭」项；音轨关掉就没声音了，不给关闭入口
        if (isText) labels += "关闭字幕"
        var checked = -1
        groups.forEachIndexed { gi, g ->
            for (i in 0 until g.length) {
                labels += trackLabel(g.getTrackFormat(i), groups.size > 1, gi, g.length, i)
                if (g.isTrackSelected(i)) checked = labels.lastIndex
            }
        }
        // 一条字幕轨都没选中时等同于「已关闭」，把「关闭字幕」标为当前项，
        // 否则弹窗打开时两个单选都没勾上，看起来像是没生效
        if (checked < 0 && isText) checked = 0
        if (labels.size == 1 && !isText) {
            toast("当前内容只有一条音轨")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isText) "选择字幕" else "选择音轨")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, w ->
                d.dismiss()
                // 关闭字幕按整类禁用处理：不同分组的字幕可能都被默认选中，
                // 逐条 clearOverride 会漏掉没展开过的分组
                if (isText && w == 0) {
                    applyParams { it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
                    toast("已关闭字幕")
                    return@setSingleChoiceItems
                }
                val flat = w - if (isText) 1 else 0
                var seen = 0
                var picked: Tracks.Group? = null
                var pickedIdx = 0
                for (g in groups) {
                    if (flat < seen + g.length) {
                        picked = g
                        pickedIdx = flat - seen
                        break
                    }
                    seen += g.length
                }
                val g = picked ?: return@setSingleChoiceItems
                applyParams {
                    // 字幕可能已被整类禁用过，先放行再覆盖，否则选了也不显示
                    it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    it.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, pickedIdx))
                }
                toast(if (isText) "已切换字幕" else "已切换音轨")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 在当前轨道参数之上做一次增量修改并立即生效 */
    private fun applyParams(block: (TrackSelectionParameters.Builder) -> Unit) {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
        block(builder)
        exo.trackSelectionParameters = builder.build()
    }

    /** 轨道显示文本：见 [com.xing.tv.player.trackLabel] */
    private fun trackLabel(
        format: Format,
        multiGroup: Boolean,
        groupIndex: Int,
        groupLen: Int,
        indexInGroup: Int
    ): String = com.xing.tv.player.trackLabel(
        label = format.label,
        language = format.language,
        groupIndex = groupIndex,
        groupLen = groupLen,
        indexInGroup = indexInGroup,
        multiGroup = multiGroup
    )

    // ---------- 在线字幕与外挂载入 ----------

    /**
     * 字幕菜单：在线搜索 / 直链 / 本地文件 / Token 设置 / 关闭外挂。
     *
     * 内挂字幕走「音轨与字幕」切换；这里只管外挂：搜到或拿到 srt/vtt 后，
     * 按当前进度重建播放项挂上去，不用退出重进。
     */
    private fun showSubtitleMenu() {
        if (isLive) {
            toast("直播没有外挂字幕")
            return
        }
        val items = mutableListOf("在线搜索字幕", "输入字幕直链", "本地字幕文件")
        items += "字幕源：迅雷（免 Token）"
        if (externalSubUri != null) items += "关闭外挂字幕"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("字幕")
            .setItems(items.toTypedArray()) { d, w ->
                d.dismiss()
                when (items[w]) {
                    "在线搜索字幕" -> showSubtitleSearch()
                    "输入字幕直链" -> showSubtitleUrlInput()
                    "本地字幕文件" -> pickSubtitle.launch(arrayOf("*/*"))
                    "关闭外挂字幕" -> {
                        externalSubUri = null
                        externalSubMime = null
                        applyExternalSubs()
                        toast("已关闭外挂字幕")
                    }
                    "字幕源：迅雷（免 Token）" -> toast("当前字幕搜索走迅雷公开源，无需 Token")
                    else -> toast("当前字幕搜索走迅雷公开源，无需 Token")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSubtitleSearch() {
        val et = android.widget.EditText(this).apply {
            setText(vodName)
            hint = "输入片名搜索字幕"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
        }
        val pad = dp(12)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(et)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("在线搜索字幕")
            .setView(box)
            .setPositiveButton("搜索") { _, _ ->
                val kw = et.text.toString().trim()
                if (kw.length < 2) {
                    toast("关键词太短")
                    return@setPositiveButton
                }
                runSubtitleSearch(kw)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSubtitleSearch(keyword: String) {
        toast("搜索中…")
        lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                runCatching { SubtitleApi.search(Prefs.subtitleToken, keyword) }
            }
            if (isFinishing || isDestroyed) return@launch
            hits.onSuccess { list ->
                if (list.isEmpty()) toast("没有找到「$keyword」的字幕")
                else showSubtitleResults(list)
            }.onFailure {
                toast(it.message ?: "搜索失败")
            }
        }
    }

    private fun showSubtitleResults(hits: List<SubtitleApi.SubHit>) {
        val labels = hits.map {
            val extra = listOf(it.lang, it.format).filter { s -> s.isNotEmpty() }.joinToString(" · ")
            if (extra.isEmpty()) it.title else "${it.title}\n$extra"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择字幕（${hits.size} 个）")
            .setItems(labels) { d, w ->
                d.dismiss()
                downloadSubtitle(hits[w].id, hits[w].title)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadSubtitle(id: Long, title: String) {
        toast("正在下载字幕…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val files = SubtitleApi.detail(Prefs.subtitleToken, id)
                    val file = files.firstOrNull()
                        ?: throw IllegalStateException("该字幕没有可下载的 srt/vtt 文件")
                    val target = java.io.File(cacheDir, "subs").apply { mkdirs() }
                        .resolve("sub_$id-${file.name.substringAfterLast('/')}")
                    com.xing.tv.net.Http.download(file.url, target, api = true)
                    target.absolutePath to mimeOf(target.name)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { (path, mime) ->
                if (mime == null) {
                    toast("不支持的字幕格式")
                    return@onSuccess
                }
                loadExternalSubtitle(android.net.Uri.fromFile(java.io.File(path)).toString(), mime)
            }.onFailure {
                toast(it.message ?: "字幕下载失败")
            }
        }
    }

    private fun showSubtitleUrlInput() {
        val et = android.widget.EditText(this).apply {
            hint = "粘贴 .srt / .vtt 字幕直链"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
        }
        val pad = dp(12)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(et)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("输入字幕直链")
            .setView(box)
            .setPositiveButton("载入") { _, _ ->
                val url = et.text.toString().trim()
                val mime = mimeOf(url.substringBefore('?'))
                if (!url.startsWith("http")) {
                    toast("地址需以 http 开头")
                    return@setPositiveButton
                }
                if (mime == null) {
                    toast("只支持 .srt / .vtt 字幕")
                    return@setPositiveButton
                }
                // 先下载到本地再挂：直链可能带跳转或时效，播放器直接播容易断
                lifecycleScope.launch {
                    toast("正在下载字幕…")
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val target = java.io.File(cacheDir, "subs").apply { mkdirs() }
                                .resolve("url_${url.hashCode()}.${url.substringAfterLast('.', "srt")}")
                            com.xing.tv.net.Http.download(url, target, api = true)
                            target.absolutePath
                        }
                    }
                    if (isFinishing || isDestroyed) return@launch
                    result.onSuccess { path ->
                        loadExternalSubtitle(android.net.Uri.fromFile(java.io.File(path)).toString(), mime)
                    }.onFailure {
                        toast(it.message ?: "字幕下载失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTokenInput() {
        val et = android.widget.EditText(this).apply {
            setText(Prefs.subtitleToken)
            hint = "ASSRT Token（官网免费注册获取）"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
        }
        val pad = dp(12)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(et)
            addView(android.widget.TextView(context).apply {
                text = "字幕搜索走你自己的配额，应用不内置共享密钥"
                textSize = 11f
                setTextColor(context.getColor(R.color.text_muted))
                setPadding(0, dp(8), 0, 0)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置字幕 Token")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                Prefs.subtitleToken = et.text.toString()
                toast(if (Prefs.subtitleToken.isEmpty()) "已清除 Token" else "Token 已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 本地字幕文件：SAF 授权后直接用 content:// 挂，不复制 */
    private fun loadLocalSubtitle(uri: android.net.Uri) {
        val name = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull().orEmpty()
        val mime = mimeOf(name)
        if (mime == null) {
            toast("只支持 .srt / .vtt 字幕")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        loadExternalSubtitle(uri.toString(), mime)
    }

    /** 按当前进度重建播放项，把外挂字幕挂上去（或摘掉） */
    private fun loadExternalSubtitle(uri: String, mime: String) {
        externalSubUri = uri
        externalSubMime = mime
        applyExternalSubs()
        toast("外挂字幕已载入，可在「音轨与字幕」里切换")
    }

    private fun applyExternalSubs() {
        val p = player ?: return
        if (streamUrl.isEmpty()) return
        val pos = p.currentPosition.coerceAtLeast(0L)
        val builder = MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(streamMime?.takeIf { it.isNotBlank() })
        externalSubUri?.let { u ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(u))
                        .setMimeType(externalSubMime ?: MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("zh")
                        .setLabel("外挂字幕")
                        .build()
                )
            )
        }
        p.setMediaItem(builder.build(), pos)
        p.prepare()
        p.playWhenReady = true
    }

    /** 文件名后缀 → Media3 外挂字幕 MIME；不支持返回 null */
    private fun mimeOf(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            n.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            n.endsWith(".ttml") || n.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------- 播放器设置 ----------
    //
    // 底部设置弹窗（仿影视仓播放器设置的结构：一组快捷行 + 底部动作）。
    // 去广告在本应用里的落点就是这里：跳过片头/片尾一次设好，
    // 之后每次起播自动跳过，不用再手动拖进度条。

    private val skipOptions = intArrayOf(0, 5, 10, 15, 30, 60, 90)


    private fun showKernelPicker() {
        val labels = PlayerKernelType.entries.map { it.label }.toTypedArray()
        val current = PlayerKernelType.fromName(Prefs.playerKernel)
        val checked = PlayerKernelType.entries.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择播放内核")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val chosen = PlayerKernelType.entries[which]
                Prefs.playerKernel = chosen.name
                d.dismiss()
                toast("播放内核：${chosen.label}（下次起播生效）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPlayerSettings() {
        val dialog = BottomSheetDialog(this, R.style.Sheet_XingYing)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(8), pad, dp(16))
        }
        root.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            }
            setBackgroundResource(R.drawable.bg_tab_indicator)
            alpha = 0.5f
        })
        root.addView(android.widget.TextView(this).apply {
            text = "播放设置"
            textSize = 17f
            setTextColor(getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        })

        root.addView(settingRow("播放倍速", "${speed}x") { showSpeedPicker() })
        root.addView(settingRow("画面比例", aspectLabel()) { cycleAspect(dialog) })
        root.addView(settingRow("跳过片头", secondsText(Prefs.skipIntroSec)) {
            pickSkip("跳过片头", Prefs.skipIntroSec) {
                Prefs.skipIntroSec = it
                dialog.dismiss()
                toast("片头跳过已设为${secondsText(it)}")
            }
        })
        root.addView(settingRow("跳过片尾", secondsText(Prefs.skipOutroSec)) {
            pickSkip("跳过片尾", Prefs.skipOutroSec) {
                Prefs.skipOutroSec = it
                dialog.dismiss()
                toast("片尾跳过已设为${secondsText(it)}")
            }
        })
        root.addView(settingRow("在线字幕", if (externalSubUri == null) "未载入" else "已载入外挂") {
            dialog.dismiss()
            showSubtitleMenu()
        })
        root.addView(settingRow("音轨与字幕", "内挂轨道切换") {
            dialog.dismiss()
            showTrackPicker()
        })
        // 影视仓播放设置里的几项开关，直接落 Prefs 并即时生效
        root.addView(settingRow("迷你进度条", if (Prefs.miniProgress) "开" else "关") {
            Prefs.miniProgress = !Prefs.miniProgress
            dialog.dismiss()
            toast("迷你进度条：${if (Prefs.miniProgress) "开" else "关"}")
        })
        root.addView(settingRow("播放页自动横屏", if (Prefs.autoLandscape) "开" else "关") {
            Prefs.autoLandscape = !Prefs.autoLandscape
            dialog.dismiss()
            applyOrientation()
            toast("自动横屏：${if (Prefs.autoLandscape) "开" else "关"}")
        })
        root.addView(settingRow("播放失败自动换源", if (Prefs.autoSwitchSource) "开" else "关") {
            Prefs.autoSwitchSource = !Prefs.autoSwitchSource
            dialog.dismiss()
        })
        val kernelLabel = PlayerKernelType.fromName(Prefs.playerKernel).label
        root.addView(settingRow("播放内核", kernelLabel) {
            dialog.dismiss()
            showKernelPicker()
        })

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        btnRow.addView(actionButton("重播本集") {
            dialog.dismiss()
            player?.seekTo(0)
            player?.playWhenReady = true
        })
        btnRow.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(8), 1)
        })
        btnRow.addView(actionButton("刷新重试") {
            dialog.dismiss()
            if (playUrl.isNotEmpty()) resolveAndPlay(playUrl)
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun settingRow(
        title: String,
        value: String,
        onClick: () -> Unit
    ): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            // 代码里拿不到 ?attr/selectableItemBackground，按主题解一次
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(android.widget.TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(android.widget.TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            maxLines = 1
        })
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_muted))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                leftMargin = dp(4)
            }
        })
        return row
    }

    private fun actionButton(label: String, onClick: () -> Unit): android.widget.TextView =
        android.widget.TextView(this).apply {
            text = label
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.on_primary))
            setBackgroundResource(R.drawable.bg_gradient_button)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun aspectLabel(): String = when (b.playerView.resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "填充"
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "拉伸"
        else -> "自适应"
    }

    /** 自适应 → 填充 → 拉伸轮换（控制条直接点，不进设置面板） */
    private fun cycleAspectMode() {
        b.playerView.resizeMode = when (b.playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        toast("画面比例：${aspectLabel()}")
    }

    private fun cycleAspect(dialog: BottomSheetDialog) {
        cycleAspectMode()
        dialog.dismiss()
    }

    /** 横竖屏切换：影视仓控制条右侧的全屏键就是这个行为 */
    private fun toggleFullscreen() {
        requestedOrientation =
            if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    private fun pickSkip(title: String, current: Int, onPick: (Int) -> Unit) {
        val checked = skipOptions.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(skipOptions.map { secondsText(it) }.toTypedArray(), checked) { d, which ->
                onPick(skipOptions[which])
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun secondsText(sec: Int): String = if (sec == 0) "不跳过" else "${sec} 秒"

    private fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            b.topBar.visibility = View.GONE
            b.btnUnlock.visibility = View.VISIBLE
            b.playerView.useController = false
            toast("已锁定，点击左侧按钮解锁")
        } else {
            b.btnUnlock.visibility = View.GONE
            b.playerView.useController = true
            b.topBar.visibility = View.VISIBLE
            b.playerView.showController()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
                toast("已进入小窗")
            }.onFailure { toast("当前设备不支持小窗") }
        } else {
            toast("系统版本过低，不支持小窗")
        }
    }


    /** 启动进度记录循环（幂等：重复调用不会叠加任务） */
    private fun startPositionLoop() {
        positionHandler.removeCallbacks(positionTask)
        positionHandler.post(positionTask)
        outroSkipped = false
        skipHandler.removeCallbacks(skipTask)
        skipHandler.post(skipTask)
    }

    private fun persistPosition(positionMs: Long) {
        // 全局无痕：连进度也不落盘，退出后首页不会出现记录
        if (Prefs.incognito) return
        val total = player?.duration?.takeIf { it > 0 } ?: 0L
        HistoryStore.saveHistory(
            HistoryStore.Record(
                siteKey = siteKey,
                vodId = vodId,
                name = vodName,
                pic = intent.getStringExtra("vodPic") ?: "",
                episodeName = episodeName,
                playUrl = playUrl,
                lineIndex = lineIndex,
                episodeIndex = episodeIndex,
                positionMs = positionMs,
                updatedAt = System.currentTimeMillis(),
                durationMs = total
            )
        )
    }

    /**
     * 片头 / 片尾自动跳过。
     *
     * 直播流的 duration 是 [C.TIME_UNSET]，取不到正数时直接跳过本方法，
     * 因此不需要单独判断 isLive。
     */
    private fun maybeSkip(p: ExoPlayer) {
        if (!p.isPlaying) return
        val dur = p.duration.takeIf { it > 0 } ?: return
        val pos = p.currentPosition
        val outroMs = Prefs.skipOutroSec * 1000L
        val introMs = Prefs.skipIntroSec * 1000L
        // 片尾：整片必须明显长于设定值，否则会把短片直接跳没
        if (outroMs > 0 && !outroSkipped && dur > outroMs && pos >= dur - outroMs) {
            outroSkipped = true
            p.seekTo(dur)
            toast("已跳过片尾")
            return
        }
        if (introMs > 0 && dur > introMs && pos < introMs) {
            p.seekTo(introMs)
            toast("已跳过片头")
        }
    }

    private fun toggleControls() {
        if (locked) return
        b.topBar.visibility = if (b.topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        b.playerView.showController()
    }

    private fun showPanel(show: Boolean) {
        b.sidePanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        b.tvError.text = msg
        b.errorPanel.visibility = View.VISIBLE
        // 只有配置里还有其他可搜索站点时才提供换源入口
        b.btnSwitchSource.visibility =
            if (otherSearchableSites().isNotEmpty()) View.VISIBLE else View.GONE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 除当前站点外，其余可搜索的站点 */
    private fun otherSearchableSites(): List<com.xing.tv.model.TvConfig.Site> =
        ConfigHolder.sites.filter {
            it.key != siteKey && !triedSites.contains(it.key) && it.searchable == 1 && !it.isScript
        }

    /**
     * 换源重试：当前线路播放失败时，用影片名去其他站点搜索，
     * 命中同名影片则取对应线路/集数继续播放。
     *
     * 只匹配完全同名的条目，避免播到同名不同剧的内容。
     */
    private fun switchSource() {
        if (vodName.isEmpty()) {
            showError("无法换源：缺少影片名")
            return
        }
        triedSites.add(siteKey)
        b.tvError.text = "正在从其他线路查找…"
        b.errorPanel.visibility = View.VISIBLE
        b.btnSwitchSource.visibility = View.GONE

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { searchOtherSites() }
            if (isFinishing || isDestroyed) return@launch
            if (found == null) {
                b.tvError.text = "其他线路也没有找到「$vodName」"
                b.errorPanel.visibility = View.VISIBLE
                b.btnSwitchSource.visibility = View.GONE
                return@launch
            }
            val (site, detail) = found
            // 更新当前站点，后续换源不会重复尝试它
            siteKey = site.key
            triedSites.add(site.key)
            playDetail(detail)
        }
    }

    /** 在其他站点搜索同名影片，返回第一个能取到详情的结果 */
    private fun searchOtherSites(): Pair<com.xing.tv.model.TvConfig.Site, com.xing.tv.model.VodDetail>? {
        for (site in otherSearchableSites()) {
            val detail = runCatching {
                val src = SourceFactory.create(site)
                val hit = src.search(vodName, 1).items.firstOrNull { it.vodName == vodName }
                    ?: return@runCatching null
                src.detail(hit.vodId)
            }.getOrNull() ?: continue
            if (detail != null && detail.lines.isNotEmpty()) return site to detail
        }
        return null
    }

    /** 按原集数名定位剧集；找不到则退回第一集 */
    private fun playDetail(detail: com.xing.tv.model.VodDetail) {
        // 空列表时 lastIndex = -1，coerceIn(0, -1) 会抛异常，故先判空
        val lineIdx =
            if (detail.lines.isEmpty()) 0 else lineIndex.coerceIn(0, detail.lines.lastIndex)
        val line = detail.lines.getOrNull(lineIdx) ?: return
        val epIdx =
            if (line.episodes.isEmpty()) 0 else episodeIndex.coerceIn(0, line.episodes.lastIndex)
        val ep = line.episodes.getOrNull(epIdx)
            ?: line.episodes.firstOrNull()
            ?: return

        lineName = line.name
        this.lineIndex = lineIdx
        episodeIndex = epIdx
        episodeName = ep.name
        playUrl = ep.url
        b.tvEpisode.text = ep.name
        // 换源后进度不再适用（地址已不同），从头开始
        resumeMs = 0L
        player?.seekTo(0)
        detailLines = detail.lines
        renderPanelList()
        resolveAndPlay(ep.url)
    }

    // ---------- 选集面板与下一集 ----------

    /**
     * 详情页进来时播放器只带当集地址，选集面板靠这次取数填充。
     * 直播（无 siteKey/vodId）不取，面板保持空提示。
     */
    private fun loadPanelEpisodes() {
        if (isLive || siteKey.isEmpty() || vodId.isEmpty()) return
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching {
                    val site = ConfigHolder.siteOf(siteKey) ?: return@runCatching emptyList<PlayLine>()
                    SourceFactory.createOrNull(site)?.detail(vodId)?.lines.orEmpty()
                }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            if (lines.isNotEmpty()) {
                detailLines = lines
                renderPanelList()
            }
        }
    }

    /** 渲染选集面板：当前线路名 + 剧集列表，正在播的标出 */
    private fun renderPanelList() {
        val lines = detailLines
        if (lines.isEmpty()) {
            b.episodeList.visibility = View.GONE
            b.tvPanelEmpty.visibility = View.VISIBLE
            return
        }
        val li = lineIndex.coerceIn(0, lines.lastIndex)
        b.tvPanelTitle.text = "选集 · ${lines[li].name}"
        panelAdapter.submit(lines[li].episodes, episodeIndex)
        b.episodeList.visibility = View.VISIBLE
        b.tvPanelEmpty.visibility = View.GONE
        b.episodeList.scrollToPosition(episodeIndex.coerceIn(0, (lines[li].episodes.size - 1).coerceAtLeast(0)))
    }

    /** 面板点选 / 下一集统一走这里：切集从头播，进度不再沿用 */
    private fun playEpisodeAt(lineIdx: Int, epIdx: Int) {
        val line = detailLines.getOrNull(lineIdx) ?: return
        val ep = line.episodes.getOrNull(epIdx) ?: return
        lineName = line.name
        lineIndex = lineIdx
        episodeIndex = epIdx
        episodeName = ep.name
        playUrl = ep.url
        b.tvEpisode.text = ep.name
        resumeMs = 0L
        player?.seekTo(0)
        renderPanelList()
        resolveAndPlay(ep.url)
    }

    private fun playNext() {
        val line = detailLines.getOrNull(lineIndex)
        if (line == null || line.episodes.isEmpty()) {
            toast("没有选集信息")
            return
        }
        val next = episodeIndex + 1
        if (next >= line.episodes.size) {
            toast("已经是最后一集")
            return
        }
        playEpisodeAt(lineIndex, next)
    }

    /** 选集面板适配器：纵向整行，当前集高亮 */
    private inner class PanelEpisodeAdapter(
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PanelEpisodeAdapter.VH>() {

        private val items = mutableListOf<Episode>()
        private var highlight = -1

        fun submit(list: List<Episode>, highlightIndex: Int) {
            items.clear()
            items.addAll(list)
            highlight = highlightIndex
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemEpisodeRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemEpisodeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val on = position == highlight
            holder.b.tvRow.text = items[position].name
            holder.b.tvRow.isSelected = on
            holder.b.tvRow.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    holder.b.tvRow.context,
                    if (on) R.color.on_primary_container else R.color.text_primary
                )
            )
            holder.b.root.setOnClickListener { onClick(position) }
        }
    }

    private var gestureToastTime = 0L

    private fun showGesture(text: String) {
        b.tvGesture.text = text
        b.tvGesture.visibility = View.VISIBLE
        b.tvGesture.alpha = 1f
        gestureToastTime = System.currentTimeMillis()
        b.tvGesture.postDelayed({
            if (System.currentTimeMillis() - gestureToastTime >= 900) {
                b.tvGesture.visibility = View.GONE
            }
        }, 1000)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        // 画中画时 Activity 仍处于「暂停」但应在后台继续播放
        if (!isInPictureInPictureMode) player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 按 Home 键自动收进小窗，是移动端播放器的常见预期
        if (player?.isPlaying == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPip()
    }

    override fun onDestroy() {
        super.onDestroy()
        positionHandler.removeCallbacks(positionTask)
        skipHandler.removeCallbacks(skipTask)
        player?.release()
        player = null
        // 释放本机代理端口；再次需要时由 resolveUrl 按需重启
        LocalProxyServer.stop()
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val LONG_PRESS_MS = 500L
        private const val BOOST_SPEED = 3.0f

        /** 进度记录间隔：粗粒度即可满足续播 */
        private const val POSITION_INTERVAL_MS = 5000L

        /** 跳片头片尾的检查间隔：太粗会让用户多看几秒片头 */
        private const val SKIP_INTERVAL_MS = 1000L
    }
}
