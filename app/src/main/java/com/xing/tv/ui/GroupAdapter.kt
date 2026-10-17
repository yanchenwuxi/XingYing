package com.xing.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xing.tv.R
import com.xing.tv.databinding.ItemGroupBinding
import com.xing.tv.model.LiveGroup

/**
 * 直播分组列适配器（左侧竖排列表）。
 *
 * 直播页改成「左分组 / 右频道」两列后，分组不再是横向胶囊，
 * 而是常驻的竖向列表；选中项用容器色 + 主色描边高亮。
 */
class GroupAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private var items: List<LiveGroup> = emptyList()
    private var selected = 0

    fun submit(list: List<LiveGroup>, selectedIndex: Int) {
        items = list
        selected = selectedIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(private val b: ItemGroupBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(group: LiveGroup, position: Int) {
            val ctx = b.root.context
            val on = position == selected
            b.tvName.text = group.name
            b.tvCount.text = group.channels.size.toString()
            b.root.isSelected = on
            b.tvName.setTextColor(
                ContextCompat.getColor(ctx, if (on) R.color.on_primary_container else R.color.text_secondary)
            )
            b.tvCount.setTextColor(
                ContextCompat.getColor(ctx, if (on) R.color.on_primary_container else R.color.text_muted)
            )
            b.root.setOnClickListener { onClick(position) }
        }
    }
}
