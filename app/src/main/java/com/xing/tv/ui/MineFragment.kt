package com.xing.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
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
 * 我的：用户卡 + 数据看板 + 功能列表。
 *
 * 功能收进「图标 + 标题 + 摘要 + 箭头」的列表行：清理缓存与关于直接在本页完成，
 * 其余跳现有页面（历史 / 收藏 / 接口源 / 播放设置），不重复造逻辑。
 * 统计数字在 onResume 时刷新，避免从历史页返回后还是旧值。
 */
class MineFragment : Fragment(R.layout.fragment_mine) {

    private var _b: FragmentMineBinding? = null
    private val b: FragmentMineBinding get() = _b!!

    /** 本地接口文件选择器；选到后交给 SourceSheet 落盘入库 */
    private val pickConfig = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) SourceSheet.importUri(this, uri) { refreshStats() } }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentMineBinding.bind(view)

        // 历史与收藏是同一页面的两个页签，入口合并为一个，不再各占一行
        b.entryHistory.setOnClickListener { startActivity(Intent(requireContext(), HistoryActivity::class.java)) }
        b.entrySource.setOnClickListener {
            SourceSheet.show(this, pickConfig) { refreshStats() }
        }
        b.entryPersonality.setOnClickListener {
            startActivity(Intent(requireContext(), PersonalityActivity::class.java))
        }
        b.entrySettings.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        b.entryCache.setOnClickListener { clearCache() }
        b.entryAbout.setOnClickListener { showAbout() }
        b.entryDisclaimer.setOnClickListener { showDisclaimer() }
        b.entryUpdate.setOnClickListener { checkUpdate() }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun refreshStats() {
        if (_b == null) return
        b.tvStatHistory.text = HistoryStore.history().size.toString()
        b.tvStatFavorite.text = HistoryStore.favorites().size.toString()
        b.tvStatSource.text = SourceStore.all().size.coerceAtLeast(1).toString()

        val url = ConfigProvider.activeUrl()
        b.tvSourceSummary.text = when {
            LocalConfigStore.isLocal(url) -> "本地文件：" + url.removePrefix(LocalConfigStore.PREFIX)
            else -> url.removePrefix(ConfigProvider.ASSET_PREFIX)
        }
        val hist = HistoryStore.history().size
        val fav = HistoryStore.favorites().size
        b.tvHistoryCount.text = when {
            hist == 0 && fav == 0 -> "还没有观看记录"
            else -> "$hist 条记录 · $fav 部收藏"
        }
        b.tvSettingsHint.text = Prefs.describe()
        b.tvAboutHint.text = "版本信息与开源说明"
        b.tvPersonalityHint.text =
            "内核 ${Prefs.playerKernel.lowercase()} · 海报 ${Prefs.gridColumns} 列 · " +
                (if (Prefs.incognito) "无痕开" else "无痕关")
        b.tvDisclaimerHint.text = "资源来源与使用范围说明"
        b.tvUpdateHint.text = "当前 v${BuildConfig.VERSION_NAME}"
        renderCacheSize()
    }

    /** 海报磁盘缓存大小；与设置页同一算法，两处显示一致 */
    private fun renderCacheSize() {
        val dir = java.io.File(requireContext().cacheDir, "image_manager_disk_cache")
        val bytes = runCatching {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        b.tvCacheSize.text = "海报缓存 ${sizeText(bytes)}，点按清理"
    }

    private fun clearCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Glide.get(requireContext()).clearDiskCache() }
                runCatching { ConfigLoader.clearCache() }
            }
            if (_b == null) return@launch
            renderCacheSize()
            context?.let { Toast.makeText(it, "缓存已清理", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showAbout() {
        val ctx = context ?: return
        val msg = buildString {
            append("版本  v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n")
            append("支持\n")
            append("· macCMS JSON / XML 采集\n")
            append("· T4 聚合、Python 脚本源\n")
            append("· jar 爬虫（csp_*）、JS 源\n")
            append("· 本地 JSON / 直播 m3u 导入\n\n")
            append("数据仅保存在本机，不经过任何服务器。\n")
            append("仅供本地播放学习，请遵守版权与当地法规。")
        }
        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("关于星影")
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    /** 免责声明：影视仓关于弹窗里的那一段，按本应用口径重写 */
    private fun showDisclaimer() {
        val ctx = context ?: return
        val msg = buildString {
            append("本应用只提供聚合展示与播放能力，不制作、不上传、不存储任何影片资源。\n\n")
            append("所有内容均来自用户自行配置的第三方接口，播放地址由对应站点提供，")
            append("本应用不参与其内容的生产与分发。\n\n")
            append("请勿用于商业传播；资源版权归原权利人所有，")
            append("如对内容有异议请向对应站点主张。\n\n")
            append("观看记录、收藏与接口配置只保存在本机，不上传任何服务器。")
        }
        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("免责声明")
            .setMessage(msg)
            .setPositiveButton("我知道了", null)
            .show()
    }

    /**
     * 检查更新：拉取 update.json，有新版本则下载安装。
     * 默认地址见 [com.xing.tv.net.Updater.DEFAULT_UPDATE_JSON]，可按发布渠道替换。
     */
    private fun checkUpdate() {
        val act = activity ?: return
        val ctx = context ?: return
        val progress = MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("检查更新")
            .setMessage("正在检查…")
            .setCancelable(false)
            .create()
        progress.show()
        com.xing.tv.net.Updater.check(ctx, com.xing.tv.net.Updater.DEFAULT_UPDATE_JSON,
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
                    val msg = buildString {
                        append("发现新版本 v${info.versionName}（${info.versionCode}）\n")
                        append("当前 v${BuildConfig.VERSION_NAME}\n\n")
                        if (info.notes.isNotBlank()) append(info.notes)
                    }
                    MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
                        .setTitle("发现新版本")
                        .setMessage(msg)
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("下载安装") { _, _ ->
                            val dl = MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
                                .setTitle("下载更新")
                                .setMessage("准备下载…")
                                .setCancelable(false)
                                .create()
                            dl.show()
                            com.xing.tv.net.Updater.downloadAndInstall(act, info,
                                object : com.xing.tv.net.Updater.Listener {
                                    override fun onChecking() {}
                                    override fun onLatest(current: String) {}
                                    override fun onAvailable(info: com.xing.tv.net.Updater.Info) {}
                                    override fun onProgress(percent: Int, read: Long, total: Long) {
                                        dl.setMessage("已下载 $percent%")
                                    }
                                    override fun onInstalling() {
                                        dl.setMessage("正在唤起安装…")
                                    }
                                    override fun onError(msg: String) {
                                        dl.dismiss()
                                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                    }
                                })
                        }
                        .show()
                }
                override fun onProgress(percent: Int, read: Long, total: Long) {}
                override fun onInstalling() {}
                override fun onError(msg: String) {
                    progress.dismiss()
                    MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
                        .setTitle("检查更新")
                        .setMessage(
                            "当前 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n" +
                                "检查失败：$msg\n\n可稍后重试，或从分发渠道手动下载安装包覆盖安装。"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                }
            })
    }

    private fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
