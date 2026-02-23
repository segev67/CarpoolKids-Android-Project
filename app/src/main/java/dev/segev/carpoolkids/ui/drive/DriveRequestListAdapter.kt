package dev.segev.carpoolkids.ui.drive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.databinding.ItemDriveRequestBinding
import dev.segev.carpoolkids.model.DriveRequest
import java.util.Calendar

class DriveRequestListAdapter(
    private var requesterNames: Map<String, String> = emptyMap()
) : RecyclerView.Adapter<DriveRequestListAdapter.ViewHolder>() {

    private val items = mutableListOf<DriveRequest>()

    fun submitList(list: List<DriveRequest>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setRequesterNames(names: Map<String, String>) {
        requesterNames = names
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDriveRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], requesterNames)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemDriveRequestBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(request: DriveRequest, requesterNames: Map<String, String>) {
            binding.itemDriveRequestPracticeDate.text = formatPracticeDate(request.practiceDateMillis)
            binding.itemDriveRequestDirection.text = if (request.direction == DriveRequest.DIRECTION_TO) "TO practice" else "FROM practice"
            binding.itemDriveRequestStatus.text = when (request.status) {
                DriveRequest.STATUS_PENDING -> "Open"
                DriveRequest.STATUS_APPROVED -> "Filled"
                DriveRequest.STATUS_DECLINED -> "Declined"
                else -> request.status
            }
            val requesterLabel = requesterNames[request.requesterUid]
                ?: binding.root.context.getString(dev.segev.carpoolkids.R.string.join_request_requester_unknown)
            binding.itemDriveRequestRequester.text = binding.root.context.getString(dev.segev.carpoolkids.R.string.drive_request_requested_by, requesterLabel)
        }

        private fun formatPracticeDate(dateMillis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = dateMillis }
            val dayOfWeek = when (c.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "Mon"
                Calendar.TUESDAY -> "Tue"
                Calendar.WEDNESDAY -> "Wed"
                Calendar.THURSDAY -> "Thu"
                Calendar.FRIDAY -> "Fri"
                Calendar.SATURDAY -> "Sat"
                Calendar.SUNDAY -> "Sun"
                else -> ""
            }
            val day = c.get(Calendar.DAY_OF_MONTH)
            val month = getShortMonth(c.get(Calendar.MONTH))
            return "$dayOfWeek, $day $month"
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
