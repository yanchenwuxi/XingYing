package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xing.tv.R
import com.xing.tv.data.HistoryStore
import com.xing.tv.databinding.FragmentHistoryBinding

/** 底栏「历史」：播放历史 / 收藏两页签（对齐星海）。 */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private var _b: FragmentHistoryBinding? = null
    private val b: FragmentHistoryBinding get() = _b!!
    private lateinit var adapter: RecordAdapter
    private var showFavorite = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentHistoryBinding.bind(view)
        adapter = RecordAdapter(
            onClick = { record ->
                if (record.playUrl.isNotEmpty()) {
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
                } else {
                    startActivity(
                        Intent(requireContext(), DetailActivity::class.java)
                            .putExtra("siteKey", record.siteKey)
                            .putExtra("vodId", record.vodId)
                            .putExtra("vodName", record.name)
                            .putExtra("vodPic", record.pic)
                    )
                }
            },
            onLongClick = { record ->
                val title = if (showFavorite) "移除收藏" else "删除记录"
                val msg = if (showFavorite) "确定移除「${record.name}」？" else "确定删除「${record.name}」？"
                MaterialAlertDialogBuilder(requireContext(), R.style.Dialog_XingYing)
                    .setTitle(title)
                    .setMessage(msg)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定") { _, _ ->
                        if (showFavorite) HistoryStore.removeFavorite(record.vodId)
                        else HistoryStore.removeHistory(record.vodId)
                        refresh()
                    }
                    .show()
            }
        )
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter
        b.tabHistory.setOnClickListener { switchTab(false) }
        b.tabFavorite.setOnClickListener { switchTab(true) }
        b.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), R.style.Dialog_XingYing)
                .setTitle(if (showFavorite) "清空收藏" else "清空历史")
                .setMessage("确定清空全部？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    if (showFavorite) HistoryStore.clearFavorites()
                    else HistoryStore.clearHistory()
                    refresh()
                }
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun switchTab(fav: Boolean) {
        showFavorite = fav
        b.tabHistory.setTextColor(requireContext().getColor(if (!fav) R.color.primary else R.color.text_muted))
        b.tabFavorite.setTextColor(requireContext().getColor(if (fav) R.color.primary else R.color.text_muted))
        refresh()
    }

    private fun refresh() {
        val list = if (showFavorite) HistoryStore.favorites() else HistoryStore.history()
        adapter.submit(list)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.tvEmpty.text = if (showFavorite) "还没有收藏" else "还没有观看记录"
    }
}
