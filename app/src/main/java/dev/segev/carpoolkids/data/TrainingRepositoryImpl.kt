package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.model.TodayTrainingUiModel
import java.util.Calendar

/**
 * Loads today's practice for the group and maps it to TodayTrainingUiModel.
 * If there is no practice today, returns null so the Home tab shows "Rest day, no practice today."
 */
object TrainingRepositoryImpl : TrainingRepository {

    override fun getTodayTraining(teamId: String, callback: (TodayTrainingUiModel?, String?) -> Unit) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MILLISECOND, -1)
        val todayEnd = cal.timeInMillis

        PracticeRepository.getPracticesForWeek(teamId, todayStart, todayEnd) { practices, err ->
            if (err != null) {
                callback(null, err)
                return@getPracticesForWeek
            }
            if (practices.isNullOrEmpty()) {
                callback(null, null)
                return@getPracticesForWeek
            }
            val practice = practices.minByOrNull { it.startTime } ?: practices.first()
            GroupRepository.getGroupById(teamId) { group, _ ->
                val teamName = group?.name?.takeIf { it.isNotBlank() } ?: ""
                val driverUids = listOfNotNull(
                    practice.driverToUid?.takeIf { it.isNotBlank() },
                    practice.driverFromUid?.takeIf { it.isNotBlank() }
                ).distinct()
                if (driverUids.isEmpty()) {
                    callback(buildModel(practice, teamName, null, null), null)
                    return@getGroupById
                }
                UserRepository.getUsersByIds(driverUids) { profileMap ->
                    val toName = practice.driverToUid?.takeIf { it.isNotBlank() }?.let { uid ->
                        profileMap[uid]?.displayName?.takeIf { it.isNotBlank() }
                            ?: profileMap[uid]?.email?.takeIf { it.isNotBlank() }
                            ?: ""
                    } ?: ""
                    val fromName = practice.driverFromUid?.takeIf { it.isNotBlank() }?.let { uid ->
                        profileMap[uid]?.displayName?.takeIf { it.isNotBlank() }
                            ?: profileMap[uid]?.email?.takeIf { it.isNotBlank() }
                            ?: ""
                    } ?: ""
                    callback(buildModel(practice, teamName, toName.ifBlank { null }, fromName.ifBlank { null }), null)
                }
            }
        }
    }

    private fun buildModel(
        practice: Practice,
        teamName: String,
        toDriverName: String?,
        fromDriverName: String?
    ): TodayTrainingUiModel {
        val dateText = formatDate(practice.dateMillis)
        val timeRange = "${practice.startTime} – ${practice.endTime}"
        return TodayTrainingUiModel(
            teamName = teamName,
            trainingTypeLabel = "Practice",
            dateText = dateText,
            timeRange = timeRange,
            locationName = practice.location.takeIf { it.isNotBlank() } ?: "",
            locationAddress = null,
            toTrainingDriverName = toDriverName,
            fromTrainingDriverName = fromDriverName
        )
    }

    private fun formatDate(dateMillis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val dayOfWeek = when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            Calendar.SUNDAY -> "Sun"
            else -> ""
        }
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = when (c.get(Calendar.MONTH)) {
            Calendar.JANUARY -> "Jan"
            Calendar.FEBRUARY -> "Feb"
            Calendar.MARCH -> "Mar"
            Calendar.APRIL -> "Apr"
            Calendar.MAY -> "May"
            Calendar.JUNE -> "Jun"
            Calendar.JULY -> "Jul"
            Calendar.AUGUST -> "Aug"
            Calendar.SEPTEMBER -> "Sep"
            Calendar.OCTOBER -> "Oct"
            Calendar.NOVEMBER -> "Nov"
            Calendar.DECEMBER -> "Dec"
            else -> ""
        }
        return "$dayOfWeek, $day $month"
    }
}
