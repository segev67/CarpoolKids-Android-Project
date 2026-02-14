package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.TodayTrainingUiModel

/**
 * Repository for today's training for a given team. No hardcoded data — null by default.
 */
interface TrainingRepository {

    fun getTodayTraining(teamId: String, callback: (TodayTrainingUiModel?, String?) -> Unit)
}
