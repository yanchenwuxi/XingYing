package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.ConfigProvider
import com.xing.tv.api.SourceFactory
import com.xing.tv.data.SearchHistory
import com.xing.tv.databinding.FragmentSearchBinding
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 搜索：跨可搜索站点检索 + 搜索历史。
 *
 * 支持「全部站点」聚合检索——公开配置动辄几十个站点，逐个手动切换试错的代价太高，
 * 聚合检索会并发请求所有可搜索站点、按片名去重后合并展示，并在副标题标注来源站点。
 *
 * 搜索历史只存本地（SharedPreferences），不上传任何服务器。
 */
class SearchFragment : Fragment(R.layout.fragment_search) {

    /** 聚合结果里站点与影片的关联；vodId 带站点前缀，见 [SEP] */
    private var hits: List<Pair<TvConfig.Site, VodItem>> = emptyList()

    /** 站点是否已就位，用于决定要不要默认落到聚合检索 */
    private var sitesReady = false

    private var _b: FragmentSearchBinding? = null
    private val b: FragmentSearchBinding get() = _b!!

    private val adapter = VodAdapter(
        onClick = { openDetail(it) },
        // 聚合检索时同一部片可能来自几十个站点，用副标题位置标注来源站点
        extraOf = { item -> VodAdapter.VodExtra(meta = sourceLabel(item) ?: item.vodYear.ifEmpty { item.vodRemarks }) }
    )

    private var sites: List<TvConfig.Site> = emptyList()
    private var current: TvConfig.Site? = null

    /** 搜索令牌：连点两次搜索时丢弃前一次结果 */
    private var token = 0

    /** 聚合检索时把站点 key 编进 vodId 的分隔符，同时保证 DiffUtil 不会把不同站点的同名 id 当成同一条 */
    private val sep = "@@@"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentSearchBinding.bind(view)

        applyResultStyle()
        b.recycler.adapter = adapter

        b.btnDoSearch.setOnClickListener { doSearch() }
        b.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        b.btnClearHistory.setOnClickListener {
            SearchHistory.clear()
            renderHistory()
            toast("搜索历史已清空")
        }

