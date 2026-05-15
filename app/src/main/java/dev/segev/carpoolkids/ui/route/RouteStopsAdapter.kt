package dev.segev.carpoolkids.ui.route

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.R
import dev.segev.carpoolkids.databinding.ItemRouteStopBinding
import dev.segev.carpoolkids.model.RouteStop
import dev.segev.carpoolkids.utilities.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders one [RouteStop] per row: numbered chip, name, ETA action line, leg distance.
 * The "You" badge highlights whichever row corresponds to the currently signed-in user — handy when
 * a child opens the route to see when they'll be picked up.
 *
 * Direction governs the action wording ("Pick up · 16:42" for PICKUP, "Drop off · 17:55" for DROPOFF).
 */
class RouteStopsAdapter(
    private val direction: String,
    private var currentUserUid: String = ""
) : RecyclerView.Adapter<RouteStopsAdapter.ViewHolder>() {

    private val items = mutableListOf<RouteStop>()

    fun submitList(list: List<RouteStop>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setCurrentUserUid(uid: String) {
        if (uid == currentUserUid) return
        currentUserUid = uid
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRouteStopBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], direction, currentUserUid)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemRouteStopBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stop: RouteStop, direction: String, currentUserUid: String) {
            val ctx = binding.root.context
            binding.itemRouteStopNumber.text = (stop.sequence + 1).toString()
            binding.itemRouteStopName.text = stop.passengerName.ifBlank { stop.passengerUid }
            binding.itemRouteStopYouBadge.visibility =
                if (stop.passengerUid == currentUserUid) View.VISIBLE else View.GONE

            val timeStr = TIME_FMT.format(Date(stop.etaMillis))
            val actionRes = if (direction == Constants.RouteDirection.DROPOFF) {
                R.string.route_drop_at
            } else {
                R.string.route_pick_up_at
            }
            binding.itemRouteStopAction.text = ctx.getString(actionRes, timeStr)

            // First stop has no "previous leg" to report a distance for — origin is the driver's home
            // (PICKUP) or the practice location (DROPOFF). Hide the distance row in that case.
            if (stop.legDistanceMeters > 0) {
                binding.itemRouteStopDistance.text = ctx.getString(
                    R.string.route_distance_from_previous,
                    formatKm(stop.legDistanceMeters)
                )
                binding.itemRouteStopDistance.visibility = View.VISIBLE
            } else {
                binding.itemRouteStopDistance.visibility = View.GONE
            }
        }

        private fun formatKm(meters: Int): String {
            val km = meters / 1000.0
            return String.format(Locale.US, "%.1f km", km)
        }

        companion object {
            private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.US)
        }
    }
}
