package com.company.campaign.builder.skill;

import java.util.List;
import java.util.Set;

/**
 * The closed set of terminal Agent state names a Segment Skill may declare.
 *
 * <p>These are the exact v1.3 terminal outcomes for this task. The wider user-save terminal is deliberately
 * absent because T16/T17 are not implemented, so a Skill cannot declare a terminal state this task can never
 * reach. A saved, applied, published, active or live-ready state is not a state at all, so a resource that
 * names one is refused.
 */
public final class SegmentSkillTerminalStates {

    /** Awaiting genuine T10 clarification input. */
    public static final String NEEDS_INPUT = "NEEDS_INPUT";

    /** The requested capability does not exist in this system. */
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /** The capability exists but this principal or DataScope cannot use it. */
    public static final String DENIED = "DENIED";

    /** The request was understood but violates a contract. */
    public static final String INVALID = "INVALID";

    /** Safe Preview and grounded explanation are ready; nothing has been saved or published. */
    public static final String READY_FOR_PREVIEW = "READY_FOR_PREVIEW";

    private static final List<String> ORDERED =
            List.of(NEEDS_INPUT, UNSUPPORTED, DENIED, INVALID, READY_FOR_PREVIEW);

    private static final Set<String> ALL = Set.copyOf(ORDERED);

    private SegmentSkillTerminalStates() {
    }

    /** The closed terminal state names in canonical order. */
    public static List<String> ordered() {
        return ORDERED;
    }

    /** True when the name is one of the closed terminal states. */
    public static boolean isTerminal(String name) {
        return name != null && ALL.contains(name);
    }
}