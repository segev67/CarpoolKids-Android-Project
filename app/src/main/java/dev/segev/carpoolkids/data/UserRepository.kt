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

    fun createUser(profile: UserProfile, callback: (Boolean, String?) -> Unit) {
        FirestoreManager.getInstance().createUserProfile(profile, callback)
    }
}
