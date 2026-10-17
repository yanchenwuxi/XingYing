package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.xing.tv.R
import com.xing.tv.data.HistoryStore
import com.xing.tv.databinding.ActivityHistoryBinding

/**
 * 历史与收藏：两页签切换，点击续播。
 * 从「我的」进入时可直接落到收藏页。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding
    private lateinit var adapter: RecordAdapter
    private var showFavorite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = RecordAdapter(
            onClick = { record ->
                if (record.playUrl.isNotEmpty()) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
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
                    // 收藏条目没有播放地址，进详情页
                    startActivity(
                        Intent(this, DetailActivity::class.java)
                            .putExtra("siteKey", record.siteKey)
                            .putExtra("vodId", record.vodId)
                            .putExtra("vodName", record.name)
                            .putExtra("vodPic", record.pic)
                    )
                }
            },
            onLongClick = { record ->
                if (showFavorite) {
                    confirm(
                        "移除收藏",
                        "确定把「${record.name}」从收藏中移除吗？"
                    ) {
                        HistoryStore.removeFavorite(record.vodId)
                        toast("已移除收藏")
                        refresh()
                    }
                } else {
                    confirm(
                        "删除记录",
                        "确定删除「${record.name}」的观看记录吗？\n删除后无法恢复。"
                    ) {
                        HistoryStore.removeHistory(record.vodId)
                        toast("已删除该条记录")
                        refresh()
                    }
                }
                true // 消费长按事件，避免继续冒泡
            }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnClear.setOnClickListener {
            if (showFavorite) {
                confirm("清空收藏", "确定清空全部收藏吗？清空后无法恢复。") {
                    HistoryStore.clearFavorites()
                    toast("收藏已清空")
                    refresh()
                }
            } else {
                confirm("清空历史", "确定清空全部观看记录吗？清空后无法恢复。") {
                    HistoryStore.clearHistory()
                    toast("历史已清空")
                    refresh()
                }
            }
        }
        b.tabHistory.setOnClickListener { switchTo(false) }
        b.tabFav.setOnClickListener { switchTo(true) }

        switchTo(intent.getStringExtra("tab") == "favorite")
    }

    private fun switchTo(favorite: Boolean) {
        showFavorite = favorite
        val ctx = this
        b.tabHistory.setBackgroundResource(
            if (favorite) R.color.surface_high else R.color.primary_container
        )
        b.tabFav.setBackgroundResource(
            if (favorite) R.color.primary_container else R.color.surface_high
        )
        b.tabHistory.setTextColor(
            ctx.getColor(if (favorite) R.color.text_secondary else R.color.on_primary_container)
        )
        b.tabFav.setTextColor(
            ctx.getColor(if (favorite) R.color.on_primary_container else R.color.text_secondary)
        )
        b.btnClear.setImageResource(if (favorite) R.drawable.ic_star else R.drawable.ic_delete)
        b.btnClear.contentDescription = if (favorite) "清空收藏" else "清空历史"
        refresh()
    }

    private fun refresh() {
        val list = if (showFavorite) HistoryStore.favorites() else HistoryStore.history()
        adapter.submit(list)
        b.tvEmpty.text =
            if (showFavorite) "还没有收藏\n在详情页点右上角的星标即可收藏" else "还没有观看记录"
        // 空状态图标跟随页签：历史用时钟，收藏用描边星标
        b.ivEmpty.setImageResource(if (showFavorite) R.drawable.ic_star_outline else R.drawable.ic_history)
        b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 删除类操作统一走确认弹窗，避免误触直接清掉数据 */
    private fun confirm(title: String, message: String, action: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
