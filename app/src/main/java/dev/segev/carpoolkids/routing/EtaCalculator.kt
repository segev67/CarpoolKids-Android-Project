package dev.segev.carpoolkids.routing

/**
 * Pure functions converting OSRM leg durations into wall-clock ETAs.
 *
 * Two route directions, two anchors:
 *   PICKUP  — anchored to practice start. Depart so the convoy arrives at the practice on time,
 *             with a small buffer (default 5 minutes) absorbing real-world slippage.
 *   DROPOFF — anchored to practice end. Depart the moment practice ends, drop kids, end at home.
 *
 * [dwellSec] is the per-stop loading time. The plan default is 60 s. It does NOT apply to the
 * final destination (the practice for PICKUP, the driver's home for DROPOFF) — the convoy is done
 * the moment it arrives.
 *
 * [numStops] in these functions = number of intermediate rider stops (excludes both endpoints).
 */
object EtaCalculator {

    fun recommendedDeparture(
        trainingStartMillis: Long,
        totalLegSec: Int,
        numStops: Int,
        dwellSec: Int,
        bufferSec: Int
    ): Long {
        val totalSec = totalLegSec + numStops * dwellSec + bufferSec
        return trainingStartMillis - totalSec * 1000L
    }

    /**
     * Wall-clock arrival times for every leg-end in [legDurations], assuming the convoy departs at
     * [departureMs] and pauses [dwellSec] at each intermediate stop. The output has the same length
     * as [legDurations]; the last entry is the destination ETA (no dwell tacked on after it).
     *
     * Example with 3 rider stops, departure D, leg durations [L0, L1, L2, L3]:
     *   stop0 ETA = D + L0
     *   stop1 ETA = D + L0 + dwell + L1
     *   stop2 ETA = D + L0 + L1 + 2*dwell + L2
     *   final ETA = D + L0 + L1 + L2 + 3*dwell + L3
     */
    fun etaPerStop(
        departureMs: Long,
        legDurations: List<Int>,
        dwellSec: Int
    ): List<Long> {
        val out = ArrayList<Long>(legDurations.size)
        var elapsedSec = 0
        legDurations.forEachIndexed { idx, legSec ->
            elapsedSec += legSec
            out += departureMs + elapsedSec * 1000L
            // Dwell happens before the next leg starts. Adding it after the final leg is harmless
            // because we exit the loop afterward.
            elapsedSec += dwellSec
        }
        return out
    }

    fun returnTime(
        trainingEndMillis: Long,
        totalLegSec: Int,
        numStops: Int,
        dwellSec: Int
    ): Long {
        val totalSec = totalLegSec + numStops * dwellSec
        return trainingEndMillis + totalSec * 1000L
    }
}
