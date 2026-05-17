package dev.segev.carpoolkids.data

import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.CarpoolRoute
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.model.RouteStop
import dev.segev.carpoolkids.model.UserProfile
import dev.segev.carpoolkids.routing.EtaCalculator
import dev.segev.carpoolkids.routing.OsrmClient
import dev.segev.carpoolkids.routing.RouteOrderHeuristic
import dev.segev.carpoolkids.utilities.Constants
import dev.segev.carpoolkids.utilities.FirestoreManager
import java.util.Calendar

/**
 * Orchestrates carpool route generation:
 *   load practice → validate → load passengers → order → OSRM → persist.
 *
 * The result is always a `carpool_routes/{practiceId}_{direction}` document so the route screen
 * can listen once and react to whichever terminal state we end up in (READY / FAILED / EMPTY_ROSTER).
 * A missing-address rider doesn't block the route — they're recorded in `missingAddressUids` and the
 * UI surfaces a warning.
 */
object CarpoolRouteRepository {

    /** Per-stop loading time used in ETA math. Kept here so Phase 5 callers don't reach into routing. */
    private const val DWELL_SEC = 60
    /** Slack added to the pickup departure so the driver isn't late if traffic creeps a bit. */
    private const val PICKUP_BUFFER_SEC = 300

    fun routeId(practiceId: String, direction: String): String = "${practiceId}_$direction"

    fun listenToRoute(routeId: String, callback: (CarpoolRoute?) -> Unit): ListenerRegistration =
        FirestoreManager.getInstance().listenToCarpoolRoute(routeId, callback)

    fun getRoute(routeId: String, callback: (CarpoolRoute?, String?) -> Unit) {
        FirestoreManager.getInstance().getCarpoolRoute(routeId, callback)
    }

    /**
     * End-to-end route generation. The callback is always invoked on the main thread (OSRM hops back
     * before notifying us). [callback] returns the persisted route on success or an error string —
     * EMPTY_ROSTER and FAILED outcomes are still considered "success" from the caller's point of view
     * because they wrote a real doc the route screen can render.
     */
    fun generateAndSave(
        practiceId: String,
        direction: String,
        driverUid: String,
        callback: (CarpoolRoute?, String?) -> Unit
    ) {
        if (direction != Constants.RouteDirection.PICKUP && direction != Constants.RouteDirection.DROPOFF) {
            callback(null, "Unknown route direction")
            return
        }
        FirestoreManager.getInstance().getPracticeById(practiceId) { practice, err ->
            if (practice == null) {
                callback(null, err ?: "Practice not found")
                return@getPracticeById
            }
            val guard = validatePractice(practice, direction, driverUid)
            if (guard != null) {
                callback(null, guard)
                return@getPracticeById
            }
            // From here on `practice.locationLat/Lng` are guaranteed non-null by validatePractice.
            UserRepository.getUser(driverUid) { driverProfile, _ ->
                if (driverProfile?.homeLat == null || driverProfile.homeLng == null) {
                    callback(null, "Driver has no home address yet")
                    return@getUser
                }
                if (practice.participantUids.isEmpty()) {
                    persistEmpty(practice, direction, driverUid, driverProfile, callback)
                    return@getUser
                }
                UserRepository.getUsersByIds(practice.participantUids) { passengerMap ->
                    val (routed, missing) = partitionByAddress(practice.participantUids, passengerMap)
                    if (routed.isEmpty()) {
                        persistEmpty(
                            practice, direction, driverUid, driverProfile,
                            callback, missingAddressUids = missing
                        )
                        return@getUsersByIds
                    }
                    runOsrmAndPersist(
                        practice, direction, driverUid, driverProfile, routed, missing, callback
                    )
                }
            }
        }
    }

    private fun validatePractice(practice: Practice, direction: String, driverUid: String): String? {
        if (practice.canceled) return "Practice has been canceled"
        if (practice.locationLat == null || practice.locationLng == null) {
            return "Practice has no coordinates yet"
        }
        val expectedDriver = when (direction) {
            Constants.RouteDirection.PICKUP -> practice.driverToUid
            Constants.RouteDirection.DROPOFF -> practice.driverFromUid
            else -> null
        }
        if (expectedDriver.isNullOrBlank()) return "No assigned driver for this direction"
        if (expectedDriver != driverUid) return "Only the assigned driver can generate this route"
        return null
    }

    private data class RoutedPassenger(
        val uid: String,
        val name: String,
        val coords: LatLng,
        val addressLabel: String?
    )

