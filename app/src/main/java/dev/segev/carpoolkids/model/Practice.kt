package dev.segev.carpoolkids.model

/**
 * A single practice occurrence for a group.
 * dateMillis = start of day (midnight) in milliseconds for the practice day.
 * startTime / endTime = time of day as "HH:mm" (e.g. "17:00", "18:30").
 */
data class Practice(
    val id: String,
    val groupId: String,
    val dateMillis: Long,
    val startTime: String,
    val endTime: String,
    val location: String,
    val driverToUid: String? = null,
    val driverFromUid: String? = null,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    /** When true, practice is off; TO/FROM drivers cleared; drive requests closed (see cancel practice). */
    val canceled: Boolean = false,
    val canceledAt: Long? = null,
    val canceledByUid: String? = null,
    val cancelReason: String? = null
)
