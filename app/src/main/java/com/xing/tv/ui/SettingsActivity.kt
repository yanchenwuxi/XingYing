package com.xing.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.xing.tv.BuildConfig
import com.xing.tv.R
import com.xing.tv.api.ConfigLoader
import com.xing.tv.data.HistoryStore
import com.xing.tv.data.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xing.tv.player.PlayerKernelType
import com.xing.tv.data.SearchHistory
import com.xing.tv.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置：播放偏好、缓存清理、历史数据的导出/导入。
 *
 * 接口源的增删切换在首页接口行的 [SourceSheet] 弹窗，这里只管「怎么播」和「本地存了什么」。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** 片头/片尾跳过可选秒数，0 表示不跳 */
    private val skipOptions = intArrayOf(0, 5, 10, 15, 30, 60, 90)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        // ---------- 播放偏好 ----------
        b.switchAuto.isChecked = Prefs.autoSwitchSource
        b.switchAuto.setOnCheckedChangeListener { _, checked -> Prefs.autoSwitchSource = checked }

        b.switchResume.isChecked = Prefs.resumeEnabled
        b.switchResume.setOnCheckedChangeListener { _, checked -> Prefs.resumeEnabled = checked }

        b.tvSpeed.text = "${Prefs.defaultSpeed}x"
        b.rowSpeed.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.Dialog_XingYing)
                .setTitle("默认倍速")
                .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, which ->
                    Prefs.defaultSpeed = speeds[which]
                    b.tvSpeed.text = "${speeds[which]}x"
                }
                .setNegativeButton("取消", null)
                .show()
        }

        bindSkipRow(
            label = "跳过片头",
            valueText = { Prefs.skipIntroSec },
            onPick = { Prefs.skipIntroSec = it },
            render = { b.tvSkipIntro.text = secondsText(Prefs.skipIntroSec) }
        )
        bindSkipRow(
            label = "跳过片尾",
            valueText = { Prefs.skipOutroSec },
            onPick = { Prefs.skipOutroSec = it },
            render = { b.tvSkipOutro.text = secondsText(Prefs.skipOutroSec) }
        )

        // ---------- 播放内核 ----------
        // 无独立布局行时，挂到倍速行的长按，避免改 XML 破坏 ViewBinding
        b.rowSpeed.setOnLongClickListener {
            val labels = PlayerKernelType.entries.map { it.label }.toTypedArray()
            val cur = PlayerKernelType.fromName(Prefs.playerKernel)
            val checked = PlayerKernelType.entries.indexOf(cur).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this, R.style.Dialog_XingYing)
                .setTitle("播放内核")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    Prefs.playerKernel = PlayerKernelType.entries[which].name
                    d.dismiss()
                    toast("已选择 ${PlayerKernelType.entries[which].label}")
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        // ---------- 缓存 ----------
        renderCacheSize()
        b.rowClearConfig.setOnClickListener {
            ConfigLoader.clearCache()
            toast("接口配置缓存已清理")
            renderCacheSize()
        }
        b.rowClearImage.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { Glide.get(this@SettingsActivity).clearDiskCache() }
                }
                renderCacheSize()
                toast("海报缓存已清理")
            }
        }
        b.rowClearSearch.setOnClickListener {
            SearchHistory.clear()
            toast("搜索历史已清空")
        }

        // ---------- 数据迁移 ----------
        b.rowExport.setOnClickListener { exportHistory() }
        b.rowImport.setOnClickListener { importHistory() }

        b.tvAbout.text = buildString {
            append("星影 v").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n")
            append("兼容 macCMS json / xml、各类 JSON 采集接口、T4 聚合与 Python 脚本源。\n")
            append("支持在「接口源」中导入本地 .json 配置文件。\n")
            append("已支持 macCMS / T4 / Python / jar 爬虫（csp_*）与 js 源。\n")
            append("历史与收藏仅保存在本机，导出/导入通过剪贴板完成，不经过任何服务器。\n\n")
            append("仅提供本地播放能力，请勿用于传播、下载受版权保护的内容。")
        }
    }

    /** 片头片尾跳过：0 表示不跳 */
    private fun bindSkipRow(
        label: String,
        valueText: () -> Int,
        onPick: (Int) -> Unit,
        render: () -> Unit
    ) {
        render()
        val row = if (label == "跳过片头") b.rowSkipIntro else b.rowSkipOutro
        val checked = skipOptions.indexOf(valueText()).coerceAtLeast(0)
        row.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.Dialog_XingYing)
                .setTitle(label)
                .setSingleChoiceItems(skipOptions.map { secondsText(it) }.toTypedArray(), checked) { d, which ->
                    onPick(skipOptions[which])
                    render()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun secondsText(sec: Int): String = if (sec == 0) "不跳过" else "${sec} 秒"

    private fun renderCacheSize() {
        b.tvConfigSize.text = sizeText(ConfigLoader.cacheBytes())
        // Glide 4 的磁盘缓存固定落在 <cacheDir>/image_manager_disk_cache
        val dir = java.io.File(cacheDir, "image_manager_disk_cache")
        val imageCache = runCatching {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        b.tvImageSize.text = sizeText(imageCache)
    }

    private fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private fun exportHistory() {
        val json = HistoryStore.exportJson()
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("星影历史记录", json))
        toast("已复制到剪贴板，可在其他设备粘贴导入")
    }

    private fun importHistory() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板没有可用内容")
            return
        }
        val count = HistoryStore.importJson(text)
        if (count == 0) toast("未识别到有效记录") else toast("已导入 $count 条记录")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
