package dev.segev.carpoolkids

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentJoinRequestsBinding
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.ui.group.GroupJoinRequestRow
import dev.segev.carpoolkids.ui.group.GroupJoinRequestsAdapter
import dev.segev.carpoolkids.utilities.Constants

/**
 * Join requests for the current group. List with status; PARENT can Approve / Decline / Block.
 */
class JoinRequestsFragment : Fragment() {

    private var _binding: FragmentJoinRequestsBinding? = null
    private val binding get() = _binding!!

    private val adapter by lazy {
        GroupJoinRequestsAdapter(
            onApprove = { request -> approve(request) },
            onDecline = { request -> decline(request) },
            onBlock = { request -> block(request) }
        )
    }
    private var joinRequestsListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJoinRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        val role = arguments?.getString(ARG_ROLE).orEmpty()

        binding.joinRequestsList.layoutManager = LinearLayoutManager(requireContext())
        binding.joinRequestsList.adapter = adapter

        joinRequestsListener = GroupRepository.listenToJoinRequestsForGroup(groupId) { requests ->
            if (_binding == null) return@listenToJoinRequestsForGroup
            if (requests.isEmpty()) {
                binding.joinRequestsEmpty.visibility = View.VISIBLE
                binding.joinRequestsList.visibility = View.GONE
                adapter.submitList(emptyList())
                return@listenToJoinRequestsForGroup
            }
            val requesterUids = requests.map { it.requesterUid }.distinct()
            UserRepository.getUsersByIds(requesterUids) { profileMap ->
                if (_binding == null) return@getUsersByIds
                val rows = requests.map { req ->
                    val profile = profileMap[req.requesterUid]
                    val requesterDisplay = profile?.email?.takeIf { it.isNotBlank() }
                        ?: profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.join_request_requester_unknown)
                    val statusLabel = when (req.status) {
                        JoinRequest.STATUS_PENDING -> getString(R.string.status_pending)
                        JoinRequest.STATUS_APPROVED -> getString(R.string.status_approved)
                        JoinRequest.STATUS_DECLINED -> getString(R.string.status_declined)
                        JoinRequest.STATUS_BLOCKED -> getString(R.string.status_blocked)
                        else -> req.status
                    }
                    val relativeTime = req.createdAt?.let { ts ->
                        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                    } ?: ""
                    GroupJoinRequestRow(
                        request = req,
                        requesterDisplay = requesterDisplay,
                        statusLabel = statusLabel,
                        relativeTime = relativeTime,
                        showActions = req.status == JoinRequest.STATUS_PENDING && role == Constants.UserRole.PARENT
                    )
                }
                binding.joinRequestsEmpty.visibility = View.GONE
                binding.joinRequestsList.visibility = View.VISIBLE
                adapter.submitList(rows)
            }
        }
    }

    override fun onDestroyView() {
        joinRequestsListener?.remove()
        joinRequestsListener = null
        super.onDestroyView()
        _binding = null
    }

    private fun approve(request: JoinRequest) {
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        GroupRepository.approveJoinRequest(groupId, request.id, request.requesterUid) { success, error ->
            if (_binding == null) return@approveJoinRequest
            if (success) {
                Snackbar.make(binding.root, getString(R.string.status_approved), Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, error ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun decline(request: JoinRequest) {
        GroupRepository.updateJoinRequestStatus(request.id, JoinRequest.STATUS_DECLINED) { success, error ->
            if (_binding == null) return@updateJoinRequestStatus
            if (success) {
                Snackbar.make(binding.root, getString(R.string.status_declined), Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, error ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun block(request: JoinRequest) {
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        GroupRepository.updateJoinRequestStatus(request.id, JoinRequest.STATUS_BLOCKED) { ok, err ->
            if (_binding == null) return@updateJoinRequestStatus
            if (!ok) {
                Snackbar.make(binding.root, err ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                return@updateJoinRequestStatus
            }
            GroupRepository.addBlockedUid(groupId, request.requesterUid) { success, error ->
                if (_binding == null) return@addBlockedUid
                if (success) {
                    Snackbar.make(binding.root, getString(R.string.status_blocked), Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, error ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): JoinRequestsFragment =
            JoinRequestsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
