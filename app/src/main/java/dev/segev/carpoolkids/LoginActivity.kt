package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract
import com.firebase.ui.auth.data.model.FirebaseAuthUIAuthenticationResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import dev.segev.carpoolkids.data.GroupRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private fun signIn() {
        hideSignUpErrorUi()
        // GoogleBuilder requires default_web_client_id in google-services.json from Firebase Console.
        // Add AuthUI.IdpConfig.GoogleBuilder().build() once you have a real Firebase project with Google Sign-In enabled.
        val providers = arrayListOf(
            AuthUI.IdpConfig.EmailBuilder().build(),
            AuthUI.IdpConfig.PhoneBuilder().build(),
        )

        val signInIntent = AuthUI.getInstance()
            .createSignInIntentBuilder()
            .setLogo(R.drawable.ic_launcher_foreground)
            .setAvailableProviders(providers)
            .setTheme(R.style.Theme_CarpoolKids)
            .build()
        signInLauncher.launch(signInIntent)
    }

    private val signInLauncher = registerForActivityResult(
        FirebaseAuthUIActivityResultContract(),
    ) { res ->
        onSignInResult(res)
    }

    private val createJoinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(GroupDashboardActivity.EXTRA_GROUP_ID)?.takeIf { it.isNotBlank() }?.let { groupId ->
                startActivity(
                    Intent(this, GroupDashboardActivity::class.java)
                        .putExtra(GroupDashboardActivity.EXTRA_GROUP_ID, groupId)
                        .putExtra(GroupDashboardActivity.EXTRA_ROLE, pendingRoleAfterLogin)
                )
                finish()
            }
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
                        .putExtra(GroupDashboardActivity.EXTRA_ROLE, pendingRoleAfterLogin)
                )
                finish()
            }
        }
    }

    private var pendingRoleAfterLogin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initViews()
        if (FirebaseAuth.getInstance().currentUser == null)
            signIn()
        else
            checkUserProfileAndNavigate()
    }

    // Wire button click listeners.
    private fun initViews() {
        binding.btnGoToSignIn.setOnClickListener {
            hideSignUpErrorUi()
            signIn()
        }
        binding.btnRetryProfile.setOnClickListener {
            checkUserProfileAndNavigate()
        }
    }

    // Show sign-up error and "back to sign in" button.
    private fun showSignUpErrorUi(isEmailCollision: Boolean) {
        binding.tvSignupError.text = if (isEmailCollision) {
            getString(R.string.sign_up_error_email_already_in_use)
        } else {
            getString(R.string.sign_up_error_generic)
        }
        binding.tvSignupError.visibility = View.VISIBLE
        binding.btnGoToSignIn.visibility = View.VISIBLE
    }

    // Hide sign-up error UI.
    private fun hideSignUpErrorUi() {
        binding.tvSignupError.visibility = View.GONE
        binding.btnGoToSignIn.visibility = View.GONE
    }

    // Handle result from Firebase Auth UI (success → check profile; failure → show error).
    private fun onSignInResult(result: FirebaseAuthUIAuthenticationResult) {
        if (result.resultCode == RESULT_OK) {
            checkUserProfileAndNavigate()
        } else {
            val isEmailCollision = isEmailAlreadyInUseError(result)
            showSignUpErrorUi(isEmailCollision)
        }
    }

    // Load user profile from Firestore; go to dashboard/choose-role or show error.
    private fun checkUserProfileAndNavigate() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        hideSignUpErrorUi()
        hideProfileErrorUi()
        showProfileProgress(true)
        UserRepository.getUser(user.uid) { profile, errorMessage ->
            showProfileProgress(false)
            when {
                profile != null -> navigateAfterLogin(user.uid, profile.role)
                errorMessage == "Profile not found" -> navigateToChooseRole()
                else -> showProfileErrorUi(errorMessage ?: getString(R.string.profile_load_error))
            }
        }
    }

    private fun showProfileProgress(show: Boolean) {
        binding.loginProgress.visibility = if (show) View.VISIBLE else View.GONE
    }

    // Show profile load error and retry button.
    private fun showProfileErrorUi(message: String) {
        binding.tvProfileError.text = message
        binding.tvProfileError.visibility = View.VISIBLE
        binding.btnRetryProfile.visibility = View.VISIBLE
    }

    private fun hideProfileErrorUi() {
        binding.tvProfileError.visibility = View.GONE
        binding.btnRetryProfile.visibility = View.GONE
    }

    // True if the auth error indicates email already in use.
    private fun isEmailAlreadyInUseError(result: FirebaseAuthUIAuthenticationResult): Boolean {
        val error = result.idpResponse?.error ?: return false
        (error as? FirebaseAuthException)?.errorCode?.let { code ->
            if (code == "ERROR_EMAIL_ALREADY_IN_USE") return true
        }
        val message = error.message.orEmpty()
        return message.contains("already in use", ignoreCase = true)
    }

    /** 0 groups → Create/Join; 1 group → Dashboard; >1 groups → Teams List, then Dashboard with chosen group. */
    private fun navigateAfterLogin(uid: String, role: String?) {
        pendingRoleAfterLogin = role
        GroupRepository.getMyGroups(uid) { groups, _ ->
            when {
                groups.isEmpty() -> {
                    val intent = Intent(this, CreateJoinGroupActivity::class.java)
                    role?.let { intent.putExtra(GroupDashboardActivity.EXTRA_ROLE, it) }
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

    // Open ChooseRoleActivity when user has no profile.
    private fun navigateToChooseRole() {
        startActivity(Intent(this, ChooseRoleActivity::class.java))
        finish()
    }
}
