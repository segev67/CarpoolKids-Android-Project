package dev.segev.carpoolkids.model

/**
 * User profile document stored at users/{uid}.
 * Used for role selection and app entry flow.
 */
data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val role: String,
    val photoUrl: String? = null,
    val createdAt: Long? = null,
    val parentUids: List<String> = emptyList(),
    val childUids: List<String> = emptyList(),
    /** Home coordinates used as the pickup point / driver origin for carpool routes. */
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    val homeAddressLabel: String? = null
)
