package dev.segev.carpoolkids.model

/**
 * A request from a user to join a group. Created when user submits invite code; status PENDING until a parent approves/declines.
 */
data class JoinRequest(
    val id: String,
    val groupId: String,
    val requesterUid: String,
    val status: String,
    val createdAt: Long?
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_DECLINED = "DECLINED"
        const val STATUS_BLOCKED = "BLOCKED"
    }
}
