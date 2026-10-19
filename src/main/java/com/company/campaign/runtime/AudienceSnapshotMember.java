package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable audience snapshot member: the fact that one member belonged to one exact Execution's audience.
 *
 * <p>It carries the tenant, Execution and member identities plus the immutable membership evidence — the hash
 * of the host evidence the member was accepted from and the observed instant that evidence carried. It carries
 * no name, no contact detail, no attribute value and no profile: a snapshot member is an identity and a
 * provenance hash, never a profile copy.
 *
 * <p>Rows are written only for an accepted, complete, within-limit audience. A stale, failed, refused or
 * over-limit materialization writes none of them.
 */
public record AudienceSnapshotMember(
        String formatVersion,
        String tenantId,
        String executionId,
        String memberId,
        ContentHash evidenceHash,
        Instant observedAt,
        ContentHash memberHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public AudienceSnapshotMember {
        if (!RuntimeVersions.AUDIENCE_MEMBER_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(memberId, "memberId");
        Objects.requireNonNull(evidenceHash, "evidenceHash");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(memberHash, "memberHash");
        if (!memberHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, executionId, memberId, evidenceHash, observedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "memberHash");
        }
    }

    /** Builds one audience snapshot member and stamps it with its own canonical hash. */
    public static AudienceSnapshotMember of(
            String tenantId,
            String executionId,
            String memberId,
            ContentHash evidenceHash,
            Instant observedAt) {
        return new AudienceSnapshotMember(
                RuntimeVersions.AUDIENCE_MEMBER_FORMAT_VERSION,
                tenantId, executionId, memberId, evidenceHash, observedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.AUDIENCE_MEMBER_FORMAT_VERSION, tenantId, executionId, memberId,
                        evidenceHash, observedAt)));
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String executionId,
            String memberId,
            ContentHash evidenceHash,
            Instant observedAt) {
        return new Material(formatVersion, tenantId, executionId, memberId, evidenceHash, observedAt);
    }

    /** Redacted diagnostics: no tenant, Execution or member identifier is printed. */
    @Override
    public String toString() {
        return "AudienceSnapshotMember[observedAt=" + observedAt
                + ", evidenceHash=" + evidenceHash
                + ", memberHash=" + memberHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String executionId,
            String memberId,
            ContentHash evidenceHash,
            Instant observedAt) {
    }
}