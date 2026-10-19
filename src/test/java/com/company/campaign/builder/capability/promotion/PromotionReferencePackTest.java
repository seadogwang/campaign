package com.company.campaign.builder.capability.promotion;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityPreviewCards;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Issue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 section 4: the concrete {@code promotion.reference} pack.
 *
 * <p>It resolves one exact frozen reward version by reference code, validates a positive quantity defaulting
 * to one and the grant's exact total against the version's own per-grant maximum, freezes the normalized
 * reward pins/kind/currency/face value/quantity, resolves one exact catalog reference and projects exactly
 * one bound capability preview card. Every unknown field, missing/disabled/unavailable version, non-positive
 * quantity and amount overflow or over-maximum grant fails closed.
 */
class PromotionReferencePackTest {

    private static final RewardVersion POINTS = reward("rw-points-100", "POINTS", "USD", "10.00", "100.00",
            true, true);
    private static final RewardVersion COUPON = reward("rw-coupon-5", "COUPON", "EUR", "5.00", "5.00",
            true, true);
    private static final RewardVersion DISABLED = reward("rw-disabled", "POINTS", "USD", "1.00", "1.00",
            false, true);
    private static final RewardVersion UNAVAILABLE = reward("rw-unavailable", "POINTS", "USD", "1.00", "1.00",
            true, false);
    private static final RewardVersion LARGE = reward("rw-large", "POINTS", "USD",
            "100000000000000.0000", "100000000000000.0000", true, true);

    private final PromotionReferencePack pack = new PromotionReferencePack(RewardCatalog.of(
            "rw-catalog-1", List.of(POINTS, COUPON, DISABLED, UNAVAILABLE, LARGE)));

    // ------------------------------------------------------------------ frozen metadata

    @Test
    void thePackDeclaresItsExactContractSlotAndHonestRuntime() {
        assertThat(pack.capabilityCode()).isEqualTo("promotion.reference");
        assertThat(pack.contractVersion()).isEqualTo("1.0");
        assertThat(pack.slot().code()).isEqualTo("campaign.promotion");
        assertThat(pack.slot().singleton()).isFalse();
        assertThat(pack.slot().ordered()).isFalse();
        assertThat(pack.enabled()).isTrue();
        // Previewable but honestly not production-publishable while T20-T23 do not exist.
        assertThat(pack.runtimeCompatibility().previewSupported()).isTrue();
        assertThat(pack.runtimeCompatibility().publishable()).isFalse();

        assertThat(pack.packMetadata().capabilityCode()).isEqualTo("promotion.reference");
        assertThat(pack.packMetadata().payloadContract().requiredNames()).containsExactly("referenceCode");
        assertThat(pack.packMetadata().payloadContract().optionalNames()).containsExactly("quantity");
        pack.packMetadata().requireValidPackHash();
    }

    // ------------------------------------------------------------------ the accepted resolution

    @Test
    void itResolvesTheExactVersionAndProjectsTheBoundCard() {
        CapabilityResolution.Resolved resolved =
                committed(resolve("promotion-a", Map.of("referenceCode", "rw-points-100", "quantity", 3)));

        assertThat(resolved.value().fields())
                .containsEntry("rewardLogicalId", "rw-points-100")
                .containsEntry("rewardVersionId", "rw-points-100-v1")
                .containsEntry("rewardContentHash", POINTS.contentHash().value())
                .containsEntry("kind", "POINTS")
                .containsEntry("currency", "USD")
                .containsEntry("quantity", 3);
        // The canonical payload normalizes the exact amount, so a whole value has no trailing scale.
        assertThat(new BigDecimal(String.valueOf(resolved.value().fields().get("faceValue"))))
                .isEqualByComparingTo("10.00");
        assertThat(resolved.references()).containsExactly(POINTS.resolvedReference());

        assertThat(resolved.capabilityPreviewCards()).hasSize(1);
        CapabilityPlanPreviewCard card = resolved.capabilityPreviewCards().get(0);
        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(card.componentLogicalId()).isEqualTo("promotion-a");
        assertThat(card.capabilityCode()).isEqualTo("promotion.reference");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(resolved.value().contentHash());
        assertThat(card.resolvedCatalogReferenceHash())
                .isEqualTo(CapabilityPreviewCards.canonicalReferenceHash(resolved.references()));
        assertThat(card.runtimeCompatibility()).isEqualTo(pack.runtimeCompatibility());
        assertThat(card.evaluationAssetHash())
                .isEqualTo(pack.packMetadata().evaluationAsset().contentHash());
        assertThat(resolved.cards()).isEmpty();
    }

