package com.xing.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xing.tv.R
import com.xing.tv.databinding.ItemChannelBinding
import com.xing.tv.model.LiveChannel

/**
 * 直播频道列表适配器（右侧竖排列表）。
 *
 * 与左侧分组列并排，行内是「序号 + 频道名 +（直播中）」；
 * 正在内嵌播放器里播放的那一行高亮，并标出「直播中」。
 */
class ChannelAdapter(
    private val onClick: (LiveChannel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<LiveChannel>()

    /** 当前播放地址；按 url 而非下标标记，换源/刷新后下标会变 */
    private var playingUrl = ""

    fun submit(list: List<LiveChannel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun markPlaying(url: String) {
        if (playingUrl == url) return
        val old = playingUrl
        playingUrl = url
        notifyItemsChanged(old, url)
    }

    private fun notifyItemsChanged(old: String, new: String) {
        items.forEachIndexed { i, c ->
            if (c.url == old || c.url == new) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(private val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(channel: LiveChannel, position: Int) {
            val on = channel.url == playingUrl
            b.tvIndex.text = "%02d".format(position + 1)
            b.tvName.text = channel.name
            b.root.isSelected = on
            b.tvName.setTextColor(
                ContextCompat.getColor(b.root.context, if (on) R.color.on_primary_container else R.color.text_primary)
            )
            b.tvPlaying.visibility = if (on) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onClick(channel) }
        }
    }
}
