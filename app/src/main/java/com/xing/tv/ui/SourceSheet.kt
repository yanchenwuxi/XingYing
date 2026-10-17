package com.xing.tv.ui

import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.xing.tv.R
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.ConfigProvider
import com.xing.tv.data.LocalConfigStore
import com.xing.tv.data.Prefs
import com.xing.tv.data.SourceStore
import com.xing.tv.databinding.ItemSourceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 接口源弹窗：当前接口列表（点按切换）+ 添加接口 + 本地导入。
 *
 * 结构参考影视仓的「多仓管理」弹窗：底部弹出，列表为主体，
 * 添加与本地导入收进底部按钮，切换后立即生效，不用跳独立页面。
 *
 * 本地文件选择器（SAF）的 launcher 必须由宿主 Fragment/Activity 在
 * onCreate 前注册，这里只接收启动器与回传的 URI，避免重复注册。
 */
object SourceSheet {

    /** 当前展开的弹窗里的列表适配器：SAF 回调与弹窗不在同一调用栈，靠它刷新 */
    private var activeAdapter: SourceAdapter? = null

    /**
     * 弹出接口源管理。
     * @param pickLauncher 宿主注册的 `OpenDocument` 启动器，选到文件后把 URI 交给 [importUri]
     * @param onChanged 切换/增删后回调，宿主据此重拉配置（如首页 `vm.start()`）
     */
    fun show(
        host: Fragment,
        pickLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        onChanged: () -> Unit
    ) {
        val ctx = host.requireContext()
        val dialog = BottomSheetDialog(ctx, R.style.Sheet_XingYing)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.dp(16)
            setPadding(pad, ctx.dp(8), pad, ctx.dp(16))
        }
        // 顶部小横条：底部弹窗的通用把手
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = ctx.dp(4)
            }
            setBackgroundResource(R.drawable.bg_tab_indicator)
            alpha = 0.5f
        })
        root.addView(android.widget.TextView(ctx).apply {
            text = "仓库管理"
            textSize = 17f
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, ctx.dp(6), 0, ctx.dp(2))
        })
        root.addView(android.widget.TextView(ctx).apply {
            text = "长按复制，左滑删除 · 点选即切换"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_muted))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, ctx.dp(8))
        })

        val adapter = SourceAdapter(
            onSelect = { item ->
                // v1.3.0：即使选中当前接口也重新加载，便于失败后重试
                Prefs.configUrl = item.url
                toast(ctx, "已切换到「${item.name}」")
                dialog.dismiss()
                onChanged()
            },
            onDelete = { item ->
                LocalConfigStore.delete(item.url)
                SourceStore.remove(item.url)
                if (Prefs.configUrl == item.url) {
                    Prefs.configUrl = ""
                    toast(ctx, "已删除，请重新添加接口源")
                } else {
                    toast(ctx, "已删除「${item.name}」")
                }
                activeAdapter?.submit(SourceStore.all())
                onChanged()
            }
        )
        root.addView(RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(280)
            )
        })
        adapter.submit(SourceStore.all())
        activeAdapter = adapter

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(12), 0, 0)
        }
        btnRow.addView(ctx.sourceButton("添加接口") { showAddInput(host, adapter, pickLauncher, onChanged) }, rowParams(ctx, 1f))
        btnRow.addView(Space(ctx), rowParams(ctx, 0f, ctx.dp(8)))
        btnRow.addView(ctx.sourceButton("本地导入") {
            // 各处文件管理器后缀标注不一，放开到 */* 最省事
            pickLauncher.launch(arrayOf("*/*"))
        }, rowParams(ctx, 1f))
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.setOnDismissListener { activeAdapter = null }
        dialog.show()
    }

    private fun rowParams(ctx: android.content.Context, weight: Float, width: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    private fun Space(ctx: android.content.Context): View = View(ctx)

    private fun android.content.Context.sourceButton(label: String, onClick: () -> Unit): android.widget.TextView =
        android.widget.TextView(this).apply {
            text = label
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.on_primary))
            setBackgroundResource(R.drawable.bg_gradient_button)
            val padV = dp(12)
            setPadding(0, padV, 0, padV)
            setOnClickListener { onClick() }
        }

    /** 添加接口：点播配置 / 纯直播源 均可；自动识别 m3u */
    private fun showAddInput(
        host: Fragment,
        adapter: SourceAdapter,
        pickLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        onChanged: () -> Unit
    ) {
        val ctx = host.requireContext()
        val pad = ctx.dp(12)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ctx.dp(8), pad, ctx.dp(4))
        }
        // 与影视仓一致：名称 + 地址（含本地文件）
        val etName = EditText(ctx).apply {
            hint = "输入仓库/线路名称"
            textSize = 15f
            setTextColor(ctx.getColor(R.color.text_primary))
            setHintTextColor(ctx.getColor(R.color.text_muted))
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }
        val etUrl = EditText(ctx).apply {
            hint = "输入仓库/线路地址"
            textSize = 15f
            setTextColor(ctx.getColor(R.color.text_primary))
            setHintTextColor(ctx.getColor(R.color.text_muted))
            setBackgroundResource(0)
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(4), ctx.dp(10))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val folderBtn = android.widget.ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_folder)
            imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.text_secondary))
            setBackgroundResource(0)
            contentDescription = "选择本地文件"
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            setOnClickListener {
                pickLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
        }
        val urlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
            addView(etUrl)
            addView(folderBtn)
        }
        val space = android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(10)
            )
        }
        box.addView(etName)
        box.addView(space)
        box.addView(urlRow)
        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
            .setTitle("输入配置地址")
            .setView(box)
            .setPositiveButton("确认") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    toast(ctx, "地址需以 http:// 或 https:// 开头")
                    return@setPositiveButton
                }
                val item = SourceStore.add(etName.text.toString().trim(), url)
                adapter.submit(SourceStore.all())
                validateAndReport(host, item, onChanged)
            }
            .setNeutralButton("仅作直播源") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    toast(ctx, "地址需以 http:// 或 https:// 开头")
                    return@setNeutralButton
                }
                val name = etName.text.toString().trim().ifBlank {
                    runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault("直播源")
                }
                // 写入一条仅含 lives 的本地 JSON，走同一套配置管线
                host.viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val json = """{"lives":[{"name":"$name","url":"$url","type":0}],"sites":[]}"""
                            val fileName = "live_" + Integer.toHexString(url.hashCode()) + ".json"
                            com.xing.tv.data.LocalConfigStore.importText(json, fileName)
                        }
                    }
                    if (!host.isAdded) return@launch
                    result.onSuccess { localUrl ->
                        SourceStore.add("直播·$name", localUrl)
                        adapter.submit(SourceStore.all())
                        toast(ctx, "已添加直播源「$name」，请到直播页查看")
                        onChanged()
                    }.onFailure {
                        toast(ctx, "添加失败：${it.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 处理 SAF 回传的 URI：读内容 → 校验 → 落盘 → 入列 → 验证。
     * 由宿主的 launcher 回调里调用。
     */
    fun importUri(host: Fragment, uri: Uri, onChanged: () -> Unit) {
        val ctx = host.requireContext()
        val displayName = ctx.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        host.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalConfigStore.importFrom(uri, displayName) }
            }
            if (!host.isAdded) return@launch
            result.onSuccess { localUrl ->
                val label = displayName ?: "本地配置"
                val item = SourceStore.add("本地·$label", localUrl)
                activeAdapter?.submit(SourceStore.all())
                toast(ctx, "已导入「$label」")
                validateAndReport(host, item, onChanged)
            }.onFailure {
                toast(ctx, "导入失败：${it.message}")
            }
        }
    }

    /**
     * 立即拉取配置并把可用性讲清楚。
     * 网络配置要真拉一次才知道站点能不能用；本地配置读文件即可。
     */
    private fun validateAndReport(host: Fragment, item: SourceStore.Item, onChanged: () -> Unit) {
        val ctx = host.requireContext()
        host.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigProvider.fetch(item.url, force = true) }
            }
            if (!host.isAdded) return@launch
            result.onSuccess { cfg ->
                onChanged()
                val d = cfg.diag
                when {
                    d.noUsableSite && d.usableLives > 0 -> {
                        toast(ctx, "已识别为直播源，直播 ${d.usableLives} 路可用，请到「直播」页观看")
                    }
                    d.noUsableSite -> {
                        MaterialAlertDialogBuilder(ctx, R.style.Dialog_XingYing)
                            .setTitle("点播不可用")
                            .setMessage("已添加「${item.name}」，但该配置无法用于点播。\n\n" + d.explain())
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                    else -> toast(ctx, "已添加，${d.brief()}，可用站点 ${d.supportedSites} 个")
                }
            }.onFailure {
                android.util.Log.w("SourceSheet", "validate config failed: ${item.url}", it)
                toast(ctx, "已添加，但配置读取失败：${it.message}")
            }
        }
    }

    private fun toast(ctx: android.content.Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /** 极简列表适配器：条目数少且结构固定，不值得再套一层 ListAdapter */
    private class SourceAdapter(
        private val onSelect: (SourceStore.Item) -> Unit,
        private val onDelete: (SourceStore.Item) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        private val items = mutableListOf<SourceStore.Item>()

        fun submit(list: List<SourceStore.Item>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(com.xing.tv.databinding.ItemSourceBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val vb: com.xing.tv.databinding.ItemSourceBinding) :
            RecyclerView.ViewHolder(vb.root) {

            fun bind(item: SourceStore.Item) {
                vb.tvName.text = item.name
                vb.tvUrl.text =
                    if (LocalConfigStore.isLocal(item.url)) "本地文件 · 已复制到应用内"
                    else item.url
                val active = item.url == Prefs.configUrl
                vb.tvBadge.visibility = if (active) View.VISIBLE else View.GONE
                vb.btnDelete.visibility = if (active) View.GONE else View.VISIBLE
                vb.root.setOnClickListener { onSelect(item) }
                vb.btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
