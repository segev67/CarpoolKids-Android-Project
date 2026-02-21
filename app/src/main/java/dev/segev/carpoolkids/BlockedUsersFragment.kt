package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentBlockedUsersBinding
import dev.segev.carpoolkids.ui.group.BlockedUserRow
import dev.segev.carpoolkids.ui.group.BlockedUsersAdapter

/** Blocked users for the group (PARENT only). List with Unblock action. */
class BlockedUsersFragment : Fragment() {

    private var _binding: FragmentBlockedUsersBinding? = null
    private val binding get() = _binding!!

    private val adapter by lazy { BlockedUsersAdapter(onUnblock = { uid -> unblock(uid) }) }
    private var groupListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlockedUsersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()

        binding.blockedUsersList.layoutManager = LinearLayoutManager(requireContext())
        binding.blockedUsersList.adapter = adapter

        groupListener = GroupRepository.listenToGroup(groupId) { group ->
            if (_binding == null) return@listenToGroup
            val uids = group?.blockedUids ?: emptyList()
            if (uids.isEmpty()) {
                binding.blockedUsersEmpty.visibility = View.VISIBLE
                binding.blockedUsersList.visibility = View.GONE
                adapter.submitList(emptyList())
                return@listenToGroup
            }
            UserRepository.getUsersByIds(uids) { profileMap ->
                if (_binding == null) return@getUsersByIds
                val rows = uids.map { uid ->
                    val profile = profileMap[uid]
                    val displayLabel = profile?.email?.takeIf { it.isNotBlank() }
                        ?: profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.join_request_requester_unknown)
                    BlockedUserRow(uid = uid, displayLabel = displayLabel)
                }
                binding.blockedUsersEmpty.visibility = View.GONE
                binding.blockedUsersList.visibility = View.VISIBLE
                adapter.submitList(rows)
            }
        }
    }

    override fun onDestroyView() {
        groupListener?.remove()
        groupListener = null
        super.onDestroyView()
        _binding = null
    }

    private fun unblock(uid: String) {
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        GroupRepository.removeBlockedUid(groupId, uid) { success, error ->
            if (_binding == null) return@removeBlockedUid
            if (!success) {
                Snackbar.make(binding.root, error ?: getString(R.string.create_join_error), Snackbar.LENGTH_LONG).show()
                return@removeBlockedUid
            }
            GroupRepository.deleteBlockedJoinRequestsForRequesterInGroup(groupId, uid) { deleteOk, _ ->
                if (_binding == null) return@deleteBlockedJoinRequestsForRequesterInGroup
                Snackbar.make(binding.root, getString(R.string.btn_unblock), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): BlockedUsersFragment =
            BlockedUsersFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
