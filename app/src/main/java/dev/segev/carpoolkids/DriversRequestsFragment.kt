package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.DriveRequestRepository
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.databinding.FragmentDriveRequestsBinding
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.ui.drive.DriveRequestListAdapter
import dev.segev.carpoolkids.utilities.Constants
import java.util.Calendar
import java.util.UUID

/**
 * Drive requests tab: list of broadcast drive requests for the current group.
 * Phase 2: create request from tab (practice + direction picker) and from practice detail.
 */
class DriversRequestsFragment : Fragment() {

    private var _binding: FragmentDriveRequestsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DriveRequestListAdapter
    private var driveRequestsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriveRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        val role = arguments?.getString(ARG_ROLE).orEmpty()
        val isParent = role == Constants.UserRole.PARENT

        adapter = DriveRequestListAdapter(
            requesterNames = emptyMap(),
            practiceMap = emptyMap(),
            isParent = isParent,
            onAcceptClick = { request -> onAcceptRequest(request) },
            onDeclineClick = { request -> onDeclineRequest(request) }
        )
        binding.driveRequestsList.layoutManager = LinearLayoutManager(requireContext())
        binding.driveRequestsList.adapter = adapter

        binding.driveRequestsRequestBtn.setOnClickListener { openRequestDriveDialog(groupId) }
        binding.driveRequestsIllDriveBtn.visibility = if (isParent) View.VISIBLE else View.GONE
        binding.driveRequestsIllDriveBtn.setOnClickListener { openIllDriveDialog(groupId) }
        attachListener(groupId)
    }

    private fun onAcceptRequest(request: DriveRequest) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        DriveRequestRepository.acceptDriveRequest(request, uid) { success, err ->
            if (_binding == null) return@acceptDriveRequest
            if (success) {
                Snackbar.make(binding.root, getString(R.string.drive_request_approved), Snackbar.LENGTH_SHORT).show()
            } else {
                val msg = when {
                    err?.contains("Slot already taken", ignoreCase = true) == true ||
                    err?.contains("no longer pending", ignoreCase = true) == true ->
                        getString(R.string.drive_request_slot_taken_else)
                    else -> err ?: getString(R.string.create_join_error)
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun onDeclineRequest(request: DriveRequest) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        DriveRequestRepository.declineDriveRequest(request, uid) { success, err ->
            if (_binding == null) return@declineDriveRequest
            if (success) {
                Snackbar.make(binding.root, getString(R.string.drive_request_declined), Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        driveRequestsListener?.remove()
        driveRequestsListener = null
        _binding = null
    }

    private fun openRequestDriveDialog(groupId: String) {
        if (groupId.isEmpty()) return
        openPracticeDirectionPicker(groupId, getString(R.string.drive_request_dialog_title)) { practice, direction ->
            createRequestForSlot(groupId, practice, direction)
        }
    }

    private fun openIllDriveDialog(groupId: String) {
        if (groupId.isEmpty()) return
        openPracticeDirectionPicker(groupId, getString(R.string.drive_request_ill_drive)) { practice, direction ->
            selfDeclareForSlot(groupId, practice, direction)
        }
    }

    private fun openPracticeDirectionPicker(
        groupId: String,
        title: String,
        onSelected: (Practice, String) -> Unit
    ) {
        if (groupId.isEmpty()) return
        val weekStart = getSundayStartOfWeek(Calendar.getInstance().timeInMillis)
        val weekEnd = weekStart + 4 * 7 * 24 * 60 * 60 * 1000L - 1
        PracticeRepository.getPracticesForWeek(groupId, weekStart, weekEnd) { practices, error ->
            if (_binding == null) return@getPracticesForWeek
            if (error != null || practices.isEmpty()) {
                Snackbar.make(binding.root, getString(R.string.drive_requests_no_practices), Snackbar.LENGTH_LONG).show()
                return@getPracticesForWeek
            }
            val now = System.currentTimeMillis()
            val futurePractices = practices.filter { practiceStartMillis(it) > now }
            if (futurePractices.isEmpty()) {
                Snackbar.make(binding.root, getString(R.string.drive_requests_no_practices), Snackbar.LENGTH_LONG).show()
                return@getPracticesForWeek
            }
            val options = mutableListOf<Pair<Practice, String>>()
            for (p in futurePractices) {
                options.add(p to DriveRequest.DIRECTION_TO)
                options.add(p to DriveRequest.DIRECTION_FROM)
            }
            val labels = options.map { (p, dir) -> "${formatPracticeDateShort(p.dateMillis)} – $dir" }
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setItems(labels.toTypedArray()) { _, which ->
                    val (practice, direction) = options[which]
                    onSelected(practice, direction)
                }
                .show()
        }
    }

    /**
     * Epoch ms of the practice start (date + startTime "HH:mm").
     * Used to filter out past practices so we only show future ones.
     */
    private fun practiceStartMillis(practice: Practice): Long {
        val parts = practice.startTime.split(":")
        if (parts.size < 2) return practice.dateMillis
        val hour = parts[0].toIntOrNull() ?: 0
        val minute = parts[1].toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply { timeInMillis = practice.dateMillis }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun createRequestForSlot(groupId: String, practice: Practice, direction: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        DriveRequestRepository.canCreateDriveRequest(groupId, practice.id, direction) { canCreate, errorMessage ->
            if (_binding == null) return@canCreateDriveRequest
            if (!canCreate) {
                val msg = when (errorMessage) {
                    "Slot already taken" -> getString(R.string.drive_request_slot_taken)
                    "A request is already open for this" -> getString(R.string.drive_request_already_open)
                    else -> errorMessage ?: getString(R.string.create_join_error)
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                return@canCreateDriveRequest
            }
            val request = DriveRequest(
                id = UUID.randomUUID().toString(),
                groupId = groupId,
                practiceId = practice.id,
                practiceDateMillis = practice.dateMillis,
                direction = direction,
                requesterUid = uid,
                status = DriveRequest.STATUS_PENDING,
                acceptedByUid = null,
                createdAt = null
            )
            DriveRequestRepository.createDriveRequest(request) { success, err ->
                if (_binding == null) return@createDriveRequest
                if (success) {
                    Snackbar.make(binding.root, getString(R.string.drive_request_created), Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun selfDeclareForSlot(groupId: String, practice: Practice, direction: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        DriveRequestRepository.canSelfDeclare(groupId, practice.id, direction) { canDo, errorMessage ->
            if (_binding == null) return@canSelfDeclare
            if (!canDo) {
                val msg = when (errorMessage) {
                    "Slot already taken" -> getString(R.string.drive_request_slot_taken)
                    "A request is already open for this" -> getString(R.string.drive_request_already_open)
                    else -> errorMessage ?: getString(R.string.create_join_error)
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                return@canSelfDeclare
            }
            val driverToUid = if (direction == DriveRequest.DIRECTION_TO) uid else null
            val driverFromUid = if (direction == DriveRequest.DIRECTION_FROM) uid else null
            PracticeRepository.updatePractice(
                practice.id,
                startTime = null,
                endTime = null,
                location = null,
                driverToUid = driverToUid,
                driverFromUid = driverFromUid
            ) { success, err ->
                if (_binding == null) return@updatePractice
                if (success) {
                    Snackbar.make(binding.root, getString(R.string.drive_request_self_declared), Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun formatPracticeDateShort(dateMillis: Long): String {
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
        val month = getShortMonth(c.get(Calendar.MONTH))
        return "$dayOfWeek, $day $month"
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

    private fun attachListener(groupId: String) {
        driveRequestsListener?.remove()
        driveRequestsListener = null
        if (groupId.isEmpty()) {
            binding.driveRequestsEmpty.visibility = View.VISIBLE
            binding.driveRequestsList.visibility = View.GONE
            adapter.submitList(emptyList())
            return
        }
        driveRequestsListener = DriveRequestRepository.listenToDriveRequestsForGroup(groupId) { requests ->
            if (_binding == null) return@listenToDriveRequestsForGroup
            if (requests.isEmpty()) {
                binding.driveRequestsEmpty.visibility = View.VISIBLE
                binding.driveRequestsList.visibility = View.GONE
                adapter.submitList(emptyList())
                adapter.setRequesterNames(emptyMap())
                adapter.setPractices(emptyMap())
            } else {
                binding.driveRequestsEmpty.visibility = View.GONE
                binding.driveRequestsList.visibility = View.VISIBLE
                adapter.submitList(requests)
                val allUids = (requests.map { it.requesterUid } +
                    requests.mapNotNull { it.acceptedByUid } +
                    requests.mapNotNull { it.declinedByUid }
                ).distinct()
                val practiceIds = requests.map { it.practiceId }.distinct()
                PracticeRepository.getPracticesByIds(practiceIds) { practices ->
                    if (_binding == null) return@getPracticesByIds
                    adapter.setPractices(practices)
                }
                if (allUids.isEmpty()) {
                    adapter.setRequesterNames(emptyMap())
                } else {
                    UserRepository.getUsersByIds(allUids) { profileMap ->
                        if (_binding == null) return@getUsersByIds
                        val nameMap = profileMap.mapValues { (_, profile) ->
                            profile.displayName?.takeIf { it.isNotBlank() }
                                ?: profile.email?.takeIf { it.isNotBlank() }
                                ?: getString(R.string.join_request_requester_unknown)
                        }
                        adapter.setRequesterNames(nameMap)
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): DriversRequestsFragment =
            DriversRequestsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
