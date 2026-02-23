package dev.segev.carpoolkids.data

import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.utilities.FirestoreManager

object DriveRequestRepository {

    fun createDriveRequest(request: DriveRequest, callback: (Boolean, String?) -> Unit) {
        FirestoreManager.getInstance().createDriveRequest(request, callback)
    }

    /**
     * Returns true if the user can create a drive request for (groupId, practiceId, direction):
     * slot is free (no driver for that direction) and no PENDING request exists.
     * On failure, callback receives (false, errorMessage).
     */
    fun canCreateDriveRequest(
        groupId: String,
        practiceId: String,
        direction: String,
        callback: (Boolean, String?) -> Unit
    ) {
        PracticeRepository.getPracticeById(practiceId) { practice, error ->
            if (error != null || practice == null) {
                callback(false, "Practice not found")
                return@getPracticeById
            }
            val slotTaken = when (direction) {
                DriveRequest.DIRECTION_TO -> !practice.driverToUid.isNullOrBlank()
                DriveRequest.DIRECTION_FROM -> !practice.driverFromUid.isNullOrBlank()
                else -> true
            }
            if (slotTaken) {
                callback(false, "Slot already taken")
                return@getPracticeById
            }
            FirestoreManager.getInstance().getPendingDriveRequest(groupId, practiceId, direction) { pending ->
                if (pending != null) {
                    callback(false, "A request is already open for this")
                } else {
                    callback(true, null)
                }
            }
        }
    }

    /** Real-time listener for drive requests of a group. Remove the returned registration in onDestroyView. */
    fun listenToDriveRequestsForGroup(groupId: String, callback: (List<DriveRequest>) -> Unit): ListenerRegistration =
        FirestoreManager.getInstance().listenToDriveRequestsForGroup(groupId, callback)
}
