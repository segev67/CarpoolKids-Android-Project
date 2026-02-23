package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.ActivityChooseRoleBinding
import dev.segev.carpoolkids.model.UserProfile
import dev.segev.carpoolkids.utilities.Constants

class ChooseRoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChooseRoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseRoleBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        initViews()
    }

    private val createJoinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val groupId = result.data?.getStringExtra(GroupDashboardActivity.EXTRA_GROUP_ID).orEmpty()
            val role = result.data?.getStringExtra(GroupDashboardActivity.EXTRA_ROLE) ?: pendingRoleAfterChoose
            startActivity(
                Intent(this, GroupDashboardActivity::class.java)
                    .putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, groupId)
                    .putExtra(GroupDashboardActivity.EXTRA_ROLE, role)
            )
            finish()
        }
    }

    private val teamsListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(GroupDashboardActivity.EXTRA_GROUP_ID)?.takeIf { it.isNotBlank() }?.let { groupId ->
                startActivity(
                    Intent(this, GroupDashboardActivity::class.java)
                        .putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, groupId)
                        .putExtra(GroupDashboardActivity.EXTRA_ROLE, pendingRoleAfterChoose)
                )
                finish()
            }
        }
    }

    private var pendingRoleAfterChoose: String? = null

    // Wire role buttons and retry.
    private fun initViews() {
        binding.btnRoleParent.setOnClickListener { saveRoleAndNavigate(Constants.UserRole.PARENT) }
        binding.btnRoleChild.setOnClickListener { saveRoleAndNavigate(Constants.UserRole.CHILD) }
        binding.btnRetrySave.setOnClickListener {
            hideErrorUi()
            setRoleButtonsEnabled(true)
        }
    }

    // Save chosen role to Firestore and navigate by group count (Create/Join, Dashboard, or Teams List).
    private fun saveRoleAndNavigate(role: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        setRoleButtonsEnabled(false)
        hideErrorUi()
        showProgress(true)

        val profile = UserProfile(
            uid = user.uid,
            email = user.email,
            displayName = user.displayName,
            role = role,
            photoUrl = user.photoUrl?.toString()
        )

        UserRepository.createUser(profile) { success, errorMessage ->
            showProgress(false)
            setRoleButtonsEnabled(true)
            if (success) {
                navigateAfterLogin(user.uid, role)
            } else {
                showErrorUi(errorMessage ?: getString(R.string.choose_role_save_error))
            }
        }
    }

    // Enable or disable Parent/Child buttons during save.
    private fun setRoleButtonsEnabled(enabled: Boolean) {
        binding.btnRoleParent.isEnabled = enabled
        binding.btnRoleChild.isEnabled = enabled
    }

    private fun showProgress(show: Boolean) {
        binding.chooseRoleProgress.visibility = if (show) View.VISIBLE else View.GONE
    }

    // Show save error and retry button.
    private fun showErrorUi(message: String) {
        binding.tvChooseRoleError.text = message
        binding.tvChooseRoleError.visibility = View.VISIBLE
        binding.btnRetrySave.visibility = View.VISIBLE
    }

    private fun hideErrorUi() {
        binding.tvChooseRoleError.visibility = View.GONE
        binding.btnRetrySave.visibility = View.GONE
    }

    // 0 groups → Create/Join; 1 group → Dashboard; >1 groups → Teams List, then Dashboard with chosen group.
    private fun navigateAfterLogin(uid: String, role: String?) {
        pendingRoleAfterChoose = role
        GroupRepository.getMyGroups(uid) { groups, _ ->
            when {
                groups.isEmpty() -> {
                    val intent = Intent(this, CreateJoinGroupActivity::class.java)
                    intent.putExtra(GroupDashboardActivity.EXTRA_ROLE, role)
                    createJoinLauncher.launch(intent)
                }
                groups.size == 1 -> {
                    startActivity(
                        Intent(this, GroupDashboardActivity::class.java)
                            .putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, groups.first().id)
                            .putExtra(GroupDashboardActivity.EXTRA_ROLE, role)
                    )
                    finish()
                }
                else -> {
                    teamsListLauncher.launch(Intent(this, TeamsListActivity::class.java))
                }
            }
        }
    }
}
