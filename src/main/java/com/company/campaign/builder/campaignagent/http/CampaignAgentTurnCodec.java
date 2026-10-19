package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignAgentLimits;
import com.company.campaign.builder.campaignagent.CampaignAgentTurnResult;
import com.company.campaign.builder.campaignagent.CampaignAudienceSummary;
import com.company.campaign.builder.campaignagent.CampaignClarification;
import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignComponentClarification;
import com.company.campaign.builder.campaignagent.CampaignComponentCode;
import com.company.campaign.builder.campaignagent.CampaignComponentManifest;
import com.company.campaign.builder.campaignagent.CampaignConversationProjection;
import com.company.campaign.builder.campaignagent.CampaignConversationTurn;
import com.company.campaign.builder.campaignagent.CampaignJourneySummary;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.CanonicalJsonException;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.contracts.ContractPatterns;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The strict request codec and the bounded response mapper of the Campaign conversation route.
 *
 * <p>The request side accepts one well-formed JSON object with exactly six fields — {@code conversationId},
 * {@code runId}, {@code expectedRevision}, {@code message}, {@code answers} and {@code at} — and refuses an
 * unknown field, an alternate casing, a missing field, a duplicate object key, a non-strict UTF-8 body, a
 * wrong JSON shape, an out-of-range revision, a malformed identifier or hash and an over-size body, all before
 * a turn request exists.
 *
 * <p>The response side projects only the bounded conversation facts, including the bounded ordered component
 * views of the T27 inspection. It never echoes a model byte, a Tool artifact, a capability payload, a template
 * body or an exception message, and it serializes the one host-produced Proposal as the exact canonical JSON
 * the accepted Proposal contract already defines.
 */
public final class CampaignAgentTurnCodec {

    /** The absolute bound on one wire request body. */
    public static final int MAX_REQUEST_BYTES = 16_384;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    private static final Set<String> REQUEST_FIELDS =
            Set.of("conversationId", "runId", "expectedRevision", "message", "answers", "at");
    private static final Set<String> ANSWER_FIELDS = Set.of("code", "optionId", "freeText", "component");

