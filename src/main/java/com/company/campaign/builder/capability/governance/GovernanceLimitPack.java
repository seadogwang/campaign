package com.company.campaign.builder.capability.governance;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.Issue;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The concrete, frozen {@code governance.limit} capability pack at contract version 1.0.
 *
 * <p>It validates and deterministically normalizes one closed declaration: a positive, policy-bounded
 * {@code maxRecipients} and {@code maxRewardGrants}, an exact, non-negative, policy-bounded
 * {@code maxRewardFaceValue}, and a canonical ISO-4217 {@code currency}. Unknown fields, missing fields,
 * wrongly typed values, out-of-bounds limits, inexact or negative amounts and non-canonical currencies all
 * fail closed with one ordered, bounded issue that never echoes the rejected value.
 *
 * <p>A declared limit is an authoring declaration, never a reservation: this pack performs no cross-capability
 * composition validation, proves no reward exposure, reserves no inventory and reads no balance. Its honest
 * runtime compatibility therefore reports Preview as supported and publication as unsupported.
 *
 * <p>The pack is a pure function of its request. It holds no repository, network, provider, model, clock,
 * random source or mutable registry, resolves no catalog reference, and produces one normalized canonical
 * payload plus exactly one bound generic capability preview card carrying only already-safe machine facts.
 */
public final class GovernanceLimitPack implements CapabilityPackContract {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final CapabilityPackMetadata METADATA = buildMetadata();

    /** The declared ISO-4217 currency form: exactly three uppercase letters, never coerced from another case. */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Every declared field name, for the closed unknown-field check. */
    private static final Set<String> DECLARED_FIELDS = Set.of(
            GovernanceLimitVersions.FIELD_MAX_RECIPIENTS,
            GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS,
            GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE,
            GovernanceLimitVersions.FIELD_CURRENCY);

    private static final List<String> RISK_CODES = List.of(
            GovernanceLimitVersions.RISK_PREVIEW_ONLY,
            GovernanceLimitVersions.RISK_NOT_A_RESERVATION);

    private static final String FAILURE_MESSAGE =
            "The governance limit capability resolution failed closed.";

    @Override
    public String capabilityCode() {
        return GovernanceLimitVersions.CAPABILITY_CODE;
    }

    @Override
    public String contractVersion() {
        return GovernanceLimitVersions.CONTRACT_VERSION;
    }

    @Override
    public CompositionSlot slot() {
        return GovernanceLimitVersions.SLOT;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public RuntimeCompatibility runtimeCompatibility() {
        return GovernanceLimitVersions.RUNTIME_COMPATIBILITY;
    }

    @Override
    public CapabilityPackMetadata packMetadata() {
        return METADATA;
    }

    // ------------------------------------------------------------------ resolution

    @Override
    public CapabilityResolution resolve(CapabilityRequest request) {
        if (request == null || request.payload() == null || request.logicalId() == null) {
            return failed(GovernanceLimitVersions.ISSUE_INPUT);
        }
        try {
            return resolveOrThrow(request);
        } catch (Rejected rejected) {
            // Every expected rejection is one ordered, bounded issue; the rejected value is never echoed.
            return failed(rejected.code);
        } catch (RuntimeException exception) {
            // A payload that is not a bounded canonical object, or any unexpected shape, fails closed.
            return failed(GovernanceLimitVersions.ISSUE_FIELD);
        }
    }

    private CapabilityResolution resolveOrThrow(CapabilityRequest request) {
        Map<String, Object> fields = request.payload().fields();
        requireKnownFields(fields);

        long maxRecipients = requireLimit(fields, GovernanceLimitVersions.FIELD_MAX_RECIPIENTS);
        long maxRewardGrants = requireLimit(fields, GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS);
        BigDecimal maxRewardFaceValue =
                requireFaceValue(fields, GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE);
        String currency = requireCurrency(fields, GovernanceLimitVersions.FIELD_CURRENCY);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, maxRecipients);
        normalized.put(GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, maxRewardGrants);
        normalized.put(GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, maxRewardFaceValue);
        normalized.put(GovernanceLimitVersions.FIELD_CURRENCY, currency);

        CapabilityPayload payload = CapabilityPayload.of(normalized);
        return new CapabilityResolution.Resolved(
                payload, List.of(), List.of(), List.of(card(request.logicalId(), payload)));
    }

