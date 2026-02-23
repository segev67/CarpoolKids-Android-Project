package dev.segev.carpoolkids.ui.drive

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.databinding.ItemDriveRequestBinding
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.model.Practice
import java.util.Calendar

class DriveRequestListAdapter(
    private var requesterNames: Map<String, String> = emptyMap(),
    private var practiceMap: Map<String, Practice> = emptyMap()
) : RecyclerView.Adapter<DriveRequestListAdapter.ViewHolder>() {

    private val items = mutableListOf<DriveRequest>()

    fun submitList(list: List<DriveRequest>) {
        val oldSize = items.size
        items.clear()
        items.addAll(list)
        when {
            oldSize == items.size -> notifyItemRangeChanged(0, items.size)
            oldSize < items.size -> {
                notifyItemRangeChanged(0, oldSize)
                notifyItemRangeInserted(oldSize, items.size - oldSize)
            }
            else -> {
                notifyItemRangeChanged(0, items.size)
                notifyItemRangeRemoved(items.size, oldSize - items.size)
            }
        }
    }

    fun setRequesterNames(names: Map<String, String>) {
        requesterNames = names
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun setPractices(practices: Map<String, Practice>) {
        practiceMap = practices
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDriveRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], requesterNames, practiceMap)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemDriveRequestBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            request: DriveRequest,
            requesterNames: Map<String, String>,
            practiceMap: Map<String, Practice>
        ) {
            val requesterLabel = requesterNames[request.requesterUid]
                ?: binding.root.context.getString(dev.segev.carpoolkids.R.string.join_request_requester_unknown)
            binding.itemDriveRequestRequester.text = binding.root.context.getString(
                dev.segev.carpoolkids.R.string.drive_request_requested_by,
                requesterLabel
            )

            val dateStr = formatPracticeDateLong(request.practiceDateMillis)
            val directionStr = binding.root.context.getString(
                if (request.direction == DriveRequest.DIRECTION_TO) dev.segev.carpoolkids.R.string.drive_request_direction_to_practice
                else dev.segev.carpoolkids.R.string.drive_request_direction_from_practice
            )
            binding.itemDriveRequestPracticeDate.text = binding.root.context.getString(
                dev.segev.carpoolkids.R.string.drive_request_date_direction,
                dateStr,
                directionStr
            )

            val practice = practiceMap[request.practiceId]
            binding.itemDriveRequestTime.text = practice?.startTime ?: "—"
            binding.itemDriveRequestLocation.text = practice?.location?.takeIf { it.isNotBlank() } ?: "—"

            binding.itemDriveRequestStatus.text = when (request.status) {
                DriveRequest.STATUS_PENDING -> "Pending"
                DriveRequest.STATUS_APPROVED -> "Approved"
                DriveRequest.STATUS_DECLINED -> "Declined"
                else -> request.status
            }

            val showActions = request.status == DriveRequest.STATUS_PENDING
            binding.itemDriveRequestActions.visibility = if (showActions) View.VISIBLE else View.GONE
            binding.itemDriveRequestAccept.setOnClickListener { /* Phase 3 */ }
            binding.itemDriveRequestDecline.setOnClickListener { /* Phase 3 */ }
        }

        private fun formatPracticeDateLong(dateMillis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = dateMillis }
            val dayOfWeek = when (c.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "Monday"
                Calendar.TUESDAY -> "Tuesday"
                Calendar.WEDNESDAY -> "Wednesday"
                Calendar.THURSDAY -> "Thursday"
                Calendar.FRIDAY -> "Friday"
                Calendar.SATURDAY -> "Saturday"
                Calendar.SUNDAY -> "Sunday"
                else -> ""
            }
            val day = c.get(Calendar.DAY_OF_MONTH)
            val month = getShortMonth(c.get(Calendar.MONTH))
            return "$dayOfWeek, $month $day"
        }

        private fun getShortMonth(month: Int): String = when (month) {
            Calendar.JANUARY -> "Jan"
            Calendar.FEBRUARY -> "Feb"
            Calendar.MARCH -> "Mar"
            Calendar.APRIL -> "Apr"
            Calendar.MAY -> "May"
            Calendar.JUNE -> "Jun"
            Calendar.JULY -> "Jul"
            Calendar.AUGUST -> "Aug"
            Calendar.SEPTEMBER -> "Sep"
            Calendar.OCTOBER -> "Oct"
            Calendar.NOVEMBER -> "Nov"
            Calendar.DECEMBER -> "Dec"
            else -> ""
        }
    }
}
