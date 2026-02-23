package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.DriveRequestRepository
import dev.segev.carpoolkids.databinding.FragmentDriveRequestsBinding
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.ui.drive.DriveRequestListAdapter

/**
 * Drive requests tab: list of broadcast drive requests for the current group.
 * Phase 1: empty state or list; no create/accept/cancel actions yet.
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

        adapter = DriveRequestListAdapter(requesterNames = emptyMap())
        binding.driveRequestsList.layoutManager = LinearLayoutManager(requireContext())
        binding.driveRequestsList.adapter = adapter

        attachListener(groupId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        driveRequestsListener?.remove()
        driveRequestsListener = null
        _binding = null
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
            } else {
                binding.driveRequestsEmpty.visibility = View.GONE
                binding.driveRequestsList.visibility = View.VISIBLE
                adapter.submitList(requests)
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
