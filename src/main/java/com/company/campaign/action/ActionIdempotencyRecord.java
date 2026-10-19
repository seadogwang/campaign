package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable ActionIdempotencyRecord: the durable binding of one {@code (tenantId, idempotencyKey)} pair to
 * exactly one request hash, one frozen-material hash and one ActionExecution identity.
 *
 * <p>It is the record a duplicate key is decided against. The same key with the same frozen material returns
 * the original action and never calls a Provider; the same key with anything else is a bounded conflict forever,
 * because the record is immutable and never overwritten. It is deliberately retention-protected: archive never
 * discards it, so a redelivered occurrence or a late reconciliation can always be answered.
 *
 * <p>{@link #toString()} prints no member, tenant or Execution identity.
 */
public record ActionIdempotencyRecord(
        String formatVersion,
        String recordId,
        String tenantId,
        String idempotencyKey,
        String actionId,
        ContentHash frozenMaterialHash,
        ContentHash requestHash,
        Instant createdAt,
        ContentHash recordHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionIdempotencyRecord {
        if (!ActionVersions.ACTION_IDEMPOTENCY_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(recordId, "recordId");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        ActionSupport.requireIdentifier(actionId, "actionId");
        Objects.requireNonNull(frozenMaterialHash, "frozenMaterialHash");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(recordHash, "recordHash");
        if (!recordHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, recordId, tenantId, idempotencyKey, actionId, frozenMaterialHash, requestHash,
                createdAt)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "recordHash");
        }
    }

    /** Binds one business key to one action, request hash and frozen-material hash. */
    public static ActionIdempotencyRecord of(
            String tenantId,
            String idempotencyKey,
            String actionId,
            ContentHash frozenMaterialHash,
            ContentHash requestHash,
            Instant at) {
        Objects.requireNonNull(at, "at");
        String recordId = recordId(tenantId, idempotencyKey);
        return new ActionIdempotencyRecord(
                ActionVersions.ACTION_IDEMPOTENCY_FORMAT_VERSION, recordId, tenantId, idempotencyKey, actionId,
                frozenMaterialHash, requestHash, at,
                CANONICAL_JSON.hash(material(
                        ActionVersions.ACTION_IDEMPOTENCY_FORMAT_VERSION, recordId, tenantId, idempotencyKey,
                        actionId, frozenMaterialHash, requestHash, at)));
    }

    /** The one deterministic idempotency-record identity of one business key inside one tenant. */
    public static String recordId(String tenantId, String idempotencyKey) {
        return ActionSupport.derivedId("actkey", List.of(
                ActionVersions.IDEMPOTENCY_RECORD_ID_VERSION, tenantId, idempotencyKey));
    }

    private static Material material(
            String formatVersion,
            String recordId,
            String tenantId,
            String idempotencyKey,
            String actionId,
            ContentHash frozenMaterialHash,
            ContentHash requestHash,
            Instant createdAt) {
        return new Material(formatVersion, recordId, tenantId, idempotencyKey, actionId, frozenMaterialHash,
                requestHash, createdAt);
    }

    /** Redacted diagnostics: no member, tenant or Execution identity is printed. */
    @Override
    public String toString() {
        return "ActionIdempotencyRecord[recordId=" + recordId
                + ", createdAt=" + createdAt
                + ", recordHash=" + recordHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String recordId,
            String tenantId,
            String idempotencyKey,
            String actionId,
            ContentHash frozenMaterialHash,
            ContentHash requestHash,
            Instant createdAt) {
    }
}