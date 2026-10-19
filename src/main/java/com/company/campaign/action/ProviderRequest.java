package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One immutable, hash-bound bounded Provider request: the minimum allowlisted projection of an
 * {@link ActionRequest} that a Provider SPI is ever handed.
 *
 * <p>It carries the action kind, the business idempotency key, the host-resolved opaque recipient target
 * reference, the frozen message channel or the exact frozen reward material, and any provider-routing pin.
 * There is deliberately no field for a full published definition, a raw member profile, a recipient address, a
 * template body, a reward description or secret, SQL, a credential or a Provider URL: none of those is
 * representable here at all, so no Provider call can leak one.
 *
 * <p>{@link #toString()} prints no member value, no recipient reference and no Provider routing value.
 */
public record ProviderRequest(
        String formatVersion,
        String requestId,
        ActionKind actionKind,
        String idempotencyKey,
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
        String providerRoutingPin,
        ContentHash requestHash,
        ContentHash providerRequestHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ProviderRequest {
        if (!ActionVersions.PROVIDER_REQUEST_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(requestId, "requestId");
        Objects.requireNonNull(actionKind, "actionKind");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        ActionSupport.requireOptionalIdentifier(channelCode, "channelCode");
        ActionSupport.requireOptionalIdentifier(templateLogicalId, "templateLogicalId");
        ActionSupport.requireOptionalIdentifier(templateVersionId, "templateVersionId");
        ActionSupport.requireOptionalIdentifier(rewardLogicalId, "rewardLogicalId");
        ActionSupport.requireOptionalIdentifier(rewardVersionId, "rewardVersionId");
        ActionSupport.requireOptionalIdentifier(recipientRef, "recipientRef");
        ActionSupport.requireOptionalIdentifier(providerRoutingPin, "providerRoutingPin");
        if (rewardQuantity != null && (rewardQuantity < 1 || rewardQuantity > ActionVersions.MAX_REWARD_QUANTITY)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "rewardQuantity");
        }
        if (rewardCurrency != null) {
            ActionSupport.requireCurrency(rewardCurrency, "rewardCurrency");
        }
        if (rewardFaceValue != null) {
            rewardFaceValue = ActionSupport.requireAmount(rewardFaceValue, "rewardFaceValue");
        }
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(providerRequestHash, "providerRequestHash");
        String expectedRequestId = ActionSupport.derivedId("prvreq", List.of(
                formatVersion, requestHash, actionKind.code()));
        if (!requestId.equals(expectedRequestId)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_REQUEST_HASH_MISMATCH, "requestId");
        }
        if (!providerRequestHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, requestId, actionKind, idempotencyKey, channelCode, templateLogicalId,
                templateVersionId, templateContentHash, rewardLogicalId, rewardVersionId, rewardContentHash,
                rewardQuantity, rewardCurrency, rewardFaceValue, recipientRef, providerRoutingPin,
                requestHash)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_REQUEST_HASH_MISMATCH, "providerRequestHash");
        }
    }

    /** Builds one bounded Provider request and stamps it with its own canonical hash. */
    public static ProviderRequest of(
            ActionKind actionKind,
            String idempotencyKey,
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
            String providerRoutingPin,
            ContentHash requestHash) {
        if (rewardFaceValue != null) {
            rewardFaceValue = ActionSupport.requireAmount(rewardFaceValue, "rewardFaceValue");
        }
        String requestId = ActionSupport.derivedId("prvreq", List.of(
                ActionVersions.PROVIDER_REQUEST_FORMAT_VERSION, requestHash, actionKind.code()));
        Material material = material(
                ActionVersions.PROVIDER_REQUEST_FORMAT_VERSION, requestId, actionKind, idempotencyKey,
                channelCode, templateLogicalId, templateVersionId, templateContentHash, rewardLogicalId,
                rewardVersionId, rewardContentHash, rewardQuantity, rewardCurrency, rewardFaceValue,
                recipientRef, providerRoutingPin, requestHash);
        return new ProviderRequest(
                ActionVersions.PROVIDER_REQUEST_FORMAT_VERSION, requestId, actionKind, idempotencyKey,
                channelCode, templateLogicalId, templateVersionId, templateContentHash, rewardLogicalId,
                rewardVersionId, rewardContentHash, rewardQuantity, rewardCurrency, rewardFaceValue,
                recipientRef, providerRoutingPin, requestHash, CANONICAL_JSON.hash(material));
    }

    private static Material material(
            String formatVersion,
            String requestId,
            ActionKind actionKind,
            String idempotencyKey,
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
            String providerRoutingPin,
            ContentHash requestHash) {
        return new Material(formatVersion, requestId, actionKind, idempotencyKey, channelCode, templateLogicalId,
                templateVersionId, templateContentHash, rewardLogicalId, rewardVersionId, rewardContentHash,
                rewardQuantity, rewardCurrency, rewardFaceValue, recipientRef, providerRoutingPin, requestHash);
    }

    /** Redacted diagnostics: no member, recipient or Provider routing value is printed. */
    @Override
    public String toString() {
        return "ProviderRequest[requestId=" + requestId
                + ", actionKind=" + actionKind
                + ", requestHash=" + requestHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String requestId,
            ActionKind actionKind,
            String idempotencyKey,
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
            String providerRoutingPin,
            ContentHash requestHash) {
    }
}
