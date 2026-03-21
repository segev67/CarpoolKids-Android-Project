package dev.segev.carpoolkids

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import dev.segev.carpoolkids.data.LeaveCarpoolPolicy
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
        } else {
            // No group selected: still show "Hello <name>" (loadGroupAndToday is not called).
            loadCurrentUserGreeting()
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

        if (groupId.isBlank()) {
            loadCurrentUserGreeting()
            return
        }

        GroupRepository.getGroupById(groupId) { group, _ ->
            if (_binding == null) return@getGroupById
            if (group != null) {
                binding.dashboardHomeGroupName.text = getString(R.string.carpool_header_format, group.name)
                binding.dashboardHomeGroupName.visibility = View.VISIBLE
                loadCurrentUserGreeting()
                loadTodayTraining(groupId)
            } else {
                // Group id invalid or deleted: still show greeting.
                loadCurrentUserGreeting()
            }
        }
    }

    private fun loadCurrentUserGreeting() {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
        fun applyGreeting(displayName: String, role: String) {
            if (_binding == null) return
            currentUserRole = role
            binding.dashboardHomeHelloUser.text = getString(R.string.home_hello_user, displayName)
            binding.dashboardHomeHelloUser.visibility = View.VISIBLE
            // Leave applies only when this dashboard has a selected group (not whether user is "in a carpool" elsewhere).
            val hasSelectedGroup = activeGroupId.isNotBlank()
            binding.dashboardHomeBtnLeaveCarpool.visibility =
                if (hasSelectedGroup && (currentUserRole == Constants.UserRole.PARENT || currentUserRole == Constants.UserRole.CHILD)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
        fun fallbackNameFromAuth(): String =
            auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: auth.currentUser?.email?.takeIf { it.isNotBlank() }
                ?: getString(R.string.join_request_requester_unknown)

        if (uid.isNullOrBlank()) {
            applyGreeting(fallbackNameFromAuth(), currentUserRole)
            return
        }
        UserRepository.getUser(uid) { profile, _ ->
            if (_binding == null) return@getUser
            val name = profile?.displayName?.takeIf { it.isNotBlank() }
                ?: profile?.email?.takeIf { it.isNotBlank() }
                ?: fallbackNameFromAuth()
            applyGreeting(name, profile?.role.orEmpty())
        }
    }

    private fun requestParentLeaveCarpool(groupId: String, parentUid: String) {
        binding.dashboardHomeBtnLeaveCarpool.isEnabled = false
        GroupRepository.getGroupById(groupId) { group, err ->
            if (_binding == null) return@getGroupById
            if (group == null || err != null) {
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                Snackbar.make(
                    binding.root,
                    err ?: getString(R.string.dashboard_load_error),
                    Snackbar.LENGTH_LONG
                ).show()
                return@getGroupById
            }
            UserRepository.getUsersByIds(group.memberIds) { profiles ->
                if (_binding == null) return@getUsersByIds
                when (val outcome = LeaveCarpoolPolicy.evaluateParentLeave(group, profiles, parentUid)) {
                    LeaveCarpoolPolicy.ParentLeaveOutcome.BlockedLastParentWithChildren -> {
                        binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                        showLastParentBlockedDialog(group.inviteCode)
                    }
                    LeaveCarpoolPolicy.ParentLeaveOutcome.AllowedDissolveInactive -> {
                        loadParentFutureDrivesAndShowLeaveDialog(groupId, parentUid, deleteEntireCarpool = true)
                    }
                    LeaveCarpoolPolicy.ParentLeaveOutcome.AllowedLeaveNormal -> {
                        loadParentFutureDrivesAndShowLeaveDialog(groupId, parentUid, deleteEntireCarpool = false)
                    }
                }
            }
        }
    }

    private fun showLastParentBlockedDialog(inviteCode: String) {
        val msg = getString(R.string.leave_carpool_last_parent_blocked_message, inviteCode)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.leave_carpool_last_parent_blocked_title)
            .setMessage(msg)
            .setNeutralButton(R.string.leave_carpool_copy_invite_code) { _, _ ->
                val cm =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("invite", inviteCode))
                Snackbar.make(
                    binding.root,
                    R.string.leave_carpool_invite_code_copied,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** After eligibility passes: load future approved drives + practices where parent is driver, then confirm. */
    private fun loadParentFutureDrivesAndShowLeaveDialog(
        groupId: String,
        parentUid: String,
        deleteEntireCarpool: Boolean
    ) {
        val now = System.currentTimeMillis()
        DriveRequestRepository.getAcceptedDriveRequestsForGroup(groupId, parentUid) { requests, err ->
            if (_binding == null) return@getAcceptedDriveRequestsForGroup
            if (err != null) {
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
                return@getAcceptedDriveRequestsForGroup
            }
            PracticeRepository.getPracticesWhereUserIsDriver(groupId, parentUid) { driverPractices, err2 ->
                if (_binding == null) return@getPracticesWhereUserIsDriver
                if (err2 != null) {
                    binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                    Snackbar.make(binding.root, err2, Snackbar.LENGTH_LONG).show()
                    return@getPracticesWhereUserIsDriver
                }
                val practiceIds =
                    (requests.map { it.practiceId } + driverPractices.map { it.id }).distinct()
                if (practiceIds.isEmpty()) {
                    showLeaveDialog(
                        groupId,
                        parentUid,
                        emptyList(),
                        emptyMap(),
                        upcomingDriveCount = 0,
                        deleteEntireCarpool
                    )
                    return@getPracticesWhereUserIsDriver
                }
                PracticeRepository.getPracticesByIds(practiceIds) { practicesById ->
                    if (_binding == null) return@getPracticesByIds
                    val futureRequests = requests.filter { req ->
                        if (req.status != DriveRequest.STATUS_APPROVED) return@filter false
                        val practice = practicesById[req.practiceId] ?: return@filter false
                        practiceStartMillis(practice) > now
                    }
                    val futureDriverPractices =
                        driverPractices.filter { p -> practiceStartMillis(p) > now }
                    val clearMap =
                        mergeParentPracticeClears(futureRequests, futureDriverPractices, parentUid)
                    val upcomingCount = computeParentUpcomingDriveCount(futureRequests, clearMap)
                    showLeaveDialog(
                        groupId,
                        parentUid,
                        futureRequests,
                        clearMap,
                        upcomingCount,
                        deleteEntireCarpool
                    )
                }
            }
        }
    }

    /** Which TO/FROM slots to clear per practice (from approved requests + schedule rows). */
    private data class PracticeDriverClear(val clearTo: Boolean, val clearFrom: Boolean)

    private fun mergeParentPracticeClears(
        futureRequests: List<DriveRequest>,
        futureDriverPractices: List<Practice>,
        parentUid: String
    ): Map<String, PracticeDriverClear> {
        val map = mutableMapOf<String, PracticeDriverClear>()
        for (r in futureRequests) {
            val cur = map[r.practiceId] ?: PracticeDriverClear(clearTo = false, clearFrom = false)
            map[r.practiceId] = PracticeDriverClear(
                clearTo = cur.clearTo || r.direction == DriveRequest.DIRECTION_TO,
                clearFrom = cur.clearFrom || r.direction == DriveRequest.DIRECTION_FROM
            )
        }
        for (p in futureDriverPractices) {
            val cur = map[p.id] ?: PracticeDriverClear(clearTo = false, clearFrom = false)
            map[p.id] = PracticeDriverClear(
                clearTo = cur.clearTo || p.driverToUid == parentUid,
                clearFrom = cur.clearFrom || p.driverFromUid == parentUid
            )
        }
        return map.filterValues { it.clearTo || it.clearFrom }
    }

    private fun computeParentUpcomingDriveCount(
        futureRequests: List<DriveRequest>,
        clearMap: Map<String, PracticeDriverClear>
    ): Int {
        if (futureRequests.isNotEmpty()) return futureRequests.size
        return clearMap.values.sumOf { c ->
            (if (c.clearTo) 1 else 0) + (if (c.clearFrom) 1 else 0)
        }
    }

    private fun requestChildLeaveCarpool(groupId: String, childUid: String) {
        binding.dashboardHomeBtnLeaveCarpool.isEnabled = false
        GroupRepository.getGroupById(groupId) { group, gErr ->
            if (_binding == null) return@getGroupById
            if (group == null || gErr != null) {
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                Snackbar.make(
                    binding.root,
                    gErr ?: getString(R.string.dashboard_load_error),
                    Snackbar.LENGTH_LONG
                ).show()
                return@getGroupById
            }
            val deleteEntireCarpool =
                group.memberIds.size == 1 && group.memberIds[0] == childUid
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
                    showChildLeaveDialog(groupId, childUid, emptyList(), deleteEntireCarpool)
                    return@getDriveRequestsForGroupAndRequester
                }

                val practiceIds = activeRequests.map { it.practiceId }.distinct()
                PracticeRepository.getPracticesByIds(practiceIds) { practicesById ->
                    if (_binding == null) return@getPracticesByIds
                    val futureRequests = activeRequests.filter { req ->
                        val practice = practicesById[req.practiceId] ?: return@filter false
                        practiceStartMillis(practice) > now
                    }
                    showChildLeaveDialog(groupId, childUid, futureRequests, deleteEntireCarpool)
                }
            }
        }
    }

    private fun showChildLeaveDialog(
        groupId: String,
        childUid: String,
        futureRequests: List<DriveRequest>,
        deleteEntireCarpool: Boolean
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
                applyChildLeave(groupId, childUid, futureRequests, deleteEntireCarpool)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (_binding != null) binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
            }
            .show()
    }

    private fun applyChildLeave(
        groupId: String,
        childUid: String,
        futureRequests: List<DriveRequest>,
        deleteEntireCarpool: Boolean
    ) {
        if (futureRequests.isEmpty()) {
            completeChildLeaveGroup(groupId, childUid, deleteEntireCarpool) childLeave@{ ok, err ->
                if (_binding == null) return@childLeave
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                if (!ok) {
                    Snackbar.make(binding.root, err ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                    return@childLeave
                }
                if (deleteEntireCarpool) {
                    Snackbar.make(binding.root, R.string.leave_carpool_dissolved_success, Snackbar.LENGTH_LONG).show()
                }
                navigateAfterLeave()
            }
            return
        }

        val approved = futureRequests.filter { it.status == DriveRequest.STATUS_APPROVED }

        fun declineAllChildDriveRequests() {
            val done = AtomicInteger(0)
            val allOk = AtomicBoolean(true)
            val total = futureRequests.size
            futureRequests.forEach { request ->
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
                    completeChildLeaveGroup(groupId, childUid, deleteEntireCarpool) childLeave@{ okLeave, errLeave ->
                        if (_binding == null) return@childLeave
                        if (!okLeave) {
                            Snackbar.make(binding.root, errLeave ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                            return@childLeave
                        }
                        if (deleteEntireCarpool) {
                            Snackbar.make(binding.root, R.string.leave_carpool_dissolved_success, Snackbar.LENGTH_LONG).show()
                        }
                        navigateAfterLeave()
                    }
                }
            }
        }

        if (approved.isEmpty()) {
            declineAllChildDriveRequests()
            return
        }

        val byPractice = approved.groupBy { it.practiceId }
        val practiceEntries = byPractice.entries.toList()

        fun applyNextChildPracticeClear(index: Int) {
            if (index >= practiceEntries.size) {
                declineAllChildDriveRequests()
                return
            }
            val (practiceId, reqs) = practiceEntries[index]
            var clearTo = false
            var clearFrom = false
            for (r in reqs) {
                if (r.direction == DriveRequest.DIRECTION_TO) clearTo = true
                if (r.direction == DriveRequest.DIRECTION_FROM) clearFrom = true
            }
            PracticeRepository.updatePractice(
                practiceId,
                startTime = null,
                endTime = null,
                location = null,
                driverToUid = if (clearTo) "" else null,
                driverFromUid = if (clearFrom) "" else null
            ) { _, _ ->
                if (_binding == null) return@updatePractice
                applyNextChildPracticeClear(index + 1)
            }
        }
        applyNextChildPracticeClear(0)
    }

    private fun showLeaveDialog(
        groupId: String,
        parentUid: String,
        futureApprovedRequests: List<DriveRequest>,
        practiceClearById: Map<String, PracticeDriverClear>,
        upcomingDriveCount: Int,
        deleteEntireCarpool: Boolean
    ) {
        val msg = if (upcomingDriveCount == 0) {
            getString(R.string.leave_carpool_no_future_drives)
        } else {
            getString(R.string.leave_carpool_with_future_drives, upcomingDriveCount)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.leave_carpool_warning_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.leave_carpool)) { _, _ ->
                applyParentLeave(
                    groupId,
                    parentUid,
                    futureApprovedRequests,
                    practiceClearById,
                    deleteEntireCarpool
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (_binding != null) {
                    binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                }
            }
            .show()
    }

    private fun completeChildLeaveGroup(
        groupId: String,
        uid: String,
        deleteEntireCarpool: Boolean,
        onDone: (Boolean, String?) -> Unit
    ) {
        if (deleteEntireCarpool) {
            GroupRepository.deleteCarpoolAndRelatedData(groupId, uid, onDone)
        } else {
            GroupRepository.leaveGroup(groupId, uid, onDone)
        }
    }

    private fun completeParentLeaveGroup(
        groupId: String,
        parentUid: String,
        deleteEntireCarpool: Boolean,
        onDone: (Boolean, String?) -> Unit
    ) {
        if (deleteEntireCarpool) {
            GroupRepository.deleteCarpoolAndRelatedData(groupId, parentUid, onDone)
        } else {
            GroupRepository.leaveGroup(groupId, parentUid, onDone)
        }
    }

    private fun applyParentLeave(
        groupId: String,
        parentUid: String,
        futureApprovedRequests: List<DriveRequest>,
        practiceClearById: Map<String, PracticeDriverClear>,
        deleteEntireCarpool: Boolean
    ) {
        if (practiceClearById.isEmpty()) {
            completeParentLeaveGroup(groupId, parentUid, deleteEntireCarpool) leaveDone@{ ok, err ->
                if (_binding == null) return@leaveDone
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                if (!ok) {
                    Snackbar.make(binding.root, err ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                    return@leaveDone
                }
                if (deleteEntireCarpool) {
                    Snackbar.make(binding.root, R.string.leave_carpool_dissolved_success, Snackbar.LENGTH_LONG).show()
                }
                navigateAfterLeave()
            }
            return
        }

        val practiceEntries = practiceClearById.entries.toList()

        fun finishParentLeaveAfterDeclines() {
            completeParentLeaveGroup(groupId, parentUid, deleteEntireCarpool) leaveDone@{ okLeave, errLeave ->
                if (_binding == null) return@leaveDone
                binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                if (!okLeave) {
                    Snackbar.make(binding.root, errLeave ?: getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                    return@leaveDone
                }
                if (deleteEntireCarpool) {
                    Snackbar.make(binding.root, R.string.leave_carpool_dissolved_success, Snackbar.LENGTH_LONG).show()
                }
                navigateAfterLeave()
            }
        }

        fun declineAllParentDriveRequests() {
            val total = futureApprovedRequests.size
            if (total == 0) {
                finishParentLeaveAfterDeclines()
                return
            }
            val done = AtomicInteger(0)
            val allOk = AtomicBoolean(true)
            futureApprovedRequests.forEach { request ->
                DriveRequestRepository.declineDriveRequest(request, parentUid) { okDecline, _ ->
                    if (_binding == null) return@declineDriveRequest
                    if (!okDecline) allOk.set(false)
                    val finished = done.incrementAndGet() >= total
                    if (!finished) return@declineDriveRequest
                    if (!allOk.get()) {
                        binding.dashboardHomeBtnLeaveCarpool.isEnabled = true
                        Snackbar.make(binding.root, getString(R.string.leave_carpool_cancel_error), Snackbar.LENGTH_LONG).show()
                        return@declineDriveRequest
                    }
                    finishParentLeaveAfterDeclines()
                }
            }
        }

        fun applyNextPracticeClear(index: Int) {
            if (index >= practiceEntries.size) {
                declineAllParentDriveRequests()
                return
            }
            val (practiceId, flags) = practiceEntries[index]
            PracticeRepository.updatePractice(
                practiceId,
                startTime = null,
                endTime = null,
                location = null,
                driverToUid = if (flags.clearTo) "" else null,
                driverFromUid = if (flags.clearFrom) "" else null
            ) { _, _ ->
                if (_binding == null) return@updatePractice
                applyNextPracticeClear(index + 1)
            }
        }
        applyNextPracticeClear(0)
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
