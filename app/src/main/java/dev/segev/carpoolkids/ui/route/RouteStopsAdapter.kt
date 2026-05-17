package dev.segev.carpoolkids.ui.route

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.segev.carpoolkids.R
import dev.segev.carpoolkids.databinding.ItemRouteEndpointBinding
import dev.segev.carpoolkids.databinding.ItemRouteStopBinding
import dev.segev.carpoolkids.model.RouteStop
import dev.segev.carpoolkids.utilities.Constants
import dev.segev.carpoolkids.utilities.bidiSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the route as: optional ORIGIN row, one row per rider stop, optional DESTINATION row.
 *
 * - PICKUP: origin = driver's home, destination = practice location.
 * - DROPOFF: origin = practice location; destination is omitted (the route ends at the last rider).
 *
 * Each rider row shows their saved home address as a subtitle when the route was generated with
 * an address on the rider's profile. Older route docs (pre-Phase-9) don't carry the field and the
 * subtitle is hidden.
 */
class RouteStopsAdapter(
    private val direction: String,
    private var currentUserUid: String = "",
    private val onStopClick: (RouteStop) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Endpoint(val labelRes: Int, val address: String?) : Row()
        data class Rider(val stop: RouteStop) : Row()
    }

    private val rows = mutableListOf<Row>()

    /**
     * Replace the rendered rows. Pass non-null [originAddress] / [destinationAddress] to render
     * those endpoint rows; pass null to omit them. For the empty / failed / not-generated states
     * the fragment calls this with both nulls and an empty [stops] list to render zero rows.
     */
    fun submit(
        stops: List<RouteStop>,
        originAddress: String?,
        destinationAddress: String?
    ) {
        rows.clear()
        if (originAddress != null) {
            rows += Row.Endpoint(R.string.route_endpoint_start, originAddress)
        }
        stops.forEach { rows += Row.Rider(it) }
        if (destinationAddress != null) {
            rows += Row.Endpoint(R.string.route_endpoint_end, destinationAddress)
        }
        notifyDataSetChanged()
    }

    fun setCurrentUserUid(uid: String) {
        if (uid == currentUserUid) return
        currentUserUid = uid
        if (rows.isNotEmpty()) notifyItemRangeChanged(0, rows.size)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Endpoint -> VIEW_TYPE_ENDPOINT
        is Row.Rider -> VIEW_TYPE_RIDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ENDPOINT -> EndpointViewHolder(
                ItemRouteEndpointBinding.inflate(inflater, parent, false)
            )
            VIEW_TYPE_RIDER -> RiderViewHolder(
                ItemRouteStopBinding.inflate(inflater, parent, false)
            )
            else -> error("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Endpoint -> (holder as EndpointViewHolder).bind(row.labelRes, row.address)
            is Row.Rider -> {
                (holder as RiderViewHolder).bind(row.stop, direction, currentUserUid)
                holder.itemView.setOnClickListener { onStopClick(row.stop) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    class EndpointViewHolder(private val binding: ItemRouteEndpointBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(labelRes: Int, addressLabel: String?) {
            val ctx = binding.root.context
            binding.itemRouteEndpointLabel.setText(labelRes)
            val address = addressLabel?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.route_endpoint_no_address)
            binding.itemRouteEndpointAddress.text = bidiSafe(address)
        }
    }

    class RiderViewHolder(private val binding: ItemRouteStopBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stop: RouteStop, direction: String, currentUserUid: String) {
            val ctx = binding.root.context
            binding.itemRouteStopNumber.text = (stop.sequence + 1).toString()
            binding.itemRouteStopName.text = bidiSafe(stop.passengerName.ifBlank { stop.passengerUid })
            binding.itemRouteStopYouBadge.visibility =
                if (stop.passengerUid == currentUserUid) View.VISIBLE else View.GONE

            val timeStr = TIME_FMT.format(Date(stop.etaMillis))
            val actionRes = if (direction == Constants.RouteDirection.DROPOFF) {
                R.string.route_drop_at
            } else {
                R.string.route_pick_up_at
            }
            binding.itemRouteStopAction.text = ctx.getString(actionRes, timeStr)

            val address = stop.addressLabel?.takeIf { it.isNotBlank() }
            if (address != null) {
                binding.itemRouteStopAddress.text = bidiSafe(address)
                binding.itemRouteStopAddress.visibility = View.VISIBLE
            } else {
                binding.itemRouteStopAddress.visibility = View.GONE
            }

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

    companion object {
        private const val VIEW_TYPE_ENDPOINT = 0
        private const val VIEW_TYPE_RIDER = 1
    }
}
