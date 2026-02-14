package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.SessionUiModel

/**
 * Default implementation: returns empty list until sessions collection/listeners are wired.
 * Replace with Firestore (or other) when ready.
 */
object SessionsRepositoryImpl : SessionsRepository {

    override fun getUpcomingSessions(groupId: String, callback: (List<SessionUiModel>, String?) -> Unit) {
        callback(emptyList(), null)
    }
}
