package dev.segev.carpoolkids.model

/**
 * UI model for a team/group row on the Home screen.
 * Data comes from repository — no hardcoded UI content.
 */
data class TeamUiModel(
    val id: String,
    val name: String,
    val sportOrAgeGroup: String?,
    val memberCount: Int
)
