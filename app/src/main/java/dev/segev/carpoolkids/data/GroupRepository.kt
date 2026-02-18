package dev.segev.carpoolkids.data

import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.utilities.FirestoreManager
import java.util.UUID

object GroupRepository {

    fun getMyGroups(uid: String, callback: (List<Group>, String?) -> Unit) {
        FirestoreManager.getInstance().getMyGroups(uid, callback)
    }

    fun getGroupById(groupId: String, callback: (Group?, String?) -> Unit) {
        FirestoreManager.getInstance().getGroupById(groupId, callback)
    }

    fun getGroupByInviteCode(code: String, callback: (Group?, String?) -> Unit) {
        FirestoreManager.getInstance().getGroupByInviteCode(code, callback)
    }

    fun createGroup(name: String, createdByUid: String, callback: (Group?, String?) -> Unit) {
        val id = UUID.randomUUID().toString()
        val inviteCode = generateInviteCode()
        val group = Group(
            id = id,
            name = name.trim(),
            inviteCode = inviteCode,
            memberIds = listOf(createdByUid),
            createdBy = createdByUid
        )
        FirestoreManager.getInstance().createGroup(group) { success, error ->
            if (success) callback(group, null) else callback(null, error)
        }
    }

    /**
     * Submit a join request (PENDING). No immediate join; user stays in NO GROUP until a parent approves.
     * Callback: (alreadyMemberGroup, error). If error != null then failure. If alreadyMemberGroup != null then user is already in group (open dashboard). Else request was created (show "Join request sent").
     * Enforces: one active PENDING per (user, group); blocked users cannot request.
     */
    fun createJoinRequest(inviteCode: String, uid: String, callback: (Group?, String?) -> Unit) {
        FirestoreManager.getInstance().getGroupByInviteCode(inviteCode) { group, error ->
            if (group == null) {
                callback(null, error)
                return@getGroupByInviteCode
            }
            if (uid in group.memberIds) {
                callback(group, null) // already a member: open dashboard with this group
                return@getGroupByInviteCode
            }
            if (uid in group.blockedUids) {
                callback(null, "You cannot request to join this group.")
                return@getGroupByInviteCode
            }
            FirestoreManager.getInstance().hasPendingJoinRequest(group.id, uid) { hasPending, err ->
                if (err != null) {
                    callback(null, err)
                    return@hasPendingJoinRequest
                }
                if (hasPending) {
                    callback(null, "You already have a pending request for this group.")
                    return@hasPendingJoinRequest
                }
                FirestoreManager.getInstance().createJoinRequest(group.id, uid) { success, createErr ->
                    if (success) callback(null, null) else callback(null, createErr)
                }
            }
        }
    }

    /** Real-time listener for current user's join requests (Home "My Requests"). Returns registration to remove in onDestroyView. */
    fun listenToMyJoinRequests(uid: String, callback: (List<JoinRequest>) -> Unit): ListenerRegistration {
        return FirestoreManager.getInstance().listenToJoinRequestsForUser(uid, callback)
    }

    /** Batch get groups by id; returns map groupId -> Group. */
    fun getGroupsByIds(ids: List<String>, callback: (Map<String, Group>) -> Unit) {
        FirestoreManager.getInstance().getGroupsByIds(ids, callback)
    }

    /** Regenerates invite code for the group, returns new code on success (PARENT-only). */
    fun regenerateInviteCode(groupId: String, callback: (String?, String?) -> Unit) {
        if (groupId.isBlank()) {
            callback(null, "No group")
            return
        }
        val newCode = generateInviteCode()
        FirestoreManager.getInstance().updateGroupInviteCode(groupId, newCode) { success, error ->
            if (success) callback(newCode, null) else callback(null, error)
        }
    }

    /** 6-char uppercase alphanumeric for easy sharing. */
    private fun generateInviteCode(): String =
        UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
}
