package dev.segev.carpoolkids.utilities

import android.content.Context
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.model.UserProfile
import java.lang.ref.WeakReference

class FirestoreManager private constructor(context: Context) {
    private val contextRef = WeakReference(context)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun createUserProfile(
        user: UserProfile,
        callback: (Boolean, String?) -> Unit
    ) {
        val data = hashMapOf<String, Any>(
            "uid" to user.uid,
            "displayName" to (user.displayName ?: ""),
            "email" to (user.email ?: ""),
            "role" to user.role,
            "photoUrl" to (user.photoUrl ?: ""),
            "createdAt" to FieldValue.serverTimestamp()
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
                    val role = doc.getString("role") ?: ""
                    val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
                    val parentUids = (doc.get("parentUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val childUids = (doc.get("childUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val profile = UserProfile(
                        uid = doc.getString("uid") ?: uid,
                        email = doc.getString("email"),
                        displayName = doc.getString("displayName"),
                        role = role,
                        photoUrl = photoUrl,
                        createdAt = createdAt,
                        parentUids = parentUids,
                        childUids = childUids
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
            "createdBy" to group.createdBy,
            "blockedUids" to (group.blockedUids.ifEmpty { emptyList<String>() })
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
                val list = snapshot.documents.mapNotNull { doc -> documentToGroup(doc) }
                callback(list, null)
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to get groups"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(emptyList(), message)
            }
    }

    fun getGroupById(groupId: String, callback: (Group?, String?) -> Unit) {
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    documentToGroup(doc)?.let { callback(it, null) }
                        ?: callback(null, "Invalid group data")
                } else {
                    callback(null, "Group not found")
                }
            }
            .addOnFailureListener { e ->
                callback(null, e.message ?: "Failed to load group")
            }
    }

    /** Lookup by invite code for join flow, first match only. */
    fun getGroupByInviteCode(inviteCode: String, callback: (Group?, String?) -> Unit) {
        if (inviteCode.isBlank()) {
            callback(null, "Enter an invite code")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .whereEqualTo("inviteCode", inviteCode.trim().uppercase())
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                val doc = snapshot.documents.firstOrNull()
                if (doc != null) {
                    documentToGroup(doc)?.let { callback(it, null) }
                        ?: callback(null, "Invalid group data")
                } else {
                    callback(null, "No group with this code")
                }
            }
            .addOnFailureListener { e ->
                callback(null, e.message ?: "Failed to find group")
            }
    }

    /** Adds uid to memberIds, safe to call if already a member. */
    fun addMemberToGroup(groupId: String, uid: String, callback: (Boolean, String?) -> Unit) {
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .update("memberIds", FieldValue.arrayUnion(uid))
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to join group") }
    }

    /** Updates the group invite code (PARENT-only use). */
    fun updateGroupInviteCode(groupId: String, newInviteCode: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || newInviteCode.isBlank()) {
            callback(false, "Invalid group or code")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .update("inviteCode", newInviteCode.trim().uppercase())
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to update invite code") }
    }

    private fun documentToGroup(doc: com.google.firebase.firestore.DocumentSnapshot): Group? {
        val id = doc.getString("id") ?: doc.id
        val name = doc.getString("name") ?: return null
        val inviteCode = doc.getString("inviteCode") ?: return null
        val memberIds = (doc.get("memberIds") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val createdBy = doc.getString("createdBy") ?: return null
        val blockedUids = (doc.get("blockedUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return Group(id, name, inviteCode, memberIds, createdBy, blockedUids)
    }

    /** Creates a PENDING join request; does not add the user to the group. */
    fun createJoinRequest(groupId: String, requesterUid: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || requesterUid.isBlank()) {
            callback(false, "Invalid group or user")
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        val data = hashMapOf<String, Any>(
            "id" to id,
            "groupId" to groupId,
            "requesterUid" to requesterUid,
            "status" to JoinRequest.STATUS_PENDING,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection(Constants.Firestore.COLLECTION_JOIN_REQUESTS)
            .document(id)
            .set(data)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to create join request") }
    }

    /** Returns true if there is already a PENDING join request for this (groupId, requesterUid). */
    fun hasPendingJoinRequest(groupId: String, requesterUid: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || requesterUid.isBlank()) {
            callback(false, null)
            return
        }
        db.collection(Constants.Firestore.COLLECTION_JOIN_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("requesterUid", requesterUid)
            .whereEqualTo("status", JoinRequest.STATUS_PENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                callback(snapshot?.documents?.isNotEmpty() == true, null)
            }
            .addOnFailureListener { e -> callback(false, e.message) }
    }

    private fun documentToJoinRequest(doc: com.google.firebase.firestore.DocumentSnapshot): JoinRequest? {
        val id = doc.getString("id") ?: doc.id
        val groupId = doc.getString("groupId") ?: return null
        val requesterUid = doc.getString("requesterUid") ?: return null
        val status = doc.getString("status") ?: return null
        val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
        return JoinRequest(id, groupId, requesterUid, status, createdAt)
    }

    /** Real-time listener for a user's join requests (for "My Requests" on Home). Order: newest first; caller should sort PENDING first and take 5. */
    fun listenToJoinRequestsForUser(uid: String, callback: (List<JoinRequest>) -> Unit): ListenerRegistration {
        if (uid.isBlank()) {
            callback(emptyList())
            return ListenerRegistration { }
        }
        return db.collection(Constants.Firestore.COLLECTION_JOIN_REQUESTS)
            .whereEqualTo("requesterUid", uid)
            .limit(30)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    android.util.Log.w("FirestoreManager", "listenToJoinRequestsForUser failed (add index?): ${e.message}", e)
                    callback(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { documentToJoinRequest(it) } ?: emptyList()
                callback(list)
            }
    }

    /** Batch get groups by ids; returns map groupId -> Group (for resolving team names in My Requests). */
    fun getGroupsByIds(ids: List<String>, callback: (Map<String, Group>) -> Unit) {
        if (ids.isEmpty()) {
            callback(emptyMap())
            return
        }
        val distinctIds = ids.distinct().take(30)
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .whereIn(FieldPath.documentId(), distinctIds)
            .get()
            .addOnSuccessListener { snapshot ->
                val map = snapshot?.documents?.mapNotNull { documentToGroup(it) }?.associateBy { it.id } ?: emptyMap()
                callback(map)
            }
            .addOnFailureListener { callback(emptyMap()) }
    }

    fun createLinkCode(creatorUid: String, creatorRole: String, groupId: String, callback: (String?, String?) -> Unit) {
        val code = java.util.UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        val data = hashMapOf<String, Any>(
            "creatorUid" to creatorUid,
            "creatorRole" to creatorRole,
            "groupId" to groupId,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection(Constants.Firestore.COLLECTION_LINK_CODES)
            .document(code)
            .set(data)
            .addOnSuccessListener { callback(code, null) }
            .addOnFailureListener { e -> callback(null, e.message ?: "Failed to create code") }
    }

    fun getLinkCode(code: String, callback: (creatorUid: String?, creatorRole: String?, groupId: String?, createdAt: Long?, String?) -> Unit) {
        val normalized = code.trim().uppercase()
        if (normalized.isBlank()) {
            callback(null, null, null, null, "Enter a link code")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_LINK_CODES)
            .document(normalized)
            .get()
            .addOnSuccessListener { doc ->
                if (doc == null || !doc.exists()) {
                    callback(null, null, null, null, "Invalid or expired code")
                    return@addOnSuccessListener
                }
                val creatorUid = doc.getString("creatorUid")
                val creatorRole = doc.getString("creatorRole")
                val groupId = doc.getString("groupId")
                val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
                if (creatorUid.isNullOrBlank() || creatorRole.isNullOrBlank() || groupId.isNullOrBlank() || createdAt == null) {
                    callback(null, null, null, null, "Invalid code data")
                    return@addOnSuccessListener
                }
                callback(creatorUid, creatorRole, groupId, createdAt, null)
            }
            .addOnFailureListener { e -> callback(null, null, null, null, e.message ?: "Failed to read code") }
    }

    fun deleteLinkCode(code: String, callback: (Boolean, String?) -> Unit) {
        db.collection(Constants.Firestore.COLLECTION_LINK_CODES)
            .document(code.trim().uppercase())
            .delete()
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message) }
    }

    fun addParentChildLink(parentUid: String, childUid: String, callback: (Boolean, String?) -> Unit) {
        val users = db.collection(Constants.Firestore.COLLECTION_USERS)
        users.document(parentUid).update("childUids", FieldValue.arrayUnion(childUid))
            .addOnSuccessListener {
                users.document(childUid).update("parentUids", FieldValue.arrayUnion(parentUid))
                    .addOnSuccessListener { callback(true, null) }
                    .addOnFailureListener { e -> callback(false, e.message ?: "Failed to update child") }
            }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to update parent") }
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
                val list = snapshot?.documents?.mapNotNull { documentToGroup(it) } ?: emptyList()
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
