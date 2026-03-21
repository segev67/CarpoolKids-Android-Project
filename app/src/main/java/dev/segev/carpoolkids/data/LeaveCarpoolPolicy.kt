package dev.segev.carpoolkids.data

import dev.segev.carpoolkids.model.Group
import dev.segev.carpoolkids.model.UserProfile
import dev.segev.carpoolkids.utilities.Constants

/**
 * Centralized rules for leaving a carpool as a parent.
 * A carpool must always have at least one parent while any child remains in the group.
 */
object LeaveCarpoolPolicy {

    sealed class ParentLeaveOutcome {
        /** Last parent cannot leave while children remain; no Firestore writes. */
        data object BlockedLastParentWithChildren : ParentLeaveOutcome()

        /** Last parent, no children: delete carpool doc and related data (sole member). */
        data object AllowedDissolveInactive : ParentLeaveOutcome()

        /** At least one other parent remains after this leave. */
        data object AllowedLeaveNormal : ParentLeaveOutcome()
    }

    /**
     * @param group current group document
     * @param profilesByUid profiles for [group.memberIds] (missing uids are ignored for role checks)
     * @param leavingUid uid of the parent attempting to leave
     */
    fun evaluateParentLeave(
        group: Group,
        profilesByUid: Map<String, UserProfile>,
        leavingUid: String
    ): ParentLeaveOutcome {
        if (leavingUid !in group.memberIds) return ParentLeaveOutcome.AllowedLeaveNormal

        val parentMemberIds = group.memberIds.filter { uid ->
            profilesByUid[uid]?.role == Constants.UserRole.PARENT
        }
        val remainingParentsAfterLeave = parentMemberIds.filter { it != leavingUid }
        if (remainingParentsAfterLeave.isNotEmpty()) {
            return ParentLeaveOutcome.AllowedLeaveNormal
        }

        val childCount = group.memberIds.count { uid ->
            profilesByUid[uid]?.role == Constants.UserRole.CHILD
        }

        if (childCount > 0) {
            return ParentLeaveOutcome.BlockedLastParentWithChildren
        }

        return ParentLeaveOutcome.AllowedDissolveInactive
    }
}
