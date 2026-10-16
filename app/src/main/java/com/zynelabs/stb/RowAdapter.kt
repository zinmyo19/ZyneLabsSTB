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
    val header: Boolean = false,
    /** v6.3.3: highlight (e.g. current channel in the player drawer) — red title. */
    val highlight: Boolean = false
)

class RowAdapter(
    /**
     * v6.3.26: compact mode for the player drawers (channels + settings).
     * Smaller text + tighter padding so more rows fit. Default false so
     * every other list (Groups, Radio, Settings, VOD, provider list)
     * keeps its current look. This param stays FIRST so the trailing
     * lambda still binds to [onClick] at every call site.
     */
    private val compact: Boolean = false,
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
            // v6.3.26: compact rows — always set sizes/padding here so a
            // recycled holder never keeps the other mode's values.
            val d = binding.root.resources.displayMetrics.density
            if (compact) {
                binding.root.setPadding(
                    (12 * d).toInt(), (6 * d).toInt(),
                    (12 * d).toInt(), (6 * d).toInt()
                )
            } else {
                binding.root.setPadding(
                    (16 * d).toInt(), (16 * d).toInt(),
                    (16 * d).toInt(), (16 * d).toInt()
                )
            }
            // v5.0: section headers are non-interactive and styled distinctly.
            if (item.header) {
                binding.root.isFocusable = false
                binding.root.isClickable = false
                binding.tvTitle.setTextColor(0xFFD4AF37.toInt())
                binding.tvTitle.textSize = if (compact) 12f else 14f
                binding.tvTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                binding.root.isFocusable = true
                binding.root.isClickable = true
                // v6.3.3: highlighted rows (current channel) get a red title.
                binding.tvTitle.setTextColor(
                    if (item.highlight) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt()
                )
                binding.tvTitle.textSize = if (compact) 14f else 18f
                binding.tvValue.textSize = if (compact) 13f else 16f
                binding.tvTitle.typeface =
                    if (item.highlight) android.graphics.Typeface.DEFAULT_BOLD
                    else android.graphics.Typeface.DEFAULT
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
