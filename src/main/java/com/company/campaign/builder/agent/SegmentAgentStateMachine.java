package com.company.campaign.builder.agent;

import com.company.campaign.builder.skill.SegmentSkillLimits;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed, deterministic state machine of one Agent run.
 *
 * <p>Every transition is derived from explicit input and returned evidence; there is no wall clock, no random
 * source, no default locale/timezone, no thread-local or global security context and no mutable "latest"
 * registry. A skipped, repeated, backward or post-terminal transition is refused deterministically, so the
 * recorded trace is always one of the finitely many legal paths.
 *
 * <p>The happy path is {@code UNDERSTANDING → DISCOVERING → RESOLVING → GENERATING_DSL → VALIDATING →
 * PLANNING → PREVIEWING → READY_FOR_PREVIEW}. Each state may also terminate at the one outcome its evidence
 * supports; no state may jump ahead or return to an earlier state.
 */
public final class SegmentAgentStateMachine {

    /** The evidence code of the single start transition. */
    public static final String START = "AGENT_RUN_START";

    private static final Map<SegmentAgentState, Set<SegmentAgentState>> ALLOWED = allowedTransitions();

    private final int maxSteps;
    private SegmentAgentState current;
    private boolean terminal;
    private final List<AgentTransition> trace = new ArrayList<>();

    /** A machine bounded by the absolute step ceiling, for direct state-machine tests. */
    public SegmentAgentStateMachine() {
        this(SegmentSkillLimits.MAX_STEPS);
    }

    /** A machine bounded by the exact Skill-declared step ceiling of one run. */
    public SegmentAgentStateMachine(int maxSteps) {
        if (maxSteps < 1 || maxSteps > SegmentSkillLimits.MAX_STEPS) {
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

    /** Enters the first state, {@code UNDERSTANDING}. */
    public void start() {
        if (current != null) {
            throw refusal("The run has already started");
        }
        append(null, SegmentAgentState.UNDERSTANDING, START, null);
    }

    /** Moves to the target state with explicit evidence, or refuses the transition deterministically. */
    public void transition(SegmentAgentState to, String evidenceCode, String toolId) {
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
    public SegmentAgentState current() {
        return current;
    }

    /** True once a terminal state has been entered. */
    public boolean isTerminal() {
        return terminal;
    }

    /** The immutable ordered transition trace. */
    public List<AgentTransition> trace() {
        return List.copyOf(trace);
    }

    private void append(SegmentAgentState from, SegmentAgentState to, String evidenceCode, String toolId) {
        trace.add(new AgentTransition(from, to, evidenceCode, toolId, trace.size()));
        current = to;
        terminal = to.isTerminal();
    }

    private static SegmentAgentException refusal(String message) {
        return SegmentAgentException.invalid(SegmentAgentErrorCodes.ILLEGAL_TRANSITION, message);
    }

    private static Map<SegmentAgentState, Set<SegmentAgentState>> allowedTransitions() {
        Map<SegmentAgentState, Set<SegmentAgentState>> allowed = new EnumMap<>(SegmentAgentState.class);
        allowed.put(SegmentAgentState.UNDERSTANDING, EnumSet.of(
                SegmentAgentState.DISCOVERING, SegmentAgentState.UNSUPPORTED, SegmentAgentState.INVALID));
        allowed.put(SegmentAgentState.DISCOVERING, EnumSet.of(
                SegmentAgentState.RESOLVING, SegmentAgentState.UNSUPPORTED, SegmentAgentState.DENIED,
                SegmentAgentState.INVALID));
        allowed.put(SegmentAgentState.RESOLVING, EnumSet.of(
                SegmentAgentState.GENERATING_DSL, SegmentAgentState.NEEDS_INPUT, SegmentAgentState.UNSUPPORTED,
                SegmentAgentState.DENIED, SegmentAgentState.INVALID));
        allowed.put(SegmentAgentState.GENERATING_DSL, EnumSet.of(
                SegmentAgentState.VALIDATING, SegmentAgentState.UNSUPPORTED, SegmentAgentState.DENIED,
                SegmentAgentState.INVALID));
        allowed.put(SegmentAgentState.VALIDATING, EnumSet.of(
                SegmentAgentState.PLANNING, SegmentAgentState.DENIED, SegmentAgentState.INVALID));
        allowed.put(SegmentAgentState.PLANNING, EnumSet.of(
                SegmentAgentState.PREVIEWING, SegmentAgentState.DENIED, SegmentAgentState.INVALID));
        allowed.put(SegmentAgentState.PREVIEWING, EnumSet.of(
                SegmentAgentState.READY_FOR_PREVIEW, SegmentAgentState.DENIED, SegmentAgentState.INVALID));
        return Map.copyOf(allowed);
    }
}