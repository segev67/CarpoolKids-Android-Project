package dev.segev.carpoolkids.ui.sessions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.R
import dev.segev.carpoolkids.databinding.ItemSessionCardBinding
import dev.segev.carpoolkids.model.SessionUiModel

class SessionsAdapter : ListAdapter<SessionUiModel, SessionsAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        private val binding: ItemSessionCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(model: SessionUiModel) {
            binding.sessionCardDay.text = model.dayLabel
            binding.sessionCardTime.text = model.timeRange
            binding.sessionCardBadge.text = model.typeLabel
            binding.sessionCardLocation.text = model.location
            binding.sessionCardLocation.visibility = if (model.location.isBlank()) View.GONE else View.VISIBLE

            binding.sessionCardToLabel.text = binding.root.context.getString(R.string.home_to_training)
            binding.sessionCardFromLabel.text = binding.root.context.getString(R.string.home_from_training)
            val hasTo = !model.driverTo.isNullOrBlank()
            val hasBack = !model.driverBack.isNullOrBlank()
            binding.sessionCardDriverToRow.visibility = if (hasTo) View.VISIBLE else View.GONE
            binding.sessionCardDriverBackRow.visibility = if (hasBack) View.VISIBLE else View.GONE
            if (hasTo) binding.sessionCardDriverTo.text = model.driverTo
            if (hasBack) binding.sessionCardDriverBack.text = model.driverBack
        }
    }

    private class SessionDiffCallback : DiffUtil.ItemCallback<SessionUiModel>() {
        override fun areItemsTheSame(old: SessionUiModel, new: SessionUiModel) = old == new
        override fun areContentsTheSame(old: SessionUiModel, new: SessionUiModel) = old == new
    }
}