    @Test
    void anOmittedQuantityDefaultsToOne() {
        assertThat(committed(resolve("promotion-a", Map.of("referenceCode", "rw-points-100")))
                .value().fields()).containsEntry("quantity", 1);
    }

    @Test
    void theResolutionIsADeterministicPureFunctionOfItsRequest() {
        CapabilityResolution.Resolved first =
                committed(resolve("promotion-a", Map.of("referenceCode", "rw-points-100", "quantity", 2)));
        CapabilityResolution.Resolved second =
                committed(resolve("promotion-a", Map.of("referenceCode", "rw-points-100", "quantity", 2)));

        assertThat(first.value()).isEqualTo(second.value());
        assertThat(first.value().canonicalBytes()).isEqualTo(second.value().canonicalBytes());
        assertThat(first.capabilityPreviewCards()).isEqualTo(second.capabilityPreviewCards());
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void aNonPositiveOrNonIntegralOrOverBoundQuantityFailsClosed() {
        for (Object quantity : List.of(0, -1, 1_000_001, 1.5, "three", true)) {
            assertThat(code(resolve("promotion-a",
                    Map.of("referenceCode", "rw-points-100", "quantity", quantity))))
                    .as("quantity %s", quantity)
                    .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_QUANTITY_INVALID);
        }
    }

    @Test
    void aClientAttemptToDeclareInventoryOrAGrantResultFailsClosed() {
        assertThat(code(resolve("promotion-a",
                Map.of("referenceCode", "rw-points-100", "inventory", Map.of("available", 10)))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_FIELD);
        assertThat(code(resolve("promotion-a",
                Map.of("referenceCode", "rw-points-100", "grantResult", "GRANTED"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_FIELD);
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw-points-100", "amount", "10.00"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_FIELD);
    }

    @Test
    void aMissingDisabledOrUnavailableVersionFailsClosedAndNeverFallsBackToLatest() {
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw-absent"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_REFERENCE);
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw-"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_REFERENCE);
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw-disabled"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_DISABLED);
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw-unavailable"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_UNAVAILABLE);
        assertThat(code(resolve("promotion-a", Map.of())))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_PAYLOAD_INVALID);
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw points"))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_PAYLOAD_INVALID);
    }

    @Test
    void aGrantThatOverflowsOrExceedsThePerGrantMaximumFailsClosed() {
        assertThat(code(resolve("promotion-a",
                Map.of("referenceCode", "rw-large", "quantity", 1_000_000))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_AMOUNT_OVERFLOW);
        // 5.00 x 2 exceeds a per-grant maximum of 5.00.
        assertThat(code(resolve("promotion-a", Map.of("referenceCode", "rw-coupon-5", "quantity", 2))))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_EXCEEDS_MAX_PER_GRANT);
        // 5.00 x 1 stays exactly at the per-grant maximum.
        assertThat(committed(resolve("promotion-a", Map.of("referenceCode", "rw-coupon-5", "quantity", 1)))
                .value().fields()).containsEntry("quantity", 1);
    }

    @Test
    void anAbsentRequestFailsClosed() {
        assertThat(code(pack.resolve(null)))
                .isEqualTo(RewardReferenceErrorCodes.REWARD_REFERENCE_PAYLOAD_INVALID);
    }

    @Test
    void aFailureEchoesNoRejectedValue() {
        CapabilityResolution.Failed failure = (CapabilityResolution.Failed) resolve("promotion-a",
                Map.of("referenceCode", "rw-coupon-5", "quantity", 2));
        for (Issue issue : failure.issues()) {
            assertThat(issue.message()).doesNotContain("rw-coupon-5");
        }
    }

    // ------------------------------------------------------------------ helpers

    private CapabilityResolution resolve(String logicalId, Map<String, Object> payload) {
        return pack.resolve(new CapabilityRequest(logicalId, CapabilityPayload.of(payload),
                BuilderFixtures.catalog(), BuilderFixtures.policy(), BuilderFixtures.context()));
    }

    private static CapabilityResolution.Resolved committed(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Resolved.class);
        return (CapabilityResolution.Resolved) resolution;
    }

    private static String code(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        return ((CapabilityResolution.Failed) resolution).issues().get(0).code();
    }

    private static RewardVersion reward(String referenceCode, String kind, String currency, String faceValue,
            String maxPerGrant, boolean enabled, boolean runtimeAvailable) {
        String logicalId = referenceCode;
        String versionId = referenceCode + "-v1";
        return RewardVersion.of(referenceCode, logicalId, versionId, kind, currency,
                new BigDecimal(faceValue), new BigDecimal(maxPerGrant), enabled, runtimeAvailable);
    }
}