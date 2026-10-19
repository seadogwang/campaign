package com.company.campaign.runtime;

import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * The complete, bounded request one Runtime audience materialization hands to the host.
 *
 * <p>It carries only exact frozen pins and bounded policy: the tenant and DataScope the query runs under, the
 * Campaign and frozen full definition, the frozen base Manifest hash, the exact audience Segment component
 * identity and payload hash the audience is defined by, the fixed {@code asOf} evaluation instant, the frozen
 * freshness policy and the declared recipient limit.
 *
 * <p>There is deliberately no field for raw SQL, a client-supplied member list, a trusted readiness
 * declaration, a mutable catalog pointer, an owner identity chosen by the caller or an unrestricted filter
 * expression. A host that needs more than these facts to answer is a host this contract cannot serve, and the
 * materialization fails closed rather than widening the request.
 */
public record AudienceMaterializationRequest(
        String formatVersion,
        String tenantId,
        String dataScopeId,
        String campaignId,
        String fullDefinitionId,
        ContentHash fullDefinitionHash,
        ContentHash manifestHash,
        String segmentLogicalId,
        String segmentCapabilityCode,
        String segmentContractVersion,
        ContentHash segmentPayloadHash,
        Instant asOf,
        long freshnessMaxAgeSeconds,
        long maxRecipients,
        ContentHash requestHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public AudienceMaterializationRequest {
        if (!RuntimeVersions.MATERIALIZATION_REQUEST_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(dataScopeId, "dataScopeId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        RuntimeSupport.requireIdentifier(segmentLogicalId, "segmentLogicalId");
        RuntimeSupport.requireIdentifier(segmentCapabilityCode, "segmentCapabilityCode");
        RuntimeSupport.requireIdentifier(segmentContractVersion, "segmentContractVersion");
        Objects.requireNonNull(segmentPayloadHash, "segmentPayloadHash");
        Objects.requireNonNull(asOf, "asOf");
        if (freshnessMaxAgeSeconds < RuntimeVersions.MIN_FRESHNESS_AGE.toSeconds()
                || freshnessMaxAgeSeconds > RuntimeVersions.MAX_FRESHNESS_AGE.toSeconds()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "freshnessMaxAgeSeconds");
        }
        if (maxRecipients < 1 || maxRecipients > GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_LIMIT_INVALID, "maxRecipients");
        }
        Objects.requireNonNull(requestHash, "requestHash");
        if (!requestHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, dataScopeId, campaignId, fullDefinitionId, fullDefinitionHash,
                manifestHash, segmentLogicalId, segmentCapabilityCode, segmentContractVersion,
                segmentPayloadHash, asOf, freshnessMaxAgeSeconds, maxRecipients)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "requestHash");
        }
    }

    /** Builds one audience materialization request and stamps it with its own canonical hash. */
    public static AudienceMaterializationRequest of(
            String tenantId,
            String dataScopeId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash manifestHash,
            String segmentLogicalId,
            String segmentCapabilityCode,
            String segmentContractVersion,
            ContentHash segmentPayloadHash,
            Instant asOf,
            long freshnessMaxAgeSeconds,
            long maxRecipients) {
        return new AudienceMaterializationRequest(
                RuntimeVersions.MATERIALIZATION_REQUEST_FORMAT_VERSION,
                tenantId, dataScopeId, campaignId, fullDefinitionId, fullDefinitionHash, manifestHash,
                segmentLogicalId, segmentCapabilityCode, segmentContractVersion, segmentPayloadHash, asOf,
                freshnessMaxAgeSeconds, maxRecipients,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.MATERIALIZATION_REQUEST_FORMAT_VERSION, tenantId, dataScopeId,
                        campaignId, fullDefinitionId, fullDefinitionHash, manifestHash, segmentLogicalId,
                        segmentCapabilityCode, segmentContractVersion, segmentPayloadHash, asOf,
                        freshnessMaxAgeSeconds, maxRecipients)));
    }

    /**
     * The limit this phase-A build actually enforces: the smaller of the frozen declaration and this build's
     * own structural maximum.
     *
     * <p>It is never used to truncate. A candidate count above it is an explicit over-limit failure, because
     * this build refuses to claim it materialized an audience it could not hold.
     */
    public long effectiveLimit() {
        return Math.min(maxRecipients, RuntimeVersions.MAX_AUDIENCE_MEMBERS);
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String dataScopeId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash manifestHash,
            String segmentLogicalId,
            String segmentCapabilityCode,
            String segmentContractVersion,
            ContentHash segmentPayloadHash,
            Instant asOf,
            long freshnessMaxAgeSeconds,
            long maxRecipients) {
        return new Material(formatVersion, tenantId, dataScopeId, campaignId, fullDefinitionId,
                fullDefinitionHash, manifestHash, segmentLogicalId, segmentCapabilityCode,
                segmentContractVersion, segmentPayloadHash, asOf, freshnessMaxAgeSeconds, maxRecipients);
    }

    /** Redacted diagnostics: no tenant, DataScope, Campaign or Segment identity is printed. */
    @Override
    public String toString() {
        return "AudienceMaterializationRequest[asOf=" + asOf
                + ", freshnessMaxAgeSeconds=" + freshnessMaxAgeSeconds
                + ", maxRecipients=" + maxRecipients
                + ", requestHash=" + requestHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String dataScopeId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash manifestHash,
            String segmentLogicalId,
            String segmentCapabilityCode,
            String segmentContractVersion,
            ContentHash segmentPayloadHash,
            Instant asOf,
            long freshnessMaxAgeSeconds,
            long maxRecipients) {
    }
}