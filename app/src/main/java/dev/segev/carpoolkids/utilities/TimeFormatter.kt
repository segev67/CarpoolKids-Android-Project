package dev.segev.carpoolkids.utilities

object TimeFormatter {

    fun formatTime(deltaMs: Long): String {
        var seconds = (deltaMs / 1000).toInt()
        var minutes = seconds / 60
        seconds %= 60
        var hours = minutes / 60
        minutes %= 60
        hours %= 24

        return buildString {
            append(String.format("%02d", hours))
            append(":")
            append(String.format("%02d", minutes))
            append(":")
            append(String.format("%02d", seconds))
        }
    }

    fun formatDurationMinutes(lengthInMinutes: Int): String {
        val hours = lengthInMinutes / 60
        val minutes = lengthInMinutes % 60
        return buildString {
            append(String.format(locale = null, format = "%02dH", hours))
            append(" ")
            append(String.format(locale = null, format = "%02dM", minutes))
        }
    }
}
