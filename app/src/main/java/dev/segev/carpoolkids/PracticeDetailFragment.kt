package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentPracticeDetailBinding
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.utilities.Constants
import java.util.Calendar

/**
 * Practice detail screen: view and edit start/end time, location, driver TO, driver FROM.
 * Day and date are read-only.
 */
class PracticeDetailFragment : Fragment() {

    private var _binding: FragmentPracticeDetailBinding? = null
    private val binding get() = _binding!!

    private var practice: Practice? = null
    private var parentUids: List<String> = emptyList()
    private var parentLabels: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPracticeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val practiceId = arguments?.getString(ARG_PRACTICE_ID).orEmpty()
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()

        if (practiceId.isEmpty()) {
            Snackbar.make(binding.root, R.string.practice_load_error, Snackbar.LENGTH_SHORT).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        binding.practiceDetailSave.setOnClickListener { save(groupId) }

        PracticeRepository.getPracticeById(practiceId) { p, error ->
            if (_binding == null) return@getPracticeById
            if (p == null || error != null) {
                Snackbar.make(binding.root, getString(R.string.practice_load_error), Snackbar.LENGTH_SHORT).show()
                requireActivity().onBackPressedDispatcher.onBackPressed()
                return@getPracticeById
            }
            practice = p
            bindPractice(p)
            loadParentsForSpinners(groupId, p)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindPractice(p: Practice) {
        binding.practiceDetailDay.text = formatDayOfWeek(p.dateMillis)
        binding.practiceDetailDate.text = formatDate(p.dateMillis)
        binding.practiceDetailStartTime.setText(p.startTime)
        binding.practiceDetailEndTime.setText(p.endTime)
        binding.practiceDetailLocation.setText(p.location)
    }

    private fun loadParentsForSpinners(groupId: String, p: Practice) {
        if (groupId.isEmpty()) {
            setupDriverSpinners(emptyList(), emptyList(), p.driverToUid, p.driverFromUid)
            return
        }
        GroupRepository.getGroupById(groupId) { group, _ ->
            if (_binding == null) return@getGroupById
            val memberIds = group?.memberIds ?: emptyList()
            if (memberIds.isEmpty()) {
                setupDriverSpinners(emptyList(), emptyList(), p.driverToUid, p.driverFromUid)
                return@getGroupById
            }
            UserRepository.getUsersByIds(memberIds) { profileMap ->
                if (_binding == null) return@getUsersByIds
                val parents = memberIds.mapNotNull { uid ->
                    profileMap[uid]?.takeIf { it.role == Constants.UserRole.PARENT }?.let { profile ->
                        uid to (profile.displayName?.takeIf { it.isNotBlank() }
                            ?: profile.email?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.join_request_requester_unknown))
                    }
                }
                val uids = listOf("") + parents.map { it.first }
                val labels = listOf(getString(R.string.schedule_no_driver)) + parents.map { it.second }
                parentUids = uids
                parentLabels = labels
                setupDriverSpinners(uids, labels, p.driverToUid, p.driverFromUid)
            }
        }
    }

    private fun setupDriverSpinners(
        uids: List<String>,
        labels: List<String>,
        selectedToUid: String?,
        selectedFromUid: String?
    ) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.practiceDetailDriverTo.adapter = adapter
        binding.practiceDetailDriverFrom.adapter = adapter
        val toPos = uids.indexOf(selectedToUid.orEmpty().takeIf { it.isNotBlank() }.orEmpty())
        val fromPos = uids.indexOf(selectedFromUid.orEmpty().takeIf { it.isNotBlank() }.orEmpty())
        binding.practiceDetailDriverTo.setSelection(if (toPos >= 0) toPos else 0)
        binding.practiceDetailDriverFrom.setSelection(if (fromPos >= 0) fromPos else 0)
    }

    private fun save(groupId: String) {
        val p = practice ?: return
        val startTime = binding.practiceDetailStartTime.text?.toString()?.trim().orEmpty()
        val endTime = binding.practiceDetailEndTime.text?.toString()?.trim().orEmpty()
        val location = binding.practiceDetailLocation.text?.toString()?.trim().orEmpty()

        if (startTime.isBlank() || endTime.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }

        val driverToPos = binding.practiceDetailDriverTo.selectedItemPosition
        val driverFromPos = binding.practiceDetailDriverFrom.selectedItemPosition
        val driverToUid = if (parentUids.isNotEmpty() && driverToPos in parentUids.indices) parentUids[driverToPos] else ""
        val driverFromUid = if (parentUids.isNotEmpty() && driverFromPos in parentUids.indices) parentUids[driverFromPos] else ""

        PracticeRepository.updatePractice(
            p.id,
            startTime,
            endTime,
            location,
            driverToUid,
            driverFromUid
        ) { success, error ->
            if (_binding == null) return@updatePractice
            if (success) {
                Snackbar.make(binding.root, getString(R.string.practice_saved), Snackbar.LENGTH_SHORT).show()
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } else {
                Snackbar.make(binding.root, error ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun formatDayOfWeek(dateMillis: Long): String = when (
        Calendar.getInstance().apply { timeInMillis = dateMillis }.get(Calendar.DAY_OF_WEEK)
    ) {
        Calendar.MONDAY -> "Mon"
        Calendar.TUESDAY -> "Tue"
        Calendar.WEDNESDAY -> "Wed"
        Calendar.THURSDAY -> "Thu"
        Calendar.FRIDAY -> "Fri"
        Calendar.SATURDAY -> "Sat"
        Calendar.SUNDAY -> "Sun"
        else -> ""
    }

    private fun formatDate(dateMillis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = dateMillis }
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
        return "$day $month ${c.get(Calendar.YEAR)}"
    }

    companion object {
        private const val ARG_PRACTICE_ID = "practice_id"
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(practiceId: String, groupId: String, role: String): PracticeDetailFragment =
            PracticeDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRACTICE_ID, practiceId)
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
