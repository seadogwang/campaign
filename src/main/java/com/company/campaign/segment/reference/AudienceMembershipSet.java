package com.company.campaign.segment.reference;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;

/**
 * The exact member set of one immutable Audience Version at one effective DataScope.
 *
 * <p>It is a local, fixture-only value: it exists so a deterministic preview can evaluate a reference without
 * a database, and it is bound to the exact version pin it came from. Member identifiers are held as opaque
 * strings in a deterministic order purely so set operations are exact; they are never logged, prompted,
 * returned in a public result or persisted in an audit record, and the hash — not the identifiers — is what
 * any diagnostic or evidence artifact may carry.
 *
 * <p>Carrying the version pin alongside the members is what stops a stale membership set from being silently
 * used for a different version: a set can only ever be built for the version it was read from.
 */
public record AudienceMembershipSet(
        AudienceVersionRef version,
        List<String> memberIds,
        ContentHash memberSetHash
) {

    public AudienceMembershipSet {
        if (version == null) {
            throw ReferenceException.invalid("An audience membership set requires the exact version it came from");
        }
        if (memberIds == null) {
            throw ReferenceException.invalid("An audience membership set requires its member identifiers");
        }
        for (String memberId : memberIds) {
            if (memberId == null || memberId.isBlank()) {
                throw ReferenceException.invalid("A member identifier must be a non-blank opaque string");
            }
        }
        // Copying only after the element check keeps a null element a stable coded failure rather than a raw
        // NullPointerException from the copy itself.
        memberIds = List.copyOf(memberIds);
        if (memberSetHash == null) {
            throw ReferenceException.invalid("An audience membership set requires the hash of its exact members");
        }
    }

    /** True when the given member is in this exact set. */
    public boolean contains(String memberId) {
        return memberId != null && memberIds.contains(memberId);
    }

    @Override
    public String toString() {
        // The hash and the count are safe to print; the identifiers deliberately are not.
        return "AudienceMembershipSet[version=" + version.identityKey()
                + ", size=" + memberIds.size()
                + ", memberSetHash=" + memberSetHash
                + ']';
    }
}