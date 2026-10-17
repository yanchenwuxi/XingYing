package com.xing.tv.ui

import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.ConfigProvider
import com.xing.tv.data.HistoryStore
import com.xing.tv.data.Prefs
import com.xing.tv.data.SourceStore
import com.xing.tv.databinding.FragmentHomeBinding
import com.xing.tv.model.Category
import com.xing.tv.model.FilterOptions
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodFilter
import com.xing.tv.model.VodItem

/**
 * 首页：站点（线路）切换 + 分类标签 + 影片网格（左右滑切分类）。
 *
 * 界面只负责渲染与转发用户操作，数据与取数流程全部由 [MainViewModel] 持有：
 * - 配置地址变化（从设置页返回）时才重新拉取，onResume 反复触发不会重复请求
 * - 切站/切分类时按当前上下文丢弃过期结果
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _b: FragmentHomeBinding? = null
    private val b: FragmentHomeBinding get() = _b!!

    private val vm: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val adapter by lazy {
        VodAdapter(
            onClick = { openDetail(it) },
            extraOf = { item -> extraFor(item) }
        )
    }

    /** 最近观看索引：海报上显示进度与续播角标 */
    private val watched = HashMap<String, HistoryStore.Record>()

    /** 本地接口文件选择器；选到后交给 SourceSheet 落盘入库 */
    private val pickConfig = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) SourceSheet.importUri(this, uri) { vm.start() } }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentHomeBinding.bind(view)

        // 星海式顶栏：搜索进搜索页；站点图标切换站；长按打开仓库；刷新重载
        b.searchBar.setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        b.btnLogo.setOnClickListener { openSources() }
