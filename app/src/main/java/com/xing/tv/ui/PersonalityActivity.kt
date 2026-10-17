package com.xing.tv.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.xing.tv.R
import com.xing.tv.data.Prefs
import com.xing.tv.databinding.ActivityPersonalityBinding

/**
 * 个性化设置：把影视仓「我的 → 个性化设置」那一页里能真正生效的项搬过来。
 *
 * 每个开关都直接读写 [Prefs]，不摆「有开关没接线」的假设置：
 * - 海报列数 → 首页网格列数
 * - 搜索样式 / 搜索模式 → SearchActivity
 * - 全局无痕 → 播放器与详情页写历史前判断
 * - 接口缓存 → ConfigLoader 是否读写缓存
 * - 自动横屏 / 迷你进度 / 全屏样式 / 内核 → PlayerActivity
 */
class PersonalityActivity : AppCompatActivity() {

    private lateinit var b: ActivityPersonalityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPersonalityBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        render()
    }

    private fun render() {
        b.content.removeAllViews()

        section("播放")
        pickRow(
            "播放器内核",
            arrayOf("EXOPLAYER", "IJKPLAYER", "SYSTEM_MEDIA"),
            arrayOf("ExoPlayer（推荐）", "IJKPlayer（兼容好）", "系统播放器"),
            { Prefs.playerKernel },
            { Prefs.playerKernel = it }
        )
        switchRow("播放页自动横屏", { Prefs.autoLandscape }) { Prefs.autoLandscape = it }
        pickRow(
            "全屏样式",
            arrayOf("AUTO", "LANDSCAPE", "PORTRAIT"),
            arrayOf("跟随系统", "强制横屏", "强制竖屏"),
            { Prefs.fullscreenStyle },
            { Prefs.fullscreenStyle = it }
        )
        switchRow("迷你进度条", { Prefs.miniProgress }) { Prefs.miniProgress = it }
        pickRow(
            "默认倍速",
            arrayOf("0.75", "1.0", "1.25", "1.5", "2.0"),
            arrayOf("0.75x", "1.0x", "1.25x", "1.5x", "2.0x"),
            { Prefs.defaultSpeed.toString() },
            { Prefs.defaultSpeed = it.toFloatOrNull() ?: 1.0f }
        )
        switchRow("续播（记住进度）", { Prefs.resumeEnabled }) { Prefs.resumeEnabled = it }
        switchRow("播放失败自动换源", { Prefs.autoSwitchSource }) { Prefs.autoSwitchSource = it }

        section("显示")
        pickRow(
            "海报展示样式",
            arrayOf("2", "3", "4"),
            arrayOf("2 列（大图）", "3 列（默认）", "4 列（紧凑）"),
            { Prefs.gridColumns.toString() },
            { Prefs.gridColumns = it.toIntOrNull() ?: 3 }
        )
        switchRow("搜索结果样式（开=网格）", { Prefs.searchGrid }) { Prefs.searchGrid = it }
        switchRow("首页底部播放记录条", { Prefs.bottomHistory }) { Prefs.bottomHistory = it }

        section("搜索")
        switchRow("搜索模式（开=精确匹配）", { Prefs.searchExact }) { Prefs.searchExact = it }

        section("隐私与数据")
        switchRow("全局无痕（不记历史）", { Prefs.incognito }) { Prefs.incognito = it }
        switchRow("接口缓存", { Prefs.configCacheEnabled }) { Prefs.configCacheEnabled = it }
    }

    // ---------------------------------------------------------------- 行渲染

    private fun section(title: String) {
        b.content.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(getColor(R.color.text_muted))
            setPadding(dp(4), dp(16), 0, dp(6))
        })
    }

    /** 开关行：点一下切换，立即落盘并重绘 */
    private fun switchRow(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        row(title, if (get()) "开" else "关") {
            set(!get())
            render()
        }
    }

    /** 单选行：弹选项，选中即生效 */
    private fun pickRow(
        title: String,
        options: Array<String>,
        labels: Array<String>,
        current: () -> String,
        onPick: (String) -> Unit
    ) {
        val value = current()
        val shown = labels.getOrNull(options.indexOf(value)) ?: value
        row(title, shown) {
            val checked = options.indexOf(value).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    onPick(options[which])
                    d.dismiss()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun row(title: String, value: String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_setting_item)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
        })
        box.addView(TextView(this).apply {
            text = "  ›"
            textSize = 15f
            setTextColor(getColor(R.color.text_muted))
        })
        b.content.addView(box)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
