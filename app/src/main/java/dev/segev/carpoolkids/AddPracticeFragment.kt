package dev.segev.carpoolkids

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.databinding.FragmentAddPracticeBinding
import java.util.Calendar

/**
 * Add a new practice: pick date, start/end time, location. Save creates the practice and returns to Schedule.
 */
class AddPracticeFragment : Fragment() {

    private var _binding: FragmentAddPracticeBinding? = null
    private val binding get() = _binding!!

    /** Selected date as start-of-day millis. */
    private var selectedDateMillis: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        val defaultDateMillis = arguments?.getLong(ARG_DEFAULT_DATE_MILLIS, 0L) ?: 0L

        selectedDateMillis = if (defaultDateMillis > 0L) defaultDateMillis else getTodayStartMillis()
        updateDateLabel()

        binding.addPracticeDate.setOnClickListener { showDatePicker() }
        binding.addPracticeSave.setOnClickListener { save(groupId) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun getTodayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun updateDateLabel() {
        binding.addPracticeDate.text = formatDateLabel(selectedDateMillis)
    }

    private fun formatDateLabel(dateMillis: Long): String {
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
        val year = c.get(Calendar.YEAR)
        return "$dayOfWeek, $day $month $year"
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                selectedDateMillis = cal.timeInMillis
                updateDateLabel()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun save(groupId: String) {
        if (groupId.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        val startTime = binding.addPracticeStartTime.text?.toString()?.trim().orEmpty()
        val endTime = binding.addPracticeEndTime.text?.toString()?.trim().orEmpty()
        val location = binding.addPracticeLocation.text?.toString()?.trim().orEmpty()

        if (startTime.isBlank() || endTime.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.add_practice_enter_times), Snackbar.LENGTH_SHORT).show()
            return
        }

        val createdBy = FirebaseAuth.getInstance().currentUser?.uid

        PracticeRepository.createPractice(
            groupId,
            selectedDateMillis,
            startTime,
            endTime,
            location,
            createdBy
        ) { _, error ->
            if (_binding == null) return@createPractice
            if (error == null) {
                Snackbar.make(binding.root, getString(R.string.add_practice_created), Snackbar.LENGTH_SHORT).show()
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } else {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"
        private const val ARG_DEFAULT_DATE_MILLIS = "default_date_millis"

        fun newInstance(groupId: String, role: String, defaultDateMillis: Long = 0L): AddPracticeFragment =
            AddPracticeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                    if (defaultDateMillis > 0L) putLong(ARG_DEFAULT_DATE_MILLIS, defaultDateMillis)
                }
            }
    }
}
