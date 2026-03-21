package dev.segev.carpoolkids.model

data class Group(
    val id: String,
    val name: String,
    val inviteCode: String,
    val memberIds: List<String>,
    val createdBy: String,
    val blockedUids: List<String> = emptyList(),
    /** Set when the last parent leaves with no children; group cannot accept new joins. */
    val inactive: Boolean = false
)
