package com.xing.tv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.xing.tv.R

/** 密度无关像素 */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** 选中态的胶囊标签：站点、分类、搜索历史、详情线路统一走这里 */
fun Context.newChip(
    label: String,
    selected: Boolean,
    textSize: Float = 13f,
    verticalPad: Int = 7,
    /** 胶囊最大宽度；0 表示不限。公开配置的站点名常带推广语，不设上限会占满整行 */
    maxWidthDp: Int = 0,
    onClick: () -> Unit
): TextView = TextView(this).apply {
    text = label
    this.textSize = textSize
    gravity = Gravity.CENTER
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
    if (maxWidthDp > 0) maxWidth = dp(maxWidthDp)
    val padH = dp(14)
    setPadding(padH, dp(verticalPad), padH, dp(verticalPad))
    background = ContextCompat.getDrawable(this@newChip, R.drawable.bg_chip)
    // 选中态用「容器色 + 高对比文字」，比直接铺主色更耐看，长列表也不刺眼
    backgroundTintList = ColorStateList.valueOf(
        getColor(if (selected) R.color.primary_container else R.color.surface_high)
    )
    setTextColor(
        getColor(if (selected) R.color.primary else R.color.text_secondary)
    )
    if (selected) typeface = android.graphics.Typeface.DEFAULT_BOLD
    setOnClickListener { onClick() }
}

/** 未选中的胶囊 */
fun Context.newQuietChip(label: String, textSize: Float = 13f, onClick: () -> Unit): TextView =
    newChip(label, selected = false, textSize = textSize, onClick = onClick)

/**
 * 去掉 emoji。
 *
 * 公开配置里的站点名与直播源名常以表情开头（`🐬xxx`），配上宽度截断会留下
 * 一个占位的豆腐块，清掉更干净。
 *
 * emoji 在 UTF-16 中一律是代理对，U+D800–DFFF 区间不存在合法的独立字符，
 * 按码元区间跳过不会误伤正常文本。孤立的高位代理只占一个码元，不能连带
 * 吞掉后面的真实字符。
 */
internal fun String.stripEmoji(): String = buildString {
    val s = this@stripEmoji
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            // 成对的高低代理构成一个 emoji，整体跳过
            c in '\uD800'..'\uDBFF' && i + 1 < s.length && s[i + 1] in '\uDC00'..'\uDFFF' -> i += 2
            // 孤立代理、零宽连接符与变体选择符
            c in '\uD800'..'\uDFFF' || c == '\u200D' || c == '\uFE0F' -> i++
            else -> {
                append(c)
                i++
            }
        }
    }
}
