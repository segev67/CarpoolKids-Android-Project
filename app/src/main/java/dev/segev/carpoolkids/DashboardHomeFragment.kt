package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.firebase.ui.auth.AuthUI
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.TrainingRepository
import dev.segev.carpoolkids.data.TrainingRepositoryImpl
import dev.segev.carpoolkids.databinding.FragmentDashboardHomeBinding
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.model.TodayTrainingUiModel
import dev.segev.carpoolkids.ui.home.MyJoinRequestsAdapter
import dev.segev.carpoolkids.ui.home.MyRequestRow

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
        val groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        if (groupId.isNotEmpty()) {
            loadGroupAndToday(groupId)
        }
        binding.dashboardHomeBtnTeamsList.setOnClickListener {
            (activity as? DashboardHomeListener)?.openTeamsList()
        }
        binding.dashboardHomeBtnCreateJoin.setOnClickListener {
            (activity as? DashboardHomeListener)?.openCreateJoin()
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
                    MyRequestRow(name, statusLabel, relative)
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
        arguments = (arguments ?: Bundle()).apply { putString(ARG_GROUP_ID, groupId) }
        loadGroupAndToday(groupId)
    }

    private fun loadGroupAndToday(groupId: String) {
        if (_binding == null) return
        binding.dashboardHomeGroupName.visibility = View.GONE
        binding.dashboardHomeTodayCard.visibility = View.GONE
        binding.dashboardHomeNoTrainingMessage.visibility = View.GONE

        GroupRepository.getGroupById(groupId) { group, _ ->
            if (_binding == null) return@getGroupById
            if (group != null) {
                binding.dashboardHomeGroupName.text = group.name
                binding.dashboardHomeGroupName.visibility = View.VISIBLE
                loadTodayTraining(groupId)
            }
        }
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
}
