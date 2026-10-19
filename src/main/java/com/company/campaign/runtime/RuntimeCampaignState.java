package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable host-owned Campaign runtime/schedule projection a Runtime worker rechecks.
 *
 * <p>It names the exact owner — tenant, program and Campaign — the activation status, the monotonic state
 * revision and the active full-definition hash, and it re-proves {@link #stateHash()} against its own canonical
 * material, so a state whose bytes do not match its own hash is not representable.
 *
 * <p>The active definition hash is recorded as evidence and is deliberately <em>not</em> required to equal an
 * existing Execution's frozen definition hash: a newer publication changes only future occurrence work, while
 * an Execution already running continues the exact version it started with. Ownership, by contrast, is
 * re-proved on every use. {@link #toString()} prints no owner value.
 */
public record RuntimeCampaignState(
        String formatVersion,
        String tenantId,
        String programId,
        String campaignId,
        ContentHash fullDefinitionHash,
        RuntimeCampaignStatus status,
        long stateRevision,
        Instant updatedAt,
        ContentHash stateHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RuntimeCampaignState {
        if (!RuntimeVersions.CAMPAIGN_STATE_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(programId, "programId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(status, "status");
        RuntimeSupport.requirePositive(stateRevision, "stateRevision");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(stateHash, "stateHash");
        if (!stateHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, programId, campaignId, fullDefinitionHash, status, stateRevision,
                updatedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "stateHash");
        }
    }

    /** Builds one Campaign runtime/schedule projection and stamps it with its own canonical hash. */
    public static RuntimeCampaignState of(
            String tenantId,
            String programId,
            String campaignId,
            ContentHash fullDefinitionHash,
            RuntimeCampaignStatus status,
            long stateRevision,
            Instant updatedAt) {
        return new RuntimeCampaignState(
                RuntimeVersions.CAMPAIGN_STATE_FORMAT_VERSION,
                tenantId, programId, campaignId, fullDefinitionHash, status, stateRevision, updatedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.CAMPAIGN_STATE_FORMAT_VERSION, tenantId, programId, campaignId,
                        fullDefinitionHash, status, stateRevision, updatedAt)));
    }

    /** True when this projection permits a new local node or external-action handoff to start. */
    public boolean mayStartNewWork() {
        return status.mayStartNewWork();
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String programId,
            String campaignId,
            ContentHash fullDefinitionHash,
            RuntimeCampaignStatus status,
            long stateRevision,
            Instant updatedAt) {
        return new Material(formatVersion, tenantId, programId, campaignId, fullDefinitionHash, status,
                stateRevision, updatedAt);
    }

    /** Redacted diagnostics: no owner value and no Campaign value is printed. */
    @Override
    public String toString() {
        return "RuntimeCampaignState[status=" + status
                + ", stateRevision=" + stateRevision
                + ", updatedAt=" + updatedAt
                + ", stateHash=" + stateHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String programId,
            String campaignId,
            ContentHash fullDefinitionHash,
            RuntimeCampaignStatus status,
            long stateRevision,
            Instant updatedAt) {
    }
}