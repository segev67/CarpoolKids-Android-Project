package dev.segev.carpoolkids

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.ActivitySetHomeAddressBinding

/**
 * Onboarding step shown after [ChooseRoleActivity] for brand-new users. Offers a single, optional
 * action — set home address via the map picker — and a Skip button. Either path finishes the
 * activity; the calling activity ([ChooseRoleActivity]) continues onboarding via its result launcher.
 *
 * Existing users never reach this screen — they see the dashboard banner instead.
 */
class SetHomeAddressActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetHomeAddressBinding

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // User canceled the picker — leave them on this screen to choose again or skip.
            return@registerForActivityResult
        }
        val coords = MapPickerActivity.extractResult(result.data) ?: return@registerForActivityResult
        saveHomeAddress(coords.first, coords.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetHomeAddressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.setHomeAddressSetButton.setOnClickListener {
            mapPickerLauncher.launch(
                MapPickerActivity.intentForHome(this, currentLat = null, currentLng = null)
            )
        }
        binding.setHomeAddressSkipButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun saveHomeAddress(lat: Double, lng: Double) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank()) {
            // Auth gone — bail to caller, who will redirect to Login.
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        showProgress(true)
        setButtonsEnabled(false)
        UserRepository.updateHomeAddress(uid, lat, lng) { ok, err ->
            showProgress(false)
            if (ok) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                setButtonsEnabled(true)
                Snackbar.make(
                    binding.root,
                    err ?: getString(R.string.profile_home_save_error),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showProgress(show: Boolean) {
        binding.setHomeAddressProgress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.setHomeAddressSetButton.isEnabled = enabled
        binding.setHomeAddressSkipButton.isEnabled = enabled
    }
}
