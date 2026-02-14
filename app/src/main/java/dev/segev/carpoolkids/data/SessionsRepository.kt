package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.SessionUiModel

/**
 * Repository for upcoming sessions of a group. No hardcoded data — returns empty list by default
 * until sessions are stored and loaded from Firestore (or other backend).
 */
interface SessionsRepository {

    fun getUpcomingSessions(groupId: String, callback: (List<SessionUiModel>, String?) -> Unit)
}
