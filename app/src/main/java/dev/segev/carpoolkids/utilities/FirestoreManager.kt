package dev.segev.carpoolkids.utilities

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.model.UserProfile
import java.lang.ref.WeakReference
import java.util.Date

class FirestoreManager private constructor(context: Context) {
    private val contextRef = WeakReference(context)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun createUserProfile(
        user: UserProfile,
        callback: (Boolean, String?) -> Unit
    ) {
        val data = hashMapOf(
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

    private fun documentToUserProfile(doc: com.google.firebase.firestore.DocumentSnapshot): UserProfile? {
        if (!doc.exists()) return null
        val uid = doc.getString("uid") ?: doc.id
        val photoUrl = doc.getString("photoUrl")?.takeIf { it.isNotEmpty() }
        val role = doc.getString("role") ?: ""
        val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
        val parentUids = (doc.get("parentUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val childUids = (doc.get("childUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return UserProfile(
            uid,
            doc.getString("email"),
            doc.getString("displayName"),
            role,
            photoUrl,
            createdAt,
            parentUids,
            childUids
        )
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
                    documentToUserProfile(doc)?.let { callback(it, null) }
                        ?: callback(null, "Invalid profile data")
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

    /** Batch get user profiles by uid (for Join Requests requester display). Returns map uid -> UserProfile. */
    fun getUsersByIds(uids: List<String>, callback: (Map<String, UserProfile>) -> Unit) {
        if (uids.isEmpty()) {
            callback(emptyMap())
            return
        }
        val distinctIds = uids.distinct().take(30)
        db.collection(Constants.Firestore.COLLECTION_USERS)
            .whereIn(FieldPath.documentId(), distinctIds)
            .get()
            .addOnSuccessListener { snapshot ->
                val map = snapshot?.documents?.mapNotNull { doc ->
                    documentToUserProfile(doc)?.let { p -> doc.id to p }
                }?.toMap() ?: emptyMap()
                callback(map)
            }
            .addOnFailureListener { callback(emptyMap()) }
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
            "blockedUids" to (group.blockedUids.ifEmpty { emptyList() })
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

    /** Real-time listener for a single group (e.g. Blocked Users screen). */
    fun listenToGroup(groupId: String, callback: (Group?) -> Unit): ListenerRegistration {
        if (groupId.isBlank()) {
            callback(null)
            return ListenerRegistration { }
        }
        return db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .addSnapshotListener { snapshot, _ ->
                callback(snapshot?.let { documentToGroup(it) })
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
        val data = hashMapOf(
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

    /** Real-time listener for join requests of a group (Group tab Join Requests screen). */
    fun listenToJoinRequestsForGroup(groupId: String, callback: (List<JoinRequest>) -> Unit): ListenerRegistration {
        if (groupId.isBlank()) {
            callback(emptyList())
            return ListenerRegistration { }
        }
        return db.collection(Constants.Firestore.COLLECTION_JOIN_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .limit(100)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    callback(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { documentToJoinRequest(it) } ?: emptyList()
                callback(list)
            }
    }

    /** Update join request status (APPROVED, DECLINED, or BLOCKED). */
    fun updateJoinRequestStatus(requestId: String, status: String, callback: (Boolean, String?) -> Unit) {
        if (requestId.isBlank() || status.isBlank()) {
            callback(false, "Invalid request or status")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_JOIN_REQUESTS)
            .document(requestId)
            .update("status", status)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to update request") }
    }

    /** Add uid to group's blockedUids (FieldValue.arrayUnion). */
    fun addBlockedUid(groupId: String, uid: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || uid.isBlank()) {
            callback(false, "Invalid group or user")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .update("blockedUids", FieldValue.arrayUnion(uid))
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to block user") }
    }

    /** Remove uid from group's blockedUids (FieldValue.arrayRemove). */
    fun removeBlockedUid(groupId: String, uid: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || uid.isBlank()) {
            callback(false, "Invalid group or user")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .update("blockedUids", FieldValue.arrayRemove(uid))
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to unblock user") }
    }

    /** Delete join request documents for this (groupId, requesterUid) with status BLOCKED. Removes them from My Requests after unblock. */
    fun deleteBlockedJoinRequestsForRequesterInGroup(groupId: String, requesterUid: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || requesterUid.isBlank()) {
            callback(true, null)
            return
        }
        db.collection(Constants.Firestore.COLLECTION_JOIN_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("requesterUid", requesterUid)
            .whereEqualTo("status", JoinRequest.STATUS_BLOCKED)
            .get()
            .addOnSuccessListener { snapshot ->
                val docs = snapshot?.documents ?: emptyList()
                if (docs.isEmpty()) {
                    callback(true, null)
                    return@addOnSuccessListener
                }
                val batch = db.batch()
                docs.forEach { batch.delete(it.reference) }
                batch.commit()
                    .addOnSuccessListener { callback(true, null) }
                    .addOnFailureListener { e -> callback(false, e.message ?: "Failed to delete request") }
            }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to find request") }
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

    // ---------- Practices (Schedule tab) ----------

    fun createPractice(practice: Practice, callback: (Boolean, String?) -> Unit) {
        val data = hashMapOf(
            "id" to practice.id,
            "groupId" to practice.groupId,
            "date" to Timestamp(Date(practice.dateMillis)),
            "startTime" to practice.startTime,
            "endTime" to practice.endTime,
            "location" to practice.location,
            "driverToUid" to (practice.driverToUid ?: ""),
            "driverFromUid" to (practice.driverFromUid ?: ""),
            "createdAt" to FieldValue.serverTimestamp()
        ).apply {
            practice.createdBy?.let { put("createdBy", it) }
        }
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .document(practice.id)
            .set(data)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to create practice"
                SignalManager.getInstance().toast(message, SignalManager.ToastLength.LONG)
                callback(false, message)
            }
    }

    fun getPracticeById(practiceId: String, callback: (Practice?, String?) -> Unit) {
        if (practiceId.isBlank()) {
            callback(null, "Invalid practice id")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .document(practiceId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    documentToPractice(doc)?.let { callback(it, null) }
                        ?: callback(null, "Invalid practice data")
                } else {
                    callback(null, "Practice not found")
                }
            }
            .addOnFailureListener { e -> callback(null, e.message ?: "Failed to load practice") }
    }

    /**
     * Practices for a group in a date range (inclusive). Use week start/end day at midnight.
     * Results ordered by date; caller may sort by startTime per day.
     */
    fun getPracticesForWeek(
        groupId: String,
        weekStartMillis: Long,
        weekEndMillis: Long,
        callback: (List<Practice>, String?) -> Unit
    ) {
        if (groupId.isBlank()) {
            callback(emptyList(), null)
            return
        }
        val startTimestamp = Timestamp(Date(weekStartMillis))
        val endTimestamp = Timestamp(Date(weekEndMillis))
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .whereEqualTo("groupId", groupId)
            .whereGreaterThanOrEqualTo("date", startTimestamp)
            .whereLessThanOrEqualTo("date", endTimestamp)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot?.documents?.mapNotNull { documentToPractice(it) }
                    ?.sortedWith(compareBy({ it.dateMillis }, { it.startTime })) ?: emptyList()
                callback(list, null)
            }
            .addOnFailureListener { e ->
                val message = e.message ?: "Failed to load practices"
                callback(emptyList(), message)
            }
    }

    fun updatePractice(
        practiceId: String,
        startTime: String? = null,
        endTime: String? = null,
        location: String? = null,
        driverToUid: String? = null,
        driverFromUid: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        if (practiceId.isBlank()) {
            callback(false, "Invalid practice id")
            return
        }
        val updates = mutableMapOf<String, Any>()
        startTime?.let { updates["startTime"] = it }
        endTime?.let { updates["endTime"] = it }
        location?.let { updates["location"] = it }
        driverToUid?.let { updates["driverToUid"] = it }
        driverFromUid?.let { updates["driverFromUid"] = it }
        if (updates.isEmpty()) {
            callback(true, null)
            return
        }
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .document(practiceId)
            .update(updates)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to update practice")
            }
    }

    private fun documentToPractice(doc: com.google.firebase.firestore.DocumentSnapshot): Practice? {
        val id = doc.getString("id") ?: doc.id
        val groupId = doc.getString("groupId") ?: return null
        val dateMillis = doc.getTimestamp("date")?.toDate()?.time ?: return null
        val startTime = doc.getString("startTime") ?: return null
        val endTime = doc.getString("endTime") ?: return null
        val location = doc.getString("location") ?: return null
        val driverToUid = doc.getString("driverToUid")?.takeIf { it.isNotEmpty() }
        val driverFromUid = doc.getString("driverFromUid")?.takeIf { it.isNotEmpty() }
        val createdBy = doc.getString("createdBy")
        val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
        return Practice(
            id,
            groupId,
            dateMillis,
            startTime,
            endTime,
            location,
            driverToUid,
            driverFromUid,
            createdBy,
            createdAt
        )
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
        val data = hashMapOf(
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
