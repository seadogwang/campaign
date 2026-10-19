package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.CanonicalJsonException;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one strict decoder and guard of a bounded routing-model output.
 *
 * <p>It accepts one well-formed JSON object with exactly seven fields — {@code routeKind}, {@code campaignType},
 * {@code skillIds}, {@code questionCodes}, {@code assistantCopy}, {@code options} and {@code objectiveEvidence}
 * — and refuses everything else before a decision exists:
 * <ul>
 *   <li>an unknown field, an alternate casing or a missing field;</li>
 *   <li>a duplicate object key at any nesting level;</li>
 *   <li>a non-strict UTF-8 body, a syntax error or a non-object document;</li>
 *   <li>a field of the wrong JSON shape;</li>
 *   <li>a Campaign type value that is unknown or not in its exact server-owned spelling;</li>
 *   <li>an over-count or over-size collection or string;</li>
 *   <li>a Skill identifier that is not server-owned;</li>
 *   <li>a Skill, question or option list that is not in its canonical order, or that repeats a value;</li>
 *   <li>any string that names a Campaign Tool, a Tool argument, a host-only action or an unsafe data path;</li>
 *   <li>a route kind whose own shape does not hold (a finalize that names a Skill, a clarification with no
 *       question, an out-of-scope request that asks for work, a selection that names nothing);</li>
 *   <li>a non-Campaign route that carries a type, or a type clarification whose options are not exactly the
 *       three canonical Campaign type options.</li>
 * </ul>
 *
 * <p>A {@link CampaignRouteKind#CAMPAIGN_REQUEST} may carry a null type: an in-scope non-event Campaign is
 * classified by the host, which derives {@code AD_HOC}/{@code LIFECYCLE} from the accepted schedule mode, so
 * the router is never asked to choose between them. The router may name exactly the explicit registered
 * {@code EVENT} type when the user describes an event-triggered Campaign. The decoder still accepts the legacy
 * {@code AD_HOC}/{@code LIFECYCLE} wire names for stored or older model output, but the host treats only
 * {@code EVENT} as a routed type; a non-event type is always derived from cadence and never from routing.
 *
 * <p>The decoder has no knowledge of the session, the workspace or the user: it judges shape alone. Whether
 * a well-shaped decision may run is decided by the host, which is the only party that can see the session.
 */
public final class CampaignRoutingDecisionDecoder {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final String ROUTE_KIND = "routeKind";
    private static final String CAMPAIGN_TYPE = "campaignType";
    private static final String SKILL_IDS = "skillIds";
    private static final String QUESTION_CODES = "questionCodes";
    private static final String ASSISTANT_COPY = "assistantCopy";
    private static final String OPTIONS = "options";
    private static final String OPTION_ID = "optionId";
    private static final String LABEL = "label";

    private static final String OBJECTIVE_EVIDENCE = "objectiveEvidence";

    private static final Set<String> DECISION_FIELDS =
            Set.of(ROUTE_KIND, CAMPAIGN_TYPE, SKILL_IDS, QUESTION_CODES, ASSISTANT_COPY, OPTIONS, OBJECTIVE_EVIDENCE);
    private static final Set<String> DECISION_FIELDS_LEGACY =
            Set.of(ROUTE_KIND, CAMPAIGN_TYPE, SKILL_IDS, QUESTION_CODES, ASSISTANT_COPY, OPTIONS);
    private static final Set<String> OPTION_FIELDS = Set.of(OPTION_ID, LABEL);
    private static final Set<String> OBJECTIVE_EVIDENCE_FIELDS = Set.of("evidence");

    /** Decodes one bounded routing output, or fails closed with one stable code. */
    public CampaignRoutingDecision decode(byte[] output) {
        if (output == null || output.length == 0) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_MALFORMED, "output", "A routing output is required.");
        }
        if (output.length > CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "output",
                    "A routing output exceeds its declared bound.");
        }
        JsonNode root = readTree(strictUtf8(output));
        if (!root.isObject()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "document",
                    "A routing output must be one JSON object.");
        }
        requireExactFieldsOr(root, DECISION_FIELDS, DECISION_FIELDS_LEGACY, "document");

        CampaignRouteKind routeKind = CampaignRouteKind.fromWireName(text(root.get(ROUTE_KIND), ROUTE_KIND));
        CampaignType campaignType = campaignType(root.get(CAMPAIGN_TYPE));
        List<String> skillIds = skillIds(root.get(SKILL_IDS));
        List<String> questionCodes = questionCodes(root.get(QUESTION_CODES));
        String assistantCopy = assistantCopy(root.get(ASSISTANT_COPY));
        List<CampaignAnswerOption> options = options(root.get(OPTIONS));
        String objectiveEvidence = objectiveEvidence(root.get(OBJECTIVE_EVIDENCE));

        requireRouteShape(routeKind, campaignType, skillIds, questionCodes, options);
        return new CampaignRoutingDecision(routeKind, campaignType, skillIds, questionCodes,
                assistantCopy, options, objectiveEvidence);
    }

    // ------------------------------------------------------------------ document boundaries

    private static String strictUtf8(byte[] output) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(output)).toString();
        } catch (CharacterCodingException failure) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_MALFORMED, "encoding",
                    "A routing output must be strict UTF-8.");
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return CANONICAL_JSON.readTree(json);
        } catch (CanonicalJsonException failure) {
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(failure.code())) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_DUPLICATE_KEY, "document",
                        "A routing output must not repeat an object key.");
            }
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_MALFORMED, "document",
                    "A routing output must be one well-formed JSON document.");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> allowed, String invariant) {
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_UNKNOWN_FIELD, invariant,
                        "A routing output carries a field that is not part of the decision contract.");
            }
            seen.add(name);
        }
        if (!seen.containsAll(allowed)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant,
                    "A routing decision requires every declared field.");
        }
    }

    /** Requires that the seen fields match either of two allowed sets. */
    private static void requireExactFieldsOr(
            JsonNode node, Set<String> primary, Set<String> fallback, String invariant) {
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!primary.contains(name)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_UNKNOWN_FIELD, invariant,
                        "A routing output carries a field that is not part of the decision contract.");
            }
            seen.add(name);
        }
        if (!seen.containsAll(fallback)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant,
                    "A routing decision requires every declared field.");
        }
    }

    // ------------------------------------------------------------------ fields

    private static String text(JsonNode node, String invariant) {
        if (node == null || !node.isTextual()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant,
                    "A routing field requires a JSON string.");
        }
        return node.textValue();
    }

    /**
     * Decodes the nullable Campaign type field.
     *
     * <p>A JSON null is the one accepted absence and decodes to {@code null}; any other value must be an exact
     * server-owned wire name, so an unknown or lowercase type is refused here. The decoder never infers a type
     * from the utterance and never defaults one.
     */
    private static CampaignType campaignType(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return CampaignType.fromWireName(text(node, CAMPAIGN_TYPE));
    }

    private static List<String> skillIds(JsonNode node) {
        List<String> values = stringArray(node, SKILL_IDS, CampaignAgentLimits.MAX_SELECTED_SKILLS);
        Set<String> distinct = new LinkedHashSet<>();
        int previousRank = -1;
        for (String value : values) {
            CampaignRoutingVocabulary.requireKnownSkill(value);
            if (!distinct.add(value)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, SKILL_IDS,
                        "A routing decision must not repeat a Skill.");
            }
            int rank = CampaignSkillIds.ordered().indexOf(value);
            if (rank <= previousRank) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_ORDER_INVALID, SKILL_IDS,
                        "Selected Skills must be in the server-owned canonical order.");
            }
            previousRank = rank;
        }
        return values;
    }

    private static List<String> questionCodes(JsonNode node) {
        List<String> values = stringArray(node, QUESTION_CODES, CampaignAgentLimits.MAX_QUESTION_CODES);
        Set<String> distinct = new LinkedHashSet<>();
        String previous = null;
        for (String value : values) {
            if (!value.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, QUESTION_CODES,
                        "A question code must be a stable machine code.");
            }
            CampaignRoutingVocabulary.requireSafeMachineCode(value, QUESTION_CODES);
            if (!distinct.add(value) || (previous != null && previous.compareTo(value) >= 0)) {
                throw CampaignAgentException.invalid(
                        previous != null && previous.compareTo(value) >= 0
                                ? CampaignAgentErrorCodes.ROUTING_ORDER_INVALID
                                : CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID,
                        QUESTION_CODES,
                        "Question codes must be distinct and in canonical order.");
            }
            previous = value;
        }
        return values;
    }

    private static String assistantCopy(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = text(node, ASSISTANT_COPY);
        if (value.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, ASSISTANT_COPY,
                    "Bounded assistant copy exceeds its declared bound.");
        }
        CampaignRoutingVocabulary.requireSafeCopy(value, ASSISTANT_COPY);
        return value;
    }

    /**
     * Decodes the nullable objective-evidence field.
     *
     * <p>A JSON null is the one accepted absence and decodes to {@code null}. A present value may use the
     * canonical object form with exactly one {@code evidence} field, or the bounded textual form emitted by
     * structured-model providers that flatten a one-property object. Both forms produce the same untrusted
     * string: host-side provenance validation (substring match against the current user message) remains
     * authoritative and is performed later because the decoder has no access to the user utterance.
     */
    private static String objectiveEvidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return requireObjectiveEvidenceText(node);
        }
        if (!node.isObject()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, OBJECTIVE_EVIDENCE,
                    "Objective evidence must be a bounded string, one JSON object or null.");
        }
        requireExactFields(node, OBJECTIVE_EVIDENCE_FIELDS, OBJECTIVE_EVIDENCE);
        return requireObjectiveEvidenceText(node.get("evidence"));
    }

    private static String requireObjectiveEvidenceText(JsonNode node) {
        String evidence = objectiveEvidenceText(node);
        if (evidence == null || evidence.isBlank()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, OBJECTIVE_EVIDENCE,
                    "Objective evidence must be a non-blank string.");
        }
        return evidence;
    }

    private static String objectiveEvidenceText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, OBJECTIVE_EVIDENCE + ".evidence",
                    "Objective evidence text must be a JSON string.");
        }
        String value = node.textValue();
        if (value.length() > CampaignBusinessObjective.MAX_OBJECTIVE_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, OBJECTIVE_EVIDENCE,
                    "Objective evidence exceeds its declared bound.");
        }
        return value;
    }

    private static List<CampaignAnswerOption> options(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, OPTIONS,
                    "Answer options must be a JSON array.");
        }
        if (node.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, OPTIONS,
                    "A routing decision exceeds its option bound.");
        }
        List<CampaignAnswerOption> decoded = new ArrayList<>(node.size());
        Set<String> distinct = new LinkedHashSet<>();
        String previous = null;
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, OPTIONS,
                        "An answer option must be one JSON object.");
            }
            requireExactFields(element, OPTION_FIELDS, OPTIONS);
            String optionId = text(element.get(OPTION_ID), OPTION_ID);
            if (optionId.length() > CampaignAgentLimits.MAX_OPTION_ID_LENGTH) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, OPTION_ID,
                        "An answer option identifier exceeds its declared bound.");
            }
            String label = text(element.get(LABEL), LABEL);
            if (!distinct.add(optionId) || (previous != null && previous.compareTo(optionId) >= 0)) {
                throw CampaignAgentException.invalid(
                        previous != null && previous.compareTo(optionId) >= 0
                                ? CampaignAgentErrorCodes.ROUTING_ORDER_INVALID
                                : CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID,
                        OPTIONS,
                        "Answer options must be distinct and in canonical order.");
            }
            previous = optionId;
            decoded.add(new CampaignAnswerOption(optionId, label));
        }
        return decoded;
    }

    private static List<String> stringArray(JsonNode node, String invariant, int max) {
        if (node == null || !node.isArray()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant,
                    "A routing list must be a JSON array.");
        }
        if (node.size() > max) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, invariant,
                    "A routing list exceeds its declared bound.");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            values.add(text(element, invariant));
        }
        return List.copyOf(values);
    }

    // ------------------------------------------------------------------ route shape

    private static void requireRouteShape(
            CampaignRouteKind routeKind,
            CampaignType campaignType,
            List<String> skillIds,
            List<String> questionCodes,
            List<CampaignAnswerOption> options) {
        requireCampaignTypeShape(routeKind, campaignType, questionCodes, options);
        switch (routeKind) {
            case CAMPAIGN_REQUEST -> {
                // The current first-stage contract asks for type classification only. A non-empty list remains
                // accepted for stored/legacy decisions, but the production prompt no longer asks the model to
                // select Skills before the typed Campaign brief is complete.
            }
            case CONTINUE_CAMPAIGN -> require(!skillIds.isEmpty(), "skillIds");
            case AUDIENCE_REQUEST -> require(
                    skillIds.size() == 1 && CampaignSkillIds.SEGMENT_RULE_BASED.equals(skillIds.get(0)),
                    "audienceSkill");
            case FINALIZE_CAMPAIGN -> {
                require(skillIds.isEmpty(), "finalizeSkills");
                require(questionCodes.isEmpty(), "finalizeQuestions");
                require(options.isEmpty(), "finalizeOptions");
            }
            case NEEDS_CLARIFICATION -> {
                require(skillIds.isEmpty(), "clarificationSkills");
                require(!questionCodes.isEmpty(), "clarificationQuestions");
            }
            case OUT_OF_SCOPE -> {
                require(skillIds.isEmpty(), "scopeSkills");
                require(questionCodes.isEmpty(), "scopeQuestions");
                require(options.isEmpty(), "scopeOptions");
            }
        }
    }

    /**
     * Enforces the one Campaign type shape of the decision: only a Campaign request may carry a type, every
     * other route kind names none, and a Campaign type clarification carries only its three canonical options.
     *
     * <p>The rule is route-shape only. The decoder never infers a type from the utterance and never defaults
     * one: a Campaign request may carry a null type (the host derives the non-event type from the accepted
     * schedule mode) or the explicit registered {@code EVENT} type, while a present type on any other route is
     * refused. The legacy {@code AD_HOC}/{@code LIFECYCLE} wire names remain decodable on a Campaign request so
     * stored or older model output still parses; the host, not the decoder, decides they are not authoritative.
     */
    private static void requireCampaignTypeShape(
            CampaignRouteKind routeKind,
            CampaignType campaignType,
            List<String> questionCodes,
            List<CampaignAnswerOption> options) {
        if (routeKind != CampaignRouteKind.CAMPAIGN_REQUEST) {
            require(campaignType == null, CAMPAIGN_TYPE);
        }
        if (questionCodes.contains(CampaignRoutingVocabulary.TYPE_REQUIRED_QUESTION_CODE)) {
            require(routeKind == CampaignRouteKind.NEEDS_CLARIFICATION && questionCodes.size() == 1,
                    "campaignTypeClarification");
            require(isCanonicalTypeOptions(options), "campaignTypeOptions");
        }
    }

    /** True when the options are exactly the three canonical Campaign type options, in the canonical order. */
    private static boolean isCanonicalTypeOptions(List<CampaignAnswerOption> options) {
        List<String> expected = CampaignType.orderedWireNames();
        if (options.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(options.get(index).optionId())) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String invariant) {
        if (!condition) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, invariant,
                    "The route kind and its own field shapes disagree.");
        }
    }
}
