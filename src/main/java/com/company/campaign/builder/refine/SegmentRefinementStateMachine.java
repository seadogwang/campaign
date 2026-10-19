package com.company.campaign.builder.refine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed, deterministic state machine of one refinement run.
 *
 * <p>Every transition is derived from explicit input and returned evidence; there is no wall clock, no random
 * source, no default locale or timezone, no thread-local or global security context and no mutable "latest"
 * registry. A skipped, repeated, backward or post-terminal transition is refused deterministically, so the
 * recorded trace is always one of the finitely many legal paths, and a Skill-declared step bound is enforced
 * while the trace is being built rather than afterwards.
 */
public final class SegmentRefinementStateMachine {

    /** The evidence code of the single start transition. */
    public static final String START = "REFINEMENT_RUN_START";

    private static final Map<SegmentRefinementState, Set<SegmentRefinementState>> ALLOWED = allowedTransitions();

    private final int maxSteps;
    private final List<SegmentRefinementTransition> trace = new ArrayList<>();
    private SegmentRefinementState current;
    private boolean terminal;

    /** A machine bounded by the absolute step ceiling, for direct state-machine tests. */
    public SegmentRefinementStateMachine() {
        this(RefinementLimits.MAX_STEPS);
    }

    /** A machine bounded by the exact Skill-declared step ceiling of one run. */
    public SegmentRefinementStateMachine(int maxSteps) {
        if (maxSteps < 1 || maxSteps > RefinementLimits.MAX_STEPS) {
            throw refusal("A step bound must lie inside its absolute range");
        }
        this.maxSteps = maxSteps;
    }

    /** The step ceiling this machine enforces. */
    public int maxSteps() {
        return maxSteps;
    }

    /** True before the run has entered its first state. */
    public boolean notStarted() {
        return current == null;
    }

    /** Enters the first state, {@code REFINING}. */
    public void start() {
        if (current != null) {
            throw refusal("The run has already started");
        }
        append(null, SegmentRefinementState.REFINING, START, null);
    }

    /** Moves to the target state with explicit evidence, or refuses the transition deterministically. */
    public void transition(SegmentRefinementState to, String evidenceCode, String toolId) {
        if (to == null || evidenceCode == null) {
            throw refusal("A transition requires its target state and evidence");
        }
        if (current == null) {
            throw refusal("The run has not started");
        }
        if (terminal) {
            throw refusal("A terminal run accepts no further transition");
        }
        if (trace.size() >= maxSteps) {
            throw refusal("The run would exceed its Skill-declared step bound");
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(to)) {
            throw refusal("The transition is skipped, repeated or backward");
        }
        append(current, to, evidenceCode, toolId);
    }

    /** The current state, or null before the run started. */
    public SegmentRefinementState current() {
        return current;
    }

    /** True once a terminal state has been entered. */
    public boolean isTerminal() {
        return terminal;
    }

    /** The immutable ordered transition trace. */
    public List<SegmentRefinementTransition> trace() {
        return List.copyOf(trace);
    }

    private void append(SegmentRefinementState from, SegmentRefinementState to, String evidenceCode, String toolId) {
        trace.add(new SegmentRefinementTransition(from, to, evidenceCode, toolId, trace.size()));
        current = to;
        terminal = to.isTerminal();
    }

    private static SegmentRefinementException refusal(String message) {
        return SegmentRefinementException.of(
                SegmentRefinementAdapterErrorCodes.ILLEGAL_TRANSITION, message);
    }

    private static Map<SegmentRefinementState, Set<SegmentRefinementState>> allowedTransitions() {
        Map<SegmentRefinementState, Set<SegmentRefinementState>> allowed =
                new EnumMap<>(SegmentRefinementState.class);
        allowed.put(SegmentRefinementState.REFINING, EnumSet.of(
                SegmentRefinementState.DISCOVERING, SegmentRefinementState.DENIED,
                SegmentRefinementState.INVALID));
        allowed.put(SegmentRefinementState.DISCOVERING, EnumSet.of(
                SegmentRefinementState.APPLYING_PATCH, SegmentRefinementState.DENIED,
                SegmentRefinementState.INVALID));
        allowed.put(SegmentRefinementState.APPLYING_PATCH, EnumSet.of(
                SegmentRefinementState.VALIDATING, SegmentRefinementState.DENIED,
                SegmentRefinementState.INVALID));
        allowed.put(SegmentRefinementState.VALIDATING, EnumSet.of(
                SegmentRefinementState.PLANNING, SegmentRefinementState.DENIED,
                SegmentRefinementState.INVALID));
        allowed.put(SegmentRefinementState.PLANNING, EnumSet.of(
                SegmentRefinementState.PREVIEWING, SegmentRefinementState.DENIED,
                SegmentRefinementState.INVALID));
        allowed.put(SegmentRefinementState.PREVIEWING, EnumSet.of(
                SegmentRefinementState.READY_FOR_PREVIEW, SegmentRefinementState.DENIED,
                SegmentRefinementState.INVALID));
        return Map.copyOf(allowed);
    }
}