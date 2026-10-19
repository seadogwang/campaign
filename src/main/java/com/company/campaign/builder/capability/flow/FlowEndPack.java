package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;

import java.util.List;
import java.util.Map;

/**
 * The concrete, production Flow End capability pack.
 *
 * <p>An End node carries exactly one thing: the closed terminal code it ends with, {@code END} or
 * {@code EXIT}. The payload declares that one field and nothing else, so a missing terminal, a terminal
 * outside the closed pair and any extra field — an outgoing reference, a successor, a branch destination —
 * fail closed as unknown fields.
 *
 * <p>The pack therefore holds no edge data and invents no successor: whether a terminal node may have
 * out-edges, and every reachability and terminal-shape question around it, are T20 graph concerns. This pack
 * states only which terminal the node itself is, and its card names the deferred validation so the plan
 * material never claims the terminal shape was proven.
 */
public final class FlowEndPack extends FlowPackSupport {

    /** The canonical capability code of this pack. */
    public static final String CAPABILITY_CODE = FlowPackVersions.END;

    /** The one declared field: the closed terminal code of this node. */
    public static final String TERMINAL_FIELD = "terminal";

    /** The terminal code of a normally completed journey. */
    public static final String TERMINAL_END = "END";

    /** The terminal code of a journey the participant leaves. */
    public static final String TERMINAL_EXIT = "EXIT";

    /** The two closed terminal codes, in their declared order. */
    public static final List<String> TERMINALS = List.of(TERMINAL_END, TERMINAL_EXIT);

    /** The bounded summary code this node projects when it resolves. */
    static final List<String> SUMMARY_CODES = List.of("FLOW_END");

    /** The bounded risk codes this node projects. */
    static final List<String> RISK_CODES = List.of(
            "TERMINAL_NODE_UNVALIDATED", "JOURNEY_SEMANTICS_DEFERRED", "RUNTIME_PREVIEW_ONLY");

    /** The closed payload contract: exactly one required closed terminal code. */
    private static final CapabilityPayloadContract CONTRACT = new CapabilityPayloadContract(
            List.of(new CapabilityFieldSchema(
                    TERMINAL_FIELD, new CapabilityFieldConstraint.EnumCodes(TERMINALS))),
            List.of());

    public FlowEndPack() {
        super(CAPABILITY_CODE, CONTRACT, SUMMARY_CODES, RISK_CODES, corpus());
    }

    /** The ordered in-code evaluation fixture corpus of this node. */
    static List<FlowEvaluationFixture> corpus() {
        return List.of(
                new FlowEvaluationFixture("flow-end-completed-valid", Expectation.ACCEPTED,
                        Map.of(TERMINAL_FIELD, TERMINAL_END)),
                new FlowEvaluationFixture("flow-end-exit-valid", Expectation.ACCEPTED,
                        Map.of(TERMINAL_FIELD, TERMINAL_EXIT)),
                new FlowEvaluationFixture("flow-end-missing-terminal", Expectation.REJECTED, Map.of()),
                new FlowEvaluationFixture("flow-end-unknown-terminal", Expectation.REJECTED,
                        Map.of(TERMINAL_FIELD, "STOP")),
                new FlowEvaluationFixture("flow-end-unknown-field", Expectation.REJECTED,
                        Map.of(TERMINAL_FIELD, TERMINAL_END, "target", "flow-end-other")));
    }

    @Override
    CapabilityPayload normalizeFields(Map<String, Object> fields) {
        String terminal = requireText(fields, TERMINAL_FIELD);
        if (!TERMINALS.contains(terminal)) {
            throw new FlowPackException(FlowPackErrorCodes.END_TERMINAL_INVALID);
        }
        return CapabilityPayload.of(Map.of(TERMINAL_FIELD, terminal));
    }
}
