package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable Campaign member enrollment: the durable MVP re-entry boundary.
 *
 * <p>Its uniqueness is {@code (tenantId, campaignId, memberId)} across every published version, so one member
 * may enroll in one Campaign at most once — publishing a newer version of a Campaign does not re-open its
 * enrollment. An existing row is a deterministic exclusion, never an error and never a second Participant.
 *
 * <p>It records which Execution and which frozen full definition first enrolled the member, so the exclusion is
 * attributable rather than merely asserted. {@link #toString()} prints no member, tenant or Campaign value.
 */
public record CampaignMemberEnrollment(
        String formatVersion,
        String tenantId,
        String campaignId,
        String memberId,
        String executionId,
        String fullDefinitionId,
        long publicationRevision,
        Instant enrolledAt,
        ContentHash enrollmentHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignMemberEnrollment {
        if (!RuntimeVersions.ENROLLMENT_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(memberId, "memberId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        RuntimeSupport.requirePositive(publicationRevision, "publicationRevision");
        Objects.requireNonNull(enrolledAt, "enrolledAt");
        Objects.requireNonNull(enrollmentHash, "enrollmentHash");
        if (!enrollmentHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, campaignId, memberId, executionId, fullDefinitionId,
                publicationRevision, enrolledAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "enrollmentHash");
        }
    }

    /** Builds one Campaign member enrollment and stamps it with its own canonical hash. */
    public static CampaignMemberEnrollment of(
            String tenantId,
            String campaignId,
            String memberId,
            String executionId,
            String fullDefinitionId,
            long publicationRevision,
            Instant enrolledAt) {
        return new CampaignMemberEnrollment(
                RuntimeVersions.ENROLLMENT_FORMAT_VERSION,
                tenantId, campaignId, memberId, executionId, fullDefinitionId, publicationRevision, enrolledAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.ENROLLMENT_FORMAT_VERSION, tenantId, campaignId, memberId, executionId,
                        fullDefinitionId, publicationRevision, enrolledAt)));
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String campaignId,
            String memberId,
            String executionId,
            String fullDefinitionId,
            long publicationRevision,
            Instant enrolledAt) {
        return new Material(formatVersion, tenantId, campaignId, memberId, executionId, fullDefinitionId,
                publicationRevision, enrolledAt);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "CampaignMemberEnrollment[publicationRevision=" + publicationRevision
                + ", enrolledAt=" + enrolledAt
                + ", enrollmentHash=" + enrollmentHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String campaignId,
            String memberId,
            String executionId,
            String fullDefinitionId,
            long publicationRevision,
            Instant enrolledAt) {
    }
}