package dev.segev.carpoolkids

import android.animation.Animator
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.airbnb.lottie.LottieAnimationView
import dev.segev.carpoolkids.databinding.ActivitySplashScreenBinding

// Splash with Lottie animation, on end navigates to LoginActivity.
class SplashScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashScreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySplashScreenBinding.inflate(layoutInflater)

        enableEdgeToEdge()
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startAnimation(binding.splashLOTTIELottie)
    }

    private fun transactToLoginActivity() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun startAnimation(lottieAnimationView: LottieAnimationView) {
        lottieAnimationView.resumeAnimation()

        lottieAnimationView.addAnimatorListener(
            object : Animator.AnimatorListener {
                override fun onAnimationCancel(animation: Animator) {}

                override fun onAnimationEnd(animation: Animator) {
                    transactToLoginActivity()
                }

                override fun onAnimationRepeat(animation: Animator) {}

                override fun onAnimationStart(animation: Animator) {}
            }
        )
    }
}
