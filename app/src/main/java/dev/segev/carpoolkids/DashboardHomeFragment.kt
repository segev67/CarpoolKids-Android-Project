package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.firebase.ui.auth.AuthUI
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.DriveRequestRepository
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.data.TrainingRepository
import dev.segev.carpoolkids.data.TrainingRepositoryImpl
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentDashboardHomeBinding
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.model.TodayTrainingUiModel
import dev.segev.carpoolkids.ui.home.MyJoinRequestsAdapter
import dev.segev.carpoolkids.ui.home.MyRequestRow
import dev.segev.carpoolkids.utilities.Constants
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home tab of the Group Dashboard: group name, today's training (if any),
 * Teams List and Create/Join Group buttons, Sign out.
 */
class DashboardHomeFragment : Fragment() {

    private var _binding: FragmentDashboardHomeBinding? = null
    private val binding get() = _binding!!

    private val trainingRepository: TrainingRepository = TrainingRepositoryImpl
    private val myRequestsAdapter = MyJoinRequestsAdapter()
    private var myRequestsListener: ListenerRegistration? = null
    private var currentUserRole: String = ""
    private var activeGroupId: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activeGroupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        binding.dashboardHomeBtnLeaveCarpool.visibility = View.GONE
        if (activeGroupId.isNotEmpty()) {
            loadGroupAndToday(activeGroupId)
        }
        binding.dashboardHomeBtnTeamsList.setOnClickListener {
            (activity as? DashboardHomeListener)?.openTeamsList()
        }
        binding.dashboardHomeBtnCreateJoin.setOnClickListener {
            (activity as? DashboardHomeListener)?.openCreateJoin()
        }
        binding.dashboardHomeBtnLeaveCarpool.setOnClickListener {
            val groupId = activeGroupId
            if (groupId.isBlank()) return@setOnClickListener
            val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            if (uid.isBlank()) return@setOnClickListener
            when (currentUserRole) {
                Constants.UserRole.CHILD -> requestChildLeaveCarpool(groupId, uid)
                else -> requestParentLeaveCarpool(groupId, uid)
            }
        }
        binding.dashboardHomeBtnSignOut.setOnClickListener {
            AuthUI.getInstance().signOut(requireContext()).addOnCompleteListener {
                startActivity(
                    Intent(requireContext(), LoginActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                requireActivity().finish()
            }
        }
        binding.dashboardHomeMyRequestsList.layoutManager = LinearLayoutManager(requireContext())
        binding.dashboardHomeMyRequestsList.adapter = myRequestsAdapter
        startMyRequestsListener()
    }

    override fun onDestroyView() {
        myRequestsListener?.remove()
        myRequestsListener = null
        super.onDestroyView()
        _binding = null
    }

    /** Real-time listener: PENDING first, then newest; max 5; resolve group names. */
    private fun startMyRequestsListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        myRequestsListener = GroupRepository.listenToMyJoinRequests(uid) { requests ->
            if (_binding == null) return@listenToMyJoinRequests
            val sorted = requests
                .sortedWith(compareBy<JoinRequest> { it.status != JoinRequest.STATUS_PENDING }.thenByDescending { it.createdAt ?: 0L })
                .take(5)
            if (sorted.isEmpty()) {
                binding.dashboardHomeMyRequestsEmpty.visibility = View.VISIBLE
                binding.dashboardHomeMyRequestsList.visibility = View.GONE
                myRequestsAdapter.submitList(emptyList())
                return@listenToMyJoinRequests
            }
            val groupIds = sorted.map { it.groupId }.distinct()
            GroupRepository.getGroupsByIds(groupIds) { groupMap ->
                if (_binding == null) return@getGroupsByIds
                val rows = sorted.map { req ->
                    val name = groupMap[req.groupId]?.name ?: getString(R.string.example_group_name)
                    val relative = req.createdAt?.let { ts ->
                        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                    } ?: ""
                    val statusLabel = when (req.status) {
                        JoinRequest.STATUS_PENDING -> getString(R.string.status_pending)
                        JoinRequest.STATUS_APPROVED -> getString(R.string.status_approved)
                        JoinRequest.STATUS_DECLINED -> getString(R.string.status_declined)
                        JoinRequest.STATUS_BLOCKED -> getString(R.string.status_blocked)
                        else -> req.status
                    }
                    MyRequestRow(name, statusLabel, req.status, relative)
                }
                binding.dashboardHomeMyRequestsEmpty.visibility = View.GONE
                binding.dashboardHomeMyRequestsList.visibility = View.VISIBLE
                myRequestsAdapter.submitList(rows)
            }
        }
    }

