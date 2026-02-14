package dev.segev.carpoolkids.model

/**
 * UI model for "Today's Training" card. When null, the section is hidden.
 * All fields come from data layer — no hardcoded names or times.
 */
data class TodayTrainingUiModel(
    val teamName: String,
    val trainingTypeLabel: String,
    val dateText: String,
    val timeRange: String,
    val locationName: String,
    val locationAddress: String?,
    val toTrainingDriverName: String? = null,
    val fromTrainingDriverName: String? = null
)
