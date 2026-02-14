package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.TodayTrainingUiModel

/**
 * Default implementation: returns null until sessions/training are wired to Firestore.
 */
object TrainingRepositoryImpl : TrainingRepository {

    override fun getTodayTraining(teamId: String, callback: (TodayTrainingUiModel?, String?) -> Unit) {
        callback(null, null)
    }
}
