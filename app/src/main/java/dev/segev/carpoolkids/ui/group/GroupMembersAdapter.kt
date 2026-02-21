package dev.segev.carpoolkids.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.R
import dev.segev.carpoolkids.databinding.ItemGroupMemberBinding
import dev.segev.carpoolkids.utilities.Constants

/** One row for the Group Details members list: display name, email, role, and whether this is the current user (for "You" badge). */
data class GroupMemberRow(
    val uid: String,
    val displayName: String,
    val email: String,
    val role: String,
    val isCurrentUser: Boolean
)

class GroupMembersAdapter : ListAdapter<GroupMemberRow, GroupMembersAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemGroupMemberBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: GroupMemberRow) {
            binding.itemMemberName.text = row.displayName.ifBlank { binding.root.context.getString(R.string.join_request_requester_unknown) }
            binding.itemMemberEmail.text = row.email.ifBlank { "" }
            binding.itemMemberRole.text = if (row.role == Constants.UserRole.PARENT)
                binding.root.context.getString(R.string.group_details_role_parent)
            else
                binding.root.context.getString(R.string.group_details_role_child)
            binding.itemMemberYouBadge.visibility = if (row.isCurrentUser) View.VISIBLE else View.GONE
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<GroupMemberRow>() {
        override fun areItemsTheSame(a: GroupMemberRow, b: GroupMemberRow) = a.uid == b.uid
        override fun areContentsTheSame(a: GroupMemberRow, b: GroupMemberRow) = a == b
    }
}
