package dev.segev.carpoolkids.ui.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.R
import dev.segev.carpoolkids.databinding.ItemMyJoinRequestBinding
import dev.segev.carpoolkids.model.JoinRequest

/**
 * Display item for "My Requests" on Home: group name, status, relative time.
 * statusCode is the raw constant (e.g. JoinRequest.STATUS_PENDING) for chip coloring.
 */
data class MyRequestRow(
    val groupName: String,
    val status: String,
    val statusCode: String,
    val relativeTime: String
)

class MyJoinRequestsAdapter : ListAdapter<MyRequestRow, MyJoinRequestsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMyJoinRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemMyJoinRequestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: MyRequestRow) {
            binding.itemMyRequestGroupName.text = row.groupName
            binding.itemMyRequestStatus.text = row.status
            val ctx = binding.root.context
            val bgRes = when (row.statusCode) {
                JoinRequest.STATUS_PENDING -> R.color.status_pending_orange
                JoinRequest.STATUS_APPROVED -> R.color.status_approve_green
                JoinRequest.STATUS_BLOCKED -> R.color.status_block_red
                JoinRequest.STATUS_DECLINED -> R.color.status_block_red
                else -> R.color.dashboard_card_background
            }
            binding.itemMyRequestStatus.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, bgRes)
            )
            binding.itemMyRequestStatus.setTextColor(
                ColorStateList.valueOf(ContextCompat.getColor(ctx, android.R.color.white))
            )
            binding.itemMyRequestTime.text = row.relativeTime
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<MyRequestRow>() {
        override fun areItemsTheSame(a: MyRequestRow, b: MyRequestRow) = a.groupName == b.groupName && a.relativeTime == b.relativeTime && a.statusCode == b.statusCode
        override fun areContentsTheSame(a: MyRequestRow, b: MyRequestRow) = a == b
    }
}
