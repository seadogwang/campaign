package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;

import java.util.List;
import java.util.Map;

/**
 * The concrete, production Flow Start capability pack.
 *
 * <p>A Start node carries no configuration at all: its closed payload contract declares no field, so any
 * declared field fails closed as an unknown field and the only accepted payload is the empty one. Its
 * normalized payload is therefore the one canonical empty object, whatever object the caller submitted.
 *
 * <p>It validates its own node configuration only. Whether a campaign holds at most one Start node, and what
 * leaves a Start node, is the T20 graph contract's business and is deliberately not decided here.
 *
 * <p>Its frozen metadata is a fact of this contract version: the ordered multi-instance Flow slot
 * slot, authoring enabled, and a runtime compatibility that reports Preview as supported while honestly
 * declaring the capability not publishable, because no Journey compiler, schedule, Runtime or worker exists.
 */
public final class FlowStartPack extends FlowPackSupport {

    /** The canonical capability code of this pack. */
    public static final String CAPABILITY_CODE = FlowPackVersions.START;

    /** Optional host-validated registered event code that starts an event Campaign. */
    public static final String TRIGGER_EVENT_FIELD = "triggerEventCode";

    /** The bounded summary code this node projects when it resolves. */
    static final List<String> SUMMARY_CODES = List.of("FLOW_START");

    /** The bounded risk codes this node projects. */
    static final List<String> RISK_CODES = List.of(
            "START_NODE_UNVALIDATED", "JOURNEY_SEMANTICS_DEFERRED", "RUNTIME_PREVIEW_ONLY");

    /** The closed payload contract: an optional registered trigger-event code and nothing else. */
    private static final CapabilityPayloadContract CONTRACT = new CapabilityPayloadContract(
            List.of(),
            List.of(new CapabilityFieldSchema(
                    TRIGGER_EVENT_FIELD, new CapabilityFieldConstraint.Text(128))));

    public FlowStartPack() {
        super(CAPABILITY_CODE, CONTRACT, SUMMARY_CODES, RISK_CODES, corpus());
    }

    /** The ordered in-code evaluation fixture corpus of this node. */
    static List<FlowEvaluationFixture> corpus() {
        return List.of(
                new FlowEvaluationFixture("flow-start-empty-valid", Expectation.ACCEPTED, Map.of()),
                new FlowEvaluationFixture("flow-start-event-valid", Expectation.ACCEPTED,
                        Map.of(TRIGGER_EVENT_FIELD, "PURCHASE_COMPLETED")),
                new FlowEvaluationFixture("flow-start-unknown-field", Expectation.REJECTED,
                        Map.of("terminal", "END")));
    }

    @Override
    CapabilityPayload normalizeFields(Map<String, Object> fields) {
        if (!fields.containsKey(TRIGGER_EVENT_FIELD)) {
            return CapabilityPayload.empty();
        }
        String eventCode = requireText(fields, TRIGGER_EVENT_FIELD);
        return CapabilityPayload.of(Map.of(TRIGGER_EVENT_FIELD, eventCode));
    }
}
