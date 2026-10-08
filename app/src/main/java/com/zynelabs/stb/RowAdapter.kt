package com.zynelabs.stb

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ItemRowBinding

/**
 * Generic single-line row adapter (title + optional value).
 * Rows are focusable for D-pad; OK/click triggers [onClick].
 */
/** Item for [RowAdapter]: stable id + title + optional trailing value + optional poster. */
data class RowItem(
    val id: String,
    val title: String,
    val value: String = "",
    val posterUrl: String = "",
    /** v5.0: section header — non-clickable, styled distinctly. */
    val header: Boolean = false
)

class RowAdapter(
    private val onClick: (RowItem) -> Unit
) : ListAdapter<RowItem, RowAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RowItem>() {
            override fun areItemsTheSame(old: RowItem, new: RowItem): Boolean =
                old.id == new.id

            override fun areContentsTheSame(old: RowItem, new: RowItem): Boolean =
                old == new
        }
    }

    inner class ViewHolder(
        private val binding: ItemRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(getItem(position))
                }
            }
        }

        fun bind(item: RowItem) {
            binding.tvTitle.text = item.title
            binding.tvValue.text = item.value
            // v5.0: section headers are non-interactive and styled distinctly.
            if (item.header) {
                binding.root.isFocusable = false
                binding.root.isClickable = false
                binding.tvTitle.setTextColor(0xFF00E5CC.toInt())
                binding.tvTitle.textSize = 14f
                binding.tvTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                binding.root.isFocusable = true
                binding.root.isClickable = true
                binding.tvTitle.setTextColor(0xFFFFFFFF.toInt())
                binding.tvTitle.textSize = 18f
                binding.tvTitle.typeface = android.graphics.Typeface.DEFAULT
            }
            // v4.9: poster thumbnail (VOD items); hidden when absent.
            if (item.posterUrl.isNotBlank()) {
                binding.ivThumb.visibility = android.view.View.VISIBLE
                ImageLoader.load(item.posterUrl, binding.ivThumb, onFail = {
                    binding.ivThumb.visibility = android.view.View.GONE
                })
            } else {
                ImageLoader.cancel(binding.ivThumb)
                binding.ivThumb.visibility = android.view.View.GONE
            }
        }

        /** v4.9: cancel any pending poster load when the view is recycled. */
        fun recycle() {
            ImageLoader.cancel(binding.ivThumb)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }
}
