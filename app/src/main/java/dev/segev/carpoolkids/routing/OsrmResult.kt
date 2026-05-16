package dev.segev.carpoolkids.routing

/**
 * Parsed subset of an OSRM `/route/v1/driving` response.
 *
 * Durations are seconds and distances are meters, both truncated to ints — OSRM returns floats but
 * sub-second routing precision is meaningless for our 60s-dwell, 5-min-buffer model.
 *
 * [polyline] is the precision-5 encoded geometry string (decoded by [PolylineDecoder]).
 * [legs] has one entry per consecutive coordinate pair from the request, in order.
 */
data class OsrmResult(
    val polyline: String,
    val totalDurationSec: Int,
    val totalDistanceMeters: Int,
    val legs: List<Leg>
) {
    data class Leg(
        val durationSec: Int,
        val distanceMeters: Int
    )
}
