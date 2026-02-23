package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.snackbar.Snackbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentScheduleBinding
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.ui.schedule.ScheduleListItem
import dev.segev.carpoolkids.ui.schedule.ScheduleListAdapter
import java.util.Calendar

/**
 * Schedule tab: week selector and list of practices grouped by date. Real-time updates (Phase 5).
 */
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ScheduleListAdapter

    /** Sunday 00:00:00 of the currently displayed week (in local timezone). */
    private var weekStartMillis: Long = 0L

    private var practicesListener: ListenerRegistration? = null

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
        val role = arguments?.getString(ARG_ROLE).orEmpty()

        adapter = ScheduleListAdapter(onPracticeClick = { practice ->
            parentFragmentManager.commit {
                replace(
                    R.id.dashboard_fragment_container,
                    PracticeDetailFragment.newInstance(practice.id, groupId, role),
                    "practice_detail"
                )
                addToBackStack("practice_detail")
            }
        })
        binding.scheduleList.layoutManager = LinearLayoutManager(requireContext())
        binding.scheduleList.adapter = adapter

        // Initialize to current week (Sunday – Saturday)
        weekStartMillis = getSundayStartOfWeek(Calendar.getInstance().timeInMillis)
        updateWeekLabel()

        binding.scheduleWeekPrev.setOnClickListener {
            weekStartMillis -= 7 * 24 * 60 * 60 * 1000L
            updateWeekLabel()
            attachPracticesListener(groupId)
        }
        binding.scheduleWeekNext.setOnClickListener {
            weekStartMillis += 7 * 24 * 60 * 60 * 1000L
            updateWeekLabel()
            attachPracticesListener(groupId)
        }

        attachPracticesListener(groupId)

        binding.scheduleAddPractice.setOnClickListener {
            if (groupId.isEmpty()) {
                Snackbar.make(binding.root, getString(R.string.add_practice_no_team), Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            parentFragmentManager.commit {
                replace(
                    R.id.dashboard_fragment_container,
                    AddPracticeFragment.newInstance(groupId, role, weekStartMillis),
                    "add_practice"
                )
                addToBackStack("add_practice")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        practicesListener?.remove()
        practicesListener = null
        _binding = null
    }

    /** Attaches a real-time listener for the current week; removes any previous listener. */
    private fun attachPracticesListener(groupId: String) {
        practicesListener?.remove()
        practicesListener = null
        if (groupId.isEmpty()) {
            showEmpty()
            return
        }
        showLoading()
        val weekEndMillis = weekStartMillis + 7 * 24 * 60 * 60 * 1000L - 1
        practicesListener = PracticeRepository.listenToPracticesForWeek(
            groupId, weekStartMillis, weekEndMillis
        ) { practices, error ->
            if (_binding == null) return@listenToPracticesForWeek
            binding.scheduleProgress.visibility = View.GONE
            when {
                error != null -> {
                    binding.scheduleErrorMessage.text = getString(R.string.schedule_load_error)
                    binding.scheduleErrorContainer.visibility = View.VISIBLE
                    binding.scheduleEmpty.visibility = View.GONE
                    binding.scheduleList.visibility = View.GONE
                    adapter.submitList(emptyList())
                }
                practices.isEmpty() -> showEmpty()
                else -> {
                    binding.scheduleErrorContainer.visibility = View.GONE
                    binding.scheduleEmpty.visibility = View.GONE
                    val driverUids = practices.flatMap { p ->
                        listOfNotNull(p.driverToUid, p.driverFromUid).filter { it.isNotBlank() }
                    }.distinct()
                    if (driverUids.isEmpty()) {
                        adapter.setDriverNames(emptyMap())
                        adapter.submitList(buildListWithHeaders(practices))
                        binding.scheduleList.visibility = View.VISIBLE
                    } else {
                        UserRepository.getUsersByIds(driverUids) { profileMap ->
                            if (_binding == null) return@getUsersByIds
                            val nameMap = profileMap.mapValues { (_, profile) ->
                                profile.displayName?.takeIf { it.isNotBlank() }
                                    ?: profile.email?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.join_request_requester_unknown)
                            }
                            adapter.setDriverNames(nameMap)
                            adapter.submitList(buildListWithHeaders(practices))
                            binding.scheduleList.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun showLoading() {
        binding.scheduleProgress.visibility = View.VISIBLE
        binding.scheduleEmpty.visibility = View.GONE
        binding.scheduleErrorContainer.visibility = View.GONE
        binding.scheduleList.visibility = View.GONE
    }

    private fun showEmpty() {
        binding.scheduleErrorContainer.visibility = View.GONE
        binding.scheduleEmpty.visibility = View.VISIBLE
        binding.scheduleList.visibility = View.GONE
        adapter.submitList(emptyList())
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
