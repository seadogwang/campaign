package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The concrete, production Flow Wait capability pack.
 *
 * <p>A Wait node carries a positive ISO-8601 duration and the anchor it is measured from. The anchor is one of
 * two closed codes — {@code ENTRY} or {@code PREVIOUS_ACCEPTED_ACTION} — so a wait is always a fact of the
 * node's own configuration and never of a wall clock, a calendar or a session.
 *
 * <p>The duration is bounded by one fixed contract maximum, and it is canonicalized through
 * {@link Duration}: an equivalent spelling such as {@code PT60M} normalizes to {@code PT1H}, so two waits that
 * mean the same length have one payload and one hash. Zero, a negative duration, a non-duration and an
 * over-maximum duration all fail closed, and a dynamic resume instant is not representable at all: the
 * contract declares no such field, so it is refused as an unknown field rather than stored.
 *
 * <p>This pack computes no occurrence, resolves no daylight-saving transition and creates no scheduling work;
 * that belongs to T21.
 */
public final class FlowWaitPack extends FlowPackSupport {

    /** The canonical capability code of this pack. */
    public static final String CAPABILITY_CODE = FlowPackVersions.WAIT;

    /** The declared duration field. */
    public static final String DURATION_FIELD = "duration";

    /** The declared anchor field. */
    public static final String ANCHOR_FIELD = "anchor";

    /** The anchor that measures the wait from the entry into the node. */
    public static final String ANCHOR_ENTRY = "ENTRY";

    /** The anchor that measures the wait from the last accepted action. */
    public static final String ANCHOR_PREVIOUS_ACCEPTED_ACTION = "PREVIOUS_ACCEPTED_ACTION";

    /** The two closed anchors, in their declared order. */
    public static final List<String> ANCHORS = List.of(ANCHOR_ENTRY, ANCHOR_PREVIOUS_ACCEPTED_ACTION);

    /** The one fixed contract maximum of a wait, in seconds: thirty days. */
    public static final long MAX_WAIT_SECONDS = 2_592_000L;

    /** The bounded summary code this node projects when it resolves. */
    static final List<String> SUMMARY_CODES = List.of("FLOW_WAIT");

    /** The bounded risk codes this node projects. */
    static final List<String> RISK_CODES = List.of(
            "WAIT_DURATION_POLICY_BOUNDED", "JOURNEY_SEMANTICS_DEFERRED", "RUNTIME_PREVIEW_ONLY");

    private static final Duration MAX_WAIT = Duration.ofSeconds(MAX_WAIT_SECONDS);

    /** The closed payload contract: one positive bounded duration and one closed anchor. */
    private static final CapabilityPayloadContract CONTRACT = new CapabilityPayloadContract(
            List.of(
                    new CapabilityFieldSchema(
                            ANCHOR_FIELD, new CapabilityFieldConstraint.EnumCodes(ANCHORS)),
                    new CapabilityFieldSchema(
                            DURATION_FIELD, new CapabilityFieldConstraint.DurationBounds(1, MAX_WAIT_SECONDS))),
            List.of());

    public FlowWaitPack() {
        super(CAPABILITY_CODE, CONTRACT, SUMMARY_CODES, RISK_CODES, corpus());
    }

    /** The ordered in-code evaluation fixture corpus of this node. */
    static List<FlowEvaluationFixture> corpus() {
        return List.of(
                new FlowEvaluationFixture("flow-wait-valid-entry", Expectation.ACCEPTED,
                        Map.of(DURATION_FIELD, "PT15M", ANCHOR_FIELD, ANCHOR_ENTRY)),
                new FlowEvaluationFixture("flow-wait-valid-previous-action", Expectation.ACCEPTED,
                        Map.of(DURATION_FIELD, "P1D", ANCHOR_FIELD, ANCHOR_PREVIOUS_ACCEPTED_ACTION)),
                new FlowEvaluationFixture("flow-wait-zero-rejected", Expectation.REJECTED,
                        Map.of(DURATION_FIELD, "PT0S", ANCHOR_FIELD, ANCHOR_ENTRY)),
                new FlowEvaluationFixture("flow-wait-negative-rejected", Expectation.REJECTED,
                        Map.of(DURATION_FIELD, "-PT1H", ANCHOR_FIELD, ANCHOR_ENTRY)),
                new FlowEvaluationFixture("flow-wait-over-maximum-rejected", Expectation.REJECTED,
                        Map.of(DURATION_FIELD, "P30DT1H", ANCHOR_FIELD, ANCHOR_ENTRY)),
                new FlowEvaluationFixture("flow-wait-not-iso-rejected", Expectation.REJECTED,
                        Map.of(DURATION_FIELD, "15 minutes", ANCHOR_FIELD, ANCHOR_ENTRY)),
                new FlowEvaluationFixture("flow-wait-unknown-anchor-rejected", Expectation.REJECTED,
                        Map.of(DURATION_FIELD, "PT5M", ANCHOR_FIELD, "AFTER_ACTION")),
                new FlowEvaluationFixture("flow-wait-dynamic-resume-rejected", Expectation.REJECTED,
                        Map.of(DURATION_FIELD, "PT5M", ANCHOR_FIELD, ANCHOR_ENTRY,
                                "resumeAt", "2026-01-01T00:00:00Z")));
    }

    @Override
    CapabilityPayload normalizeFields(Map<String, Object> fields) {
        String text = requireText(fields, DURATION_FIELD);
        Duration duration;
        try {
            duration = Duration.parse(text);
        } catch (DateTimeParseException rejected) {
            throw new FlowPackException(FlowPackErrorCodes.WAIT_DURATION_INVALID);
        }
        if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_WAIT) > 0) {
            throw new FlowPackException(FlowPackErrorCodes.WAIT_DURATION_INVALID);
        }
        String anchor = requireText(fields, ANCHOR_FIELD);
        if (!ANCHORS.contains(anchor)) {
            throw new FlowPackException(FlowPackErrorCodes.WAIT_ANCHOR_INVALID);
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put(ANCHOR_FIELD, anchor);
        canonical.put(DURATION_FIELD, duration.toString());
        return CapabilityPayload.of(canonical);
    }
}
