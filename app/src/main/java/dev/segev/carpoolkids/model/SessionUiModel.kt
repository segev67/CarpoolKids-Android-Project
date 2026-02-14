package dev.segev.carpoolkids.model

/**
 * UI model for a single upcoming session card. All fields come from repository/user input — no hardcoded UI data.
 */
data class SessionUiModel(
    val dayLabel: String,
    val timeRange: String,
    val location: String,
    val typeLabel: String,
    val driverTo: String? = null,
    val driverBack: String? = null
)
