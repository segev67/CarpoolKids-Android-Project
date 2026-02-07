package dev.segev.carpoolkids.utilities

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import java.lang.ref.WeakReference

class AuthManager private constructor(context: Context) {
    private val contextRef = WeakReference(context)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    fun login(
        email: String,
        password: String,
        callback: AuthCallback
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                callback.onSuccess()
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Login failed"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback.onFailure(message)
            }
    }

    fun register(
        email: String,
        password: String,
        callback: AuthCallback
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                callback.onSuccess()
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Registration failed"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback.onFailure(message)
            }
    }

    fun logout() {
        auth.signOut()
    }

    fun currentUser(): FirebaseUser? = auth.currentUser

    interface AuthCallback {
        fun onSuccess()
        fun onFailure(message: String)
    }

    companion object {
        @Volatile
        private var instance: AuthManager? = null

        fun init(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context).also { instance = it }
            }
        }

        fun getInstance(): AuthManager {
            return instance ?: throw IllegalStateException(
                "AuthManager must be initialized by calling init(context) before use."
            )
        }
    }
}
