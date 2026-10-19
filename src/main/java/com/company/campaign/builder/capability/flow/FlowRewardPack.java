package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.contracts.ContractPatterns;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The concrete, production Flow Reward capability pack.
 *
 * <p>A Reward node carries exactly one thing: a stable {@code promotionLogicalId} reference to a resolved
 * {@code promotion.reference} component of the same campaign. The payload declares that one reference field
 * and nothing else, so a missing reference, a repeated or extra field and a value that is not a bounded stable
 * logical identifier all fail closed.
 *
 * <p>The reference is validated node-locally only. Whether the named component exists, is enabled, is at the
 * exact contract version this node requires, and whether a Governance component conservatively covers the
 * exposure this node creates, are composition checks owned by T20; this pack never reads the component set,
 * never resolves the referenced reward's catalog version, never reserves anything and never declares a grant
 * result. Its normalized payload is exactly the canonical reference.
 *
 * <p>The card this node projects therefore names the two risks that remain outside it, so a reader of the plan
 * material is never told that an unreferenced or uncovered reward is settled.
 */
public final class FlowRewardPack extends FlowPackSupport {

    /** The canonical capability code of this pack. */
    public static final String CAPABILITY_CODE = FlowPackVersions.REWARD;

    /** The one declared field: the stable reference to the Promotion component this node grants. */
    public static final String REFERENCE_FIELD = "promotionLogicalId";

    /** The closed reference kind the field declares: a resolved {@code promotion.reference} component. */
    public static final String REFERENCE_KIND = "PROMOTION_REWARD";

    /** The bounded summary code this node projects when it resolves. */
    static final List<String> SUMMARY_CODES = List.of("FLOW_REWARD");

    /** The bounded risk codes this node projects. */
    static final List<String> RISK_CODES = List.of(
            "REWARD_EXPOSURE_UNRESERVED", "GOVERNANCE_COVERAGE_REQUIRED",
            "JOURNEY_SEMANTICS_DEFERRED", "RUNTIME_PREVIEW_ONLY");

    private static final Pattern REFERENCE = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** The closed payload contract: exactly one required reference field. */
    private static final CapabilityPayloadContract CONTRACT = new CapabilityPayloadContract(
            List.of(new CapabilityFieldSchema(
                    REFERENCE_FIELD, new CapabilityFieldConstraint.ReferenceTarget(REFERENCE_KIND))),
            List.of());

    public FlowRewardPack() {
        super(CAPABILITY_CODE, CONTRACT, SUMMARY_CODES, RISK_CODES, corpus());
    }

    /** The ordered in-code evaluation fixture corpus of this node. */
    static List<FlowEvaluationFixture> corpus() {
        return List.of(
                new FlowEvaluationFixture("flow-reward-valid", Expectation.ACCEPTED,
                        Map.of(REFERENCE_FIELD, "reward-welcome-01")),
                new FlowEvaluationFixture("flow-reward-missing-reference", Expectation.REJECTED, Map.of()),
                new FlowEvaluationFixture("flow-reward-unknown-field", Expectation.REJECTED,
                        Map.of(REFERENCE_FIELD, "reward-welcome-01", "quantity", "2")),
                new FlowEvaluationFixture("flow-reward-malformed-reference", Expectation.REJECTED,
                        Map.of(REFERENCE_FIELD, "reward welcome 01")));
    }

    /**
     * Resolves this node's one declared reference against the compiler-owned same-proposal binding view.
     *
     * <p>A reference that names a component this proposal creates is replaced by that component's final
     * plan-scoped identity, so the normalized component payload carries a real identity and never a
     * {@code clientRef}. The capability expectation is the exact {@code promotion.reference} contract, so a
     * reference declared as some other component is left untouched and fails its own validation.
     */
    @Override
    Map<String, Object> bindReferences(Map<String, Object> fields, CapabilityRequest request) {
        Object value = fields.get(REFERENCE_FIELD);
        if (!(value instanceof String reference)) {
            return fields;
        }
        return request.bindings().boundTarget(reference, PromotionReferencePack.CAPABILITY_CODE)
                .<Map<String, Object>>map(bound -> Map.of(REFERENCE_FIELD, bound))
                .orElse(fields);
    }

    @Override
    CapabilityPayload normalizeFields(Map<String, Object> fields) {
        String reference = requireText(fields, REFERENCE_FIELD);
        if (!REFERENCE.matcher(reference).matches()) {
            throw new FlowPackException(FlowPackErrorCodes.REFERENCE_INVALID);
        }
        return CapabilityPayload.of(Map.of(REFERENCE_FIELD, reference));
    }
}
