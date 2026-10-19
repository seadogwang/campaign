package com.company.campaign.segment.reference;

/**
 * The closed set of outcomes of one hard-deletion attempt on a referenced version.
 *
 * <p>The three cases are kept apart because they demand different reactions: a successful deletion releases
 * storage, a refused deletion means a published dependent still holds the version and nothing was removed,
 * and an unknown target means the request named something the retention store never held. Collapsing them
 * would make "we did not delete it" indistinguishable from "there was nothing to delete".
 */
public enum HardDeletionOutcome {

    /** The exact version's immutable bytes were removed and its availability is now {@code DELETED}. */
    DELETED,

    /** The deletion was refused because at least one retention hold is still active; nothing changed. */
    REJECTED_ACTIVE_HOLD,

    /** The deletion was refused because the host retention policy keeps unheld referenced versions alive. */
    RETAINED_BY_POLICY,

    /** The retention store never held this exact version, so there was nothing to delete. */
    UNKNOWN_TARGET
}