    /** Decodes one bounded wire request. */
    public CampaignAgentTurnHttpRequest decode(byte[] body) {
        if (body == null || body.length == 0) {
            throw refuse(CampaignAgentHttpErrorCodes.REQUEST_MALFORMED, "body", "A request body is required.");
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_LIMIT_EXCEEDED, "body",
                    "The request body exceeds its declared bound.");
        }
        JsonNode root = readTree(strictUtf8(body));
        if (!root.isObject()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "document",
                    "A turn request must be one JSON object.");
        }
        requireExactFields(root, REQUEST_FIELDS, "document");

        String conversationId = identifier(root.get("conversationId"), "conversationId");
        String runId = identifier(root.get("runId"), "runId");
        long expectedRevision = revision(root.get("expectedRevision"));
        String message = boundedText(root.get("message"), "message", CampaignAgentLimits.MAX_UTTERANCE_LENGTH);
        List<CampaignClarificationAnswer> answers = answers(root.get("answers"));
        String at = optionalBoundedText(root.get("at"), "at");
        return new CampaignAgentTurnHttpRequest(conversationId, runId, expectedRevision, message, answers, at);
    }

    /** The deterministic, non-sensitive request identifier derived from the exact request bytes. */
    public String requestId(byte[] body) {
        if (body == null || body.length == 0) {
            return "req-unavailable";
        }
        String hex = ContentHash.sha256(body).value();
        return "req-" + hex.substring(CanonicalJson.HASH_PREFIX.length(), CanonicalJson.HASH_PREFIX.length() + 32);
    }

    /** Maps one bounded turn result to its wire projection. */
    public CampaignAgentTurnHttpResponse response(CampaignAgentTurnResult result) {
        return wire(result.accepted(), result.code(), result.projection());
    }

    /**
     * Maps one read-only conversation projection to the same wire projection a turn returns.
     *
     * <p>It is the history read's only projection path: no turn ran, so the projection is reported as accepted
     * with no bounded code, exactly as the last accepted turn of that conversation left it.
     */
    public CampaignAgentTurnHttpResponse projection(CampaignConversationProjection projection) {
        return wire(true, null, projection);
    }

    private CampaignAgentTurnHttpResponse wire(
            boolean accepted, String code, CampaignConversationProjection projection) {
        List<CampaignAgentTurnHttpResponse.Turn> turns = new ArrayList<>();
        for (CampaignConversationTurn turn : projection.turns()) {
            turns.add(new CampaignAgentTurnHttpResponse.Turn(
                    turn.turnId(), turn.role(), turn.ordinal(), turn.text(), turn.at()));
        }
        List<CampaignAgentTurnHttpResponse.Skill> skills = new ArrayList<>();
        projection.skills().forEach(skill -> skills.add(new CampaignAgentTurnHttpResponse.Skill(
                skill.skillId(), skill.state().name(), skill.code(), skill.missingDependencyCodes())));
        List<CampaignAgentTurnHttpResponse.Component> components = new ArrayList<>();
        projection.components().forEach(component -> components.add(new CampaignAgentTurnHttpResponse.Component(
                component.componentId(), component.skillId(), component.capabilityCode(),
                component.operationKind(), component.artifactHash())));
        return new CampaignAgentTurnHttpResponse(
                accepted,
                code,
                projection.conversationId(),
                projection.runId(),
                projection.revision(),
                projection.phase().name(),
                projection.disposition(),
                projection.routeKind() == null ? null : projection.routeKind().name(),
                turns,
                skills,
                components,
                clarification(projection.clarification()),
                projection.assistantCopy(),
                audience(projection.audience()),
                projection.inspectionHash(),
                projection.proposal() == null
                        ? null
                        : CANONICAL_JSON.readTree(projection.proposal().canonicalText()),
                projection.proposalHash(),
                projection.followUpPrompt(),
                journey(projection.journey()),
                requirements(projection.requirements()),
                componentIssues(projection.componentIssues()),
                componentManifests(projection.componentManifests()));
    }

    private static CampaignAgentTurnHttpResponse.Requirements requirements(
            com.company.campaign.builder.campaignagent.CampaignRequirementProgress progress) {
        List<CampaignAgentTurnHttpResponse.ComponentTodo> componentTodo = new ArrayList<>();
        progress.componentTodo().forEach(todo -> componentTodo.add(new CampaignAgentTurnHttpResponse.ComponentTodo(
                todo.component().name(), todo.status().name(),
                todo.requirements().stream().map(Enum::name).toList(),
                todo.validationCodes())));
        var objectiveStatus = progress.objectiveStatus();
        CampaignAgentTurnHttpResponse.Objective objective = new CampaignAgentTurnHttpResponse.Objective(
                objectiveStatus.confirmed(), objectiveStatus.evidenceLength(),
                objectiveStatus.acceptedAtRevision());
        return new CampaignAgentTurnHttpResponse.Requirements(
                progress.type() == null ? null : progress.type().name(),
                progress.audience(), progress.offerDecision().name(), progress.offerDetail(),
                progress.channels().stream().map(Enum::name).toList(), progress.message(),
                progress.branchDecision().name(), progress.branchDetail(),
                progress.branchRule() == null ? null : new CampaignAgentTurnHttpResponse.BranchRule(
                        progress.branchRule().condition(), progress.branchRule().whenTrue(),
                        progress.branchRule().whenFalse()),
                progress.cadence() == null ? null : progress.cadence().name(),
                schedule(progress.schedule()),
                progress.triggerEventCode(),
                progress.missing().stream().map(Enum::name).toList(),
                progress.complete(), progress.unsupported(), componentTodo, objective);
    }

    /** The bounded schedule evidence in wire form, or null when the brief holds none. */
    private static CampaignAgentTurnHttpResponse.Schedule schedule(
            com.company.campaign.builder.campaignagent.CampaignExecutionSchedule source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return new CampaignAgentTurnHttpResponse.Schedule(
                source.timeZone(), source.runAt(), source.localTime(), source.startDate(), source.endDate());
    }

    /** The bounded Journey summary in wire form, or null when no Journey bundle is staged. */
    private static CampaignAgentTurnHttpResponse.Journey journey(CampaignJourneySummary summary) {
        if (summary == null) {
            return null;
        }
        return new CampaignAgentTurnHttpResponse.Journey(
                summary.nodeCount(), summary.edgeCount(), summary.entryComponentId(),
                summary.terminalCount(), summary.topologyHash(), summary.artifactHash());
    }

    // ------------------------------------------------------------------ request fields

    private static String strictUtf8(byte[] body) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException failure) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_MALFORMED, "encoding", "A request body must be strict UTF-8.");
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return CANONICAL_JSON.readTree(json);
        } catch (CanonicalJsonException failure) {
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(failure.code())) {
                throw refuse(
                        CampaignAgentHttpErrorCodes.REQUEST_DUPLICATE_KEY, "document",
                        "A request body must not repeat an object key.");
            }
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_MALFORMED, "document",
                    "A request body must be one well-formed JSON document.");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> allowed, String invariant) {
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw refuse(
                        CampaignAgentHttpErrorCodes.REQUEST_UNKNOWN_FIELD, invariant,
                        "A request body carries a field that is not part of the turn contract.");
            }
            seen.add(name);
        }
        if (!seen.containsAll(allowed)) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, invariant,
                    "A turn request requires every declared field.");
        }
    }

    private static String identifier(JsonNode node, String invariant) {
        String value = boundedText(node, invariant, 128);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, invariant,
                    "A turn request requires a bounded identifier.");
        }
        return value;
    }

    private static long revision(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "expectedRevision",
                    "An expected session revision must be a whole number.");
        }
        long value = node.longValue();
        if (value < 0 || value > 1_000_000_000L) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "expectedRevision",
                    "An expected session revision is outside its declared bound.");
        }
        return value;
    }

    private static String boundedText(JsonNode node, String invariant, int max) {
        if (node == null || !node.isTextual()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, invariant,
                    "A turn field requires a JSON string.");
        }
        String value = node.textValue();
        if (value.isEmpty() || value.length() > max) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, invariant,
                    "A turn field must be bounded and non-empty.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw refuse(
                        CampaignAgentHttpErrorCodes.REQUEST_INVALID, invariant,
                        "A turn field must not carry control characters.");
            }
        }
        return value;
    }

    private static String optionalBoundedText(JsonNode node, String invariant) {
        if (node == null || node.isNull()) {
            return null;
        }
        return boundedText(node, invariant, 64);
    }

    private static List<CampaignClarificationAnswer> answers(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "answers",
                    "Clarification answers must be a JSON array.");
        }
        if (node.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_LIMIT_EXCEEDED, "answers",
                    "A turn exceeds its clarification-answer bound.");
        }
        List<CampaignClarificationAnswer> decoded = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw refuse(
                        CampaignAgentHttpErrorCodes.REQUEST_INVALID, "answers",
                        "A clarification answer must be one JSON object.");
            }
            requireExactFields(element, ANSWER_FIELDS, "answers");
            try {
                decoded.add(new CampaignClarificationAnswer(
                        optionalCode(element.get("code")),
                        optionalIdentifier(element.get("optionId")),
                        optionalText(element.get("freeText")),
                        optionalComponent(element.get("component"))));
            } catch (CampaignAgentException failure) {
                throw refuse(
                        CampaignAgentHttpErrorCodes.REQUEST_INVALID, "answers",
                        "A clarification answer is not a bounded answer.");
            }
        }
        return decoded;
    }

    private static String optionalCode(JsonNode node) {
        String value = optionalText(node);
        if (value != null && !MACHINE_CODE.matcher(value).matches()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "answers.code",
                    "A clarification answer code must be a stable machine code.");
        }
        return value;
    }

    /** A clarification option identifier is an opaque bounded token, not a reference identifier. */
    private static String optionalIdentifier(JsonNode node) {
        return optionalText(node);
    }

    /** A clarification answer component — one of the closed component-group codes, or null for legacy. */
    private static CampaignComponentCode optionalComponent(JsonNode node) {
        String text = optionalText(node);
        if (text == null) {
            return null;
        }
        CampaignComponentCode component = CampaignComponentCode.fromCode(text);
        if (component == null) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "answers.component",
                    "A clarification answer component must be a known component code.");
        }
        return component;
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return boundedText(node, "answers.text", CampaignAgentLimits.MAX_ANSWER_LENGTH);
    }

    // ------------------------------------------------------------------ response views

    private static CampaignAgentTurnHttpResponse.Clarification clarification(CampaignClarification source) {
        if (source == null) {
            return null;
        }
        List<CampaignAgentTurnHttpResponse.Option> options = new ArrayList<>();
        source.options().forEach(option -> options.add(
                new CampaignAgentTurnHttpResponse.Option(option.optionId(), option.label())));
        return new CampaignAgentTurnHttpResponse.Clarification(
                source.skillId(), source.code(), source.prompt(), source.questionCodes(), options,
                source.freeTextAllowed());
    }

    /** The per-component issue list in wire form, or null when empty. */
    private static List<CampaignAgentTurnHttpResponse.ComponentIssue> componentIssues(
            List<CampaignComponentClarification> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        List<CampaignAgentTurnHttpResponse.ComponentIssue> issues = new ArrayList<>(source.size());
        for (CampaignComponentClarification request : source) {
            List<CampaignAgentTurnHttpResponse.Option> opts = new ArrayList<>();
            request.options().forEach(option -> opts.add(
                    new CampaignAgentTurnHttpResponse.Option(option.optionId(), option.label())));
            issues.add(new CampaignAgentTurnHttpResponse.ComponentIssue(
                    request.component().name(), request.skillId(), request.code(), request.prompt(),
                    request.questionCodes(), opts, request.freeTextAllowed()));
        }
        return issues;
    }

    /** The per-capability component manifest list in wire form, or null when empty. */
    private static List<CampaignAgentTurnHttpResponse.ComponentManifest> componentManifests(
            List<CampaignComponentManifest> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        List<CampaignAgentTurnHttpResponse.ComponentManifest> manifests = new ArrayList<>(source.size());
        for (CampaignComponentManifest m : source) {
            manifests.add(new CampaignAgentTurnHttpResponse.ComponentManifest(
                    m.capability().code(),
                    m.usageDecision().code(),
                    m.decisionSource().code(),
                    m.reasonCode(),
                    m.evidence(),
                    m.selectedResourceCodes()));
        }
        return manifests;
    }

    private static CampaignAgentTurnHttpResponse.Audience audience(CampaignAudienceSummary source) {
        if (source == null) {
            return null;
        }
        return new CampaignAgentTurnHttpResponse.Audience(
                source.status(), source.matchedCount(), source.asOf(), source.timezone(),
                source.dataWatermark(), source.previewHash());
    }

    private static CampaignAgentException refuse(String code, String invariant, String message) {
        return CampaignAgentException.invalid(code, invariant, message);
    }
}
