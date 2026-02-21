package dev.segev.carpoolkids.ui.group

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.databinding.ItemBlockedUserBinding

/** Display row for blocked user: uid (for unblock) and label (email or display name). */
data class BlockedUserRow(val uid: String, val displayLabel: String)

class BlockedUsersAdapter(
    private val onUnblock: (String) -> Unit
) : ListAdapter<BlockedUserRow, BlockedUsersAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockedUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onUnblock)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemBlockedUserBinding,
        private val onUnblock: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: BlockedUserRow) {
            binding.itemBlockedUserLabel.text = row.displayLabel
            binding.itemBlockedUserBtnUnblock.setOnClickListener { onUnblock(row.uid) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<BlockedUserRow>() {
        override fun areItemsTheSame(a: BlockedUserRow, b: BlockedUserRow) = a.uid == b.uid
        override fun areContentsTheSame(a: BlockedUserRow, b: BlockedUserRow) = a == b
    }
}