    private fun partitionByAddress(
        participantUids: List<String>,
        profiles: Map<String, UserProfile>
    ): Pair<List<RoutedPassenger>, List<String>> {
        val routed = mutableListOf<RoutedPassenger>()
        val missing = mutableListOf<String>()
        // Iterate the participantUids list (preserving order) — profiles map may drop unknown uids,
        // and those should be treated as missing too rather than silently disappearing.
        for (uid in participantUids) {
            val profile = profiles[uid]
            val lat = profile?.homeLat
            val lng = profile?.homeLng
            if (profile != null && lat != null && lng != null) {
                routed += RoutedPassenger(
                    uid = uid,
                    name = profile.displayName?.takeIf { it.isNotBlank() }
                        ?: profile.email?.takeIf { it.isNotBlank() }
                        ?: uid,
                    coords = LatLng(lat, lng),
                    addressLabel = profile.homeAddressLabel?.takeIf { it.isNotBlank() }
                )
            } else {
                missing += uid
            }
        }
        return routed to missing
    }

    private fun runOsrmAndPersist(
        practice: Practice,
        direction: String,
        driverUid: String,
        driverProfile: UserProfile,
        routed: List<RoutedPassenger>,
        missing: List<String>,
        callback: (CarpoolRoute?, String?) -> Unit
    ) {
        val driverHome = LatLng(driverProfile.homeLat!!, driverProfile.homeLng!!)
        val practiceLoc = LatLng(practice.locationLat!!, practice.locationLng!!)
        val isPickup = direction == Constants.RouteDirection.PICKUP
        val origin = if (isPickup) driverHome else practiceLoc
        val destination = if (isPickup) practiceLoc else driverHome
        val orderInput = routed.map { it.uid to it.coords }
        val ordered = RouteOrderHeuristic.greedyOrder(origin, orderInput, destination, practiceLoc)
        // Re-attach the routed-passenger info to the ordered uids so we have names for the stops.
        val routedByUid = routed.associateBy { it.uid }
        val orderedPassengers = ordered.mapNotNull { (uid, _) -> routedByUid[uid] }
        val coords = listOf(origin) + orderedPassengers.map { it.coords } + destination

        OsrmClient.fetchRoute(coords) { result, err ->
            if (result == null) {
                persistFailed(
                    practice, direction, driverUid, driverProfile,
                    failureReason = err ?: "OSRM error", callback = callback,
                    missingAddressUids = missing
                )
                return@fetchRoute
            }
            val route = buildRouteDoc(
                practice = practice,
                direction = direction,
                driverUid = driverUid,
                driverProfile = driverProfile,
                orderedPassengers = orderedPassengers,
                osrmLegDurations = result.legs.map { it.durationSec },
                osrmLegDistances = result.legs.map { it.distanceMeters },
                totalDurationSec = result.totalDurationSec,
                totalDistanceMeters = result.totalDistanceMeters,
                polyline = result.polyline,
                missingAddressUids = missing
            )
            FirestoreManager.getInstance().createOrUpdateCarpoolRoute(route) { ok, writeErr ->
                if (ok) callback(route, null) else callback(null, writeErr)
            }
        }
    }

    private fun buildRouteDoc(
        practice: Practice,
        direction: String,
        driverUid: String,
        driverProfile: UserProfile,
        orderedPassengers: List<RoutedPassenger>,
        osrmLegDurations: List<Int>,
        osrmLegDistances: List<Int>,
        totalDurationSec: Int,
        totalDistanceMeters: Int,
        polyline: String,
        missingAddressUids: List<String>
    ): CarpoolRoute {
        val isPickup = direction == Constants.RouteDirection.PICKUP
        val anchorMillis = if (isPickup) {
            practiceStartMillis(practice)
        } else {
            practiceEndMillis(practice)
        }
        val departureMs = if (isPickup) {
            EtaCalculator.recommendedDeparture(
                trainingStartMillis = anchorMillis,
                totalLegSec = totalDurationSec,
                numStops = orderedPassengers.size,
                dwellSec = DWELL_SEC,
                bufferSec = PICKUP_BUFFER_SEC
            )
        } else {
            anchorMillis
        }
        val perLegEtas = EtaCalculator.etaPerStop(
            departureMs = departureMs,
            legDurations = osrmLegDurations,
            dwellSec = DWELL_SEC
        )
        // OSRM legs has one entry per consecutive coord pair. We sent (origin, p0, p1, ..., pN-1, dest),
        // so legs[i] is the leg ending at the i-th non-origin point. Stops correspond to indices
        // 0..(orderedPassengers.size-1); the final leg lands at the destination and isn't a stop.
        val stops = orderedPassengers.mapIndexed { idx, passenger ->
            RouteStop(
                sequence = idx,
                passengerUid = passenger.uid,
                passengerName = passenger.name,
                lat = passenger.coords.latitude,
                lng = passenger.coords.longitude,
                etaMillis = perLegEtas.getOrNull(idx) ?: departureMs,
                legDurationSec = osrmLegDurations.getOrNull(idx) ?: 0,
                legDistanceMeters = osrmLegDistances.getOrNull(idx) ?: 0,
                addressLabel = passenger.addressLabel
            )
        }
        return CarpoolRoute(
            id = routeId(practice.id, direction),
            groupId = practice.groupId,
            practiceId = practice.id,
            direction = direction,
            driverUid = driverUid,
            driverHomeLat = driverProfile.homeLat!!,
            driverHomeLng = driverProfile.homeLng!!,
            trainingLat = practice.locationLat!!,
            trainingLng = practice.locationLng!!,
            trainingStartTime = practice.startTime,
            trainingEndTime = practice.endTime,
            practiceDateMillis = practice.dateMillis,
            stops = stops,
            recommendedDepartureMillis = departureMs,
            totalDurationSec = totalDurationSec,
            totalDistanceMeters = totalDistanceMeters,
            polyline = polyline,
            polylinePrecision = 5,
            status = Constants.RouteStatus.READY,
            failureReason = null,
            generatedAt = System.currentTimeMillis(),
            generatedByUid = driverUid,
            missingAddressUids = missingAddressUids
        )
    }

