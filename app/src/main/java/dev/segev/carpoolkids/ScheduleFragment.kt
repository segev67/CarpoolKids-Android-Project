package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.databinding.FragmentScheduleBinding
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.ui.schedule.ScheduleListItem
import dev.segev.carpoolkids.ui.schedule.ScheduleListAdapter
import java.util.Calendar

/**
 * Schedule tab: week selector and list of practices grouped by date (read-only in Phase 2).
 */
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    private val adapter = ScheduleListAdapter()

    /** Sunday 00:00:00 of the currently displayed week (in local timezone). */
    private var weekStartMillis: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()

        binding.scheduleList.layoutManager = LinearLayoutManager(requireContext())
        binding.scheduleList.adapter = adapter

        // Initialize to current week (Sunday – Saturday)
        weekStartMillis = getSundayStartOfWeek(Calendar.getInstance().timeInMillis)
        updateWeekLabel()

        binding.scheduleWeekPrev.setOnClickListener {
            weekStartMillis -= 7 * 24 * 60 * 60 * 1000L
            updateWeekLabel()
            loadPractices(groupId)
        }
        binding.scheduleWeekNext.setOnClickListener {
            weekStartMillis += 7 * 24 * 60 * 60 * 1000L
            updateWeekLabel()
            loadPractices(groupId)
        }

        loadPractices(groupId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateWeekLabel() {
        val cal = Calendar.getInstance().apply { timeInMillis = weekStartMillis }
        val startDay = cal.get(Calendar.DAY_OF_MONTH)
        val startMonth = getShortMonth(cal.get(Calendar.MONTH))
        val startYear = cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        val endDay = cal.get(Calendar.DAY_OF_MONTH)
        val endMonth = getShortMonth(cal.get(Calendar.MONTH))
        val endYear = cal.get(Calendar.YEAR)
        binding.scheduleWeekLabel.text = if (startMonth == endMonth && startYear == endYear) {
            getString(R.string.schedule_week_format_with_year, startDay, endDay, startMonth, startYear)
        } else {
            "$startDay $startMonth $startYear – $endDay $endMonth $endYear"
        }
    }

    private fun getShortMonth(month: Int): String = when (month) {
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

    private fun loadPractices(groupId: String) {
        if (groupId.isEmpty()) {
            binding.scheduleEmpty.visibility = View.VISIBLE
            binding.scheduleList.visibility = View.GONE
            adapter.submitList(emptyList())
            return
        }
        val weekEndMillis = weekStartMillis + 7 * 24 * 60 * 60 * 1000L - 1
        PracticeRepository.getPracticesForWeek(groupId, weekStartMillis, weekEndMillis) { practices, error ->
            if (_binding == null) return@getPracticesForWeek
            if (error != null || practices.isEmpty()) {
                binding.scheduleEmpty.visibility = View.VISIBLE
                binding.scheduleList.visibility = View.GONE
                adapter.submitList(emptyList())
                return@getPracticesForWeek
            }
            binding.scheduleEmpty.visibility = View.GONE
            binding.scheduleList.visibility = View.VISIBLE
            adapter.submitList(buildListWithHeaders(practices))
        }
    }

    private fun buildListWithHeaders(practices: List<Practice>): List<ScheduleListItem> {
        val result = mutableListOf<ScheduleListItem>()
        var lastDateMillis: Long = -1L
        for (p in practices) {
            if (p.dateMillis != lastDateMillis) {
                lastDateMillis = p.dateMillis
                result.add(ScheduleListItem.Header(formatDateHeader(p.dateMillis), p.dateMillis))
            }
            result.add(ScheduleListItem.PracticeItem(p))
        }
        return result
    }

    private fun formatDateHeader(dateMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            Calendar.SUNDAY -> "Sun"
            else -> ""
        }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = getShortMonth(cal.get(Calendar.MONTH))
        return "$dayOfWeek, $day $month"
    }

    /** Returns Sunday 00:00:00 of the week containing the given time (week = Sunday – Saturday). */
    private fun getSundayStartOfWeek(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromSunday = when (dayOfWeek) {
            Calendar.SUNDAY -> 0
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 0
        }
        cal.add(Calendar.DAY_OF_MONTH, -daysFromSunday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): ScheduleFragment =
            ScheduleFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
