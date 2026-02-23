package dev.segev.carpoolkids.model

/**
 * A broadcast request for a driver for a practice slot (TO or FROM).
 * Stores who sent it (requesterUid); when approved, acceptedByUid is set and practice's driverToUid/driverFromUid updated elsewhere.
 */
data class DriveRequest(
    val id: String,
    val groupId: String,
    val practiceId: String,
    /** Start-of-day millis for the practice (for display/sort without loading practice). */
    val practiceDateMillis: Long,
    /** "TO" or "FROM" */
    val direction: String,
    val requesterUid: String,
    val status: String,
    val acceptedByUid: String? = null,
    val createdAt: Long? = null
) {
    companion object {
        const val DIRECTION_TO = "TO"
        const val DIRECTION_FROM = "FROM"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_DECLINED = "DECLINED"
    }
}
