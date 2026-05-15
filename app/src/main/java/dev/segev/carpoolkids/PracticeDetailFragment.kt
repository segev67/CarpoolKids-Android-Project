package dev.segev.carpoolkids

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.DriveRequestRepository
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentPracticeDetailBinding
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.utilities.Constants
import java.util.Calendar
import java.util.UUID

/**
 * Practice detail screen: view and edit start/end time, location, driver TO, driver FROM.
 * Day and date are read-only.
 */
class PracticeDetailFragment : Fragment() {

    private var _binding: FragmentPracticeDetailBinding? = null
    private val binding get() = _binding!!

    private var practice: Practice? = null
    private var isCancelingPractice = false
    /** True when the viewing CHILD has a PENDING drive_request for this practice — hides Join. */
    private var hasPendingDriveRequest = false
    /** Guard against double-tap join/leave while a request is in flight. */
    private var isJoinInFlight = false
    private lateinit var mapPickerLauncher: ActivityResultLauncher<Intent>
    /** Separate launcher for the post-join "set your home address" tip so its result writes to the user profile, not to the practice. */
    private lateinit var homeMapPickerLauncher: ActivityResultLauncher<Intent>

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

        mapPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (_binding == null) return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val coords = MapPickerActivity.extractResult(result.data) ?: return@registerForActivityResult
            savePracticeCoords(coords.first, coords.second)
        }

        // Phase 3 — "Set" action on the post-join Snackbar launches the home picker here, NOT the
        // practice-location picker above. Separate launcher avoids writing home coords into the practice.
        homeMapPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (_binding == null) return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val coords = MapPickerActivity.extractResult(result.data) ?: return@registerForActivityResult
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@registerForActivityResult
            UserRepository.updateHomeAddress(uid, coords.first, coords.second) { ok, err ->
                if (_binding == null) return@updateHomeAddress
                if (ok) {
                    Snackbar.make(binding.root, R.string.profile_home_saved, Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(
                        binding.root,
                        err ?: getString(R.string.profile_home_save_error),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.practiceDetailSave.setOnClickListener { save(groupId) }
        binding.practiceDetailCancelPractice.setOnClickListener { confirmCancelPractice() }
        binding.practiceDetailSetLocationOnMap.setOnClickListener { openLocationPicker() }
        binding.practiceDetailJoinButton.setOnClickListener { toggleJoinPractice() }
        binding.practiceDetailRequestTo.setOnClickListener { requestDrive(groupId, DriveRequest.DIRECTION_TO) }
        binding.practiceDetailRequestFrom.setOnClickListener { requestDrive(groupId, DriveRequest.DIRECTION_FROM) }
        binding.practiceDetailIllTakeTo.setOnClickListener { selfDeclare(groupId, DriveRequest.DIRECTION_TO) }
        binding.practiceDetailIllTakeFrom.setOnClickListener { selfDeclare(groupId, DriveRequest.DIRECTION_FROM) }
        binding.practiceDetailCancelTo.setOnClickListener { cancelDrive(groupId, DriveRequest.DIRECTION_TO) }
        binding.practiceDetailCancelFrom.setOnClickListener { cancelDrive(groupId, DriveRequest.DIRECTION_FROM) }

        PracticeRepository.getPracticeById(practiceId) { p, error ->
            if (_binding == null) return@getPracticeById
            if (p == null || error != null) {
                Snackbar.make(binding.root, getString(R.string.practice_load_error), Snackbar.LENGTH_SHORT).show()
                requireActivity().onBackPressedDispatcher.onBackPressed()
                return@getPracticeById
            }
            practice = p
            bindPractice(p)
            loadDriverNames(p)
            loadMyDriveRequestsIfChild(groupId, practiceId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindPractice(p: Practice) {
        val role = arguments?.getString(ARG_ROLE).orEmpty()
        val isParent = role == Constants.UserRole.PARENT
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        binding.practiceDetailCanceledBanner.visibility = if (p.canceled) View.VISIBLE else View.GONE

        val editable = !p.canceled
        binding.practiceDetailStartTime.isEnabled = editable
        binding.practiceDetailEndTime.isEnabled = editable
        binding.practiceDetailLocation.isEnabled = editable
        binding.practiceDetailSave.isEnabled = editable
        binding.practiceDetailSave.alpha = if (editable) 1f else 0.5f

        binding.practiceDetailCancelPractice.visibility =
            if (isParent && !p.canceled) View.VISIBLE else View.GONE
        binding.practiceDetailCancelPractice.isEnabled = !isCancelingPractice

        // Parents (only) can attach geo-coordinates to a practice via the map picker.
        // Button text reflects whether coordinates already exist on this practice.
        val canEditCoords = isParent && !p.canceled
        binding.practiceDetailSetLocationOnMap.visibility =
            if (canEditCoords) View.VISIBLE else View.GONE
        binding.practiceDetailSetLocationOnMap.setText(
            if (p.locationLat != null && p.locationLng != null)
                R.string.practice_detail_change_location_on_map
            else
                R.string.practice_detail_set_location_on_map
        )

        // Phase 3 — children manage their own participation in the carpool roster.
        val isChild = role == Constants.UserRole.CHILD
        val isParticipant = currentUid.isNotBlank() && currentUid in p.participantUids
        val canJoinLeave = isChild && !p.canceled && !hasPendingDriveRequest
        binding.practiceDetailJoinButton.visibility = if (canJoinLeave) View.VISIBLE else View.GONE
        binding.practiceDetailJoinButton.isEnabled = !isJoinInFlight
        if (canJoinLeave) {
            binding.practiceDetailJoinButton.setText(
                if (isParticipant) R.string.practice_leave_button else R.string.practice_join_button
            )
        }
        binding.practiceDetailJoinPendingHint.visibility =
            if (isChild && !p.canceled && hasPendingDriveRequest) View.VISIBLE else View.GONE
        updateRidersList(p)

        binding.practiceDetailDay.text = formatDayOfWeek(p.dateMillis)
        binding.practiceDetailDate.text = formatDate(p.dateMillis)
        binding.practiceDetailStartTime.setText(p.startTime)
        binding.practiceDetailEndTime.setText(p.endTime)
        binding.practiceDetailLocation.setText(p.location)
        val noDriver = getString(R.string.schedule_no_driver)
        binding.practiceDetailDriverToValue.text = noDriver
        binding.practiceDetailDriverFromValue.text = noDriver

        if (p.canceled) {
            binding.practiceDetailRequestTo.visibility = View.GONE
            binding.practiceDetailRequestFrom.visibility = View.GONE
            binding.practiceDetailIllTakeTo.visibility = View.GONE
            binding.practiceDetailIllTakeFrom.visibility = View.GONE
            binding.practiceDetailCancelTo.visibility = View.GONE
            binding.practiceDetailCancelFrom.visibility = View.GONE
            return
        }

        binding.practiceDetailRequestTo.visibility = if (p.driverToUid.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.practiceDetailRequestFrom.visibility = if (p.driverFromUid.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.practiceDetailIllTakeTo.visibility = if (p.driverToUid.isNullOrBlank() && isParent) View.VISIBLE else View.GONE
        binding.practiceDetailIllTakeFrom.visibility = if (p.driverFromUid.isNullOrBlank() && isParent) View.VISIBLE else View.GONE
        binding.practiceDetailCancelTo.visibility = if (p.driverToUid == currentUid) View.VISIBLE else View.GONE
        binding.practiceDetailCancelFrom.visibility = if (p.driverFromUid == currentUid) View.VISIBLE else View.GONE
    }

    /**
     * Load the current child's own drive_requests so the Join button can be hidden when there's an
     * unresolved PENDING request (avoids double-bookkeeping with the existing request flow).
     */
    private fun loadMyDriveRequestsIfChild(groupId: String, practiceId: String) {
        val role = arguments?.getString(ARG_ROLE).orEmpty()
        if (role != Constants.UserRole.CHILD) return
        if (groupId.isBlank()) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank()) return
        DriveRequestRepository.getDriveRequestsForGroupAndRequester(groupId, uid) { reqs, _ ->
            if (_binding == null) return@getDriveRequestsForGroupAndRequester
            hasPendingDriveRequest = reqs.any {
                it.practiceId == practiceId && it.status == DriveRequest.STATUS_PENDING
            }
            practice?.let { bindPractice(it) }
        }
    }

    /** Decide whether the tap should join or leave, based on current participation. */
    private fun toggleJoinPractice() {
        val p = practice ?: return
        if (p.canceled || isJoinInFlight || hasPendingDriveRequest) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank()) return
        if (uid in p.participantUids) leavePracticeAction(uid) else joinPracticeAction(uid)
    }

    private fun joinPracticeAction(uid: String) {
        val p = practice ?: return
        isJoinInFlight = true
        binding.practiceDetailJoinButton.isEnabled = false
        PracticeRepository.joinPractice(p.id, uid) { ok, err ->
            if (_binding == null) return@joinPractice
            isJoinInFlight = false
            if (!ok) {
                binding.practiceDetailJoinButton.isEnabled = true
                Snackbar.make(
                    binding.root,
                    err ?: getString(R.string.practice_join_failed),
                    Snackbar.LENGTH_LONG
                ).show()
                return@joinPractice
            }
            practice = p.copy(participantUids = (p.participantUids + uid).distinct())
            practice?.let { bindPractice(it) }
            maybeShowSetHomeTip(uid)
        }
    }

    private fun leavePracticeAction(uid: String) {
        val p = practice ?: return
        isJoinInFlight = true
        binding.practiceDetailJoinButton.isEnabled = false
        PracticeRepository.leavePractice(p.id, uid) { ok, err ->
            if (_binding == null) return@leavePractice
            isJoinInFlight = false
            if (!ok) {
                binding.practiceDetailJoinButton.isEnabled = true
                Snackbar.make(
                    binding.root,
                    err ?: getString(R.string.practice_leave_failed),
                    Snackbar.LENGTH_LONG
                ).show()
                return@leavePractice
            }
            practice = p.copy(participantUids = p.participantUids - uid)
            practice?.let { bindPractice(it) }
        }
    }

    /**
     * After a successful join, nudge the child to set their home address if they haven't yet.
     * Non-blocking; the dashboard banner remains as the long-term reminder.
     */
    private fun maybeShowSetHomeTip(uid: String) {
        UserRepository.getUser(uid) { profile, _ ->
            if (_binding == null) return@getUser
            if (profile?.homeLat == null) {
                Snackbar.make(
                    binding.root,
                    R.string.practice_join_set_home_tip,
                    8_000
                ).setAction(R.string.practice_join_set_home_action) {
                    homeMapPickerLauncher.launch(
                        MapPickerActivity.intentForHome(requireContext(), null, null)
                    )
                }.show()
            }
        }
    }

    /** Render "Riders (N): name, name, name" subtext from the practice's participantUids. */
    private fun updateRidersList(p: Practice) {
        if (p.participantUids.isEmpty()) {
            binding.practiceDetailRidersLabel.visibility = View.GONE
            binding.practiceDetailRidersValue.visibility = View.GONE
            return
        }
        binding.practiceDetailRidersLabel.text =
            getString(R.string.practice_riders_label, p.participantUids.size)
        binding.practiceDetailRidersLabel.visibility = View.VISIBLE
        binding.practiceDetailRidersValue.visibility = View.VISIBLE
        binding.practiceDetailRidersValue.text = ""
        UserRepository.getUsersByIds(p.participantUids) { profileMap ->
            if (_binding == null) return@getUsersByIds
            val names = p.participantUids.mapNotNull { uid ->
                profileMap[uid]?.displayName?.takeIf { it.isNotBlank() }
                    ?: profileMap[uid]?.email?.takeIf { it.isNotBlank() }
            }
            binding.practiceDetailRidersValue.text =
                if (names.isNotEmpty()) names.joinToString(", ")
                else getString(R.string.join_request_requester_unknown)
        }
    }

    private fun openLocationPicker() {
        val p = practice ?: return
        if (p.canceled) return
        mapPickerLauncher.launch(
            MapPickerActivity.intentForPracticeLocation(
                requireContext(),
                currentLat = p.locationLat,
                currentLng = p.locationLng
            )
        )
    }

    private fun savePracticeCoords(lat: Double, lng: Double) {
        val p = practice ?: return
        binding.practiceDetailSetLocationOnMap.isEnabled = false
        PracticeRepository.updateLocationCoords(p.id, lat, lng) { ok, err ->
            if (_binding == null) return@updateLocationCoords
            binding.practiceDetailSetLocationOnMap.isEnabled = true
            if (ok) {
                practice = p.copy(locationLat = lat, locationLng = lng)
                practice?.let { bindPractice(it) }
                Snackbar.make(
                    binding.root,
                    R.string.practice_detail_location_coords_saved,
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                Snackbar.make(
                    binding.root,
                    err ?: getString(R.string.practice_detail_location_coords_save_error),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmCancelPractice() {
        val p = practice ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (arguments?.getString(ARG_ROLE) != Constants.UserRole.PARENT) return
        if (p.canceled || isCancelingPractice) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.practice_cancel_dialog_title)
            .setMessage(R.string.practice_cancel_dialog_message)
            .setPositiveButton(R.string.practice_cancel_confirm_button) { _: DialogInterface, _: Int ->
                // p.canceled already false here (guard above); re-check mutable practice if state could change
                if (practice?.canceled == true || isCancelingPractice) return@setPositiveButton
                isCancelingPractice = true
                binding.practiceDetailCancelPractice.isEnabled = false
                UserRepository.getUser(uid) { profile, profileErr ->
                    if (_binding == null) return@getUser
                    if (profile?.role != Constants.UserRole.PARENT) {
                        isCancelingPractice = false
                        binding.practiceDetailCancelPractice.isEnabled = true
                        val msg = profileErr
                            ?: getString(R.string.practice_cancel_not_parent_profile)
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        return@getUser
                    }
                    PracticeRepository.cancelPractice(p.id, uid, null) { success, err ->
                        if (_binding == null) return@cancelPractice
                        isCancelingPractice = false
                        if (success) {
                            Snackbar.make(binding.root, R.string.practice_canceled_success, Snackbar.LENGTH_SHORT).show()
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        } else {
                            binding.practiceDetailCancelPractice.isEnabled = true
                            val msg = when {
                                err == "PERMISSION_DENIED" || err?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                                    getString(R.string.practice_cancel_permission_denied)
                                err == "Practice already canceled" -> getString(R.string.practice_already_canceled)
                                else -> err ?: getString(R.string.create_join_error)
                            }
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cancelDrive(groupId: String, direction: String) {
        val p = practice ?: return
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid.isNullOrBlank()) return
        val isDriver = when (direction) {
            DriveRequest.DIRECTION_TO -> p.driverToUid == currentUid
            DriveRequest.DIRECTION_FROM -> p.driverFromUid == currentUid
            else -> false
        }
        if (!isDriver) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.drive_request_cancel_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val driverToUid = if (direction == DriveRequest.DIRECTION_TO) "" else null
                val driverFromUid = if (direction == DriveRequest.DIRECTION_FROM) "" else null
                PracticeRepository.updatePractice(
                    p.id,
                    startTime = null,
                    endTime = null,
                    location = null,
                    driverToUid = driverToUid,
                    driverFromUid = driverFromUid
                ) { success, err ->
                    if (_binding == null) return@updatePractice
                    if (success) {
                        Snackbar.make(binding.root, getString(R.string.drive_request_slot_freed), Snackbar.LENGTH_SHORT).show()
                        binding.practiceDetailCancelTo.visibility = if (direction == DriveRequest.DIRECTION_TO) View.GONE else binding.practiceDetailCancelTo.visibility
                        binding.practiceDetailCancelFrom.visibility = if (direction == DriveRequest.DIRECTION_FROM) View.GONE else binding.practiceDetailCancelFrom.visibility
                        binding.practiceDetailRequestTo.visibility = if (direction == DriveRequest.DIRECTION_TO) View.VISIBLE else binding.practiceDetailRequestTo.visibility
                        binding.practiceDetailRequestFrom.visibility = if (direction == DriveRequest.DIRECTION_FROM) View.VISIBLE else binding.practiceDetailRequestFrom.visibility
                        binding.practiceDetailIllTakeTo.visibility = if (direction == DriveRequest.DIRECTION_TO && arguments?.getString(ARG_ROLE) == Constants.UserRole.PARENT) View.VISIBLE else binding.practiceDetailIllTakeTo.visibility
                        binding.practiceDetailIllTakeFrom.visibility = if (direction == DriveRequest.DIRECTION_FROM && arguments?.getString(ARG_ROLE) == Constants.UserRole.PARENT) View.VISIBLE else binding.practiceDetailIllTakeFrom.visibility
                    } else {
                        Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selfDeclare(groupId: String, direction: String) {
        val p = practice ?: return
        if (p.canceled) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        DriveRequestRepository.canSelfDeclare(groupId, p.id, direction) { canDo, errorMessage ->
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
                p.id,
                startTime = null,
                endTime = null,
                location = null,
                driverToUid = driverToUid,
                driverFromUid = driverFromUid
            ) { success, err ->
                if (_binding == null) return@updatePractice
                if (success) {
                    Snackbar.make(binding.root, getString(R.string.drive_request_self_declared), Snackbar.LENGTH_SHORT).show()
                    binding.practiceDetailRequestTo.visibility = if (direction == DriveRequest.DIRECTION_TO) View.GONE else binding.practiceDetailRequestTo.visibility
                    binding.practiceDetailRequestFrom.visibility = if (direction == DriveRequest.DIRECTION_FROM) View.GONE else binding.practiceDetailRequestFrom.visibility
                    binding.practiceDetailIllTakeTo.visibility = if (direction == DriveRequest.DIRECTION_TO) View.GONE else binding.practiceDetailIllTakeTo.visibility
                    binding.practiceDetailIllTakeFrom.visibility = if (direction == DriveRequest.DIRECTION_FROM) View.GONE else binding.practiceDetailIllTakeFrom.visibility
                } else {
                    Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestDrive(groupId: String, direction: String) {
        val p = practice ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        DriveRequestRepository.canCreateDriveRequest(groupId, p.id, direction) { canCreate, errorMessage ->
            if (_binding == null) return@canCreateDriveRequest
            if (!canCreate) {
                val msg = when (errorMessage) {
                    "Slot already taken" -> getString(R.string.drive_request_slot_taken)
                    "A request is already open for this" -> getString(R.string.drive_request_already_open)
                    "Practice was canceled" -> getString(R.string.practice_canceled_banner)
                    else -> errorMessage ?: getString(R.string.create_join_error)
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                return@canCreateDriveRequest
            }
            val request = DriveRequest(
                id = UUID.randomUUID().toString(),
                groupId = groupId,
                practiceId = p.id,
                practiceDateMillis = p.dateMillis,
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
                    binding.practiceDetailRequestTo.visibility = if (direction == DriveRequest.DIRECTION_TO) View.GONE else binding.practiceDetailRequestTo.visibility
                    binding.practiceDetailRequestFrom.visibility = if (direction == DriveRequest.DIRECTION_FROM) View.GONE else binding.practiceDetailRequestFrom.visibility
                } else {
                    Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadDriverNames(p: Practice) {
        val toUid = p.driverToUid?.takeIf { it.isNotBlank() }
        val fromUid = p.driverFromUid?.takeIf { it.isNotBlank() }
        if (toUid == null && fromUid == null) {
            return
        }
        val uids = listOfNotNull(toUid, fromUid).distinct()
        UserRepository.getUsersByIds(uids) { profileMap ->
            if (_binding == null) return@getUsersByIds
            val noDriver = getString(R.string.schedule_no_driver)
            val toName = toUid?.let { uid ->
                profileMap[uid]?.let { profile ->
                    profile.displayName?.takeIf { it.isNotBlank() }
                        ?: profile.email?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.join_request_requester_unknown)
                }
            } ?: noDriver
            val fromName = fromUid?.let { uid ->
                profileMap[uid]?.let { profile ->
                    profile.displayName?.takeIf { it.isNotBlank() }
                        ?: profile.email?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.join_request_requester_unknown)
                }
            } ?: noDriver
            binding.practiceDetailDriverToValue.text = toName
            binding.practiceDetailDriverFromValue.text = fromName
        }
    }

    private fun save(groupId: String) {
        val p = practice ?: return
        if (p.canceled) return
        val startTime = binding.practiceDetailStartTime.text?.toString()?.trim().orEmpty()
        val endTime = binding.practiceDetailEndTime.text?.toString()?.trim().orEmpty()
        val location = binding.practiceDetailLocation.text?.toString()?.trim().orEmpty()

        if (startTime.isBlank() || endTime.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.create_join_error), Snackbar.LENGTH_SHORT).show()
            return
        }
        PracticeRepository.updatePractice(
            p.id,
            startTime,
            endTime,
            location,
            null,
            null
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