    /**
     * Called by GroupDashboardActivity when the user returns from Teams List or Create/Join
     * with a new group id. Reloads group name and today's training for that group.
     */
    fun refreshWithGroup(groupId: String) {
        activeGroupId = groupId
        arguments = (arguments ?: Bundle()).apply { putString(ARG_GROUP_ID, groupId) }
        loadGroupAndToday(groupId)
    }

    private fun loadGroupAndToday(groupId: String) {
        activeGroupId = groupId
        if (_binding == null) return
        binding.dashboardHomeGroupName.visibility = View.GONE
        binding.dashboardHomeHelloUser.visibility = View.GONE
        binding.dashboardHomeTodayCard.visibility = View.GONE
        binding.dashboardHomeNoTrainingMessage.visibility = View.GONE

        GroupRepository.getGroupById(groupId) { group, _ ->
            if (_binding == null) return@getGroupById
            if (group != null) {
                binding.dashboardHomeGroupName.text = getString(R.string.carpool_header_format, group.name)
                binding.dashboardHomeGroupName.visibility = View.VISIBLE
                loadCurrentUserGreeting()
                loadTodayTraining(groupId)
            }
        }
    }

    private fun loadCurrentUserGreeting() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        UserRepository.getUser(uid) { profile, _ ->
            if (_binding == null) return@getUser
            val name = profile?.displayName?.takeIf { it.isNotBlank() }
                ?: profile?.email?.takeIf { it.isNotBlank() }
                ?: getString(R.string.join_request_requester_unknown)
            currentUserRole = profile?.role.orEmpty()
            binding.dashboardHomeHelloUser.text = getString(R.string.home_hello_user, name)
            binding.dashboardHomeHelloUser.visibility = View.VISIBLE
            binding.dashboardHomeBtnLeaveCarpool.visibility =
                if (currentUserRole == Constants.UserRole.PARENT || currentUserRole == Constants.UserRole.CHILD) View.VISIBLE else View.GONE
        }
    }

    private fun requestParentLeaveCarpool(groupId: String, parentUid: String) {
        binding.dashboardHomeBtnLeaveCarpool.isEnabled = false
        val now = System.currentTimeMillis()

        DriveRequestRepository.getAcceptedDriveRequestsForGroup(groupId, parentUid) { requests, err ->
            if (_binding == null) return@getAcceptedDriveRequestsForGroup
            if (err != null) {
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
                return@getAcceptedDriveRequestsForGroup
            }
            if (requests.isEmpty()) {
                showLeaveDialog(groupId, parentUid, emptyList())
                return@getAcceptedDriveRequestsForGroup
            }
            val practiceIds = requests.map { it.practiceId }.distinct()
            PracticeRepository.getPracticesByIds(practiceIds) { practicesById ->
                if (_binding == null) return@getPracticesByIds
                val futureRequests = requests.filter { req ->
                    if (req.status != DriveRequest.STATUS_APPROVED) return@filter false
                    val practice = practicesById[req.practiceId] ?: return@filter false
                    practiceStartMillis(practice) > now
                }
                showLeaveDialog(groupId, parentUid, futureRequests)
            }
        }
    }

    private fun requestChildLeaveCarpool(groupId: String, childUid: String) {
        binding.dashboardHomeBtnLeaveCarpool.isEnabled = false
        val now = System.currentTimeMillis()

        DriveRequestRepository.getDriveRequestsForGroupAndRequester(groupId, childUid) { requests, err ->
            if (_binding == null) return@getDriveRequestsForGroupAndRequester
            if (err != null) {
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
                return@getDriveRequestsForGroupAndRequester
            }

            val activeRequests = requests.filter {
                it.status == DriveRequest.STATUS_PENDING || it.status == DriveRequest.STATUS_APPROVED
            }
            if (activeRequests.isEmpty()) {
                showChildLeaveDialog(groupId, childUid, emptyList())
                return@getDriveRequestsForGroupAndRequester
            }

            val practiceIds = activeRequests.map { it.practiceId }.distinct()
            PracticeRepository.getPracticesByIds(practiceIds) { practicesById ->
                if (_binding == null) return@getPracticesByIds
                val futureRequests = activeRequests.filter { req ->
                    val practice = practicesById[req.practiceId] ?: return@filter false
                    practiceStartMillis(practice) > now
                }
                showChildLeaveDialog(groupId, childUid, futureRequests)
            }
        }
    }

    private fun showChildLeaveDialog(
        groupId: String,
        childUid: String,
        futureRequests: List<DriveRequest>
    ) {
        val msg = if (futureRequests.isEmpty()) {
            getString(R.string.leave_carpool_child_no_future_rides)
        } else {
            getString(R.string.leave_carpool_child_with_future_rides, futureRequests.size)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.leave_carpool_warning_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.leave_carpool)) { _, _ ->
                applyChildLeave(groupId, childUid, futureRequests)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (_binding != null) binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
            }
            .show()
    }

    private fun applyChildLeave(
        groupId: String,
        childUid: String,
        futureRequests: List<DriveRequest>
    ) {
        if (futureRequests.isEmpty()) {
            GroupRepository.leaveGroup(groupId, childUid) { ok, err ->
                if (_binding == null) return@leaveGroup
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                if (!ok) {
                    Snackbar.make(binding.root, err ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                    return@leaveGroup
                }
                navigateAfterLeave()
            }
            return
        }

        val done = AtomicInteger(0)
        val allOk = AtomicBoolean(true)
        val total = futureRequests.size

        futureRequests.forEach { request ->
            val decline = {
                DriveRequestRepository.declineDriveRequest(request, childUid) { okDecline, _ ->
                    if (_binding == null) return@declineDriveRequest
                    if (!okDecline) allOk.set(false)
                    val finished = done.incrementAndGet() >= total
                    if (!finished) return@declineDriveRequest

                    binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                    if (!allOk.get()) {
                        Snackbar.make(binding.root, getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                        return@declineDriveRequest
                    }
                    GroupRepository.leaveGroup(groupId, childUid) { okLeave, errLeave ->
                        if (_binding == null) return@leaveGroup
                        if (!okLeave) {
                            Snackbar.make(binding.root, errLeave ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                            return@leaveGroup
                        }
                        navigateAfterLeave()
                    }
                }
            }

            if (request.status == DriveRequest.STATUS_APPROVED) {
                val driverToUid = if (request.direction == DriveRequest.DIRECTION_TO) "" else null
                val driverFromUid = if (request.direction == DriveRequest.DIRECTION_FROM) "" else null
                PracticeRepository.updatePractice(
                    request.practiceId,
                    startTime = null,
                    endTime = null,
                    location = null,
                    driverToUid = driverToUid,
                    driverFromUid = driverFromUid
                ) { okPractice, _ ->
                    if (_binding == null) return@updatePractice
                    if (!okPractice) allOk.set(false)
                    decline()
                }
            } else {
                decline()
            }
        }
    }

    private fun showLeaveDialog(
        groupId: String,
        parentUid: String,
        futureApprovedRequests: List<DriveRequest>
    ) {
        val msg = if (futureApprovedRequests.isEmpty()) {
            getString(R.string.leave_carpool_no_future_drives)
        } else {
            getString(R.string.leave_carpool_with_future_drives, futureApprovedRequests.size)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.leave_carpool_warning_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.leave_carpool)) { _, _ ->
                applyParentLeave(groupId, parentUid, futureApprovedRequests)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (_binding != null) {
                    binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                }
            }
            .show()
    }

    private fun applyParentLeave(
        groupId: String,
        parentUid: String,
        futureApprovedRequests: List<DriveRequest>
    ) {
        if (futureApprovedRequests.isEmpty()) {
            GroupRepository.leaveGroup(groupId, parentUid) { ok, err ->
                if (_binding == null) return@leaveGroup
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                if (!ok) {
                    Snackbar.make(binding.root, err ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                    return@leaveGroup
                }
                navigateAfterLeave()
            }
            return
        }

        val done = AtomicInteger(0)
        val allOk = AtomicBoolean(true)
        val total = futureApprovedRequests.size

        futureApprovedRequests.forEach { request ->
            val driverToUid = if (request.direction == DriveRequest.DIRECTION_TO) "" else null
            val driverFromUid = if (request.direction == DriveRequest.DIRECTION_FROM) "" else null

            PracticeRepository.updatePractice(
                request.practiceId,
                startTime = null,
                endTime = null,
                location = null,
                driverToUid = driverToUid,
                driverFromUid = driverFromUid
            ) { okPractice, _ ->
                if (_binding == null) return@updatePractice
                DriveRequestRepository.declineDriveRequest(request, parentUid) { okDecline, _ ->
                    if (!okPractice || !okDecline) allOk.set(false)
                    val finished = done.incrementAndGet() >= total
                    if (!finished) return@declineDriveRequest
                    binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                    if (!allOk.get()) {
                        Snackbar.make(binding.root, getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                        return@declineDriveRequest
                    }
                    GroupRepository.leaveGroup(groupId, parentUid) { okLeave, errLeave ->
                        if (_binding == null) return@leaveGroup
                        if (!okLeave) {
                            Snackbar.make(binding.root, errLeave ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                            return@leaveGroup
                        }
                        navigateAfterLeave()
                    }
                }
            }
        }
    }

    private fun navigateAfterLeave() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank()) return
        GroupRepository.getMyGroups(uid) { groups, err ->
            if (_binding == null) return@getMyGroups
            if (err != null) {
                Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
                return@getMyGroups
            }
            val nextGroupId = groups.firstOrNull()?.id.orEmpty()
            activeGroupId = nextGroupId
            (activity as? DashboardHomeListener)?.onLeaveCarpoolCompleted(nextGroupId)
        }
    }

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

    // Load today's training; show card or "Rest day" message.
    private fun loadTodayTraining(groupId: String) {
        trainingRepository.getTodayTraining(groupId) { model, _ ->
            if (model != null) {
                showTodayCard(model)
            } else {
                binding.dashboardHomeTodayCard.visibility = View.GONE
                binding.dashboardHomeNoTrainingMessage.visibility = View.VISIBLE
            }
        }
    }

    // Show today's training card and hide rest-day message.
    private fun showTodayCard(model: TodayTrainingUiModel) {
        if (_binding == null) return
        binding.dashboardHomeNoTrainingMessage.visibility = View.GONE
        binding.dashboardHomeTodayCard.visibility = View.VISIBLE
        binding.dashboardHomeTodayTeamName.text = model.teamName
        binding.dashboardHomeTodayBadge.text = model.trainingTypeLabel
        binding.dashboardHomeTodayDate.text = model.dateText
        binding.dashboardHomeTodayTime.text = model.timeRange
        binding.dashboardHomeTodayLocation.text = model.locationName
        binding.dashboardHomeTodayLocation.visibility = if (model.locationName.isBlank()) View.GONE else View.VISIBLE
        if (!model.locationAddress.isNullOrBlank()) {
            binding.dashboardHomeTodayAddress.text = model.locationAddress
            binding.dashboardHomeTodayAddress.visibility = View.VISIBLE
        } else {
            binding.dashboardHomeTodayAddress.visibility = View.GONE
        }
        binding.dashboardHomeTodayToLabel.text = getString(R.string.home_to_training)
        binding.dashboardHomeTodayFromLabel.text = getString(R.string.home_from_training)
        val hasTo = !model.toTrainingDriverName.isNullOrBlank()
        val hasBack = !model.fromTrainingDriverName.isNullOrBlank()
        binding.dashboardHomeTodayDriverToRow.visibility = if (hasTo) View.VISIBLE else View.GONE
        binding.dashboardHomeTodayDriverBackRow.visibility = if (hasBack) View.VISIBLE else View.GONE
        if (hasTo) binding.dashboardHomeTodayDriverTo.text = model.toTrainingDriverName
        if (hasBack) binding.dashboardHomeTodayDriverBack.text = model.fromTrainingDriverName
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"

        fun newInstance(groupId: String): DashboardHomeFragment {
            return DashboardHomeFragment().apply {
                arguments = Bundle().apply { putString(ARG_GROUP_ID, groupId) }
            }
        }
    }
}

/**
 * Callback for the dashboard Home fragment to ask the Activity to open Teams List or Create/Join.
 */
interface DashboardHomeListener {
    fun openTeamsList()
    fun openCreateJoin()
    fun onLeaveCarpoolCompleted(nextGroupId: String)
}
