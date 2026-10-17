package com.xing.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestOptions
import com.xing.tv.R
import com.xing.tv.data.HistoryStore
import com.xing.tv.databinding.ItemContinueBinding

/**
 * 「继续观看」横滑卡片：海报 + 进度条 + 集数/进度。
 * 进度百分比由 positionMs 与播放总长未知时的粗略估算给出（仅做视觉提示）。
 */
class ContinueAdapter(
    private val onClick: (HistoryStore.Record) -> Unit
) : RecyclerView.Adapter<ContinueAdapter.VH>() {

    private val items = mutableListOf<HistoryStore.Record>()

    fun submit(list: List<HistoryStore.Record>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemContinueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemContinueBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(record: HistoryStore.Record) {
            b.tvName.text = record.name
            val pct = record.progressPercent
            b.tvEpisode.text = when {
                pct > 0 -> "${record.episodeName} · 已看 $pct%"
                record.episodeName.isNotEmpty() -> record.episodeName
                else -> "上次观看"
            }
            // 进度条按百分比缩放（必须等布局完成后才知道总宽）
            if (pct in 1..99) {
                b.vProgress.post {
                    val total = (b.vProgress.parent as? android.view.View)?.width ?: 0
                    if (total > 0) {
                        b.vProgress.layoutParams =
                            b.vProgress.layoutParams.apply { width = total * pct / 100 }
                    }
                }
            } else {
                b.vProgress.layoutParams =
                    b.vProgress.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
            }

            Glide.with(b.ivPoster.context)
                .load(record.pic)
                .apply(
                    RequestOptions()
                        .placeholder(R.color.surface_container)
                        .error(R.color.surface_container)
                        .transform(CenterCrop())
                )
                .into(b.ivPoster)
            b.root.setOnClickListener { onClick(record) }
        }
    }
}
