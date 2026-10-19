package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolIds;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The closed content vocabulary the bounded routing output is judged against.
 *
 * <p>Three refusals are enforced here, and all three reuse accepted T27 server-owned vocabulary rather than
 * declaring a second, weaker list:
 * <ol>
 *   <li>a Skill identifier must be one of the server-owned {@link CampaignSkillIds};</li>
 *   <li>no string may name a Campaign Tool, so a routing decision can never smuggle a Tool identifier or a
 *       Tool argument into the conversation;</li>
 *   <li>no string may name a host-only action or an unsafe data path, judged by the accepted
 *       {@link CampaignToolIds#attemptsForbiddenCapability(String)} rule.</li>
 * </ol>
 *
 * <p>Refusing a legitimate wording is the safe direction; a routing decision that cannot be executed is a
 * clarification, whereas a decision that smuggles an action through is a mutation path.
 */
public final class CampaignRoutingVocabulary {

    /**
     * The one stable question code a Campaign type clarification must carry.
     *
     * <p>It is server-owned vocabulary, not model copy: a client branches on this exact code, so a type
     * clarification whose options are the three canonical {@link CampaignType} options must carry exactly it.
     */
    public static final String TYPE_REQUIRED_QUESTION_CODE = "CAMPAIGN_TYPE_REQUIRED";

    private CampaignRoutingVocabulary() {
    }

    /** Refuses a Skill identifier that is not part of the closed server-owned vocabulary. */
    public static void requireKnownSkill(String skillId) {
        if (!CampaignSkillIds.isKnown(skillId)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_UNKNOWN_SKILL, "skillId",
                    "A routing decision may only name a server-owned Skill.");
        }
    }

    /**
     * Refuses a bounded model-supplied string that names a Tool, a Tool argument or a host-only action.
     *
     * <p>The Tool rule is deliberately broader than an exact-identifier match: any occurrence of a declared
     * Tool identifier, of a Tool-shaped token, or of a host-only action token is refused, so neither
     * {@code message.preview.safe} nor {@code saveDraft} can appear in assistant copy or in an option label.
     *
     * <p>Prose-aware forbidden-token matching is used for host-only action tokens, so a word like
     * {@code description} that happens to contain {@code script} as a substring does not trigger a false
     * positive, while {@code script} as a standalone token is still caught. Physical identifier leakage
     * (table/column names, stack traces, SQL-shaped phrases and JDBC connection-string tokens) is also
     * refused here — see {@link #containsPhysicalIdentifier(String)}.
     */
    public static void requireSafeCopy(String value, String invariant) {
        if (value == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant, "A bounded string is required.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                        "A bounded string must not carry control characters.");
            }
        }
        if (containsPhysicalIdentifier(value)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                    "A routing decision must not expose an internal physical identifier.");
        }
        if (CampaignToolIds.attemptsForbiddenCapabilityInProse(value)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                    "A routing decision must not name a host-only action or an unsafe data path.");
        }
        for (String toolId : CampaignToolIds.ordered()) {
            if (value.contains(toolId)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                        "A routing decision must not name a Tool.");
            }
        }
    }

    /**
     * Refuses a bounded server-owned machine code (a question code or a status code) that carries an
     * unsafe token.
     *
     * <p>Machine codes are strictly bounded by the {@code ^[A-Z][A-Z0-9_]{0,63}$} grammar, so they can
     * never contain a Tool identifier, a host-only action token or prose-level punctuation. The physical
     * identifier check is deliberately skipped here: a valid machine code such as
     * {@code CAMPAIGN_GOAL_CLARIFICATION} is an uppercase server-owned constant, not user-facing prose
     * that could leak a physical table or column name. This method still enforces null, control-character,
     * Tool and forbidden-capability refusals as a defence-in-depth assertion whose failure is a build-level
     * defect, not an input-level violation.
     */
    public static void requireSafeMachineCode(String value, String invariant) {
        if (value == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant, "A bounded machine code is required.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                        "A machine code must not carry control characters.");
            }
        }
        // Defence-in-depth: a machine code that passes the uppercase-underscore grammar can never name a Tool
        // or a host-only action, but the check is cheap, idempotent and cannot be sabotaged by a runtime agent.
        if (CampaignToolIds.attemptsForbiddenCapabilityInProse(value)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                    "A machine code must not name a host-only action or an unsafe data path.");
        }
        for (String toolId : CampaignToolIds.ordered()) {
            if (value.contains(toolId)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_FORBIDDEN_TOKEN, invariant,
                        "A machine code must not name a Tool.");
            }
        }
    }

    /**
     * True when the prose string leaks an internal physical identifier.
     *
     * <p>Checks for: snake_case with three or more underscore segments (physical table names),
     * {@code _id}-suffixed tokens (physical column names), {@code table <identifier>} or
     * {@code column <identifier>} patterns, stack-trace lines, JDBC connection-string tokens, and
     * SQL-shaped phrase combinations. A legitimate business token like {@code rule-based} uses a
     * hyphen, not underscores, and ordinary business verbs such as {@code Create the Plan} or
     * {@code update the schedule} are safe because they are not SQL-shaped phrases.
     */
    private static boolean containsPhysicalIdentifier(String value) {
        // Snake-case tokens with 3+ underscore segments → physical table/db name
        for (String token : value.split("[\\s,;:.!?()\\[\\]{}\"'`]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.chars().filter(c -> c == '_').count() >= 2) {
                return true;
            }
            if (token.length() > 3 && token.endsWith("_id")) {
                return true;
            }
        }
        // "table <identifier>" or "column <identifier>" patterns
        String lower = value.toLowerCase();
        for (String prefix : List.of("table ", "column ")) {
            int idx = lower.indexOf(prefix);
            while (idx >= 0) {
                int after = idx + prefix.length();
                if (after < lower.length() && Character.isLetter(lower.charAt(after))) {
                    return true;
                }
                idx = lower.indexOf(prefix, after);
            }
        }
        // Stack-trace patterns
        if (value.contains(".java:") || value.contains("\tat ")) {
            return true;
        }
        // JDBC connection-string token — a standalone uppercase acronym that is never ordinary business prose
        if (lower.contains("jdbc")) {
            return true;
        }
        // SQL-shaped phrase combinations: only multi-word patterns that form a complete SQL clause are
        // rejected. Individual words like CREATE, SELECT, UPDATE, FROM, WHERE, DROP, ALTER are ordinary
        // business verbs that appear in safe prose such as "Create the Plan when you are ready."
        // Case-insensitive keyword pairs alone are not enough: natural-language phrases such as
        // "select a reward from the catalog" must be accepted. Require at least one keyword in the
        // pair to appear in uppercase in the original input as stronger evidence of SQL-shaped leakage.
        if (matchesSqlPhrase(lower, value)) {
            return true;
        }
        return false;
    }

    /**
     * True when the lowercased prose contains at least one complete SQL-shaped phrase.
     *
     * <p>Individual SQL keywords alone are never matched: a sentence like
     * {@code Create the Plan when you are ready.} is safe. Only combinations that form a genuine SQL
     * fragment — {@code SELECT … FROM}, {@code INSERT INTO}, {@code DELETE FROM},
     * {@code UPDATE … SET}, {@code CREATE TABLE}, {@code ALTER TABLE} or {@code DROP TABLE} — are
     * treated as leakage.
     *
     * <p>Exact two-word DML/DDL phrases ({@code insert into}, {@code delete from},
     * {@code create table}, {@code alter table}, {@code drop table}) are detected case-insensitively:
     * they have no legitimate business use.
     *
     * <p>{@code SELECT … FROM} and {@code UPDATE … SET} require stronger evidence than a
     * case-insensitive keyword pair alone. At least one of the two keywords must appear in
     * uppercase in the original input, so natural-language phrases such as
     * {@code Please select a reward from the available options.} and
     * {@code select the audience from where the data shows engagement} are accepted, while
     * {@code SELECT name FROM users} and {@code UPDATE users SET status = 'active'} are still
     * rejected. A {@code FROM} position must also be preceded by a non-letter token boundary, so
     * a compound word that happens to contain the letters {@code from} (e.g. {@code therefrom})
     * cannot trigger a false positive.
     */
    private static boolean matchesSqlPhrase(String lower, String original) {
        // INSERT INTO, DELETE FROM, CREATE TABLE, ALTER TABLE, DROP TABLE — exact two-word phrases.
        // These are unambiguous SQL fragments with no legitimate business use.
        for (String phrase : List.of("insert into", "delete from", "create table", "alter table", "drop table")) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        // SELECT … FROM — require at least one keyword in uppercase and FROM at a token boundary
        int selectIdx = lower.indexOf("select ");
        if (selectIdx >= 0) {
            int fromIdx = lower.indexOf("from ", selectIdx + "select ".length());
            if (fromIdx >= 0) {
                char before = fromIdx == 0 ? ' ' : lower.charAt(fromIdx - 1);
                if (Character.isWhitespace(before) || before == ',' || before == ';' || before == '.'
                        || before == '(' || before == ')' || before == '\n' || before == '\r') {
                    if (original.contains("SELECT") || original.contains("FROM")) {
                        return true;
                    }
                }
            }
        }
        // UPDATE … SET — require at least one keyword in uppercase
        int updateIdx = lower.indexOf("update ");
        if (updateIdx >= 0) {
            if (lower.indexOf(" set ", updateIdx + "update ".length()) >= 0) {
                if (original.contains("UPDATE") || original.contains("SET")) {
                    return true;
                }
            }
        }
        return false;
    }
}