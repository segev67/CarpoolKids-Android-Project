package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.Group
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

    fun joinGroup(inviteCode: String, uid: String, callback: (Group?, String?) -> Unit) {
        FirestoreManager.getInstance().getGroupByInviteCode(inviteCode) { group, error ->
            if (group == null) {
                callback(null, error)
                return@getGroupByInviteCode
            }
            if (uid in group.memberIds) {
                callback(group, null) // already a member: treat as success
                return@getGroupByInviteCode
            }
            FirestoreManager.getInstance().addMemberToGroup(group.id, uid) { success, err ->
                if (success) callback(group, null) else callback(null, err)
            }
        }
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
