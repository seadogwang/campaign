package com.company.campaign.builder.capability.governance;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.SchemaVersion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * T19: the concrete {@code governance.limit} capability pack at contract version 1.0.
 *
 * <p>It pins the frozen metadata and payload contract, the exact positive/bounded limits, the exact
 * non-negative reward face value and the canonical ISO-4217 currency, the honest preview-only runtime
 * compatibility and the safe preview card, and proves that unknown fields, wrongly typed values,
 * out-of-bound limits, inexact or negative amounts and non-canonical currencies all fail closed. It declares
 * a limit and nothing more: no composition policy, no exposure proof and no inventory reservation.
 */
class GovernanceLimitPackTest {

    private static final GovernanceLimitPack PACK = new GovernanceLimitPack();
    private static final String LOGICAL_ID = "governance-main";

    // ------------------------------------------------------------------ frozen metadata

    @Test
    void thePackDeclaresItsExactFrozenMetadata() {
        CapabilityPackMetadata metadata = PACK.packMetadata();

        assertThat(CapabilityContract.class.isAssignableFrom(GovernanceLimitPack.class)).isTrue();
        assertThat(PACK.capabilityCode()).isEqualTo("governance.limit");
        assertThat(PACK.contractVersion()).isEqualTo("1.0");
        assertThat(PACK.slot()).isEqualTo(new CompositionSlot("campaign.governance", false, true));
        assertThat(PACK.enabled()).isTrue();
        assertThat(metadata.capabilityCode()).isEqualTo("governance.limit");
        assertThat(metadata.packVersion()).isEqualTo("t19-v1");
        assertThat(metadata.contractVersion()).isEqualTo("1.0");
        assertThat(metadata.schemaVersion()).isEqualTo(SchemaVersion.parse("1.0"));
        assertThat(metadata.slot()).isEqualTo(new CompositionSlot("campaign.governance", false, true));
        assertThat(metadata.previewCardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(metadata.previewCardVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThatCode(metadata::requireValidPackHash).doesNotThrowAnyException();
    }

    @Test
    void theRuntimeCompatibilityIsHonestlyPreviewOnly() {
        RuntimeCompatibility compatibility = PACK.runtimeCompatibility();

        assertThat(compatibility.runtimeSchemaVersion()).isEqualTo("campaign-runtime/1.0");
        assertThat(compatibility.previewSupported()).isTrue();
        // A declared limit is not a runtime reservation: the pack must never claim production support.
        assertThat(compatibility.publishable()).isFalse();
        assertThat(PACK.packMetadata().runtimeCompatibility()).isEqualTo(compatibility);
    }

    @Test
    void theFrozenPayloadContractDeclaresEveryFieldAndThePolicyBounds() {
        CapabilityPayloadContract contract = PACK.packMetadata().payloadContract();

        assertThat(contract.requiredNames()).containsExactlyInAnyOrder(
                "maxRecipients", "maxRewardGrants", "maxRewardFaceValue", "currency");
        assertThat(contract.optionalNames()).isEmpty();
        assertThat(constraintOf(contract, "maxRecipients")).isEqualTo(
                new CapabilityFieldConstraint.IntegerBounds(
                        1L, GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND));
        assertThat(constraintOf(contract, "maxRewardGrants")).isEqualTo(
                new CapabilityFieldConstraint.IntegerBounds(
                        1L, GovernanceLimitVersions.MAX_REWARD_GRANTS_POLICY_BOUND));
        assertThat(kindOf(contract, "maxRewardFaceValue")).isEqualTo(CapabilityValueKind.DECIMAL);
        assertThat(kindOf(contract, "currency")).isEqualTo(CapabilityValueKind.STRING);
    }

    // ------------------------------------------------------------------ deterministic normalization

    @Test
    void validLimitsNormalizeToTheirExactDeclaredValues() {
        CapabilityResolution.Resolved resolved = accepted(validBody());

        assertThat(resolved.value().fields()).containsEntry("maxRecipients", 1_000)
                .containsEntry("maxRewardGrants", 100)
                .containsEntry("currency", "CNY");
        Object faceValue = resolved.value().fields().get("maxRewardFaceValue");
        // Canonical JSON may choose the narrowest numeric Java representation; compare its exact value.
        assertThat(new BigDecimal(faceValue.toString())).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(resolved.value().fields()).hasSize(4);
        assertThat(resolved.references()).isEmpty();
        assertThat(resolved.cards()).isEmpty();
    }

    @Test
    void normalizationIsDeterministicAndExactAcrossEquivalentSpellings() {
        Map<String, Object> exact = validBody();
        Map<String, Object> trailingZeros = new LinkedHashMap<>(validBody());
        trailingZeros.put("maxRewardFaceValue", new BigDecimal("25.000"));

        assertThat(accepted(exact).value().canonicalText())
                .isEqualTo(accepted(trailingZeros).value().canonicalText());
        assertThat(accepted(exact).value().contentHash())
                .isEqualTo(accepted(validBody()).value().contentHash());
    }

    @Test
    void aZeroFaceValueIsAccepted() {
        Map<String, Object> body = new LinkedHashMap<>(validBody());
        body.put("maxRewardFaceValue", new BigDecimal("0.00"));

        assertThat(accepted(body).value().fields()).containsEntry("currency", "CNY");
    }

    // ------------------------------------------------------------------ the safe preview card

    @Test
    void theResolvedComponentCarriesExactlyOneBoundSafePreviewCard() {
        CapabilityResolution.Resolved resolved = accepted(validBody());
        CapabilityPlanPreviewCard card = resolved.capabilityPreviewCards().get(0);

        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(card.cardContractVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThat(card.componentLogicalId()).isEqualTo(LOGICAL_ID);
        assertThat(card.capabilityCode()).isEqualTo("governance.limit");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(resolved.value().contentHash());
        assertThat(card.resolvedCatalogReferenceHash()).isNull();
        assertThat(card.hasResolvedCatalogReference()).isFalse();
        assertThat(card.runtimeCompatibility()).isEqualTo(PACK.runtimeCompatibility());
        assertThat(card.evaluationAssetHash())
                .isEqualTo(PACK.packMetadata().evaluationAsset().contentHash());
        assertThat(card.summaryCodes()).containsExactly(GovernanceLimitVersions.SUMMARY_LIMITS_DECLARED);
        assertThat(card.riskCodes()).containsExactlyInAnyOrder(
                GovernanceLimitVersions.RISK_PREVIEW_ONLY, GovernanceLimitVersions.RISK_NOT_A_RESERVATION);
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void anUnknownFieldFailsClosed() {
        Map<String, Object> body = new LinkedHashMap<>(validBody());
        body.put("inventoryReserve", 10);

        assertRejected(body, GovernanceLimitVersions.ISSUE_UNKNOWN_FIELD);
    }

    @Test
    void aMissingFieldFailsClosed() {
        Map<String, Object> body = new LinkedHashMap<>(validBody());
        body.remove("maxRecipients");

        assertRejected(body, GovernanceLimitVersions.ISSUE_FIELD);

        Map<String, Object> missingCurrency = new LinkedHashMap<>(validBody());
        missingCurrency.remove("currency");
        assertRejected(missingCurrency, GovernanceLimitVersions.ISSUE_CURRENCY);
    }

    @Test
    void aWronglyTypedFieldFailsClosed() {
        Map<String, Object> recipients = new LinkedHashMap<>(validBody());
        recipients.put("maxRecipients", "1000");
        assertRejected(recipients, GovernanceLimitVersions.ISSUE_FIELD);

        Map<String, Object> currency = new LinkedHashMap<>(validBody());
        currency.put("currency", 840);
        assertRejected(currency, GovernanceLimitVersions.ISSUE_CURRENCY);

        Map<String, Object> faceValue = new LinkedHashMap<>(validBody());
        faceValue.put("maxRewardFaceValue", "25.00");
        assertRejected(faceValue, GovernanceLimitVersions.ISSUE_FIELD);
    }

    @Test
    void aNonPositiveOrUnboundedLimitFailsClosed() {
        Map<String, Object> zeroRecipients = new LinkedHashMap<>(validBody());
        zeroRecipients.put("maxRecipients", 0);
        assertRejected(zeroRecipients, GovernanceLimitVersions.ISSUE_LIMIT_BOUND);

        Map<String, Object> negativeGrants = new LinkedHashMap<>(validBody());
        negativeGrants.put("maxRewardGrants", -1);
        assertRejected(negativeGrants, GovernanceLimitVersions.ISSUE_LIMIT_BOUND);

        Map<String, Object> overBound = new LinkedHashMap<>(validBody());
        overBound.put("maxRecipients", GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND + 1);
        assertRejected(overBound, GovernanceLimitVersions.ISSUE_LIMIT_BOUND);

        Map<String, Object> fractional = new LinkedHashMap<>(validBody());
        fractional.put("maxRewardGrants", new BigDecimal("1.5"));
        assertRejected(fractional, GovernanceLimitVersions.ISSUE_LIMIT_BOUND);

        // The inclusive policy bound itself is accepted.
        Map<String, Object> atBound = new LinkedHashMap<>(validBody());
        atBound.put("maxRecipients", GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND);
        accepted(atBound);
    }

    @Test
    void aNegativeInexactOrUnboundedFaceValueFailsClosed() {
        Map<String, Object> negative = new LinkedHashMap<>(validBody());
        negative.put("maxRewardFaceValue", new BigDecimal("-1.00"));
        assertRejected(negative, GovernanceLimitVersions.ISSUE_FACE_VALUE);

        Map<String, Object> inexact = new LinkedHashMap<>(validBody());
        inexact.put("maxRewardFaceValue", new BigDecimal("25.001"));
        assertRejected(inexact, GovernanceLimitVersions.ISSUE_FACE_VALUE);

        Map<String, Object> overBound = new LinkedHashMap<>(validBody());
        overBound.put("maxRewardFaceValue", GovernanceLimitVersions.MAX_REWARD_FACE_VALUE.add(BigDecimal.ONE));
        assertRejected(overBound, GovernanceLimitVersions.ISSUE_FACE_VALUE);
    }

    @Test
    void aNonCanonicalCurrencyFailsClosed() {
        for (String currency : List.of("cny", "CN", "CNYY", "C1Y", "")) {
            Map<String, Object> body = new LinkedHashMap<>(validBody());
            body.put("currency", currency);
            assertRejected(body, GovernanceLimitVersions.ISSUE_CURRENCY);
        }
    }

    @Test
    void aMissingHostInputFailsClosed() {
        CapabilityResolution resolution = PACK.resolve(null);

        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        assertThat(((CapabilityResolution.Failed) resolution).issues().get(0).code())
                .isEqualTo(GovernanceLimitVersions.ISSUE_INPUT);
    }

    // ------------------------------------------------------------------ the versioned evaluation asset

    @Test
    void everyPinnedFixtureHashMatchesARealPayloadAndItsDeclaredPolarity() {
        CapabilityEvaluationAsset asset = PACK.packMetadata().evaluationAsset();

        assertThat(asset.assetId()).isEqualTo("t19-governance-limit-evaluation-v1");
        assertThat(asset.assetVersion()).isEqualTo("1.0");
        assertThat(asset.positiveFixtures()).isNotEmpty();
        assertThat(asset.negativeFixtures()).isNotEmpty();

        Map<String, Map<String, Object>> bodies = fixtureBodies();
        for (CapabilityEvaluationFixture fixture : asset.allFixtures()) {
            Map<String, Object> body = bodies.get(fixture.fixtureId());
            assertThat(body).as("fixture %s", fixture.fixtureId()).isNotNull();
            assertThat(CapabilityPayload.of(body).contentHash())
                    .as("fixture %s", fixture.fixtureId())
                    .isEqualTo(fixture.payloadHash());
            boolean accepted = resolve(body) instanceof CapabilityResolution.Resolved;
            assertThat(accepted).as("fixture %s", fixture.fixtureId()).isEqualTo(fixture.isPositive());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxRecipients", 1_000);
        body.put("maxRewardGrants", 100);
        body.put("maxRewardFaceValue", new BigDecimal("25.00"));
        body.put("currency", "CNY");
        return body;
    }

    private static Map<String, Map<String, Object>> fixtureBodies() {
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        bodies.put("governance-limits-valid", validBody());
        bodies.put("governance-zero-face-value-valid", Map.of(
                "maxRecipients", 1, "maxRewardGrants", 1,
                "maxRewardFaceValue", new BigDecimal("0.00"), "currency", "USD"));
        bodies.put("governance-recipients-not-positive", Map.of(
                "maxRecipients", 0, "maxRewardGrants", 100,
                "maxRewardFaceValue", new BigDecimal("25.00"), "currency", "CNY"));
        bodies.put("governance-face-value-negative", Map.of(
                "maxRecipients", 1_000, "maxRewardGrants", 100,
                "maxRewardFaceValue", new BigDecimal("-1.00"), "currency", "CNY"));
        bodies.put("governance-currency-not-canonical", Map.of(
                "maxRecipients", 1_000, "maxRewardGrants", 100,
                "maxRewardFaceValue", new BigDecimal("25.00"), "currency", "cny"));
        return bodies;
    }

    private static CapabilityResolution resolve(Map<String, Object> body) {
        return PACK.resolve(new CapabilityRequest(
                LOGICAL_ID, CapabilityPayload.of(body),
                BuilderFixtures.catalog(PACK), BuilderFixtures.policy(), BuilderFixtures.context()));
    }

    private static CapabilityResolution.Resolved accepted(Map<String, Object> body) {
        CapabilityResolution resolution = resolve(body);
        assertThat(resolution).isInstanceOf(CapabilityResolution.Resolved.class);
        return (CapabilityResolution.Resolved) resolution;
    }

    private static void assertRejected(Map<String, Object> body, String code) {
        CapabilityResolution resolution = resolve(body);
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<Issue> issues = ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(code);
    }

    private static CapabilityFieldConstraint constraintOf(CapabilityPayloadContract contract, String field) {
        for (CapabilityFieldSchema schema : contract.fields()) {
            if (schema.fieldName().equals(field)) {
                return schema.constraint();
            }
        }
        throw new AssertionError("undeclared field " + field);
    }

    private static CapabilityValueKind kindOf(CapabilityPayloadContract contract, String field) {
        return constraintOf(contract, field).kind();
    }
}
