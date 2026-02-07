package dev.segev.carpoolkids

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract
import com.firebase.ui.auth.data.model.FirebaseAuthUIAuthenticationResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
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
        else {
            Toast
                .makeText(
                    this,
                    "Hello ${FirebaseAuth.getInstance().currentUser?.displayName}",
                    Toast.LENGTH_LONG
                )
                .show()
            transactToNextScreen()
        }
    }

    private fun initViews() {
        binding.btnGoToSignIn.setOnClickListener {
            hideSignUpErrorUi()
            signIn()
        }
    }

    private fun showSignUpErrorUi(isEmailCollision: Boolean) {
        binding.tvSignupError.text = if (isEmailCollision) {
            getString(R.string.sign_up_error_email_already_in_use)
        } else {
            getString(R.string.sign_up_error_generic)
        }
        binding.tvSignupError.visibility = View.VISIBLE
        binding.btnGoToSignIn.visibility = View.VISIBLE
    }

    private fun hideSignUpErrorUi() {
        binding.tvSignupError.visibility = View.GONE
        binding.btnGoToSignIn.visibility = View.GONE
    }

    private fun onSignInResult(result: FirebaseAuthUIAuthenticationResult) {
        if (result.resultCode == RESULT_OK) {
            transactToNextScreen()
        } else {
            val isEmailCollision = isEmailAlreadyInUseError(result)
            showSignUpErrorUi(isEmailCollision)
        }
    }

    private fun isEmailAlreadyInUseError(result: FirebaseAuthUIAuthenticationResult): Boolean {
        val error = result.idpResponse?.error ?: return false
        (error as? FirebaseAuthException)?.errorCode?.let { code ->
            if (code == "ERROR_EMAIL_ALREADY_IN_USE") return true
        }
        val message = error.message.orEmpty()
        return message.contains("already in use", ignoreCase = true)
    }

    private fun transactToNextScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
        )
        finish()
    }
}
