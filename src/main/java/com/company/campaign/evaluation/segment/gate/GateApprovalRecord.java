package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The immutable approval evidence of one host-only Gate approval.
 *
 * <p>It binds the exact proposed-decision hash, the decision's own verdict, the complete PRODUCT/DATA/TEST
 * owner-approval quorum — every owner reference, role and explicit host-supplied time — and the
 * approval-record schema version. {@link #approvalHash()} covers the record with that field excluded, so the
 * approval itself is immutable evidence: changing any owner, role, time or the decision hash produces
 * different bytes and a different hash, and a stale or forged approval cannot be relabelled onto another
 * decision. The owner approvals are canonicalized into stable role order, so the same quorum listed in any
 * input order yields identical bytes.
 *
 * <p>An incomplete quorum is representable, because reporting it as a bounded failure is more useful than an
 * unconstructible value; {@link #hasCompleteQuorum()} names that condition and the readiness projection
 * refuses to activate on it. Duplicated roles or owner references are rejected as structurally malformed.
 *
 * <p>This record <em>is</em> the bounded audit evidence of an approval. Every field is a stable code, a hash,
 * an opaque owner reference or an explicit timestamp; no raw utterance, model output, SQL, member identifier,
 * credential, unrestricted Metadata or trusted tenant/user/DataScope fact can be represented here.
 */
public record GateApprovalRecord(
        String approvalSchemaVersion,
        ContentHash decisionHash,
        GateOutcome outcome,
        List<GateOwnerApproval> ownerApprovals,
        ContentHash approvalHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public GateApprovalRecord {
        if (!GateVersions.APPROVAL_SCHEMA_VERSION.equals(approvalSchemaVersion)) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "An approval record must declare the exact T14 approval contract version");
        }
        if (decisionHash == null || outcome == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "An approval record requires the decision hash and verdict");
        }
        if (ownerApprovals == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "An approval record must declare its owner approvals");
        }
        List<GateOwnerApproval> validated = new ArrayList<>(ownerApprovals.size());
        Set<GateOwnerRole> roles = new LinkedHashSet<>();
        Set<String> owners = new LinkedHashSet<>();
        for (GateOwnerApproval owner : ownerApprovals) {
            if (owner == null) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID, "An approval record contains a null owner approval");
            }
            if (!roles.add(owner.ownerRole()) || !owners.add(owner.ownerRef())) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID,
                        "An approval record names a duplicated owner role or owner reference");
            }
            validated.add(owner);
        }
        validated.sort((left, right) -> left.ownerRole().compareTo(right.ownerRole()));
        ownerApprovals = List.copyOf(validated);
        if (approvalHash != null) {
            // A compact constructor must not read the instance fields: they are assigned only when the body
            // returns. The expected hash is therefore computed from the validated parameters themselves.
            ContentHash expected = CANONICAL_JSON.hash(
                    new Material(approvalSchemaVersion, decisionHash, outcome, ownerApprovals));
            if (!approvalHash.equals(expected)) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID,
                        "An approval record must match its declared content hash");
            }
        }
    }

    /** Builds an approval record from a complete or incomplete owner quorum and stamps it with its hash. */
    public static GateApprovalRecord of(
            ContentHash decisionHash, GateOutcome outcome, List<GateOwnerApproval> ownerApprovals) {
        GateApprovalRecord withoutHash = new GateApprovalRecord(
                GateVersions.APPROVAL_SCHEMA_VERSION, decisionHash, outcome, ownerApprovals, null);
        return new GateApprovalRecord(
                withoutHash.approvalSchemaVersion, withoutHash.decisionHash, withoutHash.outcome,
                withoutHash.ownerApprovals, CANONICAL_JSON.hash(withoutHash.material()));
    }

    /** True only when exactly one approval exists for each required owner role. */
    public boolean hasCompleteQuorum() {
        return hasCompleteQuorum(ownerApprovals);
    }

    /** True only when the given owner approvals name exactly one owner for each required role. */
    public static boolean hasCompleteQuorum(List<GateOwnerApproval> ownerApprovals) {
        if (ownerApprovals == null || ownerApprovals.size() != GateOwnerRole.values().length) {
            return false;
        }
        Set<GateOwnerRole> roles = new LinkedHashSet<>();
        for (GateOwnerApproval owner : ownerApprovals) {
            if (owner == null || !roles.add(owner.ownerRole())) {
                return false;
            }
        }
        return roles.size() == GateOwnerRole.values().length;
    }

    /** The canonical bytes this approval hash covers: the record without its own hash component. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /** The canonical approval hash with the hash field excluded. */
    public ContentHash recomputeHash() {
        return CANONICAL_JSON.hash(material());
    }

    private Object material() {
        return new Material(approvalSchemaVersion, decisionHash, outcome, ownerApprovals);
    }

    /** The self-excluded hash material: every component except the approval hash itself. */
    private record Material(
            String approvalSchemaVersion,
            ContentHash decisionHash,
            GateOutcome outcome,
            List<GateOwnerApproval> ownerApprovals
    ) {
    }
}