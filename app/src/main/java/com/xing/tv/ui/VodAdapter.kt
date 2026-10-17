package com.xing.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.xing.tv.R
import com.xing.tv.databinding.ItemVodBinding
import com.xing.tv.model.VodItem

/**
 * 影片网格适配器（圆角海报 + 备注角标 + 片名/年份）。
 *
 * 用 ListAdapter + DiffUtil：翻页追加与下拉刷新都只做增量更新，
 * 避免 notifyDataSetChanged 造成的整列表重绘与滚动位置跳动。
 *
 * 海报的圆角由 ShapeableImageView 的 shapeAppearance 负责，这里不再叠 RoundedCorners，
 * 否则会出现双重圆角。
 */
class VodAdapter(
    private val onClick: (VodItem) -> Unit,
    private val extraOf: ((VodItem) -> VodExtra)? = null
) : ListAdapter<VodItem, VodAdapter.VH>(DIFF) {

    /** 额外展示信息：副标题、播放进度、是否可续播 */
    data class VodExtra(
        val meta: String? = null,
        val progress: Int = -1,
        val resume: Boolean = false
    )

    /**
     * 同步镜像。
     *
     * ListAdapter.submitList 的 DiffUtil 计算是异步的，提交后立即读 itemCount
     * 拿到的仍是旧值。空状态提示等即时 UI 判断须走这里的同步计数。
     */
    private val mirror = mutableListOf<VodItem>()

    /** 全量替换 */
    fun submit(list: List<VodItem>) {
        mirror.clear()
        mirror.addAll(list)
        submitList(mirror.toList())
    }

    /** 在现有列表后追加一页 */
    fun append(list: List<VodItem>) {
        if (list.isEmpty()) return
        mirror.addAll(list)
        submitList(mirror.toList())
    }

    /** 同步的条目数，供提交后立即判断空状态 */
    val syncCount: Int get() = mirror.size

    fun current(): List<VodItem> = mirror.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVodBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onClick, extraOf)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(currentList[position])
    }

    class VH(
        private val b: ItemVodBinding,
        private val onClick: (VodItem) -> Unit,
        private val extraOf: ((VodItem) -> VodExtra)?
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: VodItem) {
            b.tvName.text = item.vodName
            b.tvRemark.text = item.vodRemarks
            b.tvRemark.visibility = if (item.vodRemarks.isEmpty()) View.GONE else View.VISIBLE

            val extra = extraOf?.invoke(item)
            b.tvMeta.text = extra?.meta ?: item.vodYear.ifEmpty { item.vodRemarks }
            b.tvMeta.visibility = if (b.tvMeta.text.isEmpty()) View.GONE else View.VISIBLE

            val progress = extra?.progress ?: -1
            b.vProgress.visibility = if (progress in 0..100) View.VISIBLE else View.GONE
            // 已看过一半以上才给「继续播放」角标，避免首页铺满播放按钮
            b.ivResume.visibility =
                if (extra?.resume == true && progress >= 50) View.VISIBLE else View.GONE

            Glide.with(b.ivPoster.context)
                .load(item.vodPic)
                .apply(
                    RequestOptions()
                        .placeholder(R.color.surface_container)
                        .error(R.color.surface_container)
                        .transform(CenterCrop(), RoundedCorners(dp(b.ivPoster.context, 4)))
                )
                .into(b.ivPoster)

            b.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private fun dp(ctx: android.content.Context, v: Int): Int =
            (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

        private val DIFF = object : DiffUtil.ItemCallback<VodItem>() {
            override fun areItemsTheSame(oldItem: VodItem, newItem: VodItem): Boolean =
                oldItem.vodId == newItem.vodId

            override fun areContentsTheSame(oldItem: VodItem, newItem: VodItem): Boolean =
                oldItem == newItem
        }
    }
}
