package com.xing.tv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.ConfigProvider
import com.xing.tv.api.LiveRepository
import com.xing.tv.data.Prefs
import com.xing.tv.databinding.FragmentLiveBinding
import com.xing.tv.model.LiveChannel
import com.xing.tv.model.LiveGroup
import com.xing.tv.model.TvConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 直播：内嵌播放器 + 当前频道卡 + 左分组 / 右频道两列。
 *
 * 布局参考 YingKe（FongMi 系手机端）的直播页结构：播放器常驻顶部，
 * 边看边选台，点频道直接在本页换台；需要进度条 / 音轨 / 锁屏等控件时
 * 再点画面转到全屏播放器（[PlayerActivity] 的直播模式）。
 *
 * 生命周期：
 * - 切走 tab（`onHiddenChanged`）与离开页面（`onPause`）只暂停，不销毁播放器，
 *   切回来继续播；返回键 / 系统回收时才 [ExoPlayer.release]。
 * - 直播源来自 TVBox 配置的 `lives`，为空时在这里补一次配置加载。
 */
class LiveFragment : Fragment(R.layout.fragment_live) {

    private var _b: FragmentLiveBinding? = null
    private val b: FragmentLiveBinding get() = _b!!

    private val channelAdapter = ChannelAdapter(onClick = { channel -> play(channel) })
    private val groupAdapter = GroupAdapter(onClick = { index -> selectGroup(index) })

    private var lives: List<TvConfig.Live> = emptyList()
    private var groups: List<LiveGroup> = emptyList()

    /** 「全部」+ 各分组，下标与 [groupAdapter] 一一对应 */
    private var allGroups: List<LiveGroup> = emptyList()
    private var groupIndex = 0

    /** 加载令牌：切换直播源/分组时递增，在途结果据此丢弃，避免串数据 */
    private var token = 0

    private var player: ExoPlayer? = null

    /** 当前内嵌播放的频道；null 表示未在播放 */
    private var current: LiveChannel? = null

    /** 用户希望它在播：切走只是暂停，切回来要恢复 */
    private var shouldPlay = false

    /** 写在当前频道卡副标题里的播放状态文案 */
    private var statusText = ""

    /** 起播失败的标记：出错后播放器回到 STATE_IDLE，状态机里据此显示失败而非「起播中」 */
    private var errorText = ""

    /** 换源后按频道名重新选中同名频道 */
    private var pendingName: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentLiveBinding.bind(view)

        // 播放器高度按屏宽的 16:9 计算：写死在 XML 随分辨率露黑边，
        // 交给比例容器又要引入额外依赖，这里直接算一次即可（竖屏固定）
        b.playerBox.layoutParams = b.playerBox.layoutParams.apply {
            height = (resources.displayMetrics.widthPixels * 9f / 16f).roundToInt()
        }

        b.groupRecycler.layoutManager = LinearLayoutManager(context)
        b.groupRecycler.adapter = groupAdapter
        b.recycler.layoutManager = LinearLayoutManager(context)
        b.recycler.adapter = channelAdapter
        b.refresh.setOnRefreshListener { reload() }

        b.btnSwap.setOnClickListener { showSourcePicker() }
        b.btnFull.setOnClickListener { openFullscreen() }
        // 点播放器：显示/隐藏内嵌控件；全屏仅通过全屏按钮进入
        b.playerView.useController = true
        b.playerView.controllerAutoShow = true
        b.playerView.controllerShowTimeoutMs = 3000
        b.playerView.setOnClickListener {
            if (b.playerView.isControllerFullyVisible) {
                b.playerView.hideController()
            } else {
                b.playerView.showController()
            }
        }

