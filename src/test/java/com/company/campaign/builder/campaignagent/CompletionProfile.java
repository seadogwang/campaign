package com.company.campaign.builder.campaignagent;

import java.util.Objects;

/**
 * Test-only bounded natural facts for one collaboration probe scenario.
 *
 * <p>Each field corresponds to one bounded domain fact the test driver supplies when the
 * host asks for it via a {@link CampaignRequirementCode}. A null field means the profile
 * has no fact for that dimension; the planner must stop rather than invent one unless
 * the code has a safe built-in default (e.g. {@code BRANCH_DECISION_REQUIRED} always
 * answers "不设置分支流程").
 *
 * <p>This class is package-private — it lives under {@code src/test} and is never
 * visible to production code.
 */
record CompletionProfile(
        /** The natural-language audience description grounded in the catalog scenario. May be null. */
        String audience,
        /** The natural-language offer decision (whether an offer is provided and at what level).
         *  May be null. */
        String offerDecision,
        /** The bounded offer detail description when an offer exists. May be null. */
        String offerDetail,
        /** The preferred channel expressed as a natural keyword (e.g. {@code "SMS"}, {@code "Email"}).
         *  May be null. */
        String channel,
        /** The bounded message copy text. May be null. */
        String messageCopy,
        /** Explicit branch decision; defaults to "不设置分支流程" when absent. */
        String branchDecision,
        /** Whether the profile opts into branching (detail/rule questions). */
        boolean optedIntoBranching,
        /** Cadence description; defaults to "单次执行" when absent. */
        String cadence,
        /** Timezone identifier (e.g. {@code "Asia/Shanghai"}). May be null. */
        String timezone,
        /** An exact future execution instant relative to 2025-06-15 (e.g. {@code "2025-07-01T09:00:00"}).
         *  May be null. */
        String executionTime,
        /** A future schedule date relative to 2025-06-15 (e.g. {@code "2025-07-01"}).
         *  May be null. */
        String executionDate,
        /** An explicit registered event name for event-triggered campaigns. May be null. */
        String registeredEvent) {

    public CompletionProfile {
        Objects.requireNonNull(branchDecision, "branchDecision");
        Objects.requireNonNull(cadence, "cadence");
    }

    /** A profile with only safe defaults and no explicit facts. */
    static CompletionProfile minimal() {
        return new CompletionProfile(
                null, null, null, null, null,
                "不设置分支流程", false, "单次执行",
                null, null, null, null);
    }

    /** A bounded diagnostic: shows populated fields only, never the full text. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CompletionProfile[");
        boolean first = true;
        if (audience != null) { sb.append("audience"); first = false; }
        if (offerDecision != null) { if (!first) sb.append(", "); sb.append("offerDecision"); first = false; }
        if (offerDetail != null) { if (!first) sb.append(", "); sb.append("offerDetail"); first = false; }
        if (channel != null) { if (!first) sb.append(", "); sb.append("channel"); first = false; }
        if (messageCopy != null) { if (!first) sb.append(", "); sb.append("messageCopy"); first = false; }
        if (!"不设置分支流程".equals(branchDecision)) { if (!first) sb.append(", "); sb.append("branchDecision"); first = false; }
        if (optedIntoBranching) { if (!first) sb.append(", "); sb.append("optedIntoBranching"); first = false; }
        if (!"单次执行".equals(cadence)) { if (!first) sb.append(", "); sb.append("cadence"); first = false; }
        if (timezone != null) { if (!first) sb.append(", "); sb.append("timezone"); first = false; }
        if (executionTime != null) { if (!first) sb.append(", "); sb.append("executionTime"); first = false; }
        if (executionDate != null) { if (!first) sb.append(", "); sb.append("executionDate"); first = false; }
        if (registeredEvent != null) { if (!first) sb.append(", "); sb.append("registeredEvent"); first = false; }
        sb.append(']');
        return sb.toString();
    }
}