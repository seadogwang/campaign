package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The one versioned, model-facing prompt contract of the bounded Campaign routing model.
 *
 * <p>The contract is a fixed immutable constant of this build. {@link #VERSION} and {@link #HASH} pin it: a
 * change to one byte of the prompt changes the hash, so a model call can never be made against an unpinned
 * instruction set. The prompt describes only the facts a {@link CampaignRoutingRequest} already carries — the
 * conversation/run identity, the host-derived position, the per-Skill states, the bounded prior-turn summaries
 * and the latest utterance — plus the closed route kinds, the closed Campaign types, the closed Skill
 * identifiers, the exact JSON shape, the canonical ordering rules and the bounded safe-copy rules. It states
 * what the router must never do — ask the user to choose AD_HOC versus LIFECYCLE — rather than any scenario.
 *
 * <p>It explicitly forbids Tool identifiers, Tool arguments, capability payloads, SQL, URLs, lifecycle actions
 * and saved-state claims, so a routing model has no vocabulary for any of them. {@link #routePrompt} and
 * {@link #repairPrompt} are distinct bounded instructions, and the repair prompt receives only the stable issue
 * codes of the strict decoder — never the rejected output.
 *
 * <p>The class reads no clock, no random source, no locale, no file and no environment: it is a pure function
 * of the request it is handed.
 */
public final class CampaignRoutingPromptContract {

    /** The pinned contract version of the routing prompt. */
    public static final String VERSION = "campaign-routing-prompt/8";

    private static final String RULES = """
            You are the bounded first-stage router of a Campaign builder. Your scope is strictly Campaign work:
            understanding a Campaign goal, selecting an audience as part of a Campaign, collecting Campaign
            requirements, or continuing/finalizing an existing Campaign. Do not answer unrelated questions,
            perform general assistance, or extend the conversation beyond Campaign design. An unrelated request
            must be OUT_OF_SCOPE.

            You control only the Campaign scope and the route. You never ask the user to choose an operating
            type: whether a non-event Campaign runs once or repeatedly is derived by the host from the schedule
            the user supplies, so you never name and never offer AD_HOC or LIFECYCLE. When an INITIAL Campaign
            request is in scope, return CAMPAIGN_REQUEST with a null "campaignType". Name exactly the EVENT type
            only when the user explicitly describes a registered business or customer event that begins an
            individual customer's Journey. Do not ask downstream requirement questions in this routing stage.
            When the user explicitly limits their request to examining, analyzing, counting, inspecting or
            summarizing existing data, records or history without requesting a new outbound Campaign action,
            return OUT_OF_SCOPE. Do not ask a clarification question that invents, suggests or offers an
            outbound action the user's own message did not name or imply. An option label must never propose
            creating, sending or issuing something the user did not request.
            When the Campaign goal itself is not clear, return NEEDS_CLARIFICATION and write one concise
            Campaign-scoped question in the user's language; the question must not suggest work outside Campaign
            design and must not invent any user requirement. When the user explicitly describes the desired
            outcome but limits their request to examining existing data, the scope is known to be
            read-only — do not ask a clarification question that invents a future Campaign action.
            Do not ask what goal the user wants to achieve when their input already describes the desired
            outcome. Use the input's own description of what they want to accomplish and ask only about a
            genuinely missing business decision. Do not expose the word "Campaign" or any internal code,
            schema name, Skill identifier, database term or machine vocabulary in assistant copy.

            Route kinds (exact spelling, no alias, no other value is accepted):
            %s

            Campaign types (exact spelling, server-owned, listed in canonical order):
            %s

            Skill identifiers (exact spelling, server-owned, listed in canonical order):
            %s

            Return exactly one JSON object with exactly these seven fields and no others:
            - "routeKind": one route kind above.
            - "campaignType": either null or exactly one Campaign type above.
            - "skillIds": an array of zero or more Skill identifiers, each appearing at most once and in the
              canonical order listed above.
            - "questionCodes": an array of zero to %d stable machine codes (^[A-Z][A-Z0-9_]{0,63}$), in ascending
              order and each appearing at most once.
            - "assistantCopy": either null or a bounded sentence of at most %d characters.
            - "options": an array of zero to %d objects, each exactly {"optionId", "label"}, ordered by "optionId"
              ascending and with distinct identifiers.
            - "objectiveEvidence": either null, a bounded quote string (at most 1024 characters), or an object
              with exactly one field "evidence" containing that quote. Copy the quote verbatim from the current
              user message. Set this only when the user message clearly states or corrects the Campaign's
              business objective. Set null when the message does not state or correct an objective.

            Route shapes you must satisfy:
            - CAMPAIGN_REQUEST names either a null "campaignType" or exactly the EVENT type. On an INITIAL turn
              it names no Skill; Skill selection occurs only after the host has collected a complete Campaign
              brief.
            - CONTINUE_CAMPAIGN names at least one Skill and a null "campaignType".
            - AUDIENCE_REQUEST names exactly one Skill: %s, and a null "campaignType".
            - FINALIZE_CAMPAIGN names no Skill, no question code, no option and a null "campaignType".
            - NEEDS_CLARIFICATION names no Skill, at least one question code and a null "campaignType".
            - OUT_OF_SCOPE names no Skill, no question code, no option and a null "campaignType".

            Campaign type shape you must satisfy:
            - A CAMPAIGN_REQUEST carries a null "campaignType" unless the user explicitly describes a registered
              business or customer event that begins an individual customer's Journey, in which case it names
              exactly the EVENT type.
            - The host derives the non-event type from the schedule the user supplies, so you never name AD_HOC
              or LIFECYCLE and you never ask the user to choose between them.
            - Every other route kind names a null "campaignType", and a Campaign type is never inferred from a
              word in the request.

            Objective evidence shape you must satisfy:
            - "objectiveEvidence" must be copied verbatim and narrowly from the current user message. The
              direct string and {"evidence":"..."} forms have identical meaning.
            - It is nullable when the message does not state or correct an objective.
            - A previously confirmed objective is authoritative -- never re-ask for it.
            - Internal terms such as Campaign, Skill, component Agent, codes and schema names stay out of
              user-facing copy.

            Stay inside Campaign design even when the user asks for advice: ask only for Campaign intent needed
            to continue the Campaign. Do not provide general marketing, business, legal, financial, technical or
            operational advice.

            You must never emit and never reference a Tool identifier, a Tool argument, a capability payload, a
            capability field, SQL, a URL, an HTTP route, a lifecycle action such as apply, publish, pause, resume,
            retry or archive, or any saved, applied, published or live-state claim. You decide only which Skill
            should run, which Campaign type a clear Campaign request is and, when the goal is unclear, which
            bounded question to ask. You never write a Proposal, a Manifest, a Plan or a payload and you never
            describe an outcome as saved.
            """;

    private static final String REPAIR_RULES = """
            Your previous response was refused by the strict decoder for the reasons listed below. Return one
            corrected JSON object that satisfies the exact routing contract you were given. Do not explain, do not
            comment and do not add a field. The reasons are stable issue codes only.
            """;

    private static final String TEXT = buildText();

    /** The canonical content hash of {@link #TEXT}. */
    public static final ContentHash HASH = ContentHash.sha256(TEXT.getBytes(StandardCharsets.UTF_8));

    private CampaignRoutingPromptContract() {
    }

    /** The exact versioned contract text this build pins; it is a contract, not a secret or a runtime value. */
    public static String text() {
        return TEXT;
    }

    /** The bounded routing instruction of one request. */
    public static String routePrompt(CampaignRoutingRequest request) {
        return contractHeader() + TEXT + "\n\n" + requestFacts(request);
    }

    /**
     * The bounded repair instruction of one request, carrying only the stable issue codes of the refusal.
     *
     * <p>The rejected output is deliberately never echoed: a repair can only re-ask the same bounded request.
     */
    public static String repairPrompt(CampaignRoutingRequest request, List<String> issueCodes) {
        StringBuilder builder = new StringBuilder(REPAIR_RULES);
        builder.append("\nIssue codes:");
        List<String> codes = issueCodes == null ? List.of() : issueCodes;
        for (String code : codes) {
            if (code != null && code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                builder.append("\n- ").append(code);
            }
        }
        return builder.append("\n\n")
                .append(contractHeader())
                .append(TEXT)
                .append("\n\n")
                .append(requestFacts(request))
                .toString();
    }

    private static String contractHeader() {
        return "Prompt contract version: " + VERSION
                + "\nPrompt contract hash: " + HASH.value() + "\n";
    }

    /** The bounded request facts, rendered from the request alone and never from host-private evidence. */
    private static String requestFacts(CampaignRoutingRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("conversationId: ").append(request.conversationId()).append('\n');
        builder.append("runId: ").append(request.runId()).append('\n');
        builder.append("position: ").append(request.position().name()).append('\n');
        builder.append("skillStates:\n");
        for (CampaignSkillState state : request.skillStates()) {
            builder.append("  ").append(state.skillId()).append('=')
                    .append(state.state().name());
            if (state.code() != null) {
                builder.append(" (").append(state.code()).append(')');
            }
            builder.append('\n');
        }
        builder.append("priorTurns:\n");
        for (String summary : request.priorTurns()) {
            builder.append("  ").append(summary).append('\n');
        }
        builder.append("utterance: ").append(request.utterance()).append('\n');
        return builder.toString();
    }

    private static String buildText() {
        List<String> routes = new ArrayList<>();
        for (CampaignRouteKind kind : CampaignRouteKind.values()) {
            routes.add("  - " + kind.name());
        }
        List<String> types = new ArrayList<>();
        for (String wireName : CampaignType.orderedWireNames()) {
            types.add("  - " + wireName + ": " + CampaignType.fromWireName(wireName).description());
        }
        List<String> skills = new ArrayList<>();
        for (String skillId : CampaignSkillIds.ordered()) {
            skills.add("  - " + skillId);
        }
        return String.format(
                RULES,
                String.join("\n", routes),
                String.join("\n", types),
                String.join("\n", skills),
                CampaignAgentLimits.MAX_QUESTION_CODES,
                CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH,
                CampaignAgentLimits.MAX_OPTIONS,
                CampaignSkillIds.SEGMENT_RULE_BASED);
    }

    /** A bounded diagnostic: the pinned version and hash only, never a prompt body. */
    @Override
    public String toString() {
        return "CampaignRoutingPromptContract[version=" + VERSION + ", hash=" + HASH + ']';
    }
}
