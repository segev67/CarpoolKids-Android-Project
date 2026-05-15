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
 * n ≤ ~6 stops, and cheap enough to run on the main thread.
 *
 * **Cluster-then-route guard** (post-Phase-8): before running greedy we partition stops into a
 * "regulars" cluster (close to the practice) and an "outliers" set (a 3× distance jump beyond the
 * cluster). Outliers are always placed at the home-adjacent end of the route — for PICKUP that's
 * the start (driver picks them up first, then sweeps through the regulars on the way to practice);
 * for DROPOFF that's the end (driver drops the regulars first, then heads out to the outliers
 * alone). The previous TSP-style total-distance optimum could drag a Nahariya rider to the middle
 * of a Tel Aviv route, trapping the close kids in the car for the long detour — this guard prevents
 * that without touching the n=2..6 happy path.
 *
 * When no outliers are detected, the original greedy + tail 2-opt swap behavior is preserved.
 */
object RouteOrderHeuristic {

    /** A stop counts as an outlier when its distance to the practice is ≥ this multiple of the next-closer stop's distance. */
    private const val OUTLIER_GAP_RATIO = 3.0
    /** Floor on the "previous distance" used in the gap check, so a 5 m vs 25 m hop isn't called a cluster boundary. */
    private const val OUTLIER_MIN_BASE_METERS = 500.0
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun greedyOrder(
        start: LatLng,
        stops: List<Pair<String, LatLng>>,
        end: LatLng,
        practiceLoc: LatLng
    ): List<Pair<String, LatLng>> {
        if (stops.isEmpty()) return emptyList()
        val (regulars, outliers) = partitionOutliers(stops, practiceLoc)
        if (outliers.isEmpty()) {
            val ordered = nearestNeighborFrom(start, stops)
            return maybeSwapTail(start, ordered, end)
        }
        // Outliers are pinned to the home-adjacent end. Whichever endpoint is the practice, the
        // *other* endpoint is the driver's home, and the outliers go next to it. We figure out
        // which side that is by comparing distance — one of them is the practice itself (distance ~0).
        val homeIsStart = haversineMeters(practiceLoc, end) < haversineMeters(practiceLoc, start)
        return if (homeIsStart) {
            // PICKUP shape: home → outliers → regulars → practice. Greedy nearest-neighbor inside
            // each cluster so multi-outlier or multi-regular cases still hop sensibly.
            val outliersOrder = nearestNeighborFrom(start, outliers)
            val cursor = outliersOrder.lastOrNull()?.second ?: start
            val regularsOrder = nearestNeighborFrom(cursor, regulars)
            outliersOrder + regularsOrder
        } else {
            // DROPOFF shape: practice → regulars → outliers → home.
            val regularsOrder = nearestNeighborFrom(start, regulars)
            val cursor = regularsOrder.lastOrNull()?.second ?: start
            val outliersOrder = nearestNeighborFrom(cursor, outliers)
            regularsOrder + outliersOrder
        }
    }

    /**
     * Split [stops] into (regulars, outliers). An outlier is any stop in or beyond the first ≥3×
     * Haversine gap when sorted ascending by distance from [practiceLoc]. For n=1 nothing is ever
     * flagged; for n=2 the rule reduces to a single ratio check on the pair.
     */
    private fun partitionOutliers(
        stops: List<Pair<String, LatLng>>,
        practiceLoc: LatLng
    ): Pair<List<Pair<String, LatLng>>, List<Pair<String, LatLng>>> {
        if (stops.size < 2) return stops to emptyList()
        val sorted = stops
            .map { it to haversineMeters(practiceLoc, it.second) }
            .sortedBy { it.second }
        var splitIdx = sorted.size
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1].second
            val curr = sorted[i].second
            if (prev > OUTLIER_MIN_BASE_METERS && curr > OUTLIER_GAP_RATIO * prev) {
                splitIdx = i
                break
            }
        }
        val regulars = sorted.subList(0, splitIdx).map { it.first }
        val outliers = sorted.subList(splitIdx, sorted.size).map { it.first }
        return regulars to outliers
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
