package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.SourceFactory
import com.xing.tv.data.HistoryStore
import com.xing.tv.databinding.ActivityDetailBinding
import com.xing.tv.databinding.ItemEpisodeBinding
import com.xing.tv.model.Episode
import com.xing.tv.model.PlayLine
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页：头图 + 海报信息 + 播放/收藏 + 简介展开 + 线路/选集（倒序）+ 同类推荐。
 *
 * 结构参考 FongMi 与影视仓的详情页：播放入口与选集尽量往上放，
 * 有历史进度时播放按钮直接变为续播入口，不用再翻选集。
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private var siteKey: String = ""
    private var vodId: String = ""
    private var vodName: String = ""
    private var vodPic: String = ""
    private var detail: VodDetail? = null
    private var currentLine = 0

    /** 选集是否倒序展示；只影响展示顺序，播放定位一律按原顺序下标 */
    private var reversed = false

    private var descExpanded = false

    private val episodeAdapter = EpisodeAdapter { ep -> playAt(currentLine, ep) }
    private val relatedAdapter = RelatedAdapter { item -> openRelated(item) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        siteKey = intent.getStringExtra("siteKey") ?: ""
        vodId = intent.getStringExtra("vodId") ?: ""
        vodName = intent.getStringExtra("vodName") ?: ""
        vodPic = intent.getStringExtra("vodPic") ?: ""

        b.tvName.text = vodName
        // 头图用海报铺底：采集站不提供横图，放大裁剪 + 遮罩是成本最低的近似
        Glide.with(this).load(vodPic)
            .apply(RequestOptions().placeholder(R.color.surface_container).error(R.color.surface_container).transform(CenterCrop()))
            .into(b.ivBackdrop)
        Glide.with(this).load(vodPic)
            .apply(RequestOptions().placeholder(R.color.surface_container).error(R.color.surface_container).transform(RoundedCorners(dp(12))))
            .into(b.ivPoster)

        b.rvEpisodes.layoutManager = GridLayoutManager(this, 4)
        b.rvEpisodes.adapter = episodeAdapter
        b.rvRelated.adapter = relatedAdapter

        b.btnBack.setOnClickListener { finish() }
        b.btnPlay.setOnClickListener { playFirst() }
        b.btnFav.setOnClickListener { toggleFavorite() }
        b.btnFavText.setOnClickListener { toggleFavorite() }
        b.btnDescMore.setOnClickListener { toggleDesc() }
        b.btnReverse.setOnClickListener { toggleReverse() }

        renderResumeHint()
        updateFavIcon()
        loadDetail()
    }

    /** 有历史进度时：按钮变续播入口，并给一行上次看到哪的提示 */
    private fun renderResumeHint() {
        val record = HistoryStore.history().firstOrNull { it.vodId == vodId }
        if (record == null || record.playUrl.isEmpty()) {
            b.tvResumeHint.visibility = View.GONE
            return
        }
        val pct = record.progressPercent
        b.tvResumeHint.text = buildString {
            append("上次看到 ").append(record.episodeName.ifEmpty { "正片" })
            if (pct > 0) append(" · 已看 ").append(pct).append("%")
        }
        b.tvResumeHint.visibility = View.VISIBLE
        b.btnPlay.text = "续播：${record.episodeName.ifEmpty { "正片" }}"
    }

    private fun loadDetail() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val site = ConfigHolder.siteOf(siteKey) ?: return@runCatching null
                    SourceFactory.create(site).detail(vodId)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            b.progress.visibility = View.GONE
            result.onSuccess { d ->
                detail = d
                if (d == null) {
                    toast("未获取到详情")
                    return@onSuccess
                }
                bindDetail(d)
            }.onFailure { toast("详情加载失败: ${it.message}") }
        }
    }

    private fun bindDetail(d: VodDetail) {
        val meta = buildList {
            if (d.item.vodYear.isNotEmpty()) add(d.item.vodYear)
            if (d.item.vodArea.isNotEmpty()) add(d.item.vodArea)
            if (d.item.vodRemarks.isNotEmpty()) add(d.item.vodRemarks)
        }.joinToString(" · ")
        b.tvMeta.text = meta
        b.tvMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        b.tvActor.text = buildList {
            if (d.vodDirector.isNotEmpty()) add("导演：" + d.vodDirector)
            if (d.vodActor.isNotEmpty()) add("主演：" + d.vodActor)
        }.joinToString("\n").also {
            b.tvActor.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
        }
        val desc = d.vodContent.ifEmpty { d.vodDesc }
        b.tvDesc.text = desc
        b.tvDesc.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
        // 简介超过一行能放的字数才给展开按钮：等 layout 量行数要 post 一帧，
        // 时序不可靠（详情是网络回来后才绑定的），直接按字数判更稳
        b.btnDescMore.visibility = if (desc.length > 120) View.VISIBLE else View.GONE

        buildLineTabs()
        loadRelated(d.item)
    }

    private fun toggleDesc() {
        descExpanded = !descExpanded
        b.tvDesc.maxLines = if (descExpanded) Int.MAX_VALUE else 4
        b.btnDescMore.text = if (descExpanded) "收起" else "展开"
    }

    private fun buildLineTabs() {
        val lines = detail?.lines ?: emptyList()
        b.lineTabs.removeAllViews()
        b.tvLineCount.text = if (lines.isEmpty()) "" else "${lines.size} 条线路"
        if (lines.isEmpty()) {
            b.tvEmptyEp.visibility = View.VISIBLE
            b.rvEpisodes.visibility = View.GONE
            return
        }
        b.tvEmptyEp.visibility = View.GONE
        b.rvEpisodes.visibility = View.VISIBLE
        currentLine = currentLine.coerceIn(0, lines.indices.last)
        lines.forEachIndexed { index, line ->
            b.lineTabs.addView(
                newChip(line.name, index == currentLine, textSize = 13f) {
                    currentLine = index
                    buildLineTabs()
                }
            )
        }
        buildEpisodeGrid(lines[currentLine])
    }

    private fun buildEpisodeGrid(line: PlayLine) {
        val eps = if (reversed) line.episodes.reversed() else line.episodes
        episodeAdapter.submit(eps)
        b.tvEpCount.text = "共 ${line.episodes.size} 集"
        b.btnReverse.text = if (reversed) "正序" else "倒序"
    }

    private fun toggleReverse() {
        reversed = !reversed
        detail?.lines?.getOrNull(currentLine)?.let { buildEpisodeGrid(it) }
    }

    /** 同类推荐：同站同类型取一页，没有类型信息时退回本站推荐，排除自己 */
    private fun loadRelated(item: VodItem) {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { relatedOf(item) }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            relatedAdapter.submit(list)
            b.relatedBlock.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun relatedOf(item: VodItem): List<VodItem> {
        val site = ConfigHolder.siteOf(siteKey) ?: return emptyList()
        val src = SourceFactory.createOrNull(site) ?: return emptyList()
        val first = runCatching {
            if (item.typeId.isNotEmpty()) src.category(item.typeId, 1).items
            else src.home(1).items
        }.getOrDefault(emptyList())
        return first.filter { it.vodId != item.vodId }.take(12)
    }

    private fun openRelated(item: VodItem) {
        // 替换当前详情页，避免相关推荐叠多层后必须逐层返回
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra("siteKey", siteKey)
                .putExtra("vodId", item.vodId)
                .putExtra("vodName", item.vodName)
                .putExtra("vodPic", item.vodPic)
        )
        finish()
    }

    private fun playFirst() {
        // 有历史进度优先续播，找不到对应集数才退回第一集
        val record = HistoryStore.history().firstOrNull { it.vodId == vodId }
        val lines = detail?.lines ?: return
        if (record != null && record.playUrl.isNotEmpty()) {
            val line = lines.getOrNull(record.lineIndex)
            val ep = line?.episodes?.getOrNull(record.episodeIndex)
            if (ep != null) {
                playAt(record.lineIndex, ep)
                return
            }
        }
        val line = lines.getOrNull(currentLine) ?: return
        val ep = line.episodes.firstOrNull() ?: return
        playAt(currentLine, ep)
    }

    private fun playAt(lineIndex: Int, ep: Episode) {
        val lines = detail?.lines ?: return
        val line = lines.getOrNull(lineIndex) ?: return
        val epIndex = line.episodes.indexOf(ep)

        // 写入历史（全局无痕时跳过，收藏不受影响）
        if (!com.xing.tv.data.Prefs.incognito) HistoryStore.saveHistory(
            HistoryStore.Record(
                siteKey = siteKey,
                vodId = vodId,
                name = vodName,
                pic = vodPic,
                episodeName = ep.name,
                playUrl = ep.url,
                lineIndex = lineIndex,
                episodeIndex = epIndex,
                positionMs = HistoryStore.positionOf(vodId),
                updatedAt = System.currentTimeMillis()
            )
        )

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("siteKey", siteKey)
                .putExtra("vodId", vodId)
                .putExtra("vodName", vodName)
                // 播放器会用它写入历史记录，缺失会导致历史/收藏列表海报恒为空
                .putExtra("vodPic", vodPic)
                .putExtra("episodeName", ep.name)
                .putExtra("playUrl", ep.url)
                .putExtra("lineName", line.name)
                .putExtra("lineIndex", lineIndex)
                .putExtra("episodeIndex", epIndex)
                .putExtra("resumeMs", HistoryStore.positionOf(vodId))
        )
    }

    private fun toggleFavorite() {
        if (HistoryStore.isFavorite(vodId)) {
            HistoryStore.removeFavorite(vodId)
            toast("已取消收藏")
        } else {
            HistoryStore.saveFavorite(
                HistoryStore.Record(
                    siteKey = siteKey,
                    vodId = vodId,
                    name = vodName,
                    pic = vodPic,
                    episodeName = "",
                    playUrl = "",
                    lineIndex = 0,
                    episodeIndex = 0,
                    positionMs = 0,
                    updatedAt = System.currentTimeMillis()
                )
            )
            toast("已收藏")
        }
        updateFavIcon()
    }

    private fun updateFavIcon() {
        val fav = HistoryStore.isFavorite(vodId)
        val tint = android.content.res.ColorStateList.valueOf(
            getColor(if (fav) R.color.primary else R.color.text_muted)
        )
        b.btnFav.setImageResource(R.drawable.ic_star)
        // 无障碍：收藏状态不能只靠图标形状区分，否则 TalkBack 播报不出当前状态。
        b.btnFav.isSelected = fav
        b.btnFav.contentDescription = if (fav) "已收藏" else "收藏"
        b.btnFav.imageTintList = tint
        b.btnFavText.text = if (fav) getString(R.string.uncollect) else getString(R.string.collect)
        b.btnFavText.setTextColor(getColor(if (fav) R.color.primary else R.color.text_primary))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 选集适配器 */
    private class EpisodeAdapter(
        private val onClick: (Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        private val items = mutableListOf<Episode>()

        fun submit(list: List<Episode>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.b.tvEpisode.text = items[position].name
            holder.b.root.setOnClickListener { onClick(items[position]) }
        }
    }
}
