package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.databinding.ActivityCreateJoinGroupBinding
import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.utilities.Constants
import dev.segev.carpoolkids.utilities.SignalManager

class CreateJoinGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateJoinGroupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateJoinGroupBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        initViews(uid)
        configureRoleUi(intent.getStringExtra(GroupDashboardActivity.EXTRA_ROLE))
    }

    // Role-based create section: PARENT sees input + button; CHILD sees only the informational message.
    private fun configureRoleUi(role: String?) {
        if (role == Constants.UserRole.CHILD) {
            binding.createSectionLabel.visibility = View.GONE
            binding.inputGroupName.visibility = View.GONE
            binding.btnCreateGroup.visibility = View.GONE
            binding.childCreateGroupMessage.visibility = View.VISIBLE
        } else {
            binding.createSectionLabel.visibility = View.VISIBLE
            binding.inputGroupName.visibility = View.VISIBLE
            binding.btnCreateGroup.visibility = View.VISIBLE
            binding.childCreateGroupMessage.visibility = View.GONE
        }
    }

    // Wire create, join, continue-without-group, and retry buttons.
    private fun initViews(uid: String) {
        binding.btnCreateGroup.setOnClickListener { createGroup(uid) }
        binding.btnJoinGroup.setOnClickListener { joinGroup(uid) }
        binding.btnContinueWithoutGroup.setOnClickListener { openDashboardWithoutGroup() }
        binding.btnRetryCreateJoin.setOnClickListener {
            hideError()
            setRoleButtonsEnabled(true)
        }
    }

    private fun createGroup(uid: String) {
        val name = binding.etGroupName.text?.toString()?.trim()
        if (name.isNullOrBlank()) {
            showError(getString(R.string.group_name_required))
            return
        }
        setRoleButtonsEnabled(false)
        hideError()
        showProgress(true)
        GroupRepository.createGroup(name, uid) { group, error ->
            showProgress(false)
            setRoleButtonsEnabled(true)
            if (group != null) openDashboardAndFinish(group) else showError(error ?: getString(R.string.create_join_error))
        }
    }

    // Submit join request (PENDING); no immediate join. User stays NO GROUP until parent approves.
    private fun joinGroup(uid: String) {
        val code = binding.etInviteCode.text?.toString()?.trim()
        if (code.isNullOrBlank()) {
            showError(getString(R.string.invite_code_required))
            return
        }
        setRoleButtonsEnabled(false)
        hideError()
        showProgress(true)
        GroupRepository.createJoinRequest(code, uid) { alreadyMemberGroup, error ->
            showProgress(false)
            setRoleButtonsEnabled(true)
            when {
                error != null -> showError(error)
                alreadyMemberGroup != null -> openDashboardAndFinish(alreadyMemberGroup)
                else -> {
                    SignalManager.getInstance().toast(
                        getString(R.string.join_request_sent),
                        SignalManager.ToastLength.SHORT
                    )
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun openDashboardAndFinish(group: Group) {
        val role = intent.getStringExtra(GroupDashboardActivity.EXTRA_ROLE)
        setResult(RESULT_OK, Intent().putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, group.id).putExtra(GroupDashboardActivity.EXTRA_ROLE, role))
        finish()
    }

    // Open Group Dashboard with no group; teams list, schedule, requests empty; Home has Create/Join.
    private fun openDashboardWithoutGroup() {
        val intent = Intent(this, GroupDashboardActivity::class.java).apply {
            putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, "")
            putExtra(GroupDashboardActivity.EXTRA_ROLE, intent.getStringExtra(GroupDashboardActivity.EXTRA_ROLE))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    // Enable or disable create/join buttons during request.
    private fun setRoleButtonsEnabled(enabled: Boolean) {
        binding.btnCreateGroup.isEnabled = enabled
        binding.btnJoinGroup.isEnabled = enabled
    }

    private fun showProgress(show: Boolean) {
        binding.createJoinProgress.visibility = if (show) View.VISIBLE else View.GONE
    }

    // Show error message and retry button.
    private fun showError(message: String) {
        binding.tvCreateJoinError.text = message
        binding.tvCreateJoinError.visibility = View.VISIBLE
        binding.btnRetryCreateJoin.visibility = View.VISIBLE
    }

    // Hide error UI.
    private fun hideError() {
        binding.tvCreateJoinError.visibility = View.GONE
        binding.btnRetryCreateJoin.visibility = View.GONE
    }
}
