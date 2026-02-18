package dev.segev.carpoolkids.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.databinding.ItemMyJoinRequestBinding

/**
 * Display item for "My Requests" on Home: group name, status, relative time.
 */
data class MyRequestRow(
    val groupName: String,
    val status: String,
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
            binding.itemMyRequestTime.text = row.relativeTime
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<MyRequestRow>() {
        override fun areItemsTheSame(a: MyRequestRow, b: MyRequestRow) = a.groupName == b.groupName && a.relativeTime == b.relativeTime
        override fun areContentsTheSame(a: MyRequestRow, b: MyRequestRow) = a == b
    }
}
