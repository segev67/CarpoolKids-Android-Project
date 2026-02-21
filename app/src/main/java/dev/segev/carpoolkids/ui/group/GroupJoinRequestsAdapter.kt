package dev.segev.carpoolkids.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.databinding.ItemJoinRequestGroupBinding
import dev.segev.carpoolkids.model.JoinRequest

data class GroupJoinRequestRow(
    val request: JoinRequest,
    val requesterDisplay: String,
    val statusLabel: String,
    val relativeTime: String,
    val showActions: Boolean
)

class GroupJoinRequestsAdapter(
    private val onApprove: (JoinRequest) -> Unit,
    private val onDecline: (JoinRequest) -> Unit,
    private val onBlock: (JoinRequest) -> Unit
) : ListAdapter<GroupJoinRequestRow, GroupJoinRequestsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemJoinRequestGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onApprove, onDecline, onBlock)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemJoinRequestGroupBinding,
        private val onApprove: (JoinRequest) -> Unit,
        private val onDecline: (JoinRequest) -> Unit,
        private val onBlock: (JoinRequest) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: GroupJoinRequestRow) {
            binding.itemJoinRequestRequester.text = row.requesterDisplay
            binding.itemJoinRequestStatus.text = row.statusLabel
            binding.itemJoinRequestTime.text = row.relativeTime
            binding.itemJoinRequestActions.visibility = if (row.showActions) View.VISIBLE else View.GONE
            if (row.showActions) {
                binding.itemJoinRequestBtnApprove.setOnClickListener { onApprove(row.request) }
                binding.itemJoinRequestBtnDecline.setOnClickListener { onDecline(row.request) }
                binding.itemJoinRequestBtnBlock.setOnClickListener { onBlock(row.request) }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<GroupJoinRequestRow>() {
        override fun areItemsTheSame(a: GroupJoinRequestRow, b: GroupJoinRequestRow) = a.request.id == b.request.id
        override fun areContentsTheSame(a: GroupJoinRequestRow, b: GroupJoinRequestRow) = a == b
    }
}
