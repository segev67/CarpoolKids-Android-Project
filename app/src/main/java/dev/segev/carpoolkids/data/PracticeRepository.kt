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

    fun createPractice(
        groupId: String,
        dateMillis: Long,
        startTime: String,
        endTime: String,
        location: String,
        createdBy: String?,
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
            createdBy = createdBy
        )
        FirestoreManager.getInstance().createPractice(practice) { success, error ->
            if (success) callback(practice, null) else callback(null, error)
        }
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
}
