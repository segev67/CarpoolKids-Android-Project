package dev.segev.carpoolkids.utilities

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Transaction
import dev.segev.carpoolkids.model.CarpoolRoute
import dev.segev.carpoolkids.model.DriveRequest
import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.model.JoinRequest
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.model.RouteStop
import dev.segev.carpoolkids.model.UserProfile
import java.lang.ref.WeakReference
import java.util.Date
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        val homeLat = doc.getDouble("homeLat")
        val homeLng = doc.getDouble("homeLng")
        val homeAddressLabel = doc.getString("homeAddressLabel")?.takeIf { it.isNotEmpty() }
        return UserProfile(
            uid,
            doc.getString("email"),
            doc.getString("displayName"),
            role,
            photoUrl,
            createdAt,
            parentUids,
            childUids,
            homeLat,
            homeLng,
            homeAddressLabel
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
            "blockedUids" to (group.blockedUids.ifEmpty { emptyList() }),
            "inactive" to group.inactive
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

    /** Removes uid from memberIds (self-leave). */
    fun removeMemberFromGroup(groupId: String, uid: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank() || uid.isBlank()) {
            callback(false, "Invalid group or user")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_GROUPS)
            .document(groupId)
            .update("memberIds", FieldValue.arrayRemove(uid))
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to leave group") }
    }

    /**
     * Deletes all documents in [collection] with `groupId` == [groupId], in batches of 500.
     */
    private fun deleteDocumentsWhereGroupId(
        collection: String,
        groupId: String,
        onDone: (Boolean, String?) -> Unit
    ) {
        db.collection(collection)
            .whereEqualTo("groupId", groupId)
            .get()
            .addOnSuccessListener { snapshot ->
                val docs = snapshot.documents
                if (docs.isEmpty()) {
                    onDone(true, null)
                    return@addOnSuccessListener
                }
                deleteDocumentsInChunks(docs, onDone)
            }
            .addOnFailureListener { e -> onDone(false, e.message ?: "Query failed") }
    }

    private fun deleteDocumentsInChunks(
        docs: List<com.google.firebase.firestore.DocumentSnapshot>,
        onDone: (Boolean, String?) -> Unit
    ) {
        val chunks = docs.chunked(500)
        var index = 0
        fun commitNext() {
            if (index >= chunks.size) {
                onDone(true, null)
                return
            }
            val batch = db.batch()
            chunks[index].forEach { batch.delete(it.reference) }
            batch.commit()
                .addOnSuccessListener {
                    index++
                    commitNext()
                }
                .addOnFailureListener { e -> onDone(false, e.message ?: "Batch delete failed") }
        }
        commitNext()
    }

    /**
     * Last member left: delete join_requests, practices, drive_requests, link_codes for this group, then delete the group document.
     */
    fun deleteCarpoolAndRelatedData(groupId: String, callback: (Boolean, String?) -> Unit) {
        if (groupId.isBlank()) {
            callback(false, "Invalid group")
            return
        }
        deleteDocumentsWhereGroupId(Constants.Firestore.COLLECTION_JOIN_REQUESTS, groupId) step1@{ ok1, e1 ->
            if (!ok1) {
                callback(false, e1)
                return@step1
            }
            deleteDocumentsWhereGroupId(Constants.Firestore.COLLECTION_PRACTICES, groupId) step2@{ ok2, e2 ->
                if (!ok2) {
                    callback(false, e2)
                    return@step2
                }
                deleteDocumentsWhereGroupId(Constants.Firestore.COLLECTION_DRIVE_REQUESTS, groupId) step3@{ ok3, e3 ->
                    if (!ok3) {
                        callback(false, e3)
                        return@step3
                    }
                    deleteDocumentsWhereGroupId(Constants.Firestore.COLLECTION_LINK_CODES, groupId) step4@{ ok4, e4 ->
                        if (!ok4) {
                            callback(false, e4)
                            return@step4
                        }
                        deleteDocumentsWhereGroupId(Constants.Firestore.COLLECTION_CARPOOL_ROUTES, groupId) step5@{ ok5, e5 ->
                            if (!ok5) {
                                callback(false, e5)
                                return@step5
                            }
                            db.collection(Constants.Firestore.COLLECTION_GROUPS)
                                .document(groupId)
                                .delete()
                                .addOnSuccessListener { callback(true, null) }
                                .addOnFailureListener { e ->
                                    callback(false, e.message ?: "Failed to delete group")
                                }
                        }
                    }
                }
            }
        }
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
        val inactive = doc.getBoolean("inactive") ?: false
        return Group(id, name, inviteCode, memberIds, createdBy, blockedUids, inactive)
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
        val data = hashMapOf<String, Any>(
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
            practice.locationLat?.let { put("locationLat", it) }
            practice.locationLng?.let { put("locationLng", it) }
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
     * Phase 7 — single-practice realtime listener. Used by the route screen so participants /
     * driver / canceled changes from other users propagate within ~1 s. Caller must remove the
     * returned registration in onDestroyView.
     */
    fun listenToPractice(
        practiceId: String,
        callback: (Practice?) -> Unit
    ): ListenerRegistration {
        return db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .document(practiceId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    callback(null)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    callback(null)
                    return@addSnapshotListener
                }
                callback(documentToPractice(snapshot))
            }
    }

    /**
     * Practices in [groupId] where [uid] is assigned as TO or FROM driver (schedule document).
     * Used when leaving carpool: driver may exist without a matching drive_requests.acceptedByUid row.
     */
    fun getPracticesWhereUserIsDriver(
        groupId: String,
        uid: String,
        callback: (List<Practice>, String?) -> Unit
    ) {
        if (groupId.isBlank() || uid.isBlank()) {
            callback(emptyList(), "Invalid group or user")
            return
        }
        val col = db.collection(Constants.Firestore.COLLECTION_PRACTICES)
        col.whereEqualTo("groupId", groupId)
            .whereEqualTo("driverToUid", uid)
            .get()
            .addOnSuccessListener { snap1 ->
                val list1 = snap1.documents.mapNotNull { documentToPractice(it) }
                col.whereEqualTo("groupId", groupId)
                    .whereEqualTo("driverFromUid", uid)
                    .get()
                    .addOnSuccessListener { snap2 ->
                        val list2 = snap2.documents.mapNotNull { documentToPractice(it) }
                        val byId = LinkedHashMap<String, Practice>()
                        for (p in list1 + list2) {
                            byId[p.id] = p
                        }
                        callback(byId.values.toList(), null)
                    }
                    .addOnFailureListener { e ->
                        callback(list1, e.message ?: "Failed to load practices (from driver)")
                    }
            }
            .addOnFailureListener { e ->
                callback(emptyList(), e.message ?: "Failed to load practices (to driver)")
            }
    }

    /**
     * Fetches multiple practices by ID. Returns a map practiceId -> Practice for found documents.
     */
    fun getPracticesByIds(ids: List<String>, callback: (Map<String, Practice>) -> Unit) {
        val distinct = ids.distinct().take(30)
        if (distinct.isEmpty()) {
            callback(emptyMap())
            return
        }
        val result = ConcurrentHashMap<String, Practice>()
        val pending = AtomicInteger(distinct.size)
        for (id in distinct) {
            db.collection(Constants.Firestore.COLLECTION_PRACTICES)
                .document(id)
                .get()
                .addOnSuccessListener { doc ->
                    documentToPractice(doc)?.let { result[it.id] = it }
                    if (pending.decrementAndGet() == 0) callback(HashMap(result))
                }
                .addOnFailureListener {
                    if (pending.decrementAndGet() == 0) callback(HashMap(result))
                }
        }
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

    /**
     * Real-time listener for practices in a group for the given week (same query as getPracticesForWeek).
     * Callback receives sorted list and optional error; remove the returned ListenerRegistration when done.
     */
    fun listenToPracticesForWeek(
        groupId: String,
        weekStartMillis: Long,
        weekEndMillis: Long,
        callback: (List<Practice>, String?) -> Unit
    ): ListenerRegistration {
        if (groupId.isBlank()) {
            callback(emptyList(), null)
            return ListenerRegistration { }
        }
        val startTimestamp = Timestamp(Date(weekStartMillis))
        val endTimestamp = Timestamp(Date(weekEndMillis))
        return db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .whereEqualTo("groupId", groupId)
            .whereGreaterThanOrEqualTo("date", startTimestamp)
            .whereLessThanOrEqualTo("date", endTimestamp)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    android.util.Log.w("FirestoreManager", "listenToPracticesForWeek failed: ${e.message}", e)
                    callback(emptyList(), e.message ?: "Failed to load practices")
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { documentToPractice(it) }
                    ?.sortedWith(compareBy({ it.dateMillis }, { it.startTime })) ?: emptyList()
                callback(list, null)
            }
    }

    /**
     * Phase 2 — Set or update a user's home coordinates. Used by the home-address map picker.
     * Rules: users may write only their own document (existing rule).
     */
    fun updateHomeAddress(
        uid: String,
        lat: Double,
        lng: Double,
        label: String?,
        callback: (Boolean, String?) -> Unit
    ) {
        if (uid.isBlank()) {
            callback(false, "Invalid user")
            return
        }
        val updates = hashMapOf<String, Any>(
            "homeLat" to lat,
            "homeLng" to lng
        )
        label?.takeIf { it.isNotBlank() }?.let { updates["homeAddressLabel"] = it }
        db.collection(Constants.Firestore.COLLECTION_USERS)
            .document(uid)
            .update(updates)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to save home address")
            }
    }

    /**
     * Phase 3 — Add the calling user to a practice's [Practice.participantUids].
     * Idempotent (arrayUnion). UI hides the button when the user is already a rider.
     */
    fun joinPractice(
        practiceId: String,
        uid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (practiceId.isBlank() || uid.isBlank()) {
            callback(false, "Invalid practice or user")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .document(practiceId)
            .update("participantUids", FieldValue.arrayUnion(uid))
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to join practice")
            }
    }

    /**
     * Phase 3 — Remove the calling user from a practice's [Practice.participantUids] and, in the same
     * batch, mark any APPROVED [drive_requests] they had for this practice as CANCELED. Atomic so the
     * UI never shows a child as still riding with an active drive request after they tap Leave.
     *
     * [groupId] is required by the drive_requests `read` rule — the rule references
     * `resource.data.groupId`, so the query must include it as a filter or Firestore rejects the
     * whole query with PERMISSION_DENIED before evaluating any individual doc.
     */
    fun leavePractice(
        practiceId: String,
        groupId: String,
        uid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (practiceId.isBlank() || groupId.isBlank() || uid.isBlank()) {
            callback(false, "Invalid practice, group or user")
            return
        }
        val practiceRef = db.collection(Constants.Firestore.COLLECTION_PRACTICES).document(practiceId)
        db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("practiceId", practiceId)
            .whereEqualTo("requesterUid", uid)
            .whereEqualTo("status", DriveRequest.STATUS_APPROVED)
            .get()
            .addOnSuccessListener { snap ->
                val batch = db.batch()
                batch.update(practiceRef, "participantUids", FieldValue.arrayRemove(uid))
                for (doc in snap.documents) {
                    batch.update(doc.reference, "status", DriveRequest.STATUS_CANCELED)
                }
                batch.commit()
                    .addOnSuccessListener { callback(true, null) }
                    .addOnFailureListener { e ->
                        callback(false, e.message ?: "Failed to leave practice")
                    }
            }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to query drive requests")
            }
    }

    /**
     * Phase 3 — leaveGroup cascade. Removes [uid] from `participantUids` on every FUTURE practice in
     * the group (past practices keep history), then removes the uid from `memberIds`. If the cleanup
     * query itself fails, we fall through to just removing from the group (best-effort).
     */
    fun leaveGroupAndCleanupParticipants(
        groupId: String,
        uid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (groupId.isBlank() || uid.isBlank()) {
            callback(false, "Invalid group or user")
            return
        }
        val nowTimestamp = Timestamp(Date(System.currentTimeMillis()))
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .whereEqualTo("groupId", groupId)
            .whereGreaterThan("date", nowTimestamp)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    removeMemberFromGroup(groupId, uid, callback)
                    return@addOnSuccessListener
                }
                val batch = db.batch()
                for (doc in snap.documents) {
                    batch.update(doc.reference, "participantUids", FieldValue.arrayRemove(uid))
                }
                batch.commit()
                    .addOnSuccessListener { removeMemberFromGroup(groupId, uid, callback) }
                    .addOnFailureListener {
                        // Cleanup failed — still let the user leave the group. Orphans are tolerated.
                        removeMemberFromGroup(groupId, uid, callback)
                    }
            }
            .addOnFailureListener {
                removeMemberFromGroup(groupId, uid, callback)
            }
    }

    /**
     * Phase 2 — Set or update a practice's geographic coordinates.
     * When [addressLabel] is non-blank, it's also written into the practice's `location` text
     * field in the same atomic update (used by the map picker reverse-geocode path).
     * Rules: any group member may update; UI is gated to parents.
     */
    fun updateLocationCoords(
        practiceId: String,
        lat: Double,
        lng: Double,
        addressLabel: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        if (practiceId.isBlank()) {
            callback(false, "Invalid practice id")
            return
        }
        val updates = hashMapOf<String, Any>(
            "locationLat" to lat,
            "locationLng" to lng
        )
        addressLabel?.takeIf { it.isNotBlank() }?.let { updates["location"] = it }
        db.collection(Constants.Firestore.COLLECTION_PRACTICES)
            .document(practiceId)
            .update(updates)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to save coordinates")
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

    /**
     * Cancel practice: (1) update practice with [Practice.canceled] and clear drivers (PARENT-only in rules);
     * (2) set PENDING/APPROVED [drive_requests] to [DriveRequest.STATUS_CANCELED] (allowed when practice is already canceled).
     * Two phases so drive_request updates do not require the same batch as practice (avoids PERMISSION_DENIED on mixed rules).
     * If practice is already canceled, only step (2) runs (recovery for partial failures).
     */
    fun cancelPractice(
        practiceId: String,
        canceledByUid: String,
        cancelReason: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        if (practiceId.isBlank() || canceledByUid.isBlank()) {
            callback(false, "Invalid practice or user")
            return
        }
        val practiceRef = db.collection(Constants.Firestore.COLLECTION_PRACTICES).document(practiceId)
        practiceRef.get()
            .addOnSuccessListener { doc ->
                if (doc == null || !doc.exists()) {
                    callback(false, "Practice not found")
                    return@addOnSuccessListener
                }
                val practice = documentToPractice(doc) ?: run {
                    callback(false, "Invalid practice data")
                    return@addOnSuccessListener
                }
                // Must filter by groupId: rules tie reads to group membership; practiceId-only queries are rejected (PERMISSION_DENIED).
                db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
                    .whereEqualTo("groupId", practice.groupId)
                    .whereEqualTo("practiceId", practiceId)
                    .get()
                    .addOnSuccessListener { snap ->
                        val toClose = snap.documents.filter { d ->
                            val s = d.getString("status") ?: ""
                            s == DriveRequest.STATUS_PENDING || s == DriveRequest.STATUS_APPROVED
                        }
                        val driveOps = toClose.map { q ->
                            q.reference to mapOf("status" to DriveRequest.STATUS_CANCELED)
                        }

                        fun mapFirestoreFailure(e: Exception, fallback: String): String = when (e) {
                            is FirebaseFirestoreException ->
                                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                    "PERMISSION_DENIED"
                                } else {
                                    e.message ?: fallback
                                }
                            else -> e.message ?: fallback
                        }

                        fun commitDriveRequestBatches() {
                            if (driveOps.isEmpty()) {
                                callback(true, null)
                                return
                            }
                            var index = 0
                            fun commitNext() {
                                if (index >= driveOps.size) {
                                    callback(true, null)
                                    return
                                }
                                val batch = db.batch()
                                var count = 0
                                while (index < driveOps.size && count < 500) {
                                    val (ref, updates) = driveOps[index]
                                    batch.update(ref, updates)
                                    index++
                                    count++
                                }
                                batch.commit()
                                    .addOnSuccessListener { commitNext() }
                                    .addOnFailureListener { e ->
                                        callback(false, mapFirestoreFailure(e, "Failed to close drive requests"))
                                    }
                            }
                            commitNext()
                        }

                        if (practice.canceled) {
                            commitDriveRequestBatches()
                            return@addOnSuccessListener
                        }

                        val practiceUpdates = hashMapOf<String, Any>(
                            "canceled" to true,
                            "canceledAt" to FieldValue.serverTimestamp(),
                            "canceledByUid" to canceledByUid,
                            "driverToUid" to "",
                            "driverFromUid" to ""
                        )
                        cancelReason?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            practiceUpdates["cancelReason"] = it
                        }
                        practiceRef.update(practiceUpdates)
                            .addOnSuccessListener { commitDriveRequestBatches() }
                            .addOnFailureListener { e ->
                                callback(false, mapFirestoreFailure(e, "Failed to cancel practice"))
                            }
                    }
                    .addOnFailureListener { e ->
                        callback(false, e.message ?: "Failed to load drive requests")
                    }
            }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to load practice")
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
        val canceled = doc.getBoolean("canceled") == true
        val canceledAt = doc.getTimestamp("canceledAt")?.toDate()?.time
        val canceledByUid = doc.getString("canceledByUid")
        val cancelReason = doc.getString("cancelReason")
        val locationLat = doc.getDouble("locationLat")
        val locationLng = doc.getDouble("locationLng")
        val participantUids = (doc.get("participantUids") as? List<*>)
            ?.mapNotNull { it as? String } ?: emptyList()
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
            createdAt,
            canceled,
            canceledAt,
            canceledByUid,
            cancelReason,
            locationLat,
            locationLng,
            participantUids
        )
    }

    // ---------- Drive requests (Drive requests tab) ----------

    fun createDriveRequest(request: DriveRequest, callback: (Boolean, String?) -> Unit) {
        val data = hashMapOf(
            "id" to request.id,
            "groupId" to request.groupId,
            "practiceId" to request.practiceId,
            "practiceDateMillis" to request.practiceDateMillis,
            "direction" to request.direction,
            "requesterUid" to request.requesterUid,
            "status" to request.status,
            "createdAt" to FieldValue.serverTimestamp()
        )
        if (request.acceptedByUid != null) data["acceptedByUid"] = request.acceptedByUid
        db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .document(request.id)
            .set(data)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to create drive request") }
    }

    /**
     * Check if there is a PENDING drive request for the given (groupId, practiceId, direction).
     * Used to enforce one pending request per (practice, direction).
     */
    fun getPendingDriveRequest(
        groupId: String,
        practiceId: String,
        direction: String,
        callback: (DriveRequest?) -> Unit
    ) {
        if (groupId.isBlank() || practiceId.isBlank() || direction.isBlank()) {
            callback(null)
            return
        }
        db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("practiceId", practiceId)
            .whereEqualTo("direction", direction)
            .whereEqualTo("status", DriveRequest.STATUS_PENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                val doc = snapshot?.documents?.firstOrNull()
                callback(doc?.let { documentToDriveRequest(it) })
            }
            .addOnFailureListener { callback(null) }
    }

    /**
     * Accept a PENDING drive request (parent only). Uses a transaction: if slot is still free and request
     * still PENDING, sets practice driver and request status to APPROVED. First parent to commit wins.
     */
    fun acceptDriveRequest(
        request: DriveRequest,
        acceptedByUid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val practiceRef = db.collection(Constants.Firestore.COLLECTION_PRACTICES).document(request.practiceId)
        val requestRef = db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS).document(request.id)
        val requesterRef = db.collection(Constants.Firestore.COLLECTION_USERS).document(request.requesterUid)
        db.runTransaction { transaction: Transaction ->
            // All transaction reads must happen before any writes.
            val practiceSnap = transaction.get(practiceRef)
            val requestSnap = transaction.get(requestRef)
            val requesterSnap = transaction.get(requesterRef)
            if (practiceSnap.getBoolean("canceled") == true) {
                throw RuntimeException("Practice was canceled")
            }
            val reqStatus = requestSnap.getString("status") ?: ""
            val driverTo = practiceSnap.getString("driverToUid")?.takeIf { it.isNotEmpty() }
            val driverFrom = practiceSnap.getString("driverFromUid")?.takeIf { it.isNotEmpty() }
            val slotTaken = when (request.direction) {
                DriveRequest.DIRECTION_TO -> !driverTo.isNullOrEmpty()
                DriveRequest.DIRECTION_FROM -> !driverFrom.isNullOrEmpty()
                else -> true
            }
            if (reqStatus != DriveRequest.STATUS_PENDING || slotTaken) {
                throw RuntimeException("Slot already taken or request no longer pending")
            }
            // Phase 3: same transaction also arrayUnions the requester into participantUids so the route
            // roster stays in sync with "this child is riding." Defense-in-depth: only children become
            // riders, even if an older client created the request from a parent account.
            val driverField = if (request.direction == DriveRequest.DIRECTION_TO) "driverToUid" else "driverFromUid"
            val requesterIsChild = requesterSnap.getString("role") == Constants.UserRole.CHILD
            val practiceUpdates = mutableMapOf<String, Any>(driverField to acceptedByUid)
            if (requesterIsChild) {
                practiceUpdates["participantUids"] = FieldValue.arrayUnion(request.requesterUid)
            }
            transaction.update(practiceRef, practiceUpdates)
            transaction.update(
                requestRef,
                "status", DriveRequest.STATUS_APPROVED,
                "acceptedByUid", acceptedByUid
            )
        }
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e ->
                val msg = e.message ?: "Failed to accept"
                callback(false, msg)
            }
    }

    /** Decline a PENDING drive request (parent only). Sets status to DECLINED and declinedByUid. */
    fun declineDriveRequest(
        request: DriveRequest,
        declinedByUid: String,
        callback: (Boolean, String?) -> Unit
    ) {
        db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .document(request.id)
            .update(
                mapOf(
                    "status" to DriveRequest.STATUS_DECLINED,
                    "declinedByUid" to declinedByUid
                )
            )
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to decline") }
    }

    /**
     * Drive requests by driver (acceptedByUid).
     * Used for "Leave carpool": we need all future approved drives for that parent.
     */
    fun getAcceptedDriveRequestsForGroup(
        groupId: String,
        acceptedByUid: String,
        callback: (List<DriveRequest>, String?) -> Unit
    ) {
        if (groupId.isBlank() || acceptedByUid.isBlank()) {
            callback(emptyList(), "Invalid group or user")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("acceptedByUid", acceptedByUid)
            .limit(100)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot?.documents
                    ?.mapNotNull { documentToDriveRequest(it) }
                    ?.sortedWith(compareBy<DriveRequest> { it.practiceDateMillis }.thenBy { it.createdAt ?: 0L })
                    ?: emptyList()
                callback(list, null)
            }
            .addOnFailureListener { e ->
                callback(emptyList(), e.message ?: "Failed to load drive requests")
            }
    }

    /**
     * Drive requests for a specific requester in a group.
     * Used for child "Leave carpool" (cancel your own future rides).
     */
    fun getDriveRequestsForGroupAndRequester(
        groupId: String,
        requesterUid: String,
        callback: (List<DriveRequest>, String?) -> Unit
    ) {
        if (groupId.isBlank() || requesterUid.isBlank()) {
            callback(emptyList(), "Invalid group or user")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("requesterUid", requesterUid)
            .limit(100)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot?.documents
                    ?.mapNotNull { documentToDriveRequest(it) }
                    ?.sortedWith(compareBy<DriveRequest> { it.practiceDateMillis }.thenBy { it.createdAt ?: 0L })
                    ?: emptyList()
                callback(list, null)
            }
            .addOnFailureListener { e ->
                callback(emptyList(), e.message ?: "Failed to load drive requests")
            }
    }

    /** Real-time listener for drive requests of a group. All group members see the same list. */
    fun listenToDriveRequestsForGroup(groupId: String, callback: (List<DriveRequest>) -> Unit): ListenerRegistration {
        if (groupId.isBlank()) {
            callback(emptyList())
            return ListenerRegistration { }
        }
        return db.collection(Constants.Firestore.COLLECTION_DRIVE_REQUESTS)
            .whereEqualTo("groupId", groupId)
            .limit(100)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    android.util.Log.w("FirestoreManager", "listenToDriveRequestsForGroup failed: ${e.message}", e)
                    callback(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { documentToDriveRequest(it) }
                    ?.sortedWith(compareBy<DriveRequest> { it.practiceDateMillis }.thenBy { it.createdAt ?: 0L }) ?: emptyList()
                callback(list)
            }
    }

    private fun documentToDriveRequest(doc: com.google.firebase.firestore.DocumentSnapshot): DriveRequest? {
        val id = doc.getString("id") ?: doc.id
        val groupId = doc.getString("groupId") ?: return null
        val practiceId = doc.getString("practiceId") ?: return null
        val practiceDateMillis = (doc.get("practiceDateMillis") as? Number)?.toLong() ?: return null
        val direction = doc.getString("direction") ?: return null
        val requesterUid = doc.getString("requesterUid") ?: return null
        val status = doc.getString("status") ?: return null
        val acceptedByUid = doc.getString("acceptedByUid")?.takeIf { it.isNotEmpty() }
        val declinedByUid = doc.getString("declinedByUid")?.takeIf { it.isNotEmpty() }
        val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
        return DriveRequest(
            id,
            groupId,
            practiceId,
            practiceDateMillis,
            direction,
            requesterUid,
            status,
            acceptedByUid,
            declinedByUid,
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

    // ---------- Carpool routes (Phase 5) ----------

    /**
     * Idempotent write: creates the carpool_routes doc, or overwrites it when the driver regenerates.
     * The doc id is deterministic — `{practiceId}_{direction}` — so a regenerate replaces the prior
     * route cleanly, which is exactly what we want.
     */
    fun createOrUpdateCarpoolRoute(route: CarpoolRoute, callback: (Boolean, String?) -> Unit) {
        val data: HashMap<String, Any?> = hashMapOf(
            "id" to route.id,
            "groupId" to route.groupId,
            "practiceId" to route.practiceId,
            "direction" to route.direction,
            "driverUid" to route.driverUid,
            "driverHomeLat" to route.driverHomeLat,
            "driverHomeLng" to route.driverHomeLng,
            "trainingLat" to route.trainingLat,
            "trainingLng" to route.trainingLng,
            "trainingStartTime" to route.trainingStartTime,
            "trainingEndTime" to route.trainingEndTime,
            "practiceDateMillis" to route.practiceDateMillis,
            "stops" to route.stops.map { stopToMap(it) },
            "recommendedDepartureMillis" to route.recommendedDepartureMillis,
            "totalDurationSec" to route.totalDurationSec,
            "totalDistanceMeters" to route.totalDistanceMeters,
            "polyline" to route.polyline,
            "polylinePrecision" to route.polylinePrecision,
            "status" to route.status,
            "failureReason" to route.failureReason,
            "generatedAt" to (route.generatedAt?.let { Timestamp(Date(it)) }
                ?: FieldValue.serverTimestamp()),
            "generatedByUid" to route.generatedByUid,
            "missingAddressUids" to route.missingAddressUids,
            "aiSummary" to route.aiSummary,
            "aiSummaryGeneratedAt" to route.aiSummaryGeneratedAt?.let { Timestamp(Date(it)) }
        )
        db.collection(Constants.Firestore.COLLECTION_CARPOOL_ROUTES)
            .document(route.id)
            .set(data)
            .addOnSuccessListener { callback(true, null) }
            .addOnFailureListener { e -> callback(false, e.message ?: "Failed to save route") }
    }

    fun getCarpoolRoute(routeId: String, callback: (CarpoolRoute?, String?) -> Unit) {
        if (routeId.isBlank()) {
            callback(null, "Invalid route id")
            return
        }
        db.collection(Constants.Firestore.COLLECTION_CARPOOL_ROUTES)
            .document(routeId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc == null || !doc.exists()) {
                    callback(null, null)
                } else {
                    callback(documentToCarpoolRoute(doc), null)
                }
            }
            .addOnFailureListener { e -> callback(null, e.message ?: "Failed to load route") }
    }

    fun listenToCarpoolRoute(routeId: String, callback: (CarpoolRoute?) -> Unit): ListenerRegistration {
        return db.collection(Constants.Firestore.COLLECTION_CARPOOL_ROUTES)
            .document(routeId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    callback(null)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    callback(null)
                    return@addSnapshotListener
                }
                callback(documentToCarpoolRoute(snapshot))
            }
    }

    private fun stopToMap(stop: RouteStop): Map<String, Any?> = mapOf(
        "sequence" to stop.sequence,
        "passengerUid" to stop.passengerUid,
        "passengerName" to stop.passengerName,
        "lat" to stop.lat,
        "lng" to stop.lng,
        "etaMillis" to stop.etaMillis,
        "legDurationSec" to stop.legDurationSec,
        "legDistanceMeters" to stop.legDistanceMeters
    )

    private fun documentToCarpoolRoute(doc: com.google.firebase.firestore.DocumentSnapshot): CarpoolRoute? {
        if (!doc.exists()) return null
        val id = doc.getString("id") ?: doc.id
        val groupId = doc.getString("groupId") ?: return null
        val practiceId = doc.getString("practiceId") ?: return null
        val direction = doc.getString("direction") ?: return null
        val driverUid = doc.getString("driverUid") ?: return null
        val driverHomeLat = doc.getDouble("driverHomeLat") ?: return null
        val driverHomeLng = doc.getDouble("driverHomeLng") ?: return null
        val trainingLat = doc.getDouble("trainingLat") ?: return null
        val trainingLng = doc.getDouble("trainingLng") ?: return null
        val trainingStartTime = doc.getString("trainingStartTime") ?: ""
        val trainingEndTime = doc.getString("trainingEndTime") ?: ""
        val practiceDateMillis = doc.getLong("practiceDateMillis") ?: 0L
        val stops = (doc.get("stops") as? List<*>)?.mapNotNull { mapToStop(it) } ?: emptyList()
        val recommendedDepartureMillis = doc.getLong("recommendedDepartureMillis") ?: 0L
        val totalDurationSec = (doc.getLong("totalDurationSec") ?: 0L).toInt()
        val totalDistanceMeters = (doc.getLong("totalDistanceMeters") ?: 0L).toInt()
        val polyline = doc.getString("polyline") ?: ""
        val polylinePrecision = (doc.getLong("polylinePrecision") ?: 5L).toInt()
        val status = doc.getString("status") ?: return null
        val failureReason = doc.getString("failureReason")?.takeIf { it.isNotEmpty() }
        val generatedAt = doc.getTimestamp("generatedAt")?.toDate()?.time
        val generatedByUid = doc.getString("generatedByUid")?.takeIf { it.isNotEmpty() }
        val missingAddressUids = (doc.get("missingAddressUids") as? List<*>)
            ?.mapNotNull { it as? String } ?: emptyList()
        val aiSummary = doc.getString("aiSummary")?.takeIf { it.isNotEmpty() }
        val aiSummaryGeneratedAt = doc.getTimestamp("aiSummaryGeneratedAt")?.toDate()?.time
        return CarpoolRoute(
            id = id,
            groupId = groupId,
            practiceId = practiceId,
            direction = direction,
            driverUid = driverUid,
            driverHomeLat = driverHomeLat,
            driverHomeLng = driverHomeLng,
            trainingLat = trainingLat,
            trainingLng = trainingLng,
            trainingStartTime = trainingStartTime,
            trainingEndTime = trainingEndTime,
            practiceDateMillis = practiceDateMillis,
            stops = stops,
            recommendedDepartureMillis = recommendedDepartureMillis,
            totalDurationSec = totalDurationSec,
            totalDistanceMeters = totalDistanceMeters,
            polyline = polyline,
            polylinePrecision = polylinePrecision,
            status = status,
            failureReason = failureReason,
            generatedAt = generatedAt,
            generatedByUid = generatedByUid,
            missingAddressUids = missingAddressUids,
            aiSummary = aiSummary,
            aiSummaryGeneratedAt = aiSummaryGeneratedAt
        )
    }

    private fun mapToStop(raw: Any?): RouteStop? {
        val map = raw as? Map<*, *> ?: return null
        val sequence = (map["sequence"] as? Number)?.toInt() ?: return null
        val passengerUid = map["passengerUid"] as? String ?: return null
        val passengerName = map["passengerName"] as? String ?: ""
        val lat = (map["lat"] as? Number)?.toDouble() ?: return null
        val lng = (map["lng"] as? Number)?.toDouble() ?: return null
        val etaMillis = (map["etaMillis"] as? Number)?.toLong() ?: 0L
        val legDurationSec = (map["legDurationSec"] as? Number)?.toInt() ?: 0
        val legDistanceMeters = (map["legDistanceMeters"] as? Number)?.toInt() ?: 0
        return RouteStop(
            sequence = sequence,
            passengerUid = passengerUid,
            passengerName = passengerName,
            lat = lat,
            lng = lng,
            etaMillis = etaMillis,
            legDurationSec = legDurationSec,
            legDistanceMeters = legDistanceMeters
        )
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
