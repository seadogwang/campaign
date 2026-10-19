package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * The immutable active schedule projection of one Campaign's currently published version.
 *
 * <p>It names the exact full definition and base definition checksum it belongs to, the exact Journey and
 * Schedule hashes, its own frozen {@link CompiledSchedule}, its {@link ScheduleStatus} and a monotonically
 * increasing {@link #stateRevision()}. Every mutation — a pause, a resume, a supersession by a newer published
 * version — produces a new immutable revision rather than editing this one, so a compare-and-set on the
 * revision is the whole of the concurrency control.
 *
 * <p>Construction re-proves {@link #stateHash()} against its own canonical material, so a state whose bytes do
 * not match its own hash is not representable. {@link #toString()} prints no owner value.
 */
public record CampaignScheduleState(
        String formatVersion,
        String campaignId,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        long stateRevision,
        ScheduleStatus status,
        String fullDefinitionId,
        ContentHash fullDefinitionHash,
        ContentHash baseDefinitionChecksum,
        ContentHash journeyHash,
        CompiledSchedule schedule,
        Instant publishedAt,
        Instant updatedAt,
        ContentHash stateHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignScheduleState {
        if (!FullPublishVersions.SCHEDULE_STATE_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.SCHEDULE_STATE_INVALID, "formatVersion");
        }
        FullPublishSupport.requireIdentifier(campaignId, "campaignId");
        FullPublishSupport.requireIdentifier(tenantId, "tenantId");
        FullPublishSupport.requireIdentifier(programId, "programId");
        FullPublishSupport.requireIdentifier(userId, "userId");
        FullPublishSupport.requireIdentifier(dataScopeId, "dataScopeId");
        FullPublishSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        if (stateRevision < 1) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.SCHEDULE_STATE_INVALID, "stateRevision");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(baseDefinitionChecksum, "baseDefinitionChecksum");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(stateHash, "stateHash");
        if (!stateHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, campaignId, tenantId, programId, userId, dataScopeId, stateRevision, status,
                fullDefinitionId, fullDefinitionHash, baseDefinitionChecksum, journeyHash,
                schedule.scheduleHash(), publishedAt, updatedAt)))) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.SCHEDULE_STATE_INVALID, "stateHash");
        }
    }

    /** Builds one active schedule projection and stamps it with its own canonical hash. */
    public static CampaignScheduleState of(
            String campaignId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            long stateRevision,
            ScheduleStatus status,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            CompiledSchedule schedule,
            Instant publishedAt,
            Instant updatedAt) {
        Objects.requireNonNull(schedule, "schedule");
        return new CampaignScheduleState(
                FullPublishVersions.SCHEDULE_STATE_FORMAT_VERSION,
                campaignId, tenantId, programId, userId, dataScopeId, stateRevision, status,
                fullDefinitionId, fullDefinitionHash, baseDefinitionChecksum, journeyHash, schedule,
                publishedAt, updatedAt,
                CANONICAL_JSON.hash(material(
                        FullPublishVersions.SCHEDULE_STATE_FORMAT_VERSION,
                        campaignId, tenantId, programId, userId, dataScopeId, stateRevision, status,
                        fullDefinitionId, fullDefinitionHash, baseDefinitionChecksum, journeyHash,
                        schedule.scheduleHash(), publishedAt, updatedAt)));
    }

    /** The exact compiled schedule hash of the active version. */
    public ContentHash scheduleHash() {
        return schedule.scheduleHash();
    }

    /** True when the active schedule may commit new due occurrences. */
    public boolean planningAllowed() {
        return status.planningAllowed();
    }

    /** The same projection at the next revision with the given status, used for one compare-and-set write. */
    public CampaignScheduleState withStatus(ScheduleStatus replaced, Instant at) {
        return new CampaignScheduleState(
                formatVersion, campaignId, tenantId, programId, userId, dataScopeId, stateRevision + 1L,
                replaced, fullDefinitionId, fullDefinitionHash, baseDefinitionChecksum, journeyHash, schedule,
                publishedAt, at,
                CANONICAL_JSON.hash(material(
                        formatVersion, campaignId, tenantId, programId, userId, dataScopeId, stateRevision + 1L,
                        replaced, fullDefinitionId, fullDefinitionHash, baseDefinitionChecksum, journeyHash,
                        schedule.scheduleHash(), publishedAt, at)));
    }

    private static Material material(
            String formatVersion,
            String campaignId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            long stateRevision,
            ScheduleStatus status,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant publishedAt,
            Instant updatedAt) {
        return new Material(formatVersion, campaignId, tenantId, programId, userId, dataScopeId, stateRevision,
                status, fullDefinitionId, fullDefinitionHash, baseDefinitionChecksum, journeyHash, scheduleHash,
                publishedAt, updatedAt);
    }

    /** Redacted diagnostics: no owner value and no Campaign value is printed. */
    @Override
    public String toString() {
        return "CampaignScheduleState[stateRevision=" + stateRevision
                + ", status=" + status
                + ", scheduleHash=" + scheduleHash()
                + ", updatedAt=" + updatedAt
                + ", stateHash=" + stateHash + ']';
    }

    /** Package-private hash material; the frozen schedule is bound by its own hash. */
    record Material(
            String formatVersion,
            String campaignId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            long stateRevision,
            ScheduleStatus status,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant publishedAt,
            Instant updatedAt) {
    }
}