package com.outfitai.app.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.outfitai.app.data.OutfitRecord
import com.outfitai.app.databinding.ItemHistoryBinding
import io.noties.markwon.Markwon

class HistoryAdapter(
    private val records: List<OutfitRecord>,
    private val markwon: Markwon,
    private val onClick: (OutfitRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount() = records.size

    inner class ViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: OutfitRecord) {
            val icon = when (record.type) {
                "evaluate" -> "🎯"
                "scene" -> "🌟"
                "detail" -> "💎"
                else -> "📋"
            }
            binding.tvType.text = "$icon ${record.typeName ?: ""}"
            binding.tvTime.text = record.formattedTime

            if (record.userInput?.isNotBlank() == true) {
                binding.tvUserInput.text = record.userInput
                binding.tvUserInput.visibility = android.view.View.VISIBLE
            } else {
                binding.tvUserInput.visibility = android.view.View.GONE
            }

            markwon.setMarkdown(binding.tvSummary, record.summary)

            // 缩略图（优先使用预先生成的缩略图，Glide 异常安全）
            val thumbToLoad = if (record.thumbPath?.isNotBlank() == true) record.thumbPath
                              else record.imagePath
            if (thumbToLoad?.isNotBlank() == true) {
                binding.ivThumbnail.visibility = View.VISIBLE
                try {
                    Glide.with(binding.ivThumbnail.context)
                        .load(thumbToLoad)
                        .placeholder(android.R.color.transparent)
                        .error(android.R.color.transparent)
                        .centerCrop()
                        .into(binding.ivThumbnail)
                } catch (e: Exception) {
                    binding.ivThumbnail.setImageDrawable(null)
                }
            } else {
                binding.ivThumbnail.setImageDrawable(null)
                binding.ivThumbnail.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(record) }
        }
    }
}
