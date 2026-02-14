package dev.segev.carpoolkids.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.R
import dev.segev.carpoolkids.databinding.ItemTeamBinding
import dev.segev.carpoolkids.model.TeamUiModel

class TeamAdapter(
    private val onTeamClick: (TeamUiModel) -> Unit
) : ListAdapter<TeamUiModel, TeamAdapter.TeamViewHolder>(TeamDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TeamViewHolder {
        val binding = ItemTeamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TeamViewHolder(binding, onTeamClick)
    }

    override fun onBindViewHolder(holder: TeamViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TeamViewHolder(
        private val binding: ItemTeamBinding,
        private val onTeamClick: (TeamUiModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(model: TeamUiModel) {
            binding.teamName.text = model.name
            binding.teamMembers.text = binding.root.context.getString(
                R.string.home_members_count,
                model.memberCount
            )
            if (!model.sportOrAgeGroup.isNullOrBlank()) {
                binding.teamSubtitle.text = model.sportOrAgeGroup
                binding.teamSubtitle.visibility = android.view.View.VISIBLE
            } else {
                binding.teamSubtitle.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onTeamClick(model) }
        }
    }

    private class TeamDiffCallback : DiffUtil.ItemCallback<TeamUiModel>() {
        override fun areItemsTheSame(old: TeamUiModel, new: TeamUiModel) = old.id == new.id
        override fun areContentsTheSame(old: TeamUiModel, new: TeamUiModel) = old == new
    }
}
