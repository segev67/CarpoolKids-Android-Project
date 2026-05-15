package dev.segev.carpoolkids.data

import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.utilities.FirestoreManager
import java.util.UUID

object PracticeRepository {

    /**
     * Practices for a group in the given week (inclusive). weekStartMillis and weekEndMillis
     * should be start-of-day and end-of-day (or last moment of last day) in millis.
     */
    fun getPracticesForWeek(
        groupId: String,
        weekStartMillis: Long,
        weekEndMillis: Long,
        callback: (List<Practice>, String?) -> Unit
    ) {
        FirestoreManager.getInstance().getPracticesForWeek(groupId, weekStartMillis, weekEndMillis, callback)
    }

    /**
     * Real-time listener for practices in a group for the given week. Remove the returned
     * ListenerRegistration in onDestroyView (or when changing week).
     */
    fun listenToPracticesForWeek(
        groupId: String,
        weekStartMillis: Long,
        weekEndMillis: Long,
        callback: (List<Practice>, String?) -> Unit
    ): ListenerRegistration =
        FirestoreManager.getInstance().listenToPracticesForWeek(groupId, weekStartMillis, weekEndMillis, callback)

    fun getPracticeById(practiceId: String, callback: (Practice?, String?) -> Unit) {
        FirestoreManager.getInstance().getPracticeById(practiceId, callback)
    }

    fun getPracticesByIds(ids: List<String>, callback: (Map<String, Practice>) -> Unit) {
        FirestoreManager.getInstance().getPracticesByIds(ids, callback)
    }

    /** Practices where this user is set as TO or FROM driver on the schedule (may have no drive_request). */
    fun getPracticesWhereUserIsDriver(
        groupId: String,
        uid: String,
        callback: (List<Practice>, String?) -> Unit
    ) {
        FirestoreManager.getInstance().getPracticesWhereUserIsDriver(groupId, uid, callback)
    }

    fun createPractice(
        groupId: String,
        dateMillis: Long,
        startTime: String,
        endTime: String,
        location: String,
        createdBy: String?,
        locationLat: Double? = null,
        locationLng: Double? = null,
        callback: (Practice?, String?) -> Unit
    ) {
        val id = UUID.randomUUID().toString()
        val practice = Practice(
            id = id,
            groupId = groupId,
            dateMillis = dateMillis,
            startTime = startTime.trim(),
            endTime = endTime.trim(),
            location = location.trim(),
            driverToUid = null,
            driverFromUid = null,
            createdBy = createdBy,
            locationLat = locationLat,
            locationLng = locationLng
        )
        FirestoreManager.getInstance().createPractice(practice) { success, error ->
            if (success) callback(practice, null) else callback(null, error)
        }
    }

    /**
     * Phase 2 — set or update the practice's lat/lng (set via the map picker).
     * When [addressLabel] is non-blank, the practice's `location` text field is also updated atomically.
     */
    fun updateLocationCoords(
        practiceId: String,
        lat: Double,
        lng: Double,
        addressLabel: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        FirestoreManager.getInstance().updateLocationCoords(practiceId, lat, lng, addressLabel, callback)
    }

    fun updatePractice(
        practiceId: String,
        startTime: String? = null,
        endTime: String? = null,
        location: String? = null,
        driverToUid: String? = null,
        driverFromUid: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        FirestoreManager.getInstance().updatePractice(
            practiceId,
            startTime,
            endTime,
            location,
            driverToUid,
            driverFromUid,
            callback
        )
    }

    /**
     * Phase 1 — Cancel practice: updates Firestore practice + all active drive requests for that practice.
     * Call from UI only for allowed users (e.g. parent) in a later phase.
     */
    fun cancelPractice(
        practiceId: String,
        canceledByUid: String,
        cancelReason: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        FirestoreManager.getInstance().cancelPractice(practiceId, canceledByUid, cancelReason, callback)
    }

    /** Phase 3 — child adds themselves to the practice carpool roster (arrayUnion). */
    fun joinPractice(
        practiceId: String,
        uid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        FirestoreManager.getInstance().joinPractice(practiceId, uid, callback)
    }

    /** Phase 3 — child removes themselves; cancels any APPROVED drive_request atomically. */
    fun leavePractice(
        practiceId: String,
        uid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        FirestoreManager.getInstance().leavePractice(practiceId, uid, callback)
    }
}
