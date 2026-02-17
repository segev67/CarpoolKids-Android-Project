package dev.segev.carpoolkids

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.databinding.FragmentInviteCodeBinding
import dev.segev.carpoolkids.utilities.Constants
import dev.segev.carpoolkids.utilities.SignalManager

/**
 * Shows the group invite code and Copy button. PARENT and CHILD can view and copy.
 * Regenerate invite code is PARENT only.
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
        val role = arguments?.getString(ARG_ROLE).orEmpty()
        binding.inviteCodeBtnCopy.setOnClickListener { copyCodeToClipboard() }
        binding.inviteCodeBtnRegenerate.setOnClickListener { regenerateCode(groupId) }
        binding.inviteCodeBtnRegenerate.visibility = if (role == Constants.UserRole.PARENT) View.VISIBLE else View.GONE
        if (groupId.isEmpty()) {
            showNoGroupMessage()
            return
        }
        loadGroupAndShowCode(groupId)
    }

    private fun showNoGroupMessage() {
        binding.inviteCodeValue.text = getString(R.string.invite_code_no_group)
        binding.inviteCodeBtnCopy.visibility = View.GONE
        binding.inviteCodeBtnRegenerate.visibility = View.GONE
    }

    private fun showInviteCode(code: String) {
        binding.inviteCodeValue.text = code
        binding.inviteCodeBtnCopy.visibility = View.VISIBLE
        val role = arguments?.getString(ARG_ROLE).orEmpty()
        binding.inviteCodeBtnRegenerate.visibility = if (role == Constants.UserRole.PARENT) View.VISIBLE else View.GONE
    }

    private fun regenerateCode(groupId: String) {
        GroupRepository.regenerateInviteCode(groupId) { newCode, error ->
            if (newCode != null) {
                showInviteCode(newCode)
                SignalManager.getInstance().toast(
                    getString(R.string.invite_code_regenerated),
                    SignalManager.ToastLength.SHORT
                )
            } else {
                SignalManager.getInstance().toast(
                    error ?: getString(R.string.create_join_error),
                    SignalManager.ToastLength.SHORT
                )
            }
        }
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
