package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.SourceFactory
import com.xing.tv.data.Prefs
import com.xing.tv.databinding.FragmentShortBinding
import com.xing.tv.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 短剧页：对齐星海 PageShort。
 * 优先取分类名含「短剧/短视频/微短剧」的分类列表；拿不到再按关键词搜索。
 */
class ShortFragment : Fragment(R.layout.fragment_short) {

    private var _b: FragmentShortBinding? = null
    private val b: FragmentShortBinding get() = _b!!

    private val adapter by lazy { VodAdapter(onClick = { openDetail(it) }) }
    private var page = 1
    private var loadJob: Job? = null
    private var seq = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentShortBinding.bind(view)
        b.recycler.layoutManager = GridLayoutManager(requireContext(), Prefs.gridColumns)
        b.recycler.adapter = adapter
        b.btnSwitch.setOnClickListener { load(nextBatch = true) }
        b.btnRefresh.setOnClickListener { load(nextBatch = false) }
        load(nextBatch = false)
    }

    override fun onResume() {
        super.onResume()
        // 接口可能已切换
        if (_b != null && adapter.syncCount == 0) load(nextBatch = false)
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
        _b = null
    }

    private fun load(nextBatch: Boolean) {
        val site = ConfigHolder.config?.sites?.firstOrNull { it.supported }
            ?: ConfigHolder.config?.sites?.firstOrNull()
        if (site == null) {
            showEmpty("还没配置可用接口\n去首页切换或「我的 → 接口源」添加")
            return
        }
        if (nextBatch) page++ else page = 1
        val my = ++seq
        b.progress.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { fetchShorts(site.key, page) }.getOrDefault(emptyList())
            }
            if (_b == null || my != seq) return@launch
            b.progress.visibility = View.GONE
            if (list.isEmpty()) {
                showEmpty("这个接口没取到短剧数据\n换个接口或点右上角刷新")
                if (!nextBatch) adapter.submit(emptyList())
                return@launch
            }
            b.tvEmpty.visibility = View.GONE
            if (nextBatch) {
                // 换一批：直接替换为本页结果
                adapter.submit(list)
            } else {
                adapter.submit(list)
            }
        }
    }

    private fun fetchShorts(siteKey: String, page: Int): List<VodItem> {
        val site = ConfigHolder.siteOf(siteKey) ?: return emptyList()
        val src = SourceFactory.createOrNull(site) ?: return emptyList()
        // 1) 分类里找短剧
        val cats = runCatching { src.categories() }.getOrDefault(emptyList())
        val shortCat = cats.firstOrNull { c ->
            val n = c.name
            n.contains("短剧") || n.contains("短视频") || n.contains("微短剧") ||
                n.contains("竖屏") || n == "短剧" || n.contains("Mini")
        }
        if (shortCat != null) {
            val pageResult = runCatching { src.category(shortCat.id, page) }.getOrNull()
            if (pageResult != null && pageResult.items.isNotEmpty()) return pageResult.items
        }
        // 2) 搜索「短剧」
        val searched = runCatching { src.search("短剧", page) }.getOrNull()
        if (searched != null && searched.items.isNotEmpty()) return searched.items
        // 3) 首页列表兜底
        return runCatching { src.home(page).items }.getOrDefault(emptyList())
    }

    private fun showEmpty(msg: String) {
        if (_b == null) return
        b.progress.visibility = View.GONE
        b.tvEmpty.visibility = View.VISIBLE
        b.tvEmpty.text = msg
    }

    private fun openDetail(item: VodItem) {
        val site = ConfigHolder.config?.sites?.firstOrNull { it.supported }
            ?: ConfigHolder.config?.sites?.firstOrNull()
            ?: return
        startActivity(
            Intent(requireContext(), DetailActivity::class.java)
                .putExtra("siteKey", site.key)
                .putExtra("vodId", item.vodId)
                .putExtra("vodName", item.vodName)
                .putExtra("vodPic", item.vodPic)
        )
    }

    companion object {
        fun newInstance() = ShortFragment()
    }
}
