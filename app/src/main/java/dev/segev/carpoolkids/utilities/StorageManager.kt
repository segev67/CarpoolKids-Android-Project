package dev.segev.carpoolkids.utilities

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import java.lang.ref.WeakReference

class StorageManager private constructor(context: Context) {
    private val contextRef = WeakReference(context)
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()

    fun uploadProfileImage(
        uri: Uri,
        callback: (String?, String?) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            val message = "Not signed in"
            SignalManager.getInstance().toast(message, SignalManager.ToastLength.SHORT)
            callback(null, message)
            return
        }
        val path = "${Constants.Storage.PROFILE_IMAGES_PATH}/${uid}.jpg"
        val ref = storage.reference.child(path)
        ref.putFile(uri)
            .addOnSuccessListener {
                ref.downloadUrl
                    .addOnSuccessListener { downloadUri ->
                        callback(downloadUri.toString(), null)
                    }
                    .addOnFailureListener { e ->
                        val message = e.message ?: "Failed to get download URL"
                        SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                        callback(null, message)
                    }
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Upload failed"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(null, message)
            }
    }

    companion object {
        @Volatile
        private var instance: StorageManager? = null

        fun init(context: Context): StorageManager {
            return instance ?: synchronized(this) {
                instance ?: StorageManager(context).also { instance = it }
            }
        }

        fun getInstance(): StorageManager {
            return instance ?: throw IllegalStateException(
                "StorageManager must be initialized by calling init(context) before use."
            )
        }
    }
}
