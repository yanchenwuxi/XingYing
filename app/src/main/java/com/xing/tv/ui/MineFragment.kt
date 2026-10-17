package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xing.tv.BuildConfig
import com.xing.tv.R
import com.xing.tv.api.ConfigLoader
import com.xing.tv.api.ConfigProvider
import com.xing.tv.data.HistoryStore
import com.xing.tv.data.LocalConfigStore
import com.xing.tv.data.Prefs
import com.xing.tv.data.SourceStore
import com.xing.tv.databinding.FragmentMineBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的：对齐星海 — 头像卡 + 4 列宫格 + 下方列表。
 */
class MineFragment : Fragment(R.layout.fragment_mine) {

    private var _b: FragmentMineBinding? = null
    private val b: FragmentMineBinding get() = _b!!

    private val pickConfig = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) SourceSheet.importUri(this, uri) { refreshStats() } }

    private data class GridItem(val name: String, val icon: Int, val action: () -> Unit)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentMineBinding.bind(view)
        buildGrid()
        buildList()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun buildGrid() {
        val ctx = requireContext()
        b.mineGrid.removeAllViews()
        val items = listOf(
            GridItem("播放历史", R.drawable.ic_history) {
                (activity as? MainActivity)?.let {
                    // 切到底栏历史
                }
                startActivity(Intent(ctx, HistoryActivity::class.java))
            },
            GridItem("我的收藏", R.drawable.ic_star) {
                startActivity(Intent(ctx, HistoryActivity::class.java).putExtra("favorite", true))
            },
            GridItem("仓库管理", R.drawable.ic_site) {
                SourceSheet.show(this, pickConfig) { refreshStats() }
            },
            GridItem("播放设置", R.drawable.ic_settings) {
                startActivity(Intent(ctx, SettingsActivity::class.java))
            },
            GridItem("个性化", R.drawable.ic_grid) {
                startActivity(Intent(ctx, PersonalityActivity::class.java))
            },
            GridItem("清除缓存", R.drawable.ic_delete) { clearCache() },
            GridItem("检查更新", R.drawable.ic_refresh) { checkUpdate() },
            GridItem("关于", R.drawable.ic_info) { showAbout() }
        )
        val colW = (resources.displayMetrics.widthPixels - ctx.dp(16)) / 4
        items.forEach { item ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ctx.dp(12), 0, ctx.dp(12))
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = colW
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                }
                setOnClickListener { item.action() }
            }
            cell.addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28))
                setImageResource(item.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
            })
            cell.addView(TextView(ctx).apply {
                text = item.name
                textSize = 12f
                setTextColor(ctx.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(6), 0, 0)
            })
            b.mineGrid.addView(cell)
        }
    }

    private fun buildList() {
        val ctx = requireContext()
        b.mineList.removeAllViews()
        fun row(title: String, hint: String, onClick: () -> Unit) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ctx.dp(8) }
                setOnClickListener { onClick() }
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTextColor(ctx.getColor(R.color.text_primary))
            })
            texts.addView(TextView(ctx).apply {
                text = hint
                textSize = 12f
                setTextColor(ctx.getColor(R.color.text_muted))
                setPadding(0, ctx.dp(2), 0, 0)
            })
            row.addView(texts)
            row.addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18))
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.text_muted))
            })
            b.mineList.addView(row)
        }
        row("免责声明", "资源来源与使用范围说明") { showDisclaimer() }
        row("开源说明", "版本信息与能力列表") { showAbout() }
    }

    private fun refreshStats() {
        if (_b == null) return
        val hist = HistoryStore.history().size
        val fav = HistoryStore.favorites().size
        val src = SourceStore.all().size.coerceAtLeast(1)
        b.tvMineSub.text = "历史 $hist · 收藏 $fav · 接口 $src"
        b.tvVersion.text = "星影 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private fun clearCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Glide.get(requireContext()).clearDiskCache() }
                runCatching { ConfigLoader.clearCache() }
            }
            context?.let { Toast.makeText(it, "缓存已清理", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showAbout() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("关于星影")
            .setMessage(
                "版本  v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
                    "支持 macCMS / T4 / Python / jar 爬虫 / JS 源。\n" +
                    "数据仅保存在本机。仅供学习研究。"
            )
            .setPositiveButton("好的", null)
            .show()
    }

    private fun showDisclaimer() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("免责声明")
            .setMessage(
                "本应用只提供聚合展示与播放能力，不制作、不上传、不存储任何影片资源。\n\n" +
                    "所有内容均来自用户自行配置的第三方接口。"
            )
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun checkUpdate() {
        val act = activity ?: return
        val ctx = context ?: return
        val progress = MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("检查更新")
            .setMessage("正在检查…")
            .setCancelable(false)
            .create()
        progress.show()
        com.xing.tv.net.Updater.check(
            ctx, com.xing.tv.net.Updater.DEFAULT_UPDATE_JSON,
            object : com.xing.tv.net.Updater.Listener {
                override fun onChecking() {}
                override fun onLatest(current: String) {
                    progress.dismiss()
                    MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
                        .setTitle("检查更新")
                        .setMessage("已是最新版本 v$current")
                        .setPositiveButton("好的", null)
                        .show()
                }
                override fun onAvailable(info: com.xing.tv.net.Updater.Info) {
                    progress.dismiss()
                    MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
                        .setTitle("发现新版本")
                        .setMessage("v${info.versionName}\n\n${info.notes}")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("下载安装") { _, _ ->
                            com.xing.tv.net.Updater.downloadAndInstall(act, info, this)
                        }
                        .show()
                }
                override fun onProgress(percent: Int, read: Long, total: Long) {}
                override fun onInstalling() {
                    Toast.makeText(ctx, "正在安装…", Toast.LENGTH_SHORT).show()
                }
                override fun onError(msg: String) {
                    progress.dismiss()
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun android.content.Context.dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
