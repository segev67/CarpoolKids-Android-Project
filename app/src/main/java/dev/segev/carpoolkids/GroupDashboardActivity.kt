package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
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
        if (currentGroupId.isEmpty()) {
            finish()
            return
        }

        initViews()
        if (savedInstanceState == null) {
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
                        replace(R.id.dashboard_fragment_container, ScheduleFragment(), "schedule")
                    }
                    true
                }
                R.id.nav_group -> {
                    supportFragmentManager.commit {
                        replace(R.id.dashboard_fragment_container, GroupFragment(), "group")
                    }
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