        if (ConfigHolder.config.lives.isEmpty()) ensureConfig()
        renderHeader()
        loadSelected()
    }

    override fun onResume() {
        super.onResume()
        sync()
        // 从别的页面切回来时配置可能已换，重新对齐直播源
        if (lives != ConfigHolder.config.lives) {
            lives = emptyList()
            groups = emptyList()
            allGroups = emptyList()
            groupIndex = 0
            renderHeader()
            loadSelected()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台或进入全屏播放器：停声音但不销毁，回来还能接着播
        player?.playWhenReady = false
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) player?.playWhenReady = false else sync()
    }

    override fun onDestroyView() {
        b.playerView.player = null
        player?.release()
        player = null
        _b = null
        super.onDestroyView()
    }

    // ---------- 配置与频道列表 ----------

    /** 配置尚未加载时补一次（用户可能先点开直播 tab） */
    private fun ensureConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ConfigProvider.fetch(ConfigProvider.activeUrl(), force = false) }
            }
            if (_b == null) return@launch
            renderHeader()
            loadSelected()
        }
    }

    private fun renderHeader() {
        lives = ConfigHolder.config.lives
        b.tvLiveSource.text =
            if (lives.isEmpty()) "当前接口没有直播源" else "共 ${lives.size} 个直播源"
    }

    private fun loadSelected(force: Boolean = false) {
        val live = ConfigHolder.config.lives.getOrNull(Prefs.liveSourceIndex)
        if (live == null) {
            groups = emptyList()
            allGroups = emptyList()
            groupIndex = 0
            groupAdapter.submit(emptyList(), 0)
            channelAdapter.submit(emptyList())
            showEmpty(getString(R.string.live_empty))
            b.refresh.isRefreshing = false
            return
        }
        val t = ++token
        b.progress.visibility = View.VISIBLE
        b.emptyState.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LiveRepository.load(live, force = force) }
            }
            if (_b == null || t != token) return@launch
            b.progress.visibility = View.GONE
            b.refresh.isRefreshing = false
            result.onSuccess { list -> onGroupsLoaded(list) }
                .onFailure {
                    groups = emptyList()
                    allGroups = emptyList()
                    groupIndex = 0
                    groupAdapter.submit(emptyList(), 0)
                    channelAdapter.submit(emptyList())
                    // 地址指向 127.0.0.1 的直播源依赖客户端内置代理，本应用不提供，直接说明比抛连接异常有用
                    showEmpty(
                        if (live.isLoopback) {
                            "该直播源地址指向本机（127.0.0.1）\n" +
                                "需要客户端内置代理支持，本版本未提供\n\n" +
                                "请在「我的 → 接口源」更换配置"
                        } else {
                            "直播源解析失败\n${it.message}"
                        }
                    )
                }
        }
    }

    private fun onGroupsLoaded(list: List<LiveGroup>) {
        groups = list
        allGroups = listOf(LiveGroup(getString(R.string.live_group_all), list.flatMap { it.channels })) + list
        groupIndex = if (allGroups.isEmpty()) 0 else groupIndex.coerceIn(0, allGroups.lastIndex)
        renderGroups()
        if (list.isEmpty()) showEmpty(getString(R.string.live_empty))
        else b.emptyState.visibility = View.GONE
        reconcileSelection()
    }

    /**
     * 列表刷新/换源完成后对齐播放状态：
     * 换源后按频道名找回同名台继续播；刷新后频道消失则停掉，仍在则跳回它所在分组。
     */
    private fun reconcileSelection() {
        val pending = pendingName
        pendingName = null
        val c = current
        when {
            pending != null -> {
                val found = allGroups.firstNotNullOfOrNull { g -> g.channels.find { it.name == pending } }
                if (found != null) play(found) else stopCurrent("新直播源里没有「$pending」")
            }
            c == null -> renderCard()
            else -> {
                val idx = allGroups.indexOfFirst { g -> g.channels.any { it.url == c.url } }
                if (idx < 0) {
                    stopCurrent("刷新后该频道已不在当前直播源")
                } else {
                    if (idx != groupIndex) {
                        groupIndex = idx
                        renderGroups()
                    }
                    channelAdapter.markPlaying(c.url)
                    scrollToChannel(c)
                    renderCard()
                }
            }
        }
    }

    private fun selectGroup(index: Int) {
        if (index == groupIndex || index !in allGroups.indices) return
        groupIndex = index
        renderGroups()
        b.recycler.scrollToPosition(0)
    }

    private fun renderGroups() {
        groupAdapter.submit(allGroups, groupIndex)
        allGroups.getOrNull(groupIndex)?.let { showChannels(it) }
    }

    private fun showChannels(group: LiveGroup) {
        channelAdapter.submit(group.channels)
        current?.let { channelAdapter.markPlaying(it.url) }
        b.emptyState.visibility = if (group.channels.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEmpty(text: String) {
        channelAdapter.submit(emptyList())
        b.tvEmpty.text = text
        b.emptyState.visibility = View.VISIBLE
    }

    private fun scrollToChannel(channel: LiveChannel) {
        val shown = allGroups.getOrNull(groupIndex)?.channels ?: return
        val pos = shown.indexOfFirst { it.url == channel.url }
        if (pos >= 0) b.recycler.scrollToPosition(pos)
    }

    private fun reload() {
        groups = emptyList()
        groupIndex = 0
        LiveRepository.invalidate()
        loadSelected(force = true)
    }

    // ---------- 换源 ----------

    private fun showSourcePicker() {
        val ctx = context ?: return
        renderHeader()
        if (lives.isEmpty()) {
            Toast.makeText(ctx, "当前接口没有直播源", Toast.LENGTH_SHORT).show()
            return
        }
        // 直播源名常是「表情 + 推广语」，同样去 emoji 并截断
        val labels = lives.mapIndexed { i, live ->
            live.name.stripEmoji().ifEmpty { "线路 ${i + 1}" }
        }
        val checked = Prefs.liveSourceIndex.coerceIn(0, lives.lastIndex)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.live_source_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which != Prefs.liveSourceIndex) {
                    Prefs.liveSourceIndex = which
                    pendingName = current?.name
                    groupIndex = 0
                    loadSelected(force = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 内嵌播放 ----------

    private fun play(channel: LiveChannel) {
        current = channel
        shouldPlay = true
        errorText = ""
        statusText = "起播中…"
        // 从「全部」里点的频道可能不在当前分组：跳到它所在的分组，保证列表高亮与卡片一致
        val idx = allGroups.indexOfFirst { g -> g.channels.any { it.url == channel.url } }
        if (idx >= 0 && idx != groupIndex) {
            groupIndex = idx
            renderGroups()
        } else {
            channelAdapter.markPlaying(channel.url)
        }
        scrollToChannel(channel)
        renderCard()

        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(channel.url))
        p.prepare()
        sync()
    }

    /** 停掉当前播放并回到未选择状态，[reason] 非空时提示原因 */
    private fun stopCurrent(reason: String) {
        current = null
        shouldPlay = false
        statusText = ""
        errorText = ""
        channelAdapter.markPlaying("")
        player?.stop()
        player?.clearMediaItems()
        player?.playWhenReady = false
        renderCard()
        if (reason.isNotEmpty()) {
            context?.let { Toast.makeText(it, reason, Toast.LENGTH_SHORT).show() }
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换后按当前屏宽重算 16:9，避免播放区域高度异常导致无法播放
        val b = _b ?: return
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val boxH = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏：播放器占屏高约 100%，列表可滑
            (h * 0.55f).toInt().coerceAtLeast((w * 9f / 16f).toInt() / 2)
        } else {
            (w * 9f / 16f).roundToInt()
        }
        b.playerBox.layoutParams = b.playerBox.layoutParams.apply { height = boxH }
        b.playerBox.requestLayout()
        // 重新绑定，防止 Surface 丢失
        player?.let { b.playerView.player = it }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(requireContext()).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = refreshPlayerUi()

            override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayerUi()

            override fun onPlayerError(error: PlaybackException) {
                // 直播源失效很常见，与其露出底层错误码，不如给可操作的下一步
                errorText = "播放失败"
                refreshPlayerUi()
                context?.let {
                    Toast.makeText(it, "播放失败，可点「换源」换个直播源", Toast.LENGTH_SHORT).show()
                }
            }
        })
        player = p
        b.playerView.player = p
        return p
    }

    /** 按页面可见性决定是否真的出声：切 tab / 进全屏 / 退后台都只暂停 */
    private fun sync() {
        player?.playWhenReady = shouldPlay && current != null && isResumed && !isHidden
    }

    private fun refreshPlayerUi() {
        if (_b == null) return
        val p = player ?: return
        val state = p.playbackState
        statusText = when {
            current == null -> ""
            state == Player.STATE_IDLE && errorText.isNotEmpty() -> errorText
            state == Player.STATE_BUFFERING -> "缓冲中…"
            state == Player.STATE_READY && p.isPlaying -> "正在播放"
            state == Player.STATE_READY -> "已就绪"
            state == Player.STATE_ENDED -> "已结束"
            else -> "起播中…"
        }
        b.playerProgress.visibility =
            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        b.playerBox.keepScreenOn =
            state == Player.STATE_BUFFERING || (state == Player.STATE_READY && p.isPlaying)
        renderCard()
    }

    private fun renderCard() {
        if (_b == null) return
        val c = current
        if (c == null) {
            b.tvChannelName.setText(R.string.live_no_channel)
            b.tvChannelSub.setText(R.string.live_hint_pick)
            b.tvLogoText.text = ""
            b.ivLogo.setImageDrawable(null)
            b.ivLogo.tag = null
            b.tvBadge.visibility = View.GONE
            b.placeholder.visibility = View.VISIBLE
            b.playerProgress.visibility = View.GONE
            b.btnFull.isEnabled = false
            b.btnFull.alpha = 0.4f
            return
        }

        b.tvChannelName.text = c.name
        b.tvChannelSub.text = listOf(statusText, c.group, liveSourceName())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        b.placeholder.visibility = View.GONE
        b.btnFull.isEnabled = true
        b.btnFull.alpha = 1f
        b.tvBadge.visibility =
            if (statusText == "正在播放" || statusText == "缓冲中…" || statusText == "起播中…") {
                View.VISIBLE
            } else {
                View.GONE
            }

        // 没有 tvg-logo 时回退为文字首字，避免留一块空白
        if (c.logo.isEmpty()) {
            b.tvLogoText.text = c.name.take(1)
            b.ivLogo.setImageDrawable(null)
            b.ivLogo.tag = null
        } else {
            b.tvLogoText.text = ""
            if (b.ivLogo.tag != c.logo) {
                b.ivLogo.tag = c.logo
                Glide.with(this)
                    .load(c.logo)
                    .apply(RequestOptions().transform(RoundedCorners(b.root.context.dp(10))))
                    .into(b.ivLogo)
            }
        }
    }

    private fun liveSourceName(): String =
        lives.getOrNull(Prefs.liveSourceIndex)?.name?.stripEmoji().orEmpty()

    // ---------- 全屏 ----------

    private fun openFullscreen() {
        val c = current ?: return
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("isLive", true)
                .putExtra("vodName", c.name)
                .putExtra("vodId", c.name)
                .putExtra("vodPic", c.logo)
                .putExtra("episodeName", c.group)
                .putExtra("playUrl", c.url)
        )
    }
}
