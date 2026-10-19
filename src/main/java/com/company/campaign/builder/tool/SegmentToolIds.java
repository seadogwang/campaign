package com.company.campaign.builder.tool;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The closed, server-owned set of Tool identifiers a T11 Skill may allow.
 *
 * <p>Only capability classes this bound is willing to expose appear here: Metadata discovery/detail over the
 * permitted T04 snapshot, deterministic Intent resolution/clarification through T10, deterministic DSL
 * validation/planning through T07/T08, read-only Safe Preview through T09C and grounded explanation derived
 * from accepted evidence. There is deliberately no generic reflection, script, URL, repository, JDBC or
 * arbitrary-JSON callback Tool, and no Save/Apply/Publish/Pause/Retry/Archive/mutation Tool: those actions are
 * host-only and are not representable as an identifier in this set.
 *
 * <p>The identifiers are server-owned, so a Skill resource can only select from this set. It can never
 * register a new Tool, name a provider implementation or widen the capability surface.
 */
public final class SegmentToolIds {

    /** Metadata discovery over the exact permitted T04 snapshot. */
    public static final String METADATA_DISCOVER = "metadata.discover";

    /**
     * Metadata detail for one permitted definition of the exact T04 snapshot.
     *
     * <p>This is a server-owned capability of the closed catalog, but the committed Skill version does not
     * permit it, because this adapter's fixed flow never executes it. It stays in the server-owned set so a
     * future versioned Skill can select it, and it is refused for this Skill exactly like any other Tool the
     * allowlist does not name.
     */
    public static final String METADATA_DETAIL = "metadata.detail";

    /** Deterministic Intent resolution/clarification through T10. */
    public static final String INTENT_RESOLVE = "intent.resolve";

    /** Deterministic DSL validation/canonicalization through T07. */
    public static final String DSL_VALIDATE = "dsl.validate";

    /** Deterministic query planning through T08. */
    public static final String PLAN_BUILD = "plan.build";

    /** Read-only Safe Preview through the genuine T09C service. */
    public static final String PREVIEW_SAFE = "preview.safe";

    /** Grounded explanation derived only from accepted resolution/validation/plan/preview evidence. */
    public static final String EXPLANATION_GROUNDED = "explanation.grounded";

    /**
     * Read-only discovery of the audiences a caller may reference, over the host-owned directory.
     *
     * <p>Like {@link #METADATA_DETAIL}, this is a server-owned capability of the closed catalog that the
     * committed Skill version does not permit. It stays in the server-owned set so a future versioned Skill can
     * select it, and it is refused for the committed Skill exactly like any other Tool the allowlist omits.
     */
    public static final String AUDIENCE_REFERENCE_DISCOVER = "audience.reference.discover";

    /** Read-only resolution of one Existing Audience Reference to one immutable published version. */
    public static final String AUDIENCE_REFERENCE_RESOLVE = "audience.reference.resolve";

    /** Every identifier this build will ever admit, in the server-owned canonical order. */
    private static final List<String> ORDERED = List.of(
            METADATA_DISCOVER, METADATA_DETAIL, INTENT_RESOLVE, DSL_VALIDATE, PLAN_BUILD, PREVIEW_SAFE,
            EXPLANATION_GROUNDED, AUDIENCE_REFERENCE_DISCOVER, AUDIENCE_REFERENCE_RESOLVE);

    private static final Set<String> ALL = Set.copyOf(ORDERED);

    /**
     * Normalized tokens that name a host-only action or an unsafe data path.
     *
     * <p>They are refused explicitly, before the unknown-identifier rule, so an attempted mutation or data
     * capability is reported as such rather than as a merely unknown Tool. Matching removes separators and
     * case, so {@code Save}, {@code save.draft}, {@code SAVE-DRAFT} and {@code saveDraft} are all refused.
     */
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
            "save", "savedraft", "apply", "publish", "unpublish", "pause", "resume", "retry", "archive",
            "delete", "reject", "confirm", "approve", "mutate", "mutation", "cancel",
            "fork", "prepublish",
            // Retention and erasure are host lifecycle policy, never a Tool: T18 forbids retaining,
            // releasing or deleting an Audience Version through any model-reachable name, so the same
            // normalize-and-match rule that refuses Save/Apply/Publish refuses these too.
            "retain", "retention", "release", "purge", "erase", "destroy",
            "runtime", "activate", "activation",
            "sql", "jdbc", "datasource", "database", "query", "ddl", "dml",
            "repository", "storage", "catalog",
            "credential", "credentials", "secret", "token", "apikey", "provider", "endpoint", "url",
            "script", "reflection", "eval", "callback", "invoke");

    private SegmentToolIds() {
    }

    /** The server-owned canonical order of every permitted Tool identifier. */
    public static List<String> ordered() {
        return ORDERED;
    }

    /** Every permitted Tool identifier. */
    public static Set<String> all() {
        return ALL;
    }

    /** True when the identifier is one of the closed server-owned Tool identifiers. */
    public static boolean isKnown(String toolId) {
        return toolId != null && ALL.contains(toolId);
    }

    /**
     * True when the identifier attempts a host-only or unsafe capability.
     *
     * <p>The check is deliberately coarse and normalization-based: refusing a legitimate identifier is the
     * safe direction, whereas missing an alias would let a mutation-capable Tool be allowlisted.
     */
    public static boolean attemptsForbiddenCapability(String toolId) {
        if (toolId == null) {
            return false;
        }
        StringBuilder normalized = new StringBuilder(toolId.length());
        for (int index = 0; index < toolId.length(); index++) {
            char character = toolId.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        if (normalized.length() == 0) {
            return true;
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (normalized.indexOf(token) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Locale-independent normalization used only for diagnostics and reserved-token matching. */
    static String normalize(String toolId) {
        return toolId == null ? null : toolId.toLowerCase(Locale.ROOT);
    }
}