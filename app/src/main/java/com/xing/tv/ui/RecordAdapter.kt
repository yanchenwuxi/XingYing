package com.xing.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestOptions
import com.xing.tv.R
import com.xing.tv.data.HistoryStore
import com.xing.tv.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史/收藏记录适配器。
 * onLongClick 返回 true 表示已消费事件。
 */
class RecordAdapter(
    private val onClick: (HistoryStore.Record) -> Unit,
    private val onLongClick: (HistoryStore.Record) -> Boolean
) : RecyclerView.Adapter<RecordAdapter.VH>() {

    private val items = mutableListOf<HistoryStore.Record>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submit(list: List<HistoryStore.Record>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(record: HistoryStore.Record) {
            b.tvName.text = record.name
            val pct = record.progressPercent
            b.tvEpisode.text = when {
                record.episodeName.isEmpty() && record.playUrl.isEmpty() -> "已加入收藏"
                pct > 0 -> "${record.episodeName} · 已看 $pct%"
                else -> record.episodeName.ifEmpty { "上次观看" }
            }
            b.tvTime.text = timeFormat.format(Date(record.updatedAt))

            val lp = b.vProgress
            lp.visibility = if (pct in 1..99) View.VISIBLE else View.GONE
            if (pct in 1..99) {
                lp.post {
                    val total = (lp.parent as? ViewGroup)?.width ?: 0
                    if (total > 0) {
                        lp.layoutParams = lp.layoutParams.apply { width = total * pct / 100 }
                    }
                }
            }

            Glide.with(b.ivPoster.context)
                .load(record.pic)
                .apply(
                    RequestOptions()
                        .placeholder(R.color.surface_high)
                        .error(R.color.surface_high)
                        .transform(CenterCrop())
                )
                .into(b.ivPoster)
            b.root.setOnClickListener { onClick(record) }
            b.root.setOnLongClickListener { onLongClick(record) }
        }
    }
}
