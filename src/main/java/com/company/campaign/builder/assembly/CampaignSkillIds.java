package com.company.campaign.builder.assembly;

import com.company.campaign.builder.capability.flow.FlowPackVersions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed, server-owned Skill vocabulary of the Campaign assembly layer.
 *
 * <p>A Skill is a named, versioned orchestration unit that may invoke only the Tools its own allowlist names.
 * The set is closed and server-owned: a Skill resource, a model payload or a later task cannot introduce a
 * Skill identifier, rename the accepted {@code segment.rule-based} Skill, or claim a capability it does not
 * own.
 *
 * <p>Every Skill in this build has an accepted implementation. Five of them own exactly one capability;
 * {@code journey.flow} is the one multi-component Skill, because a Journey is a bounded set of accepted T19
 * Flow-node components plus one separate T20 {@code JourneyTopology}, never one capability payload. It
 * therefore owns exactly the six accepted Flow node capabilities and no other, and it owns no fabricated
 * single capability of its own name.
 *
 * <p>The public Skill identifier {@code journey.flow} remains the routing, progress and proposal-emission
 * identity; it is deliberately never a capability code.
 */
public final class CampaignSkillIds {

    /** The accepted T11/T16 rule-based Segment Skill, adapted only at the assembly boundary. */
    public static final String SEGMENT_RULE_BASED = "segment.rule-based";

    /** The Message template Skill. */
    public static final String MESSAGE_TEMPLATE = "message.template";

    /** The Promotion reference Skill. */
    public static final String PROMOTION_REFERENCE = "promotion.reference";

    /** The topology-aware Journey flow Skill, which orchestrates the accepted Flow node capabilities. */
    public static final String JOURNEY_FLOW = "journey.flow";

    /** The accepted Schedule Skill. */
    public static final String SCHEDULE_BASIC = "schedule.basic";

    /** The accepted Governance Skill. */
    public static final String GOVERNANCE_LIMIT = "governance.limit";

    /** The one accepted version of every Skill in this build. */
    public static final String CURRENT_VERSION = "1.0";

    /** Whether a Skill has an accepted implementation this build can run. */
    private static final Set<String> IMPLEMENTED = Set.of(
            SEGMENT_RULE_BASED, MESSAGE_TEMPLATE, PROMOTION_REFERENCE, JOURNEY_FLOW, SCHEDULE_BASIC,
            GOVERNANCE_LIMIT);

    /** Every Skill identifier in the server-owned canonical order. */
    private static final List<String> ORDERED = List.of(
            SEGMENT_RULE_BASED, MESSAGE_TEMPLATE, PROMOTION_REFERENCE,
            JOURNEY_FLOW, SCHEDULE_BASIC, GOVERNANCE_LIMIT);

    private static final Set<String> ALL = Set.copyOf(ORDERED);

    /**
     * The exact set of capabilities each Skill owns.
     *
     * <p>A capability is the concrete, compilable component a Skill produces; the mapping is server-owned so a
     * Skill can never claim a capability that belongs to another Skill. Five Skills own exactly one capability
     * each; the Journey Skill owns exactly the six accepted Flow node capabilities, which is what makes its
     * artifact a bounded multi-component bundle rather than one component.
     */
    private static final Map<String, List<String>> CAPABILITIES_BY_SKILL = capabilityIndex();

    private CampaignSkillIds() {
    }

    /** Every Skill identifier in the server-owned canonical order. */
    public static List<String> ordered() {
        return ORDERED;
    }

    /** Every Skill identifier. */
    public static Set<String> all() {
        return ALL;
    }

    /** Every Skill identifier this build has an accepted implementation for, in canonical order. */
    public static List<String> implementedSkills() {
        List<String> implemented = new java.util.ArrayList<>(IMPLEMENTED.size());
        for (String skillId : ORDERED) {
            if (IMPLEMENTED.contains(skillId)) {
                implemented.add(skillId);
            }
        }
        return List.copyOf(implemented);
    }

    /** True when the identifier is one of the closed, server-owned Skill identifiers. */
    public static boolean isKnown(String skillId) {
        return skillId != null && ALL.contains(skillId);
    }

    /** True when this build has an accepted implementation for the Skill. */
    public static boolean isImplemented(String skillId) {
        return skillId != null && IMPLEMENTED.contains(skillId);
    }

    /** The one server-owned version of a Skill, or null when the identifier is not server-owned. */
    public static String versionOf(String skillId) {
        return isKnown(skillId) ? CURRENT_VERSION : null;
    }

    /**
     * Every capability a Skill owns, in the server-owned declaration order.
     *
     * <p>It is empty for an identifier that is not server-owned. A single-component Skill returns a one-element
     * list; the Journey Skill returns the six accepted Flow node capabilities, so its ownership is as precise
     * as every other Skill's rather than being a wildcard.
     */
    public static List<String> capabilitiesOf(String skillId) {
        return skillId == null ? List.of() : CAPABILITIES_BY_SKILL.getOrDefault(skillId, List.of());
    }

    /**
     * The one capability a single-component Skill owns, or null when the identifier is not server-owned or owns
     * several capabilities.
     *
     * <p>The multi-component Journey Skill therefore answers null here: there is no single capability that
     * stands for a Journey, and returning one would be exactly the false capability this vocabulary refuses.
     * Callers that need the whole ownership use {@link #capabilitiesOf(String)}.
     */
    public static String capabilityOf(String skillId) {
        List<String> owned = capabilitiesOf(skillId);
        return owned.size() == 1 ? owned.get(0) : null;
    }

    /** True when the Skill owns the capability code. */
    public static boolean ownsCapability(String skillId, String capabilityCode) {
        return capabilityCode != null && capabilitiesOf(skillId).contains(capabilityCode);
    }

    private static Map<String, List<String>> capabilityIndex() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put(SEGMENT_RULE_BASED, List.of(SEGMENT_RULE_BASED));
        index.put(MESSAGE_TEMPLATE, List.of(MESSAGE_TEMPLATE));
        index.put(PROMOTION_REFERENCE, List.of(PROMOTION_REFERENCE));
        index.put(JOURNEY_FLOW, List.copyOf(FlowPackVersions.CAPABILITY_CODES));
        index.put(SCHEDULE_BASIC, List.of(SCHEDULE_BASIC));
        index.put(GOVERNANCE_LIMIT, List.of(GOVERNANCE_LIMIT));
        return Map.copyOf(index);
    }
}