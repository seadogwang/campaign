package com.company.campaign.builder.assembly;

import java.util.List;
import java.util.Set;

/**
 * The closed, server-owned Campaign Tool vocabulary.
 *
 * <p>Two disjoint halves exist. The <em>implemented</em> half is the exact set of model-callable Tools a Skill
 * may invoke in this build: Message (both the catalog-template Tools and the one inline-draft preview Tool),
 * Promotion, {@code schedule.basic} and {@code governance.limit}. The
 * <em>planned</em> half reserves the identifiers a later Journey Skill and a later occurrence-preview Tool will
 * use; every planned descriptor is marked unavailable and the invoker refuses it before any handler runs, so a
 * planned name is representable but never executable. No behaviour is fabricated for a planned Tool.
 *
 * <p>The Journey Skill is fully implemented and owns four model-callable Tools. A Journey is a set of accepted
 * T19 Flow-node components plus one separate T20 {@code JourneyTopology}, not one {@code journey.flow}
 * capability payload, so its Tools are topology-aware and only the final validation Tool is stageable.
 * {@code schedule.occurrences.preview} stays planned: the accepted pure T21 occurrence semantics demand a
 * source Manifest hash and a Journey hash that do not exist before publication, so this boundary cannot supply
 * them.
 *
 * <p>The accepted Segment Tool surface is deliberately reused, not duplicated: the Campaign catalog exposes one
 * host-only bridge descriptor to the accepted Segment artifact and never renames or reimplements
 * {@code metadata.discover}, {@code intent.resolve}, {@code dsl.validate}, {@code plan.build},
 * {@code preview.safe} or {@code explanation.grounded}.
 *
 * <p>There is no generic {@code execute}, script, reflection, URL, HTTP, JDBC, SQL, repository, arbitrary JSON
 * or dynamic registration identifier. Names that attempt Save, Apply, Publish, Pause, Resume, Retry, Archive,
 * Runtime activation, Provider calls, credential access or storage access are refused explicitly, before the
 * unknown-identifier rule, so an attempted host-only action is reported as such rather than as merely unknown.
 */
public final class CampaignToolIds {

    // ---- implemented, model-callable Message Tools -------------------------------------------------

    /** Bounded discovery over the host-supplied immutable Message Template catalog. */
    public static final String MESSAGE_TEMPLATE_DISCOVER = "message.template.discover";

    /** Resolve one reference to one exact enabled/runtime-compatible immutable template version. */
    public static final String MESSAGE_TEMPLATE_RESOLVE = "message.template.resolve";

    /** Validate supplied variable names/types/bounds against the exact resolved template version. */
    public static final String MESSAGE_VARIABLES_VALIDATE = "message.variables.validate";

    /** Return a bounded, non-disclosing safe card for one resolved and validated template version. */
    public static final String MESSAGE_PREVIEW_SAFE = "message.preview.safe";

    /**
     * Validate and safely preview one inline Campaign message draft over a canonical channel and bounded body.
     *
     * <p>It is the one Message Tool that needs no catalog reference: the host-owned typed request already
     * carries the exact canonical channel and the exact bounded body, so the handler only re-proves that
     * input and derives deterministic hashes and a stable identity. It neither persists the draft nor claims
     * it is sendable.
     */
    public static final String MESSAGE_INLINE_PREVIEW_SAFE = "message.inline.preview.safe";

    // ---- implemented, model-callable Promotion Tools ----------------------------------------------

    /** Bounded discovery over the host-supplied immutable Reward catalog. */
    public static final String REWARD_DISCOVER = "reward.discover";

    /** Resolve one reference to one exact enabled/runtime-compatible immutable reward version. */
    public static final String REWARD_RESOLVE = "reward.resolve";

    /** Deterministically calculate bounded per-recipient and declared maximum exposure. */
    public static final String REWARD_EXPOSURE_CALCULATE = "reward.exposure.calculate";

    /** Return a bounded, non-disclosing safe card for one resolved reward version. */
    public static final String REWARD_PREVIEW_SAFE = "reward.preview.safe";

    // ---- host-only bridge to the accepted Segment artifact ----------------------------------------

    /**
     * A host-only typed bridge to an accepted {@code segment.rule-based} Skill artifact.
     *
     * <p>It is deliberately not model-callable. The accepted Segment Tools are reused through their own
     * catalog; this descriptor only records that the assembly layer stages the accepted Segment component
     * unchanged, so its owning Skill and catalog binding are explicit rather than implicit.
     */
    public static final String SEGMENT_ARTIFACT_REFERENCE = "segment.artifact.reference";

    // ---- implemented, model-callable Schedule Tools ------------------------------------------------

    /** Normalize one bounded {@code schedule.basic} declaration through the accepted T19 pack. */
    public static final String SCHEDULE_NORMALIZE = "schedule.normalize";

