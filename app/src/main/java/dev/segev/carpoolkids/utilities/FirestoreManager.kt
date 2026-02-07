package dev.segev.carpoolkids.utilities

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.model.UserProfile
import java.lang.ref.WeakReference

class FirestoreManager private constructor(context: Context) {
    private val contextRef = WeakReference(context)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun createUserProfile(
        user: UserProfile,
        callback: (Boolean, String?) -> Unit
    ) {
        val data = hashMapOf(
            "uid" to user.uid,
            "displayName" to user.displayName,
            "email" to user.email,
            "photoUrl" to (user.photoUrl ?: "")
        )
        db.collection(Constants.Firestore.COLLECTION_USERS)
            .document(user.uid)
            .set(data)
            .addOnSuccessListener {
                callback(true, null)
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to create profile"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(false, message)
            }
    }

    fun getUserProfile(
        uid: String,
        callback: (UserProfile?, String?) -> Unit
    ) {
        db.collection(Constants.Firestore.COLLECTION_USERS)
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    val photoUrl = doc.getString("photoUrl")?.takeIf { it.isNotEmpty() }
                    val profile = UserProfile(
                        uid = doc.getString("uid") ?: uid,
                        displayName = doc.getString("displayName") ?: "",
                        email = doc.getString("email") ?: "",
                        photoUrl = photoUrl
                    )
                    callback(profile, null)
                } else {
                    callback(null, "Profile not found")
                }
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to get profile"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(null, message)
            }
    }

    fun createGroup(
        group: Group,
        callback: (Boolean, String?) -> Unit
    ) {
        val data = hashMapOf(
            "id" to group.id,
            "name" to group.name,
            "inviteCode" to group.inviteCode,
            "memberIds" to group.memberIds,
            "createdBy" to group.createdBy
        )
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(group.id)
            .set(data)
            .addOnSuccessListener {
                callback(true, null)
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to create group"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(false, message)
            }
    }

    fun getMyGroups(
        uid: String,
        callback: (List<Group>, String?) -> Unit
    ) {
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .whereArrayContains("memberIds", uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.mapNotNull { doc ->
                    val id = doc.getString("id") ?: doc.id
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val inviteCode = doc.getString("inviteCode") ?: return@mapNotNull null
                    val memberIds = (doc.get("memberIds") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val createdBy = doc.getString("createdBy") ?: return@mapNotNull null
                    Group(id, name, inviteCode, memberIds, createdBy)
                }
                callback(list, null)
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to get groups"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(emptyList(), message)
            }
    }

    fun listenToGroups(
        uid: String,
        callback: (List<Group>) -> Unit
    ): ListenerRegistration {
        return db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .whereArrayContains("memberIds", uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    SignalManager.getInstance().toast(
                        e.message ?: "Listen failed",
                        SignalManager.ToastLength.SHORT
                    )
                    callback(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val id = doc.getString("id") ?: doc.id
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val inviteCode = doc.getString("inviteCode") ?: return@mapNotNull null
                    val memberIds = (doc.get("memberIds") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val createdBy = doc.getString("createdBy") ?: return@mapNotNull null
                    Group(id, name, inviteCode, memberIds, createdBy)
                } ?: emptyList()
                callback(list)
            }
    }

    companion object {
        @Volatile
        private var instance: FirestoreManager? = null

        fun init(context: Context): FirestoreManager {
            return instance ?: synchronized(this) {
                instance ?: FirestoreManager(context).also { instance = it }
            }
        }

        fun getInstance(): FirestoreManager {
            return instance ?: throw IllegalStateException(
                "FirestoreManager must be initialized by calling init(context) before use."
            )
        }
    }
}
