package dev.segev.carpoolkids.data

import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.utilities.FirestoreManager

object DriveRequestRepository {

    fun createDriveRequest(request: DriveRequest, callback: (Boolean, String?) -> Unit) {
        FirestoreManager.getInstance().createDriveRequest(request, callback)
    }

    /** Real-time listener for drive requests of a group. Remove the returned registration in onDestroyView. */
    fun listenToDriveRequestsForGroup(groupId: String, callback: (List<DriveRequest>) -> Unit): ListenerRegistration =
        FirestoreManager.getInstance().listenToDriveRequestsForGroup(groupId, callback)
}