    private fun persistEmpty(
        practice: Practice,
        direction: String,
        driverUid: String,
        driverProfile: UserProfile,
        callback: (CarpoolRoute?, String?) -> Unit,
        missingAddressUids: List<String> = emptyList()
    ) {
        val skeleton = skeletonRoute(
            practice, direction, driverUid, driverProfile,
            status = Constants.RouteStatus.EMPTY_ROSTER,
            failureReason = null,
            missingAddressUids = missingAddressUids
        )
        FirestoreManager.getInstance().createOrUpdateCarpoolRoute(skeleton) { ok, err ->
            if (ok) callback(skeleton, null) else callback(null, err)
        }
    }

    private fun persistFailed(
        practice: Practice,
        direction: String,
        driverUid: String,
        driverProfile: UserProfile,
        failureReason: String,
        callback: (CarpoolRoute?, String?) -> Unit,
        missingAddressUids: List<String> = emptyList()
    ) {
        val skeleton = skeletonRoute(
            practice, direction, driverUid, driverProfile,
            status = Constants.RouteStatus.FAILED,
            failureReason = failureReason,
            missingAddressUids = missingAddressUids
        )
        FirestoreManager.getInstance().createOrUpdateCarpoolRoute(skeleton) { ok, err ->
            // Even if the FAILED write succeeded, propagate the underlying OSRM reason to the caller
            // so the UI can show a meaningful toast rather than just "saved".
            if (ok) callback(skeleton, failureReason) else callback(null, err ?: failureReason)
        }
    }

    private fun skeletonRoute(
        practice: Practice,
        direction: String,
        driverUid: String,
        driverProfile: UserProfile,
        status: String,
        failureReason: String?,
        missingAddressUids: List<String>
    ): CarpoolRoute = CarpoolRoute(
        id = routeId(practice.id, direction),
        groupId = practice.groupId,
        practiceId = practice.id,
        direction = direction,
        driverUid = driverUid,
        driverHomeLat = driverProfile.homeLat ?: 0.0,
        driverHomeLng = driverProfile.homeLng ?: 0.0,
        trainingLat = practice.locationLat ?: 0.0,
        trainingLng = practice.locationLng ?: 0.0,
        trainingStartTime = practice.startTime,
        trainingEndTime = practice.endTime,
        practiceDateMillis = practice.dateMillis,
        stops = emptyList(),
        recommendedDepartureMillis = 0L,
        totalDurationSec = 0,
        totalDistanceMeters = 0,
        polyline = "",
        polylinePrecision = 5,
        status = status,
        failureReason = failureReason,
        generatedAt = System.currentTimeMillis(),
        generatedByUid = driverUid,
        missingAddressUids = missingAddressUids
    )

    private fun practiceStartMillis(practice: Practice): Long =
        practiceTimeMillis(practice.dateMillis, practice.startTime)

    private fun practiceEndMillis(practice: Practice): Long =
        practiceTimeMillis(practice.dateMillis, practice.endTime)

    private fun practiceTimeMillis(dateMillis: Long, hhmm: String): Long {
        val parts = hhmm.split(":")
        if (parts.size < 2) return dateMillis
        val hour = parts[0].toIntOrNull() ?: return dateMillis
        val minute = parts[1].toIntOrNull() ?: return dateMillis
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
