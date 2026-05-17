package dev.segev.carpoolkids.model

/**
 * A single stop on a carpool route. Order in the route is determined by [sequence].
 * Used as a nested element inside [CarpoolRoute.stops].
 */
data class RouteStop(
    val sequence: Int,
    val passengerUid: String,
    val passengerName: String,
    val lat: Double,
    val lng: Double,
    /** Absolute epoch millis when the driver is expected to arrive at this stop. */
    val etaMillis: Long,
    /** Seconds from the previous stop (or from the start point for the first stop). */
    val legDurationSec: Int,
    /** Meters from the previous stop. */
    val legDistanceMeters: Int,
    /**
     * Human-readable address for this stop (reverse-geocoded label saved on the rider's profile
     * at route-generation time). Null on older route docs created before this field existed —
     * the UI hides the subtitle in that case.
     */
    val addressLabel: String? = null
)
