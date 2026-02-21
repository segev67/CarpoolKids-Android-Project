package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.databinding.ActivityGroupDashboardBinding

class GroupDashboardActivity : AppCompatActivity(), DashboardHomeListener {

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_ROLE = "extra_role"
        private const val TAG_HOME = "dashboard_home"
    }

    private lateinit var binding: ActivityGroupDashboardBinding
    var currentGroupId: String = ""
        private set
    var role: String? = null
        private set

    private val launcherTeamsList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(EXTRA_GROUP_ID)?.takeIf { it.isNotBlank() }?.let { id ->
                reloadGroup(id)
            }
        }
    }

    private val launcherCreateJoin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(EXTRA_GROUP_ID)?.takeIf { it.isNotBlank() }?.let { id ->
                reloadGroup(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupDashboardBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        currentGroupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        role = intent.getStringExtra(EXTRA_ROLE)
        // Allow empty group: user can see dashboard with empty teams/schedule/requests and use Create/Join from Home.
        initViews()
        if (savedInstanceState == null) {
            showHomeFragment()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentGroupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        role = intent.getStringExtra(EXTRA_ROLE)
        // Refresh visible fragment if Home is showing (e.g. after "Continue without group").
        val home = supportFragmentManager.findFragmentByTag(TAG_HOME) as? DashboardHomeFragment
        if (home != null) {
            showHomeFragment()
        }
    }

    // Set up bottom nav: switch between Home, Schedule, Group, Drivers fragments.
    private fun initViews() {
        binding.dashboardBottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showHomeFragment()
                    true
                }
                R.id.nav_schedule -> {
                    supportFragmentManager.commit {
                        replace(
                            R.id.dashboard_fragment_container,
                            ScheduleFragment.newInstance(currentGroupId, role.orEmpty()),
                            "schedule"
                        )
                    }
                    true
                }
                R.id.nav_group -> {
                    supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    showGroupTabWithCurrentGroup()
                    true
                }
                R.id.nav_drive -> {
                    supportFragmentManager.commit {
                        replace(R.id.dashboard_fragment_container, DriversRequestsFragment(), "drivers")
                    }
                    true
                }
                else -> false
            }
        }
    }

    // If no group is selected but user has groups, pick the first so Group tab (and Invite) have a valid group.
    private fun showGroupTabWithCurrentGroup() {
        if (currentGroupId.isNotEmpty()) {
            showGroupFragment()
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            showGroupFragment()
            return
        }
        GroupRepository.getMyGroups(uid) { groups, _ ->
            if (groups.isNotEmpty()) reloadGroup(groups.first().id)
            showGroupFragment()
        }
    }

    private fun showGroupFragment() {
        supportFragmentManager.commit {
            replace(
                R.id.dashboard_fragment_container,
                GroupFragment.newInstance(currentGroupId, role.orEmpty()),
                "group"
            )
        }
    }

    // Show dashboard Home fragment with current group id.
    private fun showHomeFragment() {
        supportFragmentManager.commit {
            replace(
                R.id.dashboard_fragment_container,
                DashboardHomeFragment.newInstance(currentGroupId),
                TAG_HOME
            )
        }
    }

    override fun openTeamsList() {
        launcherTeamsList.launch(Intent(this, TeamsListActivity::class.java))
    }

    // Launch CreateJoinGroupActivity for result (DashboardHomeListener).
    override fun openCreateJoin() {
        val intent = Intent(this, CreateJoinGroupActivity::class.java)
        role?.let { intent.putExtra(EXTRA_ROLE, it) }
        launcherCreateJoin.launch(intent)
    }

    // Update current group and refresh Home fragment if visible (after Teams List or Create/Join).
    private fun reloadGroup(groupId: String) {
        currentGroupId = groupId
        val home = supportFragmentManager.findFragmentByTag(TAG_HOME) as? DashboardHomeFragment
        home?.refreshWithGroup(groupId)
    }
}