b.btnLogo.setOnLongClickListener { openSources(); true }
b.tvSiteName.setOnClickListener { openSources() }
        b.btnRefresh.setOnClickListener {
            Toast.makeText(requireContext(), "正在刷新…", Toast.LENGTH_SHORT).show()
            vm.reload()
        }
        b.btnAllCats.setOnClickListener { showCategoryPicker() }
        b.btnRetry.setOnClickListener { vm.reload() }

        // 列数跟「个性化设置 → 海报展示样式」走，切回首页时若改过会重建
        b.recycler.layoutManager = GridLayoutManager(requireContext(), Prefs.gridColumns)
        lastGridColumns = Prefs.gridColumns
        b.recycler.adapter = adapter
        b.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (_b == null) return
                val lm = rv.layoutManager as GridLayoutManager
                b.btnTop.visibility =
                    if (lm.findFirstVisibleItemPosition() > 8) View.VISIBLE else View.GONE
                if (dy <= 0) return
                if (lm.findLastVisibleItemPosition() >= adapter.syncCount - 6) {
                    vm.loadMore()
                }
            }
        })
        // 左右滑动切换分类（对齐星海）
        val gd = android.view.GestureDetector(
            requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                private val minDx = 120
                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val cats = vm.categories.value.orEmpty()
                    if (cats.isEmpty()) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) < minDx || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.2f) return false
                    val all = listOf(com.xing.tv.model.Category("", "推荐")) + cats
                    val cur = vm.selectedCategory.value.orEmpty()
                    val idx = all.indexOfFirst { it.id == cur }.coerceAtLeast(0)
                    if (dx < 0 && idx < all.size - 1) {
                        vm.selectCategory(all[idx + 1].id)
                        return true
                    }
                    if (dx > 0 && idx > 0) {
                        vm.selectCategory(all[idx - 1].id)
                        return true
                    }
                    return false
                }
            }
        )
        b.recycler.setOnTouchListener { _, event ->
            gd.onTouchEvent(event)
            false
        }

        b.refresh.setOnRefreshListener { vm.reload() }
        b.btnTop.setOnClickListener {
            b.recycler.smoothScrollToPosition(0)
            b.btnTop.visibility = View.GONE
        }

        observeState()
        vm.start()
    }

    /** 上次渲染用的列数：从个性化设置返回时会变 */
    private var lastGridColumns = 3

    override fun onResume() {
        super.onResume()
        if (_b != null && lastGridColumns != Prefs.gridColumns) {
            lastGridColumns = Prefs.gridColumns
            b.recycler.layoutManager = GridLayoutManager(requireContext(), lastGridColumns)
            adapter.notifyDataSetChanged()
        }
        // 从设置页回来时，若配置地址变化则重新加载（地址未变时 ViewModel 内部会跳过）
        vm.start()
        renderSourceRow()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun observeState() {
        vm.sites.observe(viewLifecycleOwner) { renderSiteTabs(); renderSiteName() }
        vm.currentSite.observe(viewLifecycleOwner) { renderSiteTabs(); renderSiteName() }
        vm.categories.observe(viewLifecycleOwner) { renderCategoryTabs() }
        vm.selectedCategory.observe(viewLifecycleOwner) { renderCategoryTabs() }
        vm.filter.observe(viewLifecycleOwner) { renderCategoryTabs() }

        vm.items.observe(viewLifecycleOwner) { list ->
            refreshWatchIndex()
            adapter.submit(list)
            updateEmpty(list.isEmpty())
        }

        vm.initialLoading.observe(viewLifecycleOwner) { showProgress(it) }
        vm.refreshing.observe(viewLifecycleOwner) { b.refresh.isRefreshing = it }

        vm.toast.observe(viewLifecycleOwner) { event ->
            event.consume()?.let {
                context?.let { ctx -> Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ---------- 继续观看 ----------

    private fun resume(record: HistoryStore.Record) {
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("siteKey", record.siteKey)
                .putExtra("vodId", record.vodId)
                .putExtra("vodName", record.name)
                .putExtra("vodPic", record.pic)
                .putExtra("episodeName", record.episodeName)
                .putExtra("playUrl", record.playUrl)
                .putExtra("lineIndex", record.lineIndex)
                .putExtra("episodeIndex", record.episodeIndex)
                .putExtra("resumeMs", record.positionMs)
        )
    }

    private fun refreshWatchIndex() {
        watched.clear()
        HistoryStore.history().forEach { watched[it.vodId] = it }
    }

    private fun extraFor(item: VodItem): VodAdapter.VodExtra {
        val record = watched[item.vodId] ?: return VodAdapter.VodExtra(
            meta = item.vodYear.ifEmpty { item.vodRemarks }
        )
        val pct = record.progressPercent
        return VodAdapter.VodExtra(
            meta = if (pct > 0) "已看 $pct%" else item.vodYear.ifEmpty { item.vodRemarks },
            progress = pct,
            resume = record.positionMs > 0L
        )
    }

    private fun renderSiteName() {
        val site = vm.currentSite.value
        val raw = site?.name?.stripEmoji()?.ifBlank { site.key } ?: "未配置接口"
        val name = if (raw.length > 7) raw.take(7) else raw
        b.tvSiteName.text = name
        b.tvSiteName.setTextColor(requireContext().getColor(R.color.text_primary))
    }

    // ---------- 接口行 ----------

    private fun openSources() {
        SourceSheet.show(this, pickConfig) {
            renderSourceRow()
            vm.start()
        }
    }

    /** 接口行显示当前接口名；弹窗里切换后 onResume 会重刷 */
    private fun renderSourceRow() {
        if (_b == null) return
        val url = ConfigProvider.activeUrl()
        b.btnSource.text = when {
            com.xing.tv.data.LocalConfigStore.isLocal(url) ->
                "本地：" + url.removePrefix(com.xing.tv.data.LocalConfigStore.PREFIX)
            url.isBlank() -> "未配置接口"
            url.startsWith(ConfigProvider.ASSET_PREFIX) -> "本地资源"
            else -> SourceStore.all().firstOrNull { it.url == Prefs.configUrl }?.name
                ?.stripEmoji()?.ifEmpty { null } ?: url
        }
    }

    // ---------- 标签 ----------

    /** 星海布局不再用站点横滑条，站点切换改由搜索条内图标 + 弹窗完成 */
    private fun renderSiteTabs() {
        // no-op：保留观察者调用，避免改动 observe 链
    }

    private fun showCategoryPicker() {
        val ctx = context ?: return
        val cats = vm.categories.value.orEmpty()
        val all = listOf(Category("", "推荐")) + cats
        if (all.isEmpty()) return
        val selected = vm.selectedCategory.value.orEmpty()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet_translucent)
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(18))
        }
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = ctx.dp(10)
            }
            setBackgroundColor(0xFFFFB40A.toInt())
        })
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(android.widget.TextView(ctx).apply {
            text = "全部分类"
            textSize = 16f
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val closeBtn = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.text_secondary))
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }
        titleRow.addView(closeBtn)
        root.addView(titleRow)

        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 3
            setPadding(0, ctx.dp(12), 0, 0)
        }
        val sheetRef = arrayOf<com.google.android.material.bottomsheet.BottomSheetDialog?>(null)
        all.forEach { cat ->
            val cell = android.widget.TextView(ctx).apply {
                text = cat.name
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
                setBackgroundResource(R.drawable.bg_chip_panel)
                val on = cat.id == selected
                setTextColor(ctx.getColor(if (on) R.color.primary else R.color.text_primary))
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                }
                setOnClickListener {
                    sheetRef[0]?.dismiss()
                    vm.selectCategory(cat.id)
                }
            }
            grid.addView(cell)
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(grid)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        }
        root.addView(scroll)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.Sheet_XingYing)
        sheet.setContentView(root)
        sheet.window?.setDimAmount(0.45f)
        closeBtn.setOnClickListener { sheet.dismiss() }
        sheetRef[0] = sheet
        sheet.show()
    }

    /** 站点列表弹窗：当前站点打勾，点按即切换 */
    private fun showSitePicker() {
        val ctx = context ?: return
        val sites = vm.sites.value.orEmpty()
        if (sites.isEmpty()) return
        val currentKey = vm.currentSite.value?.key
        val names = sites.map { it.name.stripEmoji().ifEmpty { it.key } }.toTypedArray()
        val checked = sites.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("切换站点（${sites.size} 个）")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                vm.selectSite(sites[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderCategoryTabs() {
        // 站点没声明分类时只剩「推荐」一个占位，整行隐藏，不为它单占一行高度
        val cats = vm.categories.value.orEmpty()
        if (vm.sites.value.isNullOrEmpty() || cats.isEmpty()) {
            b.catTabsScroll.visibility = View.GONE
            b.btnAllCats.visibility = View.GONE
            b.catTabs.removeAllViews()
            // 没有分类时也要汇报一次，否则底部筛选图标会停在上一个站点的状态
            (activity as? MainActivity)?.syncFilterEntry(false, 0)
            return
        }
        b.catTabsScroll.visibility = View.VISIBLE
        val selected = vm.selectedCategory.value.orEmpty()
        val all = listOf(Category("", "推荐")) + cats
        b.catTabs.removeAllViews()
        val ctx = requireContext()
        all.forEach { cat ->
            b.catTabs.addView(
                ctx.newChip(cat.name, cat.id == selected, textSize = 12f, maxWidthDp = 120) {
                    // 只重渲染选中态，分类列表不重复拉取
                    vm.selectCategory(cat.id)
                }
            )
        }
        b.btnAllCats.visibility = if (cats.size > 1) View.VISIBLE else View.GONE
        renderFilterBar()
    }

    /**
     * 筛选入口已挪到底部导航栏右上角（[MainActivity] 持有那个图标）。
     * 这里只把「当前站点是否支持筛选」与「已选条件数」上报，首页顶部不再占一行。
     */
    private fun renderFilterBar() {
        val active = vm.filter.value ?: VodFilter()
        (activity as? MainActivity)?.syncFilterEntry(vm.supportsFilter(), active.selectedCount())
    }

    /** 底部筛选图标点击入口：不支持筛选时给一句提示，不做无效弹窗 */
    fun openFilterSheet() {
        if (!isAdded) return
        if (!vm.supportsFilter()) {
            context?.let { Toast.makeText(it, "当前站点不支持筛选", Toast.LENGTH_SHORT).show() }
            return
        }
        showFilterSheet()
    }

    /** 给 Activity 判断图标是否该显示（Fragment 未挂载时按不可用处理） */
    fun filterEntryVisible(): Boolean = isAdded && _b != null && vm.supportsFilter()

    /** 当前已选筛选条件数，用于图标角标 */
    fun filterActiveCount(): Int = (vm.filter.value ?: VodFilter()).selectedCount()

    /**
     * 分类筛选弹窗（仿影视仓筛选弹窗：标题 + 重置 + 分组选项 + 确定）。
     * 年份/地区/类型三组单选，「全部」即不清该项。
     */
    private fun showFilterSheet() {
        val ctx = requireContext()
        var area = vm.filter.value?.area.orEmpty()
        var year = vm.filter.value?.year.orEmpty()
        var cls = vm.filter.value?.cls.orEmpty()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.dp(16)
            setPadding(pad, ctx.dp(8), pad, ctx.dp(16))
        }
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = ctx.dp(4)
            }
            setBackgroundResource(R.drawable.bg_tab_indicator)
            alpha = 0.5f
        })

        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(android.widget.TextView(ctx).apply {
            text = "筛选"
            textSize = 17f
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(android.widget.TextView(ctx).apply {
            text = "重置"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_muted))
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(4), ctx.dp(6))
            setOnClickListener {
                // 最简单可靠的复位：关掉并直接落空筛选，不用逐个复位 chips
                sheet?.dismiss()
                vm.setFilter(VodFilter())
            }
        })
        root.addView(titleRow)

        // 三组各自横滑单选；闭包写起来绕，直接展开写三遍更清楚
        root.addView(filterGroup(ctx, "年份", FilterOptions.years, year) { year = it })
        root.addView(filterGroup(ctx, "地区", FilterOptions.areas, area) { area = it })
        root.addView(filterGroup(ctx, "类型", FilterOptions.classes, cls) { cls = it })

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(14), 0, 0)
        }
        val reset = android.widget.TextView(ctx).apply {
            text = "重置"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_btn_gray)
            setPadding(0, ctx.dp(12), 0, ctx.dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = ctx.dp(10)
            }
            setOnClickListener {
                sheet?.dismiss()
                vm.setFilter(VodFilter())
            }
        }
        val confirm = android.widget.TextView(ctx).apply {
            text = "确定"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.getColor(R.color.on_primary))
            setBackgroundResource(R.drawable.bg_btn_accent)
            setPadding(0, ctx.dp(12), 0, ctx.dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener {
                sheet?.dismiss()
                vm.setFilter(VodFilter(area = area, year = year, cls = cls))
            }
        }
        btnRow.addView(reset)
        btnRow.addView(confirm)
        root.addView(btnRow)

        sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.Sheet_XingYing)
        sheet?.setContentView(root)
        sheet?.setOnDismissListener { sheet = null }
        sheet?.show()
    }

    private var sheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    /** 一组横滑单选 chips（含「全部」） */
    private fun filterGroup(
        ctx: android.content.Context,
        label: String,
        options: List<String>,
        selected: String,
        onPick: (String) -> Unit
    ): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(10), 0, 0)
        }
        box.addView(android.widget.TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_muted))
            setPadding(0, 0, 0, ctx.dp(6))
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableListOf<android.widget.TextView>()
        (listOf("") + options).forEach { opt ->
            val chip = ctx.newChip(
                opt.ifEmpty { "全部" }, opt == selected, textSize = 12f, maxWidthDp = 110
            ) {
                onPick(opt)
                chips.forEachIndexed { i, c ->
                    val on = (listOf("") + options)[i] == opt
                    c.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ctx.getColor(if (on) R.color.primary_container else R.color.surface_high)
                    )
                    c.setTextColor(ctx.getColor(if (on) R.color.on_primary_container else R.color.text_secondary))
                }
            }
            chips += chip
            row.addView(chip)
            // chips 之间留缝：newChip 自带横向 padding，补一个小间隔即可
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(6), 1)
            })
        }
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        box.addView(scroll)
        return box
    }

    // ---------- 状态 ----------

    private fun updateEmpty(empty: Boolean) {
        val loading = b.progress.visibility == View.VISIBLE
        b.emptyState.visibility = if (empty && !loading) View.VISIBLE else View.GONE
        val diag = ConfigHolder.diag
        b.tvEmpty.text = when {
            // 配置加载成功但没有一个站点能被驱动：直接说明原因，别让用户反复重试
            vm.sites.value.isNullOrEmpty() && diag.noUsableSite ->
                buildString {
                    append("该配置 ${diag.totalSites} 个站点均无法驱动\n")
                    when {
                        diag.usableLives > 0 ->
                            append("直播源 ${diag.usableLives} 个可用，可切到「直播」页")
                        diag.livesAllLocal ->
                            append("直播源地址指向客户端内置代理（127.0.0.1）\n本版本不提供该服务，请更换配置")
                        else -> append("请点击左上角图标更换接口源")
                    }
                }
            vm.sites.value.isNullOrEmpty() -> "接口加载失败\n可在「我的 → 接口源」重新配置"
            else -> "暂无内容"
        }
    }

    private fun showProgress(show: Boolean) {
        b.progress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) b.emptyState.visibility = View.GONE
        else updateEmpty(adapter.syncCount == 0)
    }

    private fun openDetail(item: VodItem) {
        val site = vm.currentSite.value ?: return
        startActivity(
            Intent(requireContext(), DetailActivity::class.java)
                .putExtra("siteKey", site.key)
                .putExtra("vodId", item.vodId)
                .putExtra("vodName", item.vodName)
                .putExtra("vodPic", item.vodPic)
        )
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}