    /** Validate one normalized {@code schedule.basic} declaration through the accepted T19 pack. */
    public static final String SCHEDULE_VALIDATE = "schedule.validate";

    // ---- implemented, model-callable Governance Tools ---------------------------------------------

    /** Deterministically calculate the bounded declared exposure of one {@code governance.limit} declaration. */
    public static final String GOVERNANCE_EXPOSURE_CALCULATE = "governance.exposure.calculate";

    /** Validate one {@code governance.limit} declaration through the accepted T19 pack. */
    public static final String GOVERNANCE_VALIDATE = "governance.validate";

    // ---- implemented, model-callable Journey Tools -------------------------------------------------

    /** Reconstruct the closed Journey node capability catalog. */
    public static final String JOURNEY_NODE_CATALOG = "journey.node.catalog";

    /** Validate and normalize every declared Journey node through its accepted T19 Flow pack. */
    public static final String JOURNEY_COMPOSE = "journey.compose";

    /** Validate the complete authoring graph and compile the atomic Journey bundle. */
    public static final String JOURNEY_VALIDATE = "journey.validate";

    /** Project the bounded pure preview of a virtual-clock simulation; never stageable. */
    public static final String JOURNEY_SIMULATE = "journey.simulate";

    // ---- planned, unavailable future Tools ---------------------------------------------------------

    /**
     * Planned: project the bounded occurrences of a Schedule.
     *
     * <p>It stays unavailable: the accepted pure T21 semantics compile against an exact source Manifest hash and
     * Journey hash, and neither authoritative input exists at this authoring boundary.
     */
    public static final String SCHEDULE_OCCURRENCES_PREVIEW = "schedule.occurrences.preview";

    /** Every implemented, model-callable Tool identifier, in the server-owned canonical order. */
    private static final List<String> IMPLEMENTED_ORDERED = List.of(
            MESSAGE_TEMPLATE_DISCOVER, MESSAGE_TEMPLATE_RESOLVE, MESSAGE_VARIABLES_VALIDATE, MESSAGE_PREVIEW_SAFE,
            MESSAGE_INLINE_PREVIEW_SAFE,
            REWARD_DISCOVER, REWARD_RESOLVE, REWARD_EXPOSURE_CALCULATE, REWARD_PREVIEW_SAFE,
            JOURNEY_NODE_CATALOG, JOURNEY_COMPOSE, JOURNEY_VALIDATE, JOURNEY_SIMULATE,
            SCHEDULE_NORMALIZE, SCHEDULE_VALIDATE,
            GOVERNANCE_EXPOSURE_CALCULATE, GOVERNANCE_VALIDATE);

    /** Every planned, unavailable Tool identifier, in the server-owned canonical order. */
    private static final List<String> PLANNED_ORDERED = List.of(SCHEDULE_OCCURRENCES_PREVIEW);

    /** Every identifier this build will ever admit, in the server-owned canonical order. */
    private static final List<String> ORDERED = List.of(
            MESSAGE_TEMPLATE_DISCOVER, MESSAGE_TEMPLATE_RESOLVE, MESSAGE_VARIABLES_VALIDATE, MESSAGE_PREVIEW_SAFE,
            MESSAGE_INLINE_PREVIEW_SAFE,
            REWARD_DISCOVER, REWARD_RESOLVE, REWARD_EXPOSURE_CALCULATE, REWARD_PREVIEW_SAFE,
            SEGMENT_ARTIFACT_REFERENCE,
            JOURNEY_NODE_CATALOG, JOURNEY_COMPOSE, JOURNEY_VALIDATE, JOURNEY_SIMULATE,
            SCHEDULE_NORMALIZE, SCHEDULE_VALIDATE, SCHEDULE_OCCURRENCES_PREVIEW,
            GOVERNANCE_EXPOSURE_CALCULATE, GOVERNANCE_VALIDATE);

    private static final Set<String> ALL = Set.copyOf(ORDERED);
    private static final Set<String> IMPLEMENTED = Set.copyOf(IMPLEMENTED_ORDERED);
    private static final Set<String> PLANNED = Set.copyOf(PLANNED_ORDERED);

