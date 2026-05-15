package dev.segev.carpoolkids.routing

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Greedy nearest-neighbor stop ordering for a single carpool route.
 *
 * Distance metric is Haversine — close enough to real road distances for ordering decisions with
 * n ≤ ~6 stops, and cheap enough to run on the main thread. After the greedy pass we do one tail
 * 2-opt swap that uses [end] to decide whether the last two stops should be reversed; this avoids
 * the classic nearest-neighbor failure where the algorithm strands a far stop in the final slot
 * just because every closer candidate was picked earlier.
 */
object RouteOrderHeuristic {

    fun greedyOrder(
        start: LatLng,
        stops: List<Pair<String, LatLng>>,
        end: LatLng
    ): List<Pair<String, LatLng>> {
        if (stops.isEmpty()) return emptyList()
        val ordered = nearestNeighborFrom(start, stops)
        return maybeSwapTail(start, ordered, end)
    }

    private fun nearestNeighborFrom(
        start: LatLng,
        stops: List<Pair<String, LatLng>>
    ): List<Pair<String, LatLng>> {
        val remaining = stops.toMutableList()
        val out = ArrayList<Pair<String, LatLng>>(stops.size)
        var cursor = start
        while (remaining.isNotEmpty()) {
            var bestIdx = 0
            var bestDist = haversineMeters(cursor, remaining[0].second)
            for (i in 1 until remaining.size) {
                val d = haversineMeters(cursor, remaining[i].second)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            out += remaining.removeAt(bestIdx)
            cursor = out.last().second
        }
        return out
    }

    private fun maybeSwapTail(
        start: LatLng,
        ordered: List<Pair<String, LatLng>>,
        end: LatLng
    ): List<Pair<String, LatLng>> {
        if (ordered.size < 2) return ordered
        val n = ordered.size
        val anchor = if (n >= 3) ordered[n - 3].second else start
        val penultimate = ordered[n - 2].second
        val last = ordered[n - 1].second
        val currentTail = haversineMeters(anchor, penultimate) +
            haversineMeters(penultimate, last) +
            haversineMeters(last, end)
        val swappedTail = haversineMeters(anchor, last) +
            haversineMeters(last, penultimate) +
            haversineMeters(penultimate, end)
        if (swappedTail >= currentTail) return ordered
        val out = ordered.toMutableList()
        out[n - 2] = ordered[n - 1]
        out[n - 1] = ordered[n - 2]
        return out
    }

    private const val EARTH_RADIUS_M = 6_371_000.0

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }
}
