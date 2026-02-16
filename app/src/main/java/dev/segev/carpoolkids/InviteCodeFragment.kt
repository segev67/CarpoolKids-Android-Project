package dev.segev.carpoolkids

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.databinding.FragmentInviteCodeBinding
import dev.segev.carpoolkids.utilities.SignalManager

/**
 * Shows the group invite code and Copy button. PARENT and CHILD can view and copy.
 * Regenerate (PARENT only) will be added in a later phase.
 */
class InviteCodeFragment : Fragment() {

    private var _binding: FragmentInviteCodeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInviteCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        binding.inviteCodeBtnCopy.setOnClickListener { copyCodeToClipboard() }
        if (groupId.isEmpty()) {
            showNoGroupMessage()
            return
        }
        loadGroupAndShowCode(groupId)
    }

    private fun showNoGroupMessage() {
        binding.inviteCodeValue.text = getString(R.string.invite_code_no_group)
        binding.inviteCodeBtnCopy.visibility = View.GONE
    }

    private fun showInviteCode(code: String) {
        binding.inviteCodeValue.text = code
        binding.inviteCodeBtnCopy.visibility = View.VISIBLE
    }

    private fun loadGroupAndShowCode(groupId: String) {
        GroupRepository.getGroupById(groupId) { group, _ ->
            if (group != null) {
                showInviteCode(group.inviteCode)
            } else {
                showNoGroupMessage()
            }
        }
    }

    private fun copyCodeToClipboard() {
        val code = binding.inviteCodeValue.text?.toString() ?: return
        if (code == getString(R.string.invite_code_no_group)) return
        val clipboard = requireContext().getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Invite code", code))
        SignalManager.getInstance().toast(
            getString(R.string.invite_code_copied),
            SignalManager.ToastLength.SHORT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_ROLE = "role"

        fun newInstance(groupId: String, role: String): InviteCodeFragment =
            InviteCodeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_ROLE, role)
                }
            }
    }
}
