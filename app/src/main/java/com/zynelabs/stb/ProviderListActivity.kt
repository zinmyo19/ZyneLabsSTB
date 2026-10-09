package com.zynelabs.stb

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ActivityProviderListBinding
import com.zynelabs.stb.databinding.ItemRowBinding

/**
 * v6.3: the single provider-management hub (Settings → Providers).
 * Tap a provider = switch to it (or edit it when already active).
 * Long-press = delete. "Add provider" = new (any type).
 * D-pad navigable.
 */
class ProviderListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProviderListBinding
    private val adapter = ProviderAdapter(
        onTap = { p -> onProviderTap(p) },
        onDelete = { p -> confirmDelete(p) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = getString(R.string.provider_list_title)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnAdd.setOnClickListener {
            startActivity(Intent(this, AddProviderActivity::class.java))
        }
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val providers = ProviderStore.list(this)
        val activeId = ProviderStore.getActiveId(this)
        adapter.submit(providers, activeId)
        if (providers.isNotEmpty()) binding.recyclerView.requestFocus()
        else binding.btnAdd.requestFocus()
    }

    /** Tap: switch when inactive; edit when already active. */
    private fun onProviderTap(p: Provider) {
        if (p.id == ProviderStore.getActiveId(this)) {
            startActivity(
                Intent(this, AddProviderActivity::class.java)
                    .putExtra(AddProviderActivity.EXTRA_PROVIDER_ID, p.id)
            )
            return
        }
        ProviderStore.setActive(this, p.id)
        ProviderStore.applyActive(this)
        // v6.3: switching providers must drop the old provider's lists —
        // otherwise the previous portal's channels stay on screen.
        SourceManager.clearAllCaches(this)
        refresh()
        finish() // back to Settings; Home reloads fresh on next open
    }

    private fun confirmDelete(p: Provider) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_provider_title))
            .setMessage(
                getString(
                    R.string.delete_provider_msg,
                    ProviderStore.displayName(p)
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val wasActive = p.id == ProviderStore.getActiveId(this)
                ProviderStore.delete(this, p.id)
                if (wasActive) {
                    ProviderStore.list(this).firstOrNull()?.let {
                        ProviderStore.setActive(this, it.id)
                    }
                    ProviderStore.applyActive(this)
                }
                // v6.3: deleting the last provider must not leave its
                // channels playable from cache (the reported bug).
                SourceManager.clearAllCaches(this)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ adapter

    private class ProviderAdapter(
        private val onTap: (Provider) -> Unit,
        private val onDelete: (Provider) -> Unit
    ) : RecyclerView.Adapter<ProviderAdapter.VH>() {

        private var items: List<Provider> = emptyList()
        private var activeId: String = ""

        fun submit(list: List<Provider>, activeId: String) {
            items = list
            this.activeId = activeId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        private inner class VH(
            private val b: ItemRowBinding
        ) : RecyclerView.ViewHolder(b.root) {
            fun bind(p: Provider) {
                val mark = if (p.id == activeId) "● " else "○ "
                b.tvTitle.text = mark + ProviderStore.displayName(p)
                // v6.3.2: show cached expiry + channel count per row.
                val ctx = b.root.context
                val cached = ProviderStore.getPortalInfo(ctx, p.id)
                val typeLabel = ProviderStore.typeLabel(p)
                b.tvValue.text = if (cached != null) {
                    val (exp, count) = cached
                    val expKnown = exp.isNotBlank() && exp != "—"
                    when {
                        expKnown && count >= 0 -> ctx.getString(
                            R.string.provider_row_detail,
                            typeLabel, exp, count
                        )
                        !expKnown && count >= 0 -> ctx.getString(
                            R.string.provider_row_detail_noexp,
                            typeLabel, count
                        )
                        expKnown -> "$typeLabel · Exp: $exp"
                        else -> typeLabel
                    }
                } else {
                    typeLabel
                }
                b.root.setOnClickListener { onTap(p) }
                b.root.setOnLongClickListener {
                    onDelete(p)
                    true
                }
                b.root.isFocusable = true
            }
        }
    }
}
