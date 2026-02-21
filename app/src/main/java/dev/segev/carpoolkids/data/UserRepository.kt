package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.UserProfile
import dev.segev.carpoolkids.utilities.FirestoreManager

/**
 * Thin repository for user profile operations.
 * Delegates to FirestoreManager; keeps Activities free of Firestore details.
 */
object UserRepository {

    fun getUser(uid: String, callback: (UserProfile?, String?) -> Unit) {
        FirestoreManager.getInstance().getUserProfile(uid, callback)
    }

    /** Batch get profiles by uid; returns map uid -> UserProfile (e.g. for Join Requests requester display). */
    fun getUsersByIds(uids: List<String>, callback: (Map<String, UserProfile>) -> Unit) {
        FirestoreManager.getInstance().getUsersByIds(uids, callback)
    }

    fun createUser(profile: UserProfile, callback: (Boolean, String?) -> Unit) {
        FirestoreManager.getInstance().createUserProfile(profile, callback)
    }
}
