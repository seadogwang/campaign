package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The complete, bounded answer one host audience materialization returns.
 *
 * <p>Exactly one outcome, {@link AudienceMaterializationOutcome#COMPLETE}, may carry a member set, and only
 * then does the set claim to be the whole audience: a non-complete outcome carries no members at all, so a
 * silently truncated set is not representable. The result always states the exact candidate count it observed,
 * the tenant the evidence belongs to, the exact observed instant and watermark, the freshness policy it was
 * answered under and a hash of the evidence body.
 *
 * <p>The record enforces only structural shape. Whether the evidence agrees with itself — whether the distinct
 * member set really has the count it claims, whether the watermark precedes the observation, whether the
 * evidence belongs to the requesting tenant, whether {@code asOf} is inside the freshness window — is decided
 * by the consumer, which fails the Execution closed rather than trusting the answer.
 */
public record AudienceMaterializationResult(
        String formatVersion,
        AudienceMaterializationOutcome outcome,
        String evidenceTenantId,
        long candidateCount,
        List<String> members,
        Instant observedAt,
        Instant watermark,
        long freshnessMaxAgeSeconds,
        ContentHash evidenceHash,
        ContentHash resultHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public AudienceMaterializationResult {
        if (!RuntimeVersions.MATERIALIZATION_RESULT_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        Objects.requireNonNull(outcome, "outcome");
        RuntimeSupport.requireIdentifier(evidenceTenantId, "evidenceTenantId");
        if (candidateCount < 0 || candidateCount > RuntimeVersions.MAX_AUDIENCE_MEMBERS) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, "candidateCount");
        }
        members = RuntimeSupport.immutable(members, RuntimeVersions.MAX_AUDIENCE_MEMBERS, "members");
        for (String member : members) {
            RuntimeSupport.requireIdentifier(member, "member");
        }
        if (!outcome.isComplete() && !members.isEmpty()) {
            // Only a complete outcome may carry a member set; a failure never returns a partial one.
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID, "members");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(watermark, "watermark");
        if (freshnessMaxAgeSeconds < RuntimeVersions.MIN_FRESHNESS_AGE.toSeconds()
                || freshnessMaxAgeSeconds > RuntimeVersions.MAX_FRESHNESS_AGE.toSeconds()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "freshnessMaxAgeSeconds");
        }
        Objects.requireNonNull(evidenceHash, "evidenceHash");
        Objects.requireNonNull(resultHash, "resultHash");
        if (!resultHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, outcome, evidenceTenantId, candidateCount, members, observedAt, watermark,
                freshnessMaxAgeSeconds, evidenceHash)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "resultHash");
        }
    }

    /** Builds one audience materialization result and stamps it with its own canonical hash. */
    public static AudienceMaterializationResult of(
            AudienceMaterializationOutcome outcome,
            String evidenceTenantId,
            long candidateCount,
            List<String> members,
            Instant observedAt,
            Instant watermark,
            long freshnessMaxAgeSeconds,
            ContentHash evidenceHash) {
        Objects.requireNonNull(outcome, "outcome");
        List<String> bounded = outcome.isComplete() ? members : List.of();
        return new AudienceMaterializationResult(
                RuntimeVersions.MATERIALIZATION_RESULT_FORMAT_VERSION,
                outcome, evidenceTenantId, candidateCount, bounded, observedAt, watermark,
                freshnessMaxAgeSeconds, evidenceHash,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.MATERIALIZATION_RESULT_FORMAT_VERSION, outcome, evidenceTenantId,
                        candidateCount, bounded, observedAt, watermark, freshnessMaxAgeSeconds, evidenceHash)));
    }

    /** A bounded failure answer that observed no member set at all. */
    public static AudienceMaterializationResult failed(
            AudienceMaterializationOutcome outcome,
            String evidenceTenantId,
            Instant observedAt,
            Instant watermark,
            long freshnessMaxAgeSeconds,
            ContentHash evidenceHash) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.isComplete()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "outcome");
        }
        return of(outcome, evidenceTenantId, 0, List.of(), observedAt, watermark, freshnessMaxAgeSeconds,
                evidenceHash);
    }

    private static Material material(
            String formatVersion,
            AudienceMaterializationOutcome outcome,
            String evidenceTenantId,
            long candidateCount,
            List<String> members,
            Instant observedAt,
            Instant watermark,
            long freshnessMaxAgeSeconds,
            ContentHash evidenceHash) {
        return new Material(formatVersion, outcome, evidenceTenantId, candidateCount, members, observedAt,
                watermark, freshnessMaxAgeSeconds, evidenceHash);
    }

    /** Redacted diagnostics: no tenant and no member identity is printed. */
    @Override
    public String toString() {
        return "AudienceMaterializationResult[outcome=" + outcome
                + ", candidateCount=" + candidateCount
                + ", members=" + members.size()
                + ", observedAt=" + observedAt
                + ", watermark=" + watermark
                + ", resultHash=" + resultHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            AudienceMaterializationOutcome outcome,
            String evidenceTenantId,
            long candidateCount,
            List<String> members,
            Instant observedAt,
            Instant watermark,
            long freshnessMaxAgeSeconds,
            ContentHash evidenceHash) {
    }
}