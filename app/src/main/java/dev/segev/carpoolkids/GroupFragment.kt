package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.databinding.FragmentGroupBinding
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.utilities.Constants

/**
 * Group tab hub: Group details, Invite to group, Join requests (N), Blocked users (PARENT only).
 * Each row opens a dedicated screen. N = live PENDING count for the group.
 */
class GroupFragment : Fragment() {

    private var _binding: FragmentGroupBinding? = null
    private val binding get() = _binding!!
    private var joinRequestsListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        val role = arguments?.getString(ARG_ROLE).orEmpty()

        binding.groupHubCardBlocked.visibility =
            if (role == Constants.UserRole.PARENT) View.VISIBLE else View.GONE

        joinRequestsListener = GroupRepository.listenToJoinRequestsForGroup(groupId) { requests ->
            if (_binding == null) return@listenToJoinRequestsForGroup
            val pendingCount = requests.count { it.status == JoinRequest.STATUS_PENDING }
            binding.groupHubLabelJoinRequests.text = getString(R.string.group_hub_join_requests, pendingCount)
        }

        binding.groupHubCardDetails.setOnClickListener {
            openScreen(GroupDetailsFragment.newInstance(groupId, role), "group_details")
        }
        binding.groupHubCardInvite.setOnClickListener {
            openScreen(InviteCodeFragment.newInstance(groupId, role), "invite_code")
        }
        binding.groupHubCardJoinRequests.setOnClickListener {
            openScreen(JoinRequestsFragment.newInstance(groupId, role), "join_requests")
        }
        binding.groupHubCardBlocked.setOnClickListener {
            openScreen(BlockedUsersFragment.newInstance(groupId, role), "blocked_users")
        }
    }

    private fun openScreen(fragment: Fragment, tag: String) {
        parentFragmentManager.commit {
            replace(R.id.dashboard_fragment_container, fragment, tag)
            addToBackStack(tag)
        }
    }

    override fun onDestroyView() {
        joinRequestsListener?.remove()
        joinRequestsListener = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): GroupFragment =
            GroupFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