    /**
     * Normalized tokens that name a host-only action or an unsafe data path.
     *
     * <p>Matching removes separators and case, so {@code Save}, {@code save.draft}, {@code SAVE-DRAFT} and
     * {@code saveDraft} are all refused. {@code catalog} is deliberately absent: the server-owned
     * {@code journey.node.catalog} identifier legitimately contains it, and the closed vocabulary is the
     * authority on which names exist.
     */
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
            "save", "savedraft", "apply", "publish", "unpublish", "pause", "resume", "retry", "archive",
            "delete", "reject", "confirm", "approve", "mutate", "mutation", "cancel", "fork", "prepublish",
            "retain", "retention", "release", "purge", "erase", "destroy",
            "runtime", "activate", "activation",
            "sql", "jdbc", "datasource", "database", "ddl", "dml",
            "repository", "storage",
            "credential", "credentials", "secret", "token", "apikey", "provider", "endpoint", "url",
            "script", "reflection", "callback", "invoke", "execute",
            "inventory", "reserve", "reservation", "balance");

    private CampaignToolIds() {
    }

    /** Every implemented, model-callable Tool identifier, in canonical order. */
    public static List<String> implemented() {
        return IMPLEMENTED_ORDERED;
    }

    /** Every planned, unavailable Tool identifier, in canonical order. */
    public static List<String> planned() {
        return PLANNED_ORDERED;
    }

    /** Every identifier this build admits, in the server-owned canonical order. */
    public static List<String> ordered() {
        return ORDERED;
    }

    /** Every identifier this build admits. */
    public static Set<String> all() {
        return ALL;
    }

    /** True when the identifier is part of the closed, server-owned vocabulary. */
    public static boolean isKnown(String toolId) {
        return toolId != null && ALL.contains(toolId);
    }

    /** True when the identifier is an implemented, model-callable Tool of this build. */
    public static boolean isImplemented(String toolId) {
        return toolId != null && IMPLEMENTED.contains(toolId);
    }

    /** True when the identifier is a planned Tool this build deliberately cannot execute. */
    public static boolean isPlanned(String toolId) {
        return toolId != null && PLANNED.contains(toolId);
    }

    /** True only for Tool results that may become a staged Campaign component. */
    public static boolean isStageableArtifact(String skillId, String toolId) {
        if (skillId == null || toolId == null) {
            return false;
        }
        return switch (skillId) {
            case CampaignSkillIds.SEGMENT_RULE_BASED -> SEGMENT_ARTIFACT_REFERENCE.equals(toolId);
            case CampaignSkillIds.MESSAGE_TEMPLATE ->
                    MESSAGE_PREVIEW_SAFE.equals(toolId) || MESSAGE_INLINE_PREVIEW_SAFE.equals(toolId);
            case CampaignSkillIds.PROMOTION_REFERENCE -> REWARD_PREVIEW_SAFE.equals(toolId);
            case CampaignSkillIds.SCHEDULE_BASIC -> SCHEDULE_VALIDATE.equals(toolId);
            case CampaignSkillIds.GOVERNANCE_LIMIT -> GOVERNANCE_VALIDATE.equals(toolId);
            // The Journey Skill stages one atomic bundle, and only its final validation Tool may produce it:
            // the catalog, the composition and the simulation are never stageable, whatever they report.
            case CampaignSkillIds.JOURNEY_FLOW -> JOURNEY_VALIDATE.equals(toolId);
            default -> false;
        };
    }

    /**
     * True when the identifier attempts a host-only or unsafe capability.
     *
     * <p>The check is coarse and normalization-based: refusing a legitimate identifier is the safe direction,
     * whereas missing an alias would let a mutation-capable Tool be allowlisted. It uses substring matching on
     * the normalised form, which is the right rule for capability <em>identifiers</em> (e.g. {@code save.draft}
     * normalises to {@code savedraft}) but too aggressive for prose.
     */
    public static boolean attemptsForbiddenCapability(String toolId) {
        if (toolId == null) {
            return false;
        }
        return containsForbiddenToken(normalizeAlpha(toolId));
    }

    /**
     * True when a prose string contains a forbidden token as a whole word.
     *
     * <p>It is the natural-language counterpart of {@link #attemptsForbiddenCapability(String)}: it normalises
     * the input identically but splits at the <em>original non-alphanumeric boundaries</em> and matches each
     * token as a whole word. That way {@code description} (token {@code description}) does not match
     * {@code script}, but {@code Enable the script} (token {@code script}) does, and a forbidden token
     * concatenated across a separator ({@code save.draft} normalised to {@code savedraft}) is caught because
     * the token itself is the forbidden value.
     */
    public static boolean attemptsForbiddenCapabilityInProse(String prose) {
        if (prose == null) {
            return false;
        }
        // Split on non-alphanumeric boundaries and match each token as a whole word
        // against the exact forbidden set — not via substring matching, so that
        // a prose token like "description" is not falsely matched against "script".
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < prose.length(); index++) {
            char character = prose.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                current.append(Character.toLowerCase(character));
            } else {
                if (current.length() > 0) {
                    if (FORBIDDEN_TOKENS.contains(current.toString())) {
                        return true;
                    }
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            if (FORBIDDEN_TOKENS.contains(current.toString())) {
                return true;
            }
        }
        return false;
    }

    /** True when the normalised token exactly equals a forbidden token (substring match). */
    private static boolean containsForbiddenToken(String normalized) {
        if (normalized.isEmpty()) {
            return true;
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (normalized.indexOf(token) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Normalise a string to lowercase alphanumeric characters only. */
    private static String normalizeAlpha(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }
}
