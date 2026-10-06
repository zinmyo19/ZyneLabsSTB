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
/** Item for [RowAdapter]: stable id + title + optional trailing value. */
data class RowItem(val id: String, val title: String, val value: String = "")

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
}
