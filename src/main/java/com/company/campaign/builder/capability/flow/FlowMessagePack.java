package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.contracts.ContractPatterns;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The concrete, production Flow Message capability pack.
 *
 * <p>A Message node carries exactly one thing: a stable {@code messageLogicalId} reference to a resolved
 * {@code message.template} component of the same campaign. The payload declares that one reference field and
 * nothing else, so a missing reference, a repeated or extra field and a value that is not a bounded stable
 * logical identifier all fail closed.
 *
 * <p>The reference is validated node-locally only. Whether the named component exists, is enabled and is at
 * the exact contract version this node requires is a composition check owned by T20; this pack never reads the
 * component set, never resolves the referenced template's catalog version and never dereferences anything.
 * Its normalized payload is exactly the canonical reference, so the node's semantics never grow an implicit
 * copy of the referenced component's content.
 */
public final class FlowMessagePack extends FlowPackSupport {

    /** The canonical capability code of this pack. */
    public static final String CAPABILITY_CODE = FlowPackVersions.MESSAGE;

    /** The one declared field: the stable reference to the Message component this node sends. */
    public static final String REFERENCE_FIELD = "messageLogicalId";

    /** The closed reference kind the field declares: a resolved {@code message.template} component. */
    public static final String REFERENCE_KIND = "MESSAGE_TEMPLATE";

    /** The bounded summary code this node projects when it resolves. */
    static final List<String> SUMMARY_CODES = List.of("FLOW_MESSAGE");

    /** The bounded risk codes this node projects. */
    static final List<String> RISK_CODES = List.of(
            "MESSAGE_CONTENT_NOT_PROJECTED", "JOURNEY_SEMANTICS_DEFERRED", "RUNTIME_PREVIEW_ONLY");

    private static final Pattern REFERENCE = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** The closed payload contract: exactly one required reference field. */
    private static final CapabilityPayloadContract CONTRACT = new CapabilityPayloadContract(
            List.of(new CapabilityFieldSchema(
                    REFERENCE_FIELD, new CapabilityFieldConstraint.ReferenceTarget(REFERENCE_KIND))),
            List.of());

    public FlowMessagePack() {
        super(CAPABILITY_CODE, CONTRACT, SUMMARY_CODES, RISK_CODES, corpus());
    }

    /** The ordered in-code evaluation fixture corpus of this node. */
    static List<FlowEvaluationFixture> corpus() {
        return List.of(
                new FlowEvaluationFixture("flow-message-valid", Expectation.ACCEPTED,
                        Map.of(REFERENCE_FIELD, "message-welcome-01")),
                new FlowEvaluationFixture("flow-message-missing-reference", Expectation.REJECTED, Map.of()),
                new FlowEvaluationFixture("flow-message-unknown-field", Expectation.REJECTED,
                        Map.of(REFERENCE_FIELD, "message-welcome-01", "template", "welcome")),
                new FlowEvaluationFixture("flow-message-malformed-reference", Expectation.REJECTED,
                        Map.of(REFERENCE_FIELD, "message welcome 01")));
    }

    /**
     * Resolves this node's one declared reference against the compiler-owned same-proposal binding view.
     *
     * <p>A reference that names a component this proposal creates is replaced by that component's final
     * plan-scoped identity, so the normalized component payload carries a real identity and never a
     * {@code clientRef}. Only the presence of the value is read here; its grammar and its target's capability
     * are still decided by {@link #normalizeFields} and by the accepted T20 graph validation, so an undeclared,
     * wrong-capability or malformed reference still fails closed rather than being silently accepted.
     */
    @Override
    Map<String, Object> bindReferences(Map<String, Object> fields, CapabilityRequest request) {
        Object value = fields.get(REFERENCE_FIELD);
        if (!(value instanceof String reference)) {
            return fields;
        }
        return request.bindings().boundTarget(reference, MessageTemplatePack.CAPABILITY_CODE)
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
