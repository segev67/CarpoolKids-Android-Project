# Phase 4 Plan: Join Requests Screen, Approve/Decline/Block, Blocked Users

This document describes what to implement in Phase 4. **Do not start implementation until you are told to "forward to phase 4".**

---

## Current state (before Phase 4)

- **Join request flow (Phase 3):** User enters invite code → PENDING request is created. Duplicate and blocked checks exist. "My Requests" on Home shows the user’s own requests with real-time updates and team names.
- **Group tab:** Hub with Group details, Invite, **Join requests (0)** (hardcoded), Blocked users (PARENT only, placeholder). Tapping "Join requests" opens `JoinRequestsFragment` (placeholder with "Coming soon").
- **Models:** `JoinRequest` (id, groupId, requesterUid, status, createdAt; statuses PENDING/APPROVED/DECLINED/BLOCKED), `Group` (memberIds, blockedUids). Firestore has `join_requests` and `groups` with `blockedUids`.
- **FirestoreManager:** `createJoinRequest`, `hasPendingJoinRequest`, `listenToJoinRequestsForUser`, `getGroupsByIds`, `addMemberToGroup` (arrayUnion). No: update request status, listen by groupId, or update blockedUids.

---

## Phase 4 scope

### 1. Join Requests screen (Group tab → Join requests)

- **Screen:** Replace the placeholder `JoinRequestsFragment` with a real screen that shows **all join requests for the current group** (where `groupId` matches).
- **List:** Each row shows at least: requester identifier (e.g. email or "User" if no profile), status (PENDING / APPROVED / DECLINED / BLOCKED), and optionally time/date.
- **Actions (PARENT only):** For each **PENDING** request, show actions:
  - **Approve:** Set request status to APPROVED and add `requesterUid` to the group’s `memberIds` (use existing `addMemberToGroup` or equivalent). Do not remove the request document; keep it for history.
  - **Decline:** Set request status to DECLINED. No change to group members.
  - **Block:** Set request status to BLOCKED and add `requesterUid` to the group’s `blockedUids`. Do not add to `memberIds`.
- **Child / non-parent:** Can open the screen and see the list (and statuses) but **cannot** Approve/Decline/Block (hide or disable those actions).
- **Empty state:** If there are no join requests for this group, show a clear message (e.g. "No join requests") instead of an empty list.
- **Real-time:** The list should update in real time when a parent approves/declines/blocks (e.g. listener on join requests for this group).

### 2. Firestore & repo layer

- **Listen to join requests by group:** Add a way to listen to `join_requests` where `groupId == currentGroupId` (e.g. `listenToJoinRequestsForGroup(groupId, callback)` in FirestoreManager; return ListenerRegistration). Use a query that does not require a composite index if possible (e.g. only `whereEqualTo("groupId", groupId)`; sort in app if needed).
- **Update request status:** Add `updateJoinRequestStatus(requestId, status)` (or similar) that updates the `status` field of the join request document. Status one of APPROVED / DECLINED / BLOCKED.
- **Update group’s blocked list:** Add a way to add a uid to a group’s `blockedUids` (e.g. `addBlockedUid(groupId, uid)` using FieldValue.arrayUnion). Ensure Firestore rules allow this for group admins/parents if you have role checks.
- **Approve flow:** In repo or UI layer: update request to APPROVED, then call add-member (e.g. `addMemberToGroup(groupId, requesterUid)`). Run both; on failure, optionally show error and do not leave inconsistent state (e.g. if add member fails, consider reverting status or showing "Try again").

### 3. "Join requests (N)" count on Group tab

- **Source of N:** Count of **PENDING** join requests for the current group.
- **Where:** In `GroupFragment`, the label "Join requests" should show the live count, e.g. "Join requests (N)". Use the same listener as the Join Requests screen (or a dedicated count listener) so N updates in real time when requests are added or when a parent approves/declines/blocks.
- **Implementation options:** Either (a) have GroupFragment subscribe to join requests for the group and derive the PENDING count, or (b) have a small repository method that returns or listens to the PENDING count for a group. Prefer reusing one listener to avoid extra reads.

### 4. Blocked Users screen (PARENT only)

- **Screen:** Replace the placeholder `BlockedUsersFragment` with a real screen that lists users currently in the group’s `blockedUids`.
- **List:** Show something identifiable per blocked user (e.g. uid, or email/displayName if you have profiles). No need for a full profile UI if not already there.
- **Action:** **Unblock:** Remove the uid from the group’s `blockedUids` (e.g. `removeBlockedUid(groupId, uid)` with FieldValue.arrayRemove). After unblock, that user can request to join again (Phase 3 already allows non-blocked users to create a PENDING request).
- **Visibility:** This screen and the Group hub row are PARENT-only (already hidden for CHILD in `GroupFragment`).
- **Empty state:** If `blockedUids` is empty, show a message like "No blocked users."

### 5. Firestore rules (reminder)

- Ensure:
  - **join_requests:** Read for requests where `requesterUid == request.auth.uid` (for "My Requests") and also read for requests where the user is in the group (e.g. group’s memberIds contains request.auth.uid) so parents can see and act on requests. Write: create with requesterUid == auth.uid; update status (and possibly other fields) only by group members/admins if you enforce it.
  - **groups:** Update of `memberIds` (arrayUnion) and `blockedUids` (arrayUnion/arrayRemove) allowed for users who are in the group (or created the group) so that parents can approve and block/unblock.

### 6. Strings & UI details

- Add any missing strings (e.g. "Approve", "Decline", "Block", "Unblock", "No join requests", "No blocked users", "Join requests (N)" already exists).
- Keep existing styles and conventions; no full redesign. Use the same patterns as other list screens (e.g. RecyclerView + adapter, optional item layout for request row and blocked user row).

---

## Summary checklist (for when you implement)

1. **FirestoreManager:**  
   - `listenToJoinRequestsForGroup(groupId, callback)` → ListenerRegistration.  
   - `updateJoinRequestStatus(requestId, status)`.  
   - `addBlockedUid(groupId, uid)`, `removeBlockedUid(groupId, uid)`.

2. **GroupRepository (or equivalent):**  
   - Expose the above; optionally `approveJoinRequest(groupId, requestId, requesterUid, callback)` that updates status and adds member.

3. **JoinRequestsFragment:**  
   - Real UI: list of join requests for the group; for each PENDING request, show Approve / Decline / Block (PARENT only). Empty state. Real-time list via listener.

4. **GroupFragment:**  
   - Show "Join requests (N)" with live PENDING count (from same or dedicated listener).

5. **BlockedUsersFragment:**  
   - Real UI: list of blocked uids (with optional display name/email); Unblock action; empty state. PARENT only.

6. **Firestore rules:**  
   - Read join_requests for group context; allow parent to update status and update group’s memberIds/blockedUids.

7. **Strings:**  
   - Approve, Decline, Block, Unblock, No join requests, No blocked users (if not already present).

---

**When you are ready to implement, say "forward to phase 4" and the implementation can start.**