    // ------------------------------------------------------------------ field shape

    private static void requireKnownFields(Map<String, Object> fields) {
        for (String name : fields.keySet()) {
            if (!DECLARED_FIELDS.contains(name)) {
                // An undeclared field fails closed: it is not silently ignored, nor carried into the payload.
                throw new Rejected(GovernanceLimitVersions.ISSUE_UNKNOWN_FIELD);
            }
        }
    }

    /**
     * One positive, policy-bounded whole-number limit.
     *
     * <p>The value must be a JSON number with no fractional part. A numeric string, a boolean, a fractional
     * number and an out-of-range value all fail closed rather than being coerced or truncated.
     */
    private static long requireLimit(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof Number number)) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_FIELD);
        }
        BigDecimal exact;
        try {
            exact = new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_FIELD);
        }
        if (exact.scale() > 0) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_LIMIT_BOUND);
        }
        long declared;
        try {
            declared = exact.longValueExact();
        } catch (ArithmeticException exception) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_LIMIT_BOUND);
        }
        if (declared < 1 || declared > GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_LIMIT_BOUND);
        }
        return declared;
    }

    /**
     * One exact, non-negative, policy-bounded reward face value in minor units.
     *
     * <p>Exactness is a fact of the declared value, not of a rounding step: a value with more than
     * {@link GovernanceLimitVersions#FACE_VALUE_SCALE} fractional digits is refused rather than rounded, and
     * the accepted value is stored exactly.
     */
    private static BigDecimal requireFaceValue(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof Number number)) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_FIELD);
        }
        BigDecimal exact;
        try {
            exact = new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_FIELD);
        }
        if (exact.scale() > GovernanceLimitVersions.FACE_VALUE_SCALE) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_FACE_VALUE);
        }
        if (exact.signum() < 0
                || exact.compareTo(GovernanceLimitVersions.MAX_REWARD_FACE_VALUE) > 0
                || exact.compareTo(GovernanceLimitVersions.MIN_REWARD_FACE_VALUE) < 0) {
            throw new Rejected(GovernanceLimitVersions.ISSUE_FACE_VALUE);
        }
        return exact;
    }

    private static String requireCurrency(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof String text) || !CURRENCY.matcher(text).matches()) {
            // Case is never silently coerced: only the canonical uppercase ISO-4217 code is accepted.
            throw new Rejected(GovernanceLimitVersions.ISSUE_CURRENCY);
        }
        return text;
    }

    // ------------------------------------------------------------------ safe preview card

    private static CapabilityPlanPreviewCard card(String logicalId, CapabilityPayload payload) {
        return new CapabilityPlanPreviewCard(
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                logicalId,
                GovernanceLimitVersions.CAPABILITY_CODE,
                GovernanceLimitVersions.CONTRACT_VERSION,
                payload.contentHash(),
                // This capability resolves no catalog reference, so the optional slot is genuinely absent.
                null,
                GovernanceLimitVersions.RUNTIME_COMPATIBILITY,
                List.of(GovernanceLimitVersions.SUMMARY_LIMITS_DECLARED),
                RISK_CODES,
                METADATA.evaluationAsset().contentHash());
    }

    // ------------------------------------------------------------------ frozen metadata

    private static CapabilityPackMetadata buildMetadata() {
        CapabilityPayloadContract payloadContract = new CapabilityPayloadContract(
                List.of(
                        new CapabilityFieldSchema(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS,
                                new CapabilityFieldConstraint.IntegerBounds(
                                        1L, GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND)),
                        new CapabilityFieldSchema(GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS,
                                new CapabilityFieldConstraint.IntegerBounds(
                                        1L, GovernanceLimitVersions.MAX_REWARD_GRANTS_POLICY_BOUND)),
                        new CapabilityFieldSchema(GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE,
                                new CapabilityFieldConstraint.DecimalBounds(
                                        GovernanceLimitVersions.FACE_VALUE_PRECISION,
                                        GovernanceLimitVersions.FACE_VALUE_SCALE,
                                        GovernanceLimitVersions.MIN_REWARD_FACE_VALUE,
                                        GovernanceLimitVersions.MAX_REWARD_FACE_VALUE)),
                        new CapabilityFieldSchema(GovernanceLimitVersions.FIELD_CURRENCY,
                                new CapabilityFieldConstraint.Text(
                                        GovernanceLimitVersions.MAX_CURRENCY_LENGTH))),
                List.of());
        return CapabilityPackMetadata.of(
                GovernanceLimitVersions.CAPABILITY_CODE,
                GovernanceLimitVersions.PACK_VERSION,
                GovernanceLimitVersions.CONTRACT_VERSION,
                GovernanceLimitVersions.SCHEMA_VERSION,
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                GovernanceLimitVersions.SLOT,
                GovernanceLimitVersions.RUNTIME_COMPATIBILITY,
                payloadContract,
                evaluationAsset());
    }

    /**
     * The versioned evaluation asset: its positive and negative fixtures pin the canonical hash of an exact
     * payload body, so the two sets can never blur into one undifferentiated corpus.
     */
    private static CapabilityEvaluationAsset evaluationAsset() {
        List<CapabilityEvaluationFixture> positive = List.of(
                fixture("governance-limits-valid", CapabilityEvaluationFixture.Expectation.ACCEPTED,
                        Map.of(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1_000,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("25.00"),
                                GovernanceLimitVersions.FIELD_CURRENCY, "CNY")),
                fixture("governance-zero-face-value-valid", CapabilityEvaluationFixture.Expectation.ACCEPTED,
                        Map.of(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 1,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("0.00"),
                                GovernanceLimitVersions.FIELD_CURRENCY, "USD")));
        List<CapabilityEvaluationFixture> negative = List.of(
                fixture("governance-recipients-not-positive", CapabilityEvaluationFixture.Expectation.REJECTED,
                        Map.of(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 0,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("25.00"),
                                GovernanceLimitVersions.FIELD_CURRENCY, "CNY")),
                fixture("governance-face-value-negative", CapabilityEvaluationFixture.Expectation.REJECTED,
                        Map.of(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1_000,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("-1.00"),
                                GovernanceLimitVersions.FIELD_CURRENCY, "CNY")),
                fixture("governance-currency-not-canonical", CapabilityEvaluationFixture.Expectation.REJECTED,
                        Map.of(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1_000,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("25.00"),
                                GovernanceLimitVersions.FIELD_CURRENCY, "cny")));
        ContentHash hash = assetHash(positive, negative);
        return new CapabilityEvaluationAsset(
                GovernanceLimitVersions.EVALUATION_ASSET_ID,
                GovernanceLimitVersions.EVALUATION_ASSET_VERSION,
                hash, positive, negative);
    }

    private static CapabilityEvaluationFixture fixture(
            String fixtureId, CapabilityEvaluationFixture.Expectation expectation, Map<String, Object> body) {
        return new CapabilityEvaluationFixture(
                fixtureId, expectation, CapabilityPayload.of(body).contentHash());
    }

    /** The canonical asset hash: a deterministic function of the asset identity and of every pinned fixture. */
    private static ContentHash assetHash(
            List<CapabilityEvaluationFixture> positive, List<CapabilityEvaluationFixture> negative) {
        List<String> material = new ArrayList<>();
        material.add(GovernanceLimitVersions.EVALUATION_ASSET_ID);
        material.add(GovernanceLimitVersions.EVALUATION_ASSET_VERSION);
        for (CapabilityEvaluationFixture fixture : positive) {
            material.add("+" + fixture.fixtureId() + '|' + fixture.expectation() + '|'
                    + fixture.payloadHash().value());
        }
        for (CapabilityEvaluationFixture fixture : negative) {
            material.add("-" + fixture.fixtureId() + '|' + fixture.expectation() + '|'
                    + fixture.payloadHash().value());
        }
        return CANONICAL_JSON.hash(List.copyOf(material));
    }

    // ------------------------------------------------------------------ failure shape

    private static CapabilityResolution failed(String code) {
        return new CapabilityResolution.Failed(List.of(Issue.error(code, FAILURE_MESSAGE)));
    }

    /** One expected rejection, carrying its stable issue code and never the rejected value. */
    private static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient String code;

        private Rejected(String code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    /** Redacted diagnostics: this pack holds no payload value, so it prints only its frozen identity. */
    @Override
    public String toString() {
        return "GovernanceLimitPack[capabilityCode=" + GovernanceLimitVersions.CAPABILITY_CODE
                + ", contractVersion=" + GovernanceLimitVersions.CONTRACT_VERSION + ']';
    }
}