package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One immutable, hash-bound action request: every exact frozen behavioral parameter one external action rests
 * on, resolved once from the frozen definition and never from a mutable catalog, a raw client field, a template
 * body, a reward secret or a Provider URL.
 *
 * <p>It pins the owner and Journey identity (tenant, Execution, Participant and node), the action kind, the
 * exact frozen component the node names — its stable logical identity, capability code, contract version, the
 * canonical hash of its normalized payload and the canonical hash of its resolved catalog references — and the
 * exact frozen referenced catalog material: the message channel, or the reward's logical identity, version,
 * content hash, quantity, currency and total face value. It also pins the host-resolved opaque recipient target
 * evidence and any provider-routing pin.
 *
 * <p>Two hashes are derived from that material and both are frozen into the record. {@link #frozenMaterialHash()}
 * covers only the behavioral parameters a frozen definition determines, so a duplicate business key is decided
 * by comparing frozen material alone — a host that re-resolves the same recipient differently can never turn a
 * true duplicate into a false conflict. {@link #requestHash()} additionally covers the request identity and the
 * host-resolved recipient target evidence, so the exact request one Provider was asked to apply is provable
 * from the persisted record alone.
 *
 * <p>{@link #toString()} prints no member value, no recipient reference, no template body, no reward secret and
 * no Provider routing value.
 */
public record ActionRequest(
        String formatVersion,
        String requestId,
        ActionKind actionKind,
        String idempotencyKey,
        String tenantId,
        String executionId,
        String participantId,
        String nodeLogicalId,
        String componentLogicalId,
        String capabilityCode,
        String contractVersion,
        ContentHash componentPayloadHash,
        ContentHash catalogReferenceHash,
        String channelCode,
        String templateLogicalId,
        String templateVersionId,
        ContentHash templateContentHash,
        String rewardLogicalId,
        String rewardVersionId,
        ContentHash rewardContentHash,
        Integer rewardQuantity,
        String rewardCurrency,
        BigDecimal rewardFaceValue,
        String recipientRef,
        ContentHash recipientEvidenceHash,
        String providerRoutingPin,
        ContentHash frozenMaterialHash,
        ContentHash requestHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionRequest {
        if (!ActionVersions.ACTION_REQUEST_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(requestId, "requestId");
        Objects.requireNonNull(actionKind, "actionKind");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        ActionSupport.requireIdentifier(participantId, "participantId");
        ActionSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        ActionSupport.requireIdentifier(componentLogicalId, "componentLogicalId");
        ActionSupport.requireIdentifier(capabilityCode, "capabilityCode");
        ActionSupport.requireIdentifier(contractVersion, "contractVersion");
        Objects.requireNonNull(componentPayloadHash, "componentPayloadHash");
        ActionSupport.requireOptionalIdentifier(channelCode, "channelCode");
        ActionSupport.requireOptionalIdentifier(templateLogicalId, "templateLogicalId");
        ActionSupport.requireOptionalIdentifier(templateVersionId, "templateVersionId");
        ActionSupport.requireOptionalIdentifier(rewardLogicalId, "rewardLogicalId");
        ActionSupport.requireOptionalIdentifier(rewardVersionId, "rewardVersionId");
        if (rewardQuantity != null && (rewardQuantity < 1 || rewardQuantity > ActionVersions.MAX_REWARD_QUANTITY)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "rewardQuantity");
        }
        if (rewardCurrency != null) {
            ActionSupport.requireCurrency(rewardCurrency, "rewardCurrency");
        }
        if (rewardFaceValue != null) {
            rewardFaceValue = ActionSupport.requireAmount(rewardFaceValue, "rewardFaceValue");
        }
        ActionSupport.requireOptionalIdentifier(recipientRef, "recipientRef");
        ActionSupport.requireOptionalIdentifier(providerRoutingPin, "providerRoutingPin");
        requireKindShape(
                actionKind, catalogReferenceHash, channelCode, templateLogicalId, templateVersionId,
                templateContentHash, rewardLogicalId, rewardVersionId, rewardContentHash, rewardQuantity,
                rewardCurrency, rewardFaceValue);
        Objects.requireNonNull(frozenMaterialHash, "frozenMaterialHash");
        Objects.requireNonNull(requestHash, "requestHash");
        FrozenMaterial expectedFrozenMaterial = new FrozenMaterial(
                formatVersion, actionKind, idempotencyKey, tenantId, executionId, participantId,
                nodeLogicalId, componentLogicalId, capabilityCode, contractVersion, componentPayloadHash,
                catalogReferenceHash, channelCode, templateLogicalId, templateVersionId, templateContentHash,
                rewardLogicalId, rewardVersionId, rewardContentHash, rewardQuantity, rewardCurrency,
                rewardFaceValue, providerRoutingPin);
        if (!frozenMaterialHash.equals(CANONICAL_JSON.hash(expectedFrozenMaterial))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_REQUEST_HASH_MISMATCH, "frozenMaterialHash");
        }
        if (!requestHash.equals(CANONICAL_JSON.hash(
                new RequestMaterial(expectedFrozenMaterial, requestId, recipientRef, recipientEvidenceHash)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_REQUEST_HASH_MISMATCH, "requestHash");
        }
    }

    /** Builds one immutable action request and stamps it with both of its canonical hashes. */
    public static ActionRequest of(
            ActionKind actionKind,
            String idempotencyKey,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            String componentLogicalId,
            String capabilityCode,
            String contractVersion,
            ContentHash componentPayloadHash,
            ContentHash catalogReferenceHash,
            String channelCode,
            String templateLogicalId,
            String templateVersionId,
            ContentHash templateContentHash,
            String rewardLogicalId,
            String rewardVersionId,
            ContentHash rewardContentHash,
            Integer rewardQuantity,
            String rewardCurrency,
            BigDecimal rewardFaceValue,
            String recipientRef,
            ContentHash recipientEvidenceHash,
            String providerRoutingPin) {
        if (rewardFaceValue != null) {
            rewardFaceValue = ActionSupport.requireAmount(rewardFaceValue, "rewardFaceValue");
        }
        FrozenMaterial frozen = new FrozenMaterial(
                ActionVersions.ACTION_REQUEST_FORMAT_VERSION, actionKind, idempotencyKey, tenantId, executionId,
                participantId, nodeLogicalId, componentLogicalId, capabilityCode, contractVersion,
                componentPayloadHash, catalogReferenceHash, channelCode, templateLogicalId, templateVersionId,
                templateContentHash, rewardLogicalId, rewardVersionId, rewardContentHash, rewardQuantity,
                rewardCurrency, rewardFaceValue, providerRoutingPin);
        ContentHash frozenHash = CANONICAL_JSON.hash(frozen);
        String requestId = ActionSupport.derivedId("actreq", List.of(
                ActionVersions.ACTION_REQUEST_FORMAT_VERSION, frozenHash));
        return new ActionRequest(
                ActionVersions.ACTION_REQUEST_FORMAT_VERSION, requestId, actionKind, idempotencyKey, tenantId,
                executionId, participantId, nodeLogicalId, componentLogicalId, capabilityCode, contractVersion,
                componentPayloadHash, catalogReferenceHash, channelCode, templateLogicalId, templateVersionId,
                templateContentHash, rewardLogicalId, rewardVersionId, rewardContentHash, rewardQuantity,
                rewardCurrency, rewardFaceValue, recipientRef, recipientEvidenceHash, providerRoutingPin,
                frozenHash,
                CANONICAL_JSON.hash(new RequestMaterial(frozen, requestId, recipientRef, recipientEvidenceHash)));
    }

    /**
     * True when another request is the same frozen behavioral request, whatever recipient evidence the host
     * resolved for it.
     *
     * <p>This is what decides a duplicate business key: the frozen material alone. Two preparations of one
     * frozen action node therefore always agree, and only genuinely changed material is a bounded conflict.
     */
    /**
     * The same frozen request with one host-resolved recipient target frozen into it.
     *
     * <p>The frozen-material hash and the request identity do not move, so attaching the recipient can never
     * change which frozen request this is; only the request hash, which covers the exact recipient evidence a
     * Provider was asked to apply, changes.
     */
    public ActionRequest withRecipient(ActionRecipientTarget target) {
        Objects.requireNonNull(target, "target");
        return of(
                actionKind, idempotencyKey, tenantId, executionId, participantId, nodeLogicalId,
                componentLogicalId, capabilityCode, contractVersion, componentPayloadHash, catalogReferenceHash,
                channelCode, templateLogicalId, templateVersionId, templateContentHash, rewardLogicalId,
                rewardVersionId, rewardContentHash, rewardQuantity, rewardCurrency, rewardFaceValue,
                target.recipientRef(), target.evidenceHash(), providerRoutingPin);
    }

    /**
     * True when another request is the same frozen behavioral request, whatever recipient evidence the host
     * resolved for it.
     *
     * <p>This is what decides a duplicate business key: the frozen material alone, never the host-resolved
     * recipient. Two preparations of one frozen action node therefore always agree, and only genuinely changed
     * material is a bounded conflict.
     */
    public boolean hasSameFrozenMaterial(ActionRequest other) {
        return other != null && frozenMaterialHash.equals(other.frozenMaterialHash);
    }

    /**
     * The bounded Provider-facing projection of this request.
     *
     * <p>It carries only the minimum allowlisted fields a Provider needs — the action kind, the business
     * idempotency key, the resolved recipient target reference, the message channel or the exact reward material
     * and any provider-routing pin — and never the published definition, a member profile, a template body, a
     * reward secret, SQL, a credential or a Provider URL.
     */
    public ProviderRequest toProviderRequest() {
        return ProviderRequest.of(
                actionKind, idempotencyKey, channelCode, templateLogicalId, templateVersionId,
                templateContentHash, rewardLogicalId, rewardVersionId, rewardContentHash, rewardQuantity,
                rewardCurrency, rewardFaceValue, recipientRef, providerRoutingPin, requestHash);
    }

    private static void requireKindShape(
            ActionKind actionKind,
            ContentHash catalogReferenceHash,
            String channelCode,
            String templateLogicalId,
            String templateVersionId,
            ContentHash templateContentHash,
            String rewardLogicalId,
            String rewardVersionId,
            ContentHash rewardContentHash,
            Integer rewardQuantity,
            String rewardCurrency,
            BigDecimal rewardFaceValue) {
        if (actionKind == ActionKind.MESSAGE) {
            if (channelCode == null || templateLogicalId == null || templateVersionId == null
                    || templateContentHash == null || catalogReferenceHash == null
                    || rewardLogicalId != null || rewardVersionId != null || rewardContentHash != null
                    || rewardQuantity != null || rewardCurrency != null || rewardFaceValue != null) {
                throw ActionSupport.invalid(
                        ActionErrorCodes.ACTION_COMPONENT_BINDING_MISMATCH, "messageRequest");
            }
            return;
        }
        if (rewardLogicalId == null || rewardVersionId == null || rewardContentHash == null
                || rewardQuantity == null || rewardCurrency == null || rewardFaceValue == null
                || catalogReferenceHash == null
                || channelCode != null || templateLogicalId != null || templateVersionId != null
                || templateContentHash != null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_COMPONENT_BINDING_MISMATCH, "rewardRequest");
        }
    }

    private FrozenMaterial frozenMaterial() {
        return new FrozenMaterial(
                formatVersion, actionKind, idempotencyKey, tenantId, executionId, participantId, nodeLogicalId,
                componentLogicalId, capabilityCode, contractVersion, componentPayloadHash, catalogReferenceHash,
                channelCode, templateLogicalId, templateVersionId, templateContentHash, rewardLogicalId,
                rewardVersionId, rewardContentHash, rewardQuantity, rewardCurrency, rewardFaceValue,
                providerRoutingPin);
    }

    /** Redacted diagnostics: no member, recipient or Provider routing value is printed. */
    @Override
    public String toString() {
        return "ActionRequest[requestId=" + requestId
                + ", actionKind=" + actionKind
                + ", componentLogicalId=" + componentLogicalId
                + ", capabilityCode=" + capabilityCode
                + ", contractVersion=" + contractVersion
                + ", requestHash=" + requestHash + ']';
    }

    /** Package-private hash material covering every frozen behavioral parameter. */
    record FrozenMaterial(
            String formatVersion,
            ActionKind actionKind,
            String idempotencyKey,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            String componentLogicalId,
            String capabilityCode,
            String contractVersion,
            ContentHash componentPayloadHash,
            ContentHash catalogReferenceHash,
            String channelCode,
            String templateLogicalId,
            String templateVersionId,
            ContentHash templateContentHash,
            String rewardLogicalId,
            String rewardVersionId,
            ContentHash rewardContentHash,
            Integer rewardQuantity,
            String rewardCurrency,
            BigDecimal rewardFaceValue,
            String providerRoutingPin) {
    }

    /** Package-private hash material covering the frozen parameters and the host-resolved recipient evidence. */
    record RequestMaterial(
            FrozenMaterial frozen,
            String requestId,
            String recipientRef,
            ContentHash recipientEvidenceHash) {
    }
}
