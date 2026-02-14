package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.databinding.ActivityTeamsListBinding
import dev.segev.carpoolkids.model.TeamUiModel
import dev.segev.carpoolkids.ui.home.TeamAdapter

/**
 * Lists user's groups only. On team click returns EXTRA_GROUP_ID to caller (GroupDashboardActivity).
 * Does NOT start GroupDashboardActivity — Option B: single dashboard instance.
 */
class TeamsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeamsListBinding
    private lateinit var teamAdapter: TeamAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamsListBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setupRecycler()
        loadTeams()
    }

    // Set up RecyclerView; on team click return EXTRA_GROUP_ID and finish.
    private fun setupRecycler() {
        teamAdapter = TeamAdapter { team ->
            setResult(RESULT_OK, Intent().putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, team.id))
            finish()
        }
        binding.teamsListRecycler.layoutManager = LinearLayoutManager(this)
        binding.teamsListRecycler.adapter = teamAdapter
    }

    // Load user's groups and show list or empty state.
    private fun loadTeams() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        GroupRepository.getMyGroups(uid) { groups, _ ->
            val models = groups.map { g ->
                TeamUiModel(
                    id = g.id,
                    name = g.name,
                    sportOrAgeGroup = null,
                    memberCount = g.memberIds.size
                )
            }
            if (models.isEmpty()) {
                binding.teamsListRecycler.visibility = View.GONE
                binding.teamsListEmpty.visibility = View.VISIBLE
            } else {
                binding.teamsListEmpty.visibility = View.GONE
                binding.teamsListRecycler.visibility = View.VISIBLE
                teamAdapter.submitList(models)
            }
        }
    }
}