        if (ConfigHolder.sites.isEmpty()) ensureConfig() else renderSites()
        renderHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun ensureConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ConfigProvider.fetch(ConfigProvider.activeUrl(), force = false) }
            }
            if (_b == null) return@launch
            renderSites()
        }
    }

    // ---------- 站点 ----------

    private fun renderSites() {
        sites = ConfigHolder.sites.filter { it.searchable == 1 && !it.isScript }
        // 首次拿到站点时默认落到聚合：站点一多，逐个手动切换试错的体验太差
        if (!sitesReady) {
            sitesReady = true
            if (sites.size > 1) current = null
        }
        if (current != null && current !in sites) current = sites.firstOrNull()
        b.siteTabs.removeAllViews()
        val ctx = context ?: return
        if (sites.size > 1) {
            b.siteTabs.addView(
                ctx.newChip("全部 ${sites.size} 个站点", current == null, textSize = 12f, maxWidthDp = 150) {
                    if (current != null) {
                        current = null
                        renderSites()
                    }
                }
            )
        }
        sites.forEach { site ->
            b.siteTabs.addView(
                // 站点名常带推广语与表情，不截断会占满整行
                ctx.newChip(site.name.stripEmoji(), site.key == current?.key, textSize = 12f, maxWidthDp = 130) {
                    if (current?.key != site.key) {
                        current = site
                        renderSites()
                    }
                }
            )
        }
    }

    // ---------- 历史 ----------

    private fun renderHistory() {
        val list = SearchHistory.all()
        val ctx = context ?: return
        b.historyTabs.removeAllViews()
        list.forEach { kw ->
            b.historyTabs.addView(ctx.newQuietChip(kw, textSize = 12f) {
                b.etKeyword.setText(kw)
                b.etKeyword.setSelection(kw.length)
                doSearch()
            })
        }
        // 历史块与结果网格同处一个 FrameLayout：一旦有结果就把历史收起，
        // 否则历史胶囊会盖在海报上（FrameLayout 的子视图是叠加而非排列）
        b.historyBlock.visibility =
            if (list.isEmpty() || adapter.syncCount > 0) View.GONE else View.VISIBLE
        // 没有关键词也没有结果时给出引导，而不是空白
        if (list.isEmpty() && adapter.syncCount == 0) {
            b.tvEmpty.text = getString(R.string.no_history)
            b.emptyState.visibility = View.VISIBLE
        }
    }

    /** 搜索结果样式：列表（1 列）/ 网格（2 列），跟「个性化设置」走 */
    private fun applyResultStyle() {
        if (_b == null) return
        b.recycler.layoutManager =
            if (com.xing.tv.data.Prefs.searchGrid) {
                GridLayoutManager(requireContext(), 2)
            } else {
                androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            }
        lastSearchGrid = com.xing.tv.data.Prefs.searchGrid
    }

    private var lastSearchGrid: Boolean? = null

    override fun onResume() {
        super.onResume()
        if (lastSearchGrid != null && lastSearchGrid != com.xing.tv.data.Prefs.searchGrid) {
            applyResultStyle()
            adapter.notifyDataSetChanged()
        }
    }

    /** 精确匹配：只保留片名里含关键词的结果（影视仓「搜索模式：精确匹配」） */
    private fun exactFilter(items: List<VodItem>, kw: String): List<VodItem> {
        if (!com.xing.tv.data.Prefs.searchExact) return items
        val key = kw.replace(" ", "").lowercase()
        if (key.isEmpty()) return items
        val filtered = items.filter { it.vodName.replace(" ", "").lowercase().contains(key) }
        // 过滤后一条不剩时不要给出空白页：退回原结果，宁多不漏
        return filtered.ifEmpty { items }
    }

    // ---------- 搜索 ----------

    private fun doSearch() {
        val kw = b.etKeyword.text.toString().trim()
        if (kw.isEmpty()) {
            toast("请输入关键词")
            return
        }
        if (sites.isEmpty()) {
            toast("没有可搜索的站点")
            return
        }

        hideKeyboard()
        SearchHistory.push(kw)
        renderHistory()

        val selected = current
        val t = ++token
        hits = emptyList()
        adapter.submit(emptyList())
        b.progress.visibility = View.VISIBLE
        b.emptyState.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (selected == null) searchAll(kw)
                    else SourceFactory.create(selected).search(kw, 1).items
                }
            }
            if (_b == null || t != token) return@launch
            b.progress.visibility = View.GONE
            result.onSuccess { raw ->
                val items = exactFilter(raw, kw)
                adapter.submit(items)
                if (items.isEmpty()) {
                    b.tvEmpty.text =
                        if (selected == null) "全部 ${sites.size} 个站点都没找到「$kw」\n换个关键词试试"
                        else "没有找到「$kw」\n换个站点或换个关键词试试"
                    b.emptyState.visibility = View.VISIBLE
                } else {
                    b.emptyState.visibility = View.GONE
                    if (selected == null) toast("共 ${items.size} 条结果")
                }
            }.onFailure {
                adapter.submit(emptyList())
                b.tvEmpty.text = "搜索失败\n${it.message}"
                b.emptyState.visibility = View.VISIBLE
            }
            // 结果落地后重算历史块显隐：有结果时历史必须让位给网格
            renderHistory()
        }
    }

    /**
     * 聚合检索所有可搜索站点。
     *
     * 公开配置动辄几十个站点，其中相当一部分连不上或响应缓慢，因此：
     *  - 用 [Semaphore] 限制并发，避免一次性打出几十条连接；
     *  - 每个站点加超时，单个站点挂死不会拖垮整体；
     *  - 单站失败静默跳过，其余站点的结果照常展示。
     *
     * 结果按站点**轮流取**而非整段拼接：各站点内部已经排好序，
     * 直接 flatten 会让第一个返回几十条的站点把其余站点全挤到看不见，
     * 那样聚合检索就退化成了单站点检索。
     *
     * 返回值已按片名去重，并把站点 key 编进 vodId（见 [sep]），
     * 既让 DiffUtil 不会把不同站点的同名 id 当成同一条，也让点击时能找回来源站点。
     */
    private suspend fun searchAll(kw: String): List<VodItem> = coroutineScope {
        val gate = Semaphore(MAX_PARALLEL)
        // 站点 key 与该站点的结果一起带出来，避免事后按内容反查来源
        val perSite: List<Pair<TvConfig.Site, List<VodItem>>> = sites.map { site ->
            async {
                gate.withPermit {
                    val items = withTimeoutOrNull(SITE_TIMEOUT_MS) {
                        runCatching { SourceFactory.create(site).search(kw, 1).items }.getOrNull()
                    }.orEmpty().filter { it.vodName.isNotBlank() }
                    site to items
                }
            }
        }.awaitAll().filter { it.second.isNotEmpty() }

        val merged = mutableListOf<Pair<TvConfig.Site, VodItem>>()
        val seenNames = HashSet<String>()
        // 不同站点对同一部片的叫法略有出入，按片名去重即可，避免满屏重复
        for (i in 0 until (perSite.maxOfOrNull { it.second.size } ?: 0)) {
            for ((site, items) in perSite) {
                val item = items.getOrNull(i) ?: continue
                if (!seenNames.add(item.vodName.trim())) continue
                merged += site to item
            }
        }
        hits = merged
        merged.map { (site, item) -> item.copy(vodId = "${site.key}$sep${item.vodId}") }
    }

    /** 聚合结果的来源站点名；单站点检索时返回 null（此时没有标注的必要） */
    private fun sourceLabel(item: VodItem): String? {
        if (item.vodId.indexOf(sep) < 0) return null
        return sites.firstOrNull { item.vodId.startsWith(it.key + sep) }?.name?.stripEmoji()
    }

    private fun hideKeyboard() {
        val imm = context?.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(b.etKeyword.windowToken, 0)
    }

    private fun openDetail(item: VodItem) {
        // 聚合结果的 vodId 带站点前缀，得先还原成站点 key + 原始 id
        val siteKey = sites.firstOrNull { item.vodId.startsWith(it.key + sep) }?.key
            ?: current?.key
            ?: return
        val vodId = item.vodId.substringAfter(sep, item.vodId)
        startActivity(
            Intent(requireContext(), DetailActivity::class.java)
                .putExtra("siteKey", siteKey)
                .putExtra("vodId", vodId)
                .putExtra("vodName", item.vodName)
                .putExtra("vodPic", item.vodPic)
        )
    }

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /** 聚合检索的并发上限：几十个站点一起打出去会让部分 CDN 直接拒绝 */
        private const val MAX_PARALLEL = 8

        /** 单站点超时：某个站点挂死不应拖垮整次检索 */
        private const val SITE_TIMEOUT_MS = 8_000L
    }
}
