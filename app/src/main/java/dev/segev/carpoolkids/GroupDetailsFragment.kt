package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentGroupDetailsBinding
import dev.segev.carpoolkids.ui.group.GroupMemberRow
import dev.segev.carpoolkids.ui.group.GroupMembersAdapter
import dev.segev.carpoolkids.utilities.Constants

/**
 * Group Details screen: members summary (total, parents, children) and members list.
 * Visible to both PARENT and CHILD. Current user is highlighted with a "You" badge.
 */
class GroupDetailsFragment : Fragment() {

    private var _binding: FragmentGroupDetailsBinding? = null
    private val binding get() = _binding!!
    private val adapter by lazy { GroupMembersAdapter() }
    private val currentUid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()

        binding.groupDetailsList.layoutManager = LinearLayoutManager(requireContext())
        binding.groupDetailsList.adapter = adapter

        if (groupId.isEmpty()) {
            binding.groupDetailsSummary.text = getString(R.string.group_details_total, 0)
            binding.groupDetailsEmpty.visibility = View.VISIBLE
            binding.groupDetailsList.visibility = View.GONE
            adapter.submitList(emptyList())
            return
        }

        GroupRepository.getGroupById(groupId) { group, _ ->
            if (_binding == null) return@getGroupById
            val memberIds = group?.memberIds ?: emptyList()
            if (memberIds.isEmpty()) {
                binding.groupDetailsSummary.text = getString(R.string.group_details_total, 0)
                binding.groupDetailsEmpty.visibility = View.VISIBLE
                binding.groupDetailsList.visibility = View.GONE
                adapter.submitList(emptyList())
                return@getGroupById
            }
            UserRepository.getUsersByIds(memberIds) { profileMap ->
                if (_binding == null) return@getUsersByIds
                val rows = memberIds.map { uid ->
                    val profile = profileMap[uid]
                    val displayName = profile?.displayName.orEmpty().trim().ifBlank { profile?.email.orEmpty().trim() }.ifBlank { getString(R.string.join_request_requester_unknown) }
                    val email = profile?.email.orEmpty().trim()
                    val role = profile?.role ?: Constants.UserRole.PARENT
                    GroupMemberRow(
                        uid = uid,
                        displayName = displayName,
                        email = email,
                        role = role,
                        isCurrentUser = uid == currentUid
                    )
                }
                val parents = rows.count { it.role == Constants.UserRole.PARENT }
                val children = rows.count { it.role == Constants.UserRole.CHILD }
                binding.groupDetailsSummary.text = getString(R.string.group_details_total, rows.size) +
                    " · " + getString(R.string.group_details_parents, parents) +
                    " · " + getString(R.string.group_details_children, children)
                if (rows.isEmpty()) {
                    binding.groupDetailsEmpty.visibility = View.VISIBLE
                    binding.groupDetailsList.visibility = View.GONE
                    adapter.submitList(emptyList())
                } else {
                    binding.groupDetailsEmpty.visibility = View.GONE
                    binding.groupDetailsList.visibility = View.VISIBLE
                    adapter.submitList(rows)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): GroupDetailsFragment =
            GroupDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
