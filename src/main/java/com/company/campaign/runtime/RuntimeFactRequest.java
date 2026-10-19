package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The complete, bounded request one Runtime Condition node hands to the host fact port.
 *
 * <p>It carries only exact identities and the predicate's own frozen shape: the tenant, Campaign, Execution,
 * Participant and frozen node the fact is read for, the T19 fact code, the T19 value kind the predicate
 * declares, and the bounded timeout the host must answer within.
 *
 * <p>There is deliberately no field for raw SQL, an unrestricted filter, a member list, a trusted scope or
 * security fact, and no field naming a data source: a host that needs more than these facts to answer is a host
 * this contract cannot serve, and the node stops rather than widening the request.
 */
public record RuntimeFactRequest(
        String formatVersion,
        String tenantId,
        String campaignId,
        String executionId,
        String participantId,
        String nodeLogicalId,
        String factCode,
        String valueType,
        long timeoutMillis,
        ContentHash requestHash
) {

    /** The largest timeout a fact read may declare, in milliseconds. */
    public static final long MAX_TIMEOUT_MILLIS = 5_000L;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RuntimeFactRequest {
        if (!RuntimeVersions.FACT_REQUEST_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        if (!RuntimeTypedValues.isFactCode(factCode)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "factCode");
        }
        if (valueType == null || !FlowConditionPack.VALUE_TYPES.contains(valueType)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "valueType");
        }
        if (timeoutMillis < 1 || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "timeoutMillis");
        }
        Objects.requireNonNull(requestHash, "requestHash");
        if (!requestHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, campaignId, executionId, participantId, nodeLogicalId, factCode,
                valueType, timeoutMillis)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "requestHash");
        }
    }

    /** Builds one bounded fact request and stamps it with its own canonical hash. */
    public static RuntimeFactRequest of(
            String tenantId,
            String campaignId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            String factCode,
            String valueType,
            long timeoutMillis) {
        return new RuntimeFactRequest(
                RuntimeVersions.FACT_REQUEST_FORMAT_VERSION,
                tenantId, campaignId, executionId, participantId, nodeLogicalId, factCode, valueType,
                timeoutMillis,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.FACT_REQUEST_FORMAT_VERSION, tenantId, campaignId, executionId,
                        participantId, nodeLogicalId, factCode, valueType, timeoutMillis)));
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String campaignId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            String factCode,
            String valueType,
            long timeoutMillis) {
        return new Material(formatVersion, tenantId, campaignId, executionId, participantId, nodeLogicalId,
                factCode, valueType, timeoutMillis);
    }

    /** Redacted diagnostics: no tenant, Campaign, Execution or fact identity is printed. */
    @Override
    public String toString() {
        return "RuntimeFactRequest[valueType=" + valueType
                + ", timeoutMillis=" + timeoutMillis
                + ", requestHash=" + requestHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String campaignId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            String factCode,
            String valueType,
            long timeoutMillis) {
    }
}