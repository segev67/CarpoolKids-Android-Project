package dev.segev.carpoolkids.ui.schedule

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.databinding.ItemScheduleHeaderBinding
import dev.segev.carpoolkids.databinding.ItemSchedulePracticeBinding
import dev.segev.carpoolkids.model.Practice

/** One item in the schedule list: either a date header or a practice card. */
sealed class ScheduleListItem {
    data class Header(val dateLabel: String, val dateMillis: Long) : ScheduleListItem()
    data class PracticeItem(val practice: Practice) : ScheduleListItem()
}

class ScheduleListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ScheduleListItem>()

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_PRACTICE = 1
    }

    fun submitList(newItems: List<ScheduleListItem>) {
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    override fun getItemViewType(position: Int): Int =
        when (items[position]) {
            is ScheduleListItem.Header -> VIEW_TYPE_HEADER
            is ScheduleListItem.PracticeItem -> VIEW_TYPE_PRACTICE
        }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemScheduleHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemSchedulePracticeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                PracticeViewHolder(binding)
            }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ScheduleListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ScheduleListItem.PracticeItem -> (holder as PracticeViewHolder).bind(item.practice)
        }
    }

    class HeaderViewHolder(private val binding: ItemScheduleHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: ScheduleListItem.Header) {
            binding.scheduleHeaderLabel.text = header.dateLabel
        }
    }

    class PracticeViewHolder(private val binding: ItemSchedulePracticeBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(practice: Practice) {
            binding.schedulePracticeDay.text = formatDayOfWeek(practice.dateMillis)
            binding.schedulePracticeDate.text = formatDate(practice.dateMillis)
            binding.schedulePracticeTime.text = binding.root.context.getString(
                dev.segev.carpoolkids.R.string.schedule_time_range,
                practice.startTime,
                practice.endTime
            )
            binding.schedulePracticeLocation.text = practice.location.ifBlank { "-" }
            val noDriver = binding.root.context.getString(dev.segev.carpoolkids.R.string.schedule_no_driver)
            binding.schedulePracticeToValue.text = if (!practice.driverToUid.isNullOrBlank()) "—" else noDriver
            binding.schedulePracticeFromValue.text = if (!practice.driverFromUid.isNullOrBlank()) "—" else noDriver
        }

        private fun formatDayOfWeek(dateMillis: Long): String {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = dateMillis }
            return when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> "Mon"
                java.util.Calendar.TUESDAY -> "Tue"
                java.util.Calendar.WEDNESDAY -> "Wed"
                java.util.Calendar.THURSDAY -> "Thu"
                java.util.Calendar.FRIDAY -> "Fri"
                java.util.Calendar.SATURDAY -> "Sat"
                java.util.Calendar.SUNDAY -> "Sun"
                else -> ""
            }
        }

        private fun formatDate(dateMillis: Long): String {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = dateMillis }
            val day = c.get(java.util.Calendar.DAY_OF_MONTH)
            val month = when (c.get(java.util.Calendar.MONTH)) {
                java.util.Calendar.JANUARY -> "Jan"
                java.util.Calendar.FEBRUARY -> "Feb"
                java.util.Calendar.MARCH -> "Mar"
                java.util.Calendar.APRIL -> "Apr"
                java.util.Calendar.MAY -> "May"
                java.util.Calendar.JUNE -> "Jun"
                java.util.Calendar.JULY -> "Jul"
                java.util.Calendar.AUGUST -> "Aug"
                java.util.Calendar.SEPTEMBER -> "Sep"
                java.util.Calendar.OCTOBER -> "Oct"
                java.util.Calendar.NOVEMBER -> "Nov"
                java.util.Calendar.DECEMBER -> "Dec"
                else -> ""
            }
            return "$day $month"
        }
    }
}
