package com.xing.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.xing.tv.R
import com.xing.tv.databinding.ItemRelatedBinding
import com.xing.tv.model.VodItem

/**
 * 同类推荐横滑适配器（固定宽度海报卡）。
 *
 * 首页的 [VodAdapter] 条目是 match_parent 宽度，只能用在网格里；
 * 横滑行需要固定宽度条目，这里单列一个轻量实现。
 */
class RelatedAdapter(
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<RelatedAdapter.VH>() {

    private val items = mutableListOf<VodItem>()

    fun submit(list: List<VodItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRelatedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemRelatedBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: VodItem) {
            b.tvName.text = item.vodName
            Glide.with(b.ivPoster.context)
                .load(item.vodPic)
                .apply(RequestOptions().placeholder(R.color.surface_container).error(R.color.surface_container))
                .into(b.ivPoster)
            b.root.setOnClickListener { onClick(item) }
        }
    }
}
