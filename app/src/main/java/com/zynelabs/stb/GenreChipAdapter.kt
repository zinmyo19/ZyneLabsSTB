package com.zynelabs.stb

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ItemGenreChipBinding

/**
 * v6.3.12: compact single-chip adapter for the drawer genre/category row.
 * Chips are focusable for D-pad; OK/click triggers [onClick].
 * Replaces the shared RowAdapter (item_row, 16dp padding) which was too spread out.
 */
class GenreChipAdapter(
    private val onClick: (RowItem) -> Unit
) : ListAdapter<RowItem, GenreChipAdapter.ViewHolder>(RowAdapter.DIFF) {

    inner class ViewHolder(
        private val binding: ItemGenreChipBinding
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
            binding.tvChip.text = item.title
            binding.root.isFocusable = true
            binding.root.isClickable = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGenreChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
