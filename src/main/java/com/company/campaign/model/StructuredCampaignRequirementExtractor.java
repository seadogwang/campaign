package com.company.campaign.model;

import com.company.campaign.builder.campaignagent.BranchDecision;
import com.company.campaign.builder.campaignagent.CampaignBusinessObjective;
import com.company.campaign.builder.campaignagent.CampaignAnswerOption;
import com.company.campaign.builder.campaignagent.CampaignBranchRule;
import com.company.campaign.builder.campaignagent.CampaignCadence;
import com.company.campaign.builder.campaignagent.CampaignExecutionSchedule;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CampaignClarification;
import com.company.campaign.builder.campaignagent.CampaignComponentClarification;
import com.company.campaign.builder.campaignagent.CampaignComponentCode;
import com.company.campaign.builder.campaignagent.CommunicationDecision;
import com.company.campaign.builder.campaignagent.CampaignRequirementBrief;
import com.company.campaign.builder.campaignagent.CampaignRequirementCode;
import com.company.campaign.builder.campaignagent.CampaignRequirementDelta;
import com.company.campaign.builder.campaignagent.CampaignRequirementExtractorPort;
import com.company.campaign.builder.campaignagent.CampaignRoutingVocabulary;
import com.company.campaign.builder.campaignagent.ChannelOperation;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.campaignagent.OfferDecision;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The neutral, Campaign-requirement-specific typed extraction boundary over the shared structured model port.
 *
 * <p>Requirement collection is divergent and conversational: a user may describe any subset of a brief in any
 * turn, in any order. This adapter turns one bounded user document — the current turn, the already accepted
 * brief and the pending question code — into one {@link CampaignRequirementDelta} through the one bounded
 * neutral {@link StructuredModelPort}. The versioned prompt declares only the generic exact JSON schema, the
 * closed enum values, the existing accepted facts and the pending question code; it contains no concrete
 * audience, discount, channel wording or message copy, and no wording-to-fact rule of any kind, so extraction
 * is model-driven through one strict generic schema.
 *
 * <p>The decoder is deliberately narrow and fails closed: a document that is not one JSON object of exactly
 * the declared fields, carries an unknown or duplicate field, is malformed UTF-8/JSON, uses a non-canonical
 * enum code or channel order, pairs a detail with a decision that cannot own it, exceeds a text or output
 * bound, or carries reserved security/provider/lifecycle/Tool/SQL content is refused outright. A refusal is
 * the {@link CampaignRequirementExtraction#rejected()} outcome, never a partial or invented fact.
 */
public class StructuredCampaignRequirementExtractor implements CampaignRequirementExtractorPort {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The largest accepted model output, in bytes. */
    private static final int MAX_OUTPUT_BYTES = 16_384;

    private static final Set<String> FIELDS = Set.of(
            "audience", "offerDecision", "offerDetail", "communicationDecision",
            "channels", "channelOperation", "message",
            "branchDecision", "branchDetail", "branchRule", "cadence", "schedule",
            "objectiveEvidence");

    /** The exact, frozen {@code schedule.basic/1.0} evidence field names this extractor accepts. */
    private static final Set<String> SCHEDULE_FIELDS =
            Set.of("timeZone", "runAt", "localTime", "startDate", "endDate");

    private final StructuredModelPort transport;

    /** The trusted clock the per-turn document is grounded against; the system clock when a host wires none. */
    private final Clock clock;

    /** The trusted business timezone used to interpret local and relative date/time expressions. */
    private final Supplier<ZoneId> businessZone;

    public StructuredCampaignRequirementExtractor(StructuredModelPort transport) {
        this(transport, Clock.systemUTC());
    }

    public StructuredCampaignRequirementExtractor(StructuredModelPort transport, Clock clock) {
        this(transport, clock, clock::getZone);
    }

    public StructuredCampaignRequirementExtractor(
            StructuredModelPort transport, Clock clock, Supplier<ZoneId> businessZone) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.businessZone = Objects.requireNonNull(businessZone, "businessZone");
    }

    /**
     * Extracts the coherent typed facts of one turn, deriving the pending question code from the accepted
     * brief. An accepted brief with nothing missing — or a recognized-but-unsupported {@code EVENT} brief —
     * carries no pending question.
     */
    public CampaignRequirementExtraction extract(String utterance, CampaignRequirementBrief accepted) {
        CampaignRequirementBrief brief = accepted == null ? CampaignRequirementBrief.empty() : accepted;
        CampaignRequirementCode pendingQuestion = brief.unsupported() ? null : brief.nextQuestion();
        return extract(utterance, brief, pendingQuestion);
    }

    /** Extracts the coherent typed facts of one turn under one explicit pending question code. */
    public CampaignRequirementExtraction extract(
            String utterance, CampaignRequirementBrief accepted, CampaignRequirementCode pendingQuestion) {
        CampaignRequirementBrief brief = accepted == null ? CampaignRequirementBrief.empty() : accepted;
        byte[] output;
        try {
            output = transport.invokeStructured(new StructuredModelRequest(
                    RequirementPrompt.VERSION + "\n" + RequirementPrompt.TEXT,
                    document(utterance, brief, pendingQuestion),
                    RequirementPrompt.SCHEMA));
        } catch (RuntimeException failure) {
            return CampaignRequirementExtraction.rejected();
        }
        if (output == null || output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
            return CampaignRequirementExtraction.rejected();
        }
        String json = strictUtf8(output);
        if (json == null) {
            return CampaignRequirementExtraction.rejected();
        }
        json = normalizeJsonEnvelope(json);
        JsonNode root;
        try {
            root = CANONICAL_JSON.readTree(json);
        } catch (RuntimeException failure) {
            return CampaignRequirementExtraction.rejected();
        }
        try {
            return decode(root);
        } catch (Rejection failure) {
            return CampaignRequirementExtraction.rejected();
        }
    }

    @Override
    public Optional<CampaignRequirementDelta> extractDelta(
            String utterance, CampaignRequirementBrief accepted, CampaignRequirementCode pendingQuestion) {
        CampaignRequirementExtraction extraction = extract(utterance, accepted, pendingQuestion);
        return extraction.accepted() ? Optional.of(extraction.delta()) : Optional.empty();
    }

    @Override
    public Optional<String> clarificationQuestion(
            CampaignRequirementBrief accepted, CampaignRequirementCode missingRequirement) {
        return clarificationQuestion(accepted, missingRequirement, List.of());
    }

    @Override
    public Optional<String> clarificationQuestion(
            CampaignRequirementBrief accepted,
            CampaignRequirementCode missingRequirement,
            List<CampaignAnswerOption> options) {
        return clarificationQuestion("", accepted, missingRequirement, options);
    }

    @Override
    public Optional<String> clarificationQuestion(
            String utterance,
            CampaignRequirementBrief accepted,
            CampaignRequirementCode missingRequirement,
            List<CampaignAnswerOption> options) {
        if (accepted == null || missingRequirement == null
                || !accepted.missingRequirements().contains(missingRequirement)
                || missingRequirement == CampaignRequirementCode.CAMPAIGN_TYPE_UNSUPPORTED) {
            return Optional.empty();
        }
        StringBuilder input = new StringBuilder(document(utterance, accepted, missingRequirement));
        input.append("Available registered candidate labels:");
        if (options == null || options.isEmpty()) {
            input.append(" none\n");
        } else {
            input.append('\n');
            for (CampaignAnswerOption option : options) {
                input.append("- ").append(option.label()).append('\n');
            }
        }
        return generatedQuestion(new StructuredModelRequest(
                    QuestionPrompt.VERSION + "\n" + QuestionPrompt.TEXT,
                    input.toString(),
                    QuestionPrompt.SCHEMA));
    }

    @Override
    public Optional<String> skillClarificationQuestion(
            CampaignRequirementBrief accepted, CampaignClarification clarification) {
        if (accepted == null || clarification == null || clarification.skillId() == null) {
            return Optional.empty();
        }
        return generatedQuestion(new StructuredModelRequest(
                SkillQuestionPrompt.VERSION + "\n" + SkillQuestionPrompt.TEXT,
                skillQuestionDocument(accepted, clarification),
                QuestionPrompt.SCHEMA));
    }

    /**
     * Synthesizes one concise question that asks for every outstanding component clarification of the turn.
     *
     * <p>The host, not this boundary, owns which questions exist and who owns each one: the request carries the
     * host-owned component, issue and question intents, and only copy comes back. A missing, oversized or unsafe
     * answer is empty, which lets the caller degrade to its deterministic host sentence instead of failing the
     * turn.
     */
    @Override
    public Optional<String> aggregateClarificationQuestion(
            List<CampaignComponentClarification> requests) {
        if (requests == null || requests.isEmpty()) {
            return Optional.empty();
        }
        return generatedQuestion(new StructuredModelRequest(
                AggregateQuestionPrompt.VERSION + "\n" + AggregateQuestionPrompt.TEXT,
                aggregateQuestionDocument(requests),
                QuestionPrompt.SCHEMA));
    }

    private Optional<String> generatedQuestion(StructuredModelRequest request) {
        byte[] output;
        try {
            output = transport.invokeStructured(request);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (output == null || output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
            return Optional.empty();
        }
        String json = strictUtf8(output);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = CANONICAL_JSON.readTree(json);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("question")) {
                return Optional.empty();
            }
            JsonNode questionNode = root.get("question");
            if (!questionNode.isTextual()) {
                return Optional.empty();
            }
            String question = questionNode.textValue();
            if (question.isBlank() || question.length() > 1000) {
                return Optional.empty();
            }
            CampaignRoutingVocabulary.requireSafeCopy(question, "requirementQuestion");
            return Optional.of(question);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> statusMessage(
            String utterance, CampaignRequirementBrief accepted, String statusCode) {
        if (accepted == null || statusCode == null || !statusCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            return Optional.empty();
        }
        String input = document(utterance, accepted, accepted.nextQuestion())
                + "Collection outcome code: " + statusCode + '\n';
        byte[] output;
        try {
            output = transport.invokeStructured(new StructuredModelRequest(
                    StatusPrompt.VERSION + "\n" + StatusPrompt.TEXT, input, StatusPrompt.SCHEMA));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (output == null || output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
            return Optional.empty();
        }
        String json = strictUtf8(output);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = CANONICAL_JSON.readTree(json);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("message")) {
                return Optional.empty();
            }
            JsonNode messageNode = root.get("message");
            if (!messageNode.isTextual()) {
                return Optional.empty();
            }
            String message = messageNode.textValue();
            if (message.isBlank() || message.length() > 1000) {
                return Optional.empty();
            }
            CampaignRoutingVocabulary.requireSafeCopy(message, "requirementStatusMessage");
            return Optional.of(message);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ strict decoding

    private static CampaignRequirementExtraction decode(JsonNode root) {
        if (root == null || !root.isObject() || !exactFields(root)) {
            throw REJECTION;
        }
        String audience = readText(root.get("audience"));
        OfferDecision offerDecision = readOfferDecision(root.get("offerDecision"));
        String offerDetail = readText(root.get("offerDetail"));
        CommunicationDecision communicationDecision =
                readCommunicationDecision(root.get("communicationDecision"));
        List<CampaignMessageInputs.Channel> channels = readChannels(root.get("channels"));
        ChannelOperation channelOperation = readChannelOperation(root.get("channelOperation"));
        String message = readText(root.get("message"));
        BranchDecision branchDecision = readBranchDecision(root.get("branchDecision"));
        String branchDetail = readText(root.get("branchDetail"));
        CampaignBranchRule branchRule = readBranchRule(root.get("branchRule"));
        CampaignCadence cadence = readCadence(root.get("cadence"));
        CampaignExecutionSchedule schedule = readSchedule(root.get("schedule"));
        String objectiveEvidence = readObjectiveEvidence(root.get("objectiveEvidence"));
        // An operation with nothing to act on is not a coherent correction, so it is refused rather than
        // silently downgraded to an addition.
        if (channelOperation != null && channels.isEmpty()) {
            throw REJECTION;
        }
        try {
            return CampaignRequirementExtraction.of(new CampaignRequirementDelta(
                    audience, offerDecision, offerDetail, communicationDecision, channels, message,
                    branchDecision, branchDetail, cadence, branchRule, channelOperation, schedule,
                    objectiveEvidence));
        } catch (IllegalArgumentException failure) {
            // An over-bound text or a detail/decision pairing the delta cannot own is a refusal.
            throw REJECTION;
        }
    }

    /** Exactly the declared fields, all present, none unknown; a duplicate already failed the JSON parse. */
    private static boolean exactFields(JsonNode node) {
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                return false;
            }
            seen.add(name);
        }
        return seen.containsAll(FIELDS);
    }

    /** A bounded, reserved-free text fact, or {@code null} when the field explicitly carries null/empty. */
    private static String readText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw REJECTION;
        }
        String value = node.textValue();
        if (value.isEmpty()) {
            return null;
        }
        try {
            CampaignRoutingVocabulary.requireSafeCopy(value, "requirementText");
        } catch (RuntimeException failure) {
            throw REJECTION;
        }
        return value;
    }

    /** A closed Offer decision, or {@code null} for an explicit null or the {@code UNKNOWN} value. */
    private static OfferDecision readOfferDecision(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw REJECTION;
        }
        OfferDecision value = OfferDecision.fromWireName(node.textValue());
        if (value == null) {
            throw REJECTION;
        }
        return value == OfferDecision.UNKNOWN ? null : value;
    }

    /** A closed communication decision, or {@code null} for an explicit null or {@code UNKNOWN}. */
    private static CommunicationDecision readCommunicationDecision(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw REJECTION;
        }
        CommunicationDecision value = CommunicationDecision.fromWireName(node.textValue());
        if (value == null) {
            throw REJECTION;
        }
        return value == CommunicationDecision.UNKNOWN ? null : value;
    }

    /**
     * Reads the nullable objective-evidence field.
     *
     * <p>It is a JSON object with exactly one field {@code evidence} whose value is a non-blank bounded
     * string, or null when the model provides no objective evidence. The evidence is returned as an
     * untrusted string proposal — it is NOT a {@link CampaignBusinessObjective}. Only
     * {@link com.company.campaign.builder.campaignagent.CampaignConversationAgent}, after current-message
     * provenance validation, may construct a confirmed objective and place it in the brief. A present
     * but empty evidence string is treated as null (no objective proposed).
     */
    private static String readObjectiveEvidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        // Some structured-model providers flatten a one-property object into its string value. Accept the
        // equivalent bounded form here just as the routing boundary does; current-message provenance remains
        // host-validated before any CampaignBusinessObjective can be confirmed.
        if (node.isTextual()) {
            return readObjectiveEvidenceText(node);
        }
        if (!node.isObject()) {
            throw REJECTION;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!"evidence".equals(name)) {
                throw REJECTION;
            }
            seen.add(name);
        }
        if (!seen.contains("evidence")) {
            return null;
        }
        JsonNode evidenceNode = node.get("evidence");
        if (evidenceNode == null || evidenceNode.isNull()) {
            return null;
        }
        return readObjectiveEvidenceText(evidenceNode);
    }

    private static String readObjectiveEvidenceText(JsonNode evidenceNode) {
        if (!evidenceNode.isTextual()) {
            throw REJECTION;
        }
        String evidence = evidenceNode.textValue();
        if (evidence.isBlank() || evidence.length() > CampaignBusinessObjective.MAX_OBJECTIVE_LENGTH) {
            return null;
        }
        try {
            CampaignRoutingVocabulary.requireSafeCopy(evidence, "objectiveEvidence");
        } catch (RuntimeException failure) {
            throw REJECTION;
        }
        // Return untrusted evidence; only CampaignConversationAgent constructs CampaignBusinessObjective.
        return evidence;
    }

    /** A closed branch decision, or {@code null} for an explicit null or the {@code UNKNOWN} value. */
    private static BranchDecision readBranchDecision(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw REJECTION;
        }
        BranchDecision value = BranchDecision.fromWireName(node.textValue());
        if (value == null) {
            throw REJECTION;
        }
        return value == BranchDecision.UNKNOWN ? null : value;
    }

    private static CampaignBranchRule readBranchRule(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || node.size() != 3
                || !node.has("condition") || !node.has("whenTrue") || !node.has("whenFalse")) {
            throw REJECTION;
        }
        String condition = readText(node.get("condition"));
        String whenTrue = readText(node.get("whenTrue"));
        String whenFalse = readText(node.get("whenFalse"));
        try {
            return new CampaignBranchRule(condition, whenTrue, whenFalse);
        } catch (IllegalArgumentException failure) {
            throw REJECTION;
        }
    }

    /** A closed cadence, or {@code null} for an explicit null; {@code UNKNOWN} is not a cadence value. */
    private static CampaignCadence readCadence(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw REJECTION;
        }
        CampaignCadence value = CampaignCadence.fromWireName(node.textValue());
        if (value == null) {
            throw REJECTION;
        }
        return value;
    }

    /**
     * A closed channel operation, or {@code null} for an explicit null; an unknown value is refused.
     *
     * <p>The operation is server-owned vocabulary, so a decoder can never map a channel correction onto an
     * operation by matching user wording.
     */
    private static ChannelOperation readChannelOperation(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw REJECTION;
        }
        ChannelOperation value = ChannelOperation.fromWireName(node.textValue());
        if (value == null) {
            throw REJECTION;
        }
        return value;
    }

    /**
     * The bounded schedule evidence, or {@link CampaignExecutionSchedule#empty()} for an explicit null.
     *
     * <p>The object carries exactly the frozen {@code schedule.basic/1.0} field names, each an optional bounded
     * string, so an unknown field, a wrong shape or an over-bound value is refused rather than defaulted. The
     * evidence is not interpreted here: which fields a mode needs is decided later by the accepted contract.
     */
    private static CampaignExecutionSchedule readSchedule(JsonNode node) {
        if (node == null || node.isNull()) {
            return CampaignExecutionSchedule.empty();
        }
        if (!node.isObject() || node.size() != SCHEDULE_FIELDS.size()) {
            throw REJECTION;
        }
        for (String field : SCHEDULE_FIELDS) {
            if (!node.has(field)) {
                throw REJECTION;
            }
        }
        try {
            return new CampaignExecutionSchedule(
                    readText(node.get("timeZone")),
                    readText(node.get("runAt")),
                    readText(node.get("localTime")),
                    readText(node.get("startDate")),
                    readText(node.get("endDate")));
        } catch (IllegalArgumentException failure) {
            throw REJECTION;
        }
    }

    /** The canonical, unique, ordered channel list; an explicit null or empty array means not supplied. */
    private static List<CampaignMessageInputs.Channel> readChannels(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw REJECTION;
        }
        List<CampaignMessageInputs.Channel> resolved = new ArrayList<>();
        int previous = -1;
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw REJECTION;
            }
            CampaignMessageInputs.Channel channel = CampaignMessageInputs.Channel.fromCode(element.textValue());
            // A first element, an unknown code, a duplicate or an out-of-order code is non-canonical.
            if (channel == null || channel.ordinal() <= previous) {
                throw REJECTION;
            }
            previous = channel.ordinal();
            resolved.add(channel);
        }
        return List.copyOf(resolved);
    }

    private static String strictUtf8(byte[] output) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output))
                    .toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
    }

    /** Removes one exact, entire JSON code fence without changing any inner JSON validation. */
    private static String normalizeJsonEnvelope(String decoded) {
        final String open = "```json";
        final String close = "```";
        if (decoded == null || !decoded.startsWith(open)) {
            return decoded;
        }
        int bodyStart = open.length();
        if (bodyStart < decoded.length() && decoded.charAt(bodyStart) == '\r') {
            bodyStart++;
        }
        if (bodyStart >= decoded.length() || decoded.charAt(bodyStart) != '\n') {
            return decoded;
        }
        bodyStart++;
        if (!decoded.endsWith("\n" + close)) {
            return decoded;
        }
        int bodyEnd = decoded.length() - close.length() - 1;
        if (bodyEnd <= bodyStart) {
            return decoded;
        }
        if (decoded.charAt(bodyEnd - 1) == '\r') {
            bodyEnd--;
        }
        String body = decoded.substring(bodyStart, bodyEnd);
        return body.contains(close) ? decoded : body;
    }

    // ------------------------------------------------------------------ bounded prompt document

    /** The bounded user document: the current turn, the accepted facts so far and the pending question. */
    private String document(
            String utterance, CampaignRequirementBrief accepted, CampaignRequirementCode pendingQuestion) {
        StringBuilder builder = new StringBuilder();
        builder.append("Current user message:\n")
                .append(utterance == null ? "" : utterance).append('\n');
        builder.append("Accepted facts so far:\n");
        builder.append("campaignType: ").append(accepted.type() == null ? "none" : accepted.type().name()).append('\n');
        builder.append("audience: ").append(orNone(accepted.audience())).append('\n');
        builder.append("offerDecision: ").append(accepted.offerDecision().name()).append('\n');
        builder.append("offerDetail: ").append(orNone(accepted.offerDetail())).append('\n');
        builder.append("communicationDecision: ")
                .append(accepted.communicationDecision().name()).append('\n');
        builder.append("channels: ").append(channelNames(accepted.channels())).append('\n');
        builder.append("triggerEventCode: ")
                .append(accepted.triggerEventCode() == null ? "none" : accepted.triggerEventCode()).append('\n');
        builder.append("message: ").append(orNone(accepted.message())).append('\n');
        builder.append("branchDecision: ").append(accepted.branchDecision().name()).append('\n');
        builder.append("branchDetail: ").append(orNone(accepted.branchDetail())).append('\n');
        builder.append("branchRule: ").append(branchRule(accepted.branchRule())).append('\n');
        builder.append("cadence: ").append(accepted.cadence() == null ? "none" : accepted.cadence().name()).append('\n');
        builder.append("schedule: ").append(schedule(accepted.schedule())).append('\n');
        builder.append("Pending question code: ")
                .append(pendingQuestion == null ? "none" : pendingQuestion.code()).append('\n');
        // The one trusted turn time and timezone the model must ground every relative or year-omitted date
        // against. It is the same clock the host validates accepted schedule evidence against, so a date the model
        // resolves from this time is judged against the same time deterministically.
        ZoneId zone = Objects.requireNonNull(businessZone.get(), "businessZone.get()");
        builder.append("Trusted absolute turn instant: ").append(clock.instant().toString()).append('\n');
        builder.append("Trusted business timezone for local time: ").append(zone.getId()).append('\n');
        return builder.toString();
    }

    /**
     * The bounded document of every outstanding component clarification, each with its owning component.
     *
     * <p>It carries only host-owned stable codes, the bounded host fallback wording and the bounded candidate
     * labels, so the wording boundary can group the questions without ever being able to attribute one to the
     * wrong component.
     */
    private static String aggregateQuestionDocument(List<CampaignComponentClarification> requests) {
        StringBuilder builder = new StringBuilder("Pending Campaign component questions: ")
                .append(requests.size()).append('\n');
        for (CampaignComponentClarification request : requests) {
            builder.append("Component: ").append(request.component().name()).append('\n');
            builder.append("Issue: ").append(request.code()).append('\n');
            builder.append("Question intents: ")
                    .append(String.join(", ", request.questionCodes())).append('\n');
            builder.append("Host fallback wording: ").append(request.prompt()).append('\n');
            if (!request.options().isEmpty()) {
                builder.append("Candidate labels:\n");
                for (CampaignAnswerOption option : request.options()) {
                    builder.append("- ").append(option.label()).append('\n');
                }
            }
        }
        return builder.toString();
    }

    private String skillQuestionDocument(
            CampaignRequirementBrief accepted, CampaignClarification clarification) {
        StringBuilder builder = new StringBuilder(document("", accepted, null));
        builder.append("Pending Campaign component: ").append(clarification.skillId()).append('\n');
        builder.append("Pending component issue: ").append(clarification.code()).append('\n');
        builder.append("Question intents: ")
                .append(String.join(", ", clarification.questionCodes())).append('\n');
        if (clarification.questionCodes().contains("CHANNEL_UNSUPPORTED")) {
            builder.append("Host clarification constraint: the named delivery channel is unavailable; ask "
                    + "the user to choose a different supported channel or cancel communication. Do not ask "
                    + "for different message content as a way to make that channel available. Refer to it "
                    + "only as the user's requested channel; do not rename it from a canonical accepted "
                    + "channel value.\n");
        }
        if (clarification.questionCodes().contains("GOVERNANCE_INPUT_REQUIRED")) {
            builder.append("Host clarification constraint: a concrete, explicitly stated limit is required. "
                    + "The accepted governance contract admits only a positive maximum recipient count, a "
                    + "positive maximum reward-grant count, an exact two-decimal reward face value and a "
                    + "canonical currency code; there is no unlimited and no defaulted variant. If the user "
                    + "declined to give numbers, do not repeat a neutral question: state plainly that a "
                    + "specific limit is required and ask for the concrete values.\n");
        }
        builder.append("Available candidate labels:");
        if (clarification.options().isEmpty()) {
            builder.append(" none\n");
        } else {
            builder.append('\n');
            for (CampaignAnswerOption option : clarification.options()) {
                builder.append("- ").append(option.label()).append('\n');
            }
        }
        return builder.toString();
    }

    private static String orNone(String value) {
        return value == null ? "none" : value;
    }

    private static String branchRule(CampaignBranchRule rule) {
        if (rule == null) {
            return "none";
        }
        return "condition=" + rule.condition() + "; whenTrue=" + rule.whenTrue()
                + "; whenFalse=" + rule.whenFalse();
    }

    private static String schedule(CampaignExecutionSchedule schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return "none";
        }
        return "timeZone=" + orNone(schedule.timeZone())
                + "; runAt=" + orNone(schedule.runAt())
                + "; localTime=" + orNone(schedule.localTime())
                + "; startDate=" + orNone(schedule.startDate())
                + "; endDate=" + orNone(schedule.endDate());
    }

    private static String channelNames(List<CampaignMessageInputs.Channel> channels) {
        if (channels.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>(channels.size());
        for (CampaignMessageInputs.Channel channel : channels) {
            names.add(channel.name());
        }
        return String.join(", ", names);
    }

    private static List<String> channelCodes() {
        List<String> codes = new ArrayList<>();
        for (CampaignMessageInputs.Channel channel : CampaignMessageInputs.Channel.values()) {
            codes.add(channel.name());
        }
        return codes;
    }

    // ------------------------------------------------------------------ versioned prompt contract

    /** The versioned Campaign requirement extraction prompt contract: the instruction and the exact schema. */
    public static final class RequirementPrompt {

        public static final String VERSION = "campaign-requirement-extract/12";

        public static final String TEXT = """
                Extract the Campaign requirement facts the current user message supplies, and only those.
                Return exactly one JSON object with exactly these thirteen fields and no others:
                - "audience": the bounded audience requirement text the user stated, or null when the user did
                  not state it;
                - "offerDecision": one of UNKNOWN, NONE, DEFINED — UNKNOWN only when the user may still add an
                  Offer and did not rule it out, NONE when the user explicitly stated there is no Offer or
                  when the input describes a purely informational, communicative, notificational, educational,
                  public-service, safety or regulatory message that carries no promotional or incentive
                  component, or when the objective requests examination of existing records rather than
                  creating a new outbound action — such objectives do not
                  need an Offer, so always set NONE for them; DEFINED when the user stated there is an Offer
                  with a concrete benefit;
                - "offerDetail": the bounded Offer description the user stated, only when "offerDecision" is
                  DEFINED, otherwise null. A statement of how much may be sent or granted — a
                  recipient count, a grant count, a maximum face value or a currency — is a limit and
                  not an Offer definition: it never makes "offerDecision" DEFINED, it never becomes
                  "offerDetail", and it never replaces an accepted "offerDetail";
                - "communicationDecision": one of UNKNOWN, NONE, DEFINED — UNKNOWN when the user did not
                  address communication, NONE when the user explicitly stated that no communication should be
                  sent or cancelled the whole accepted communication plan, and DEFINED when the user stated or
                  changed a delivery channel or message;
                - "channels": the delivery channels the user named, each exactly one canonical code from the
                  closed set %s, listed once each in ascending declared order, or an empty array when the user
                  named none this turn;
                - "channelOperation": one of ADD, REPLACE, REMOVE — the explicit operation the user's channel
                  statement applies to the accepted channel set. ADD unions the named channels into the set,
                  REPLACE substitutes the whole set with exactly the named channels, and REMOVE drops the named
                  channels from the set. When the user states that only a named subset of channels should
                  be used — set channelOperation to REPLACE with exactly those
                  channel codes. Do not use REMOVE to express an exclusion or "only" intent; REMOVE drops
                  the named channels from the set and keeps every other channel, which inverts the user's
                  stated preference. Use null only when the user named no channel this turn;
                - "message": the bounded message content or brief the user authored, preserved exactly and
                  never invented, paraphrased, translated or extended, or null when the user did not state it;
                - "branchDecision": one of UNKNOWN, NONE, REQUIRED — UNKNOWN when the user did not address
                  branching, NONE when the user explicitly stated the Campaign does not branch, REQUIRED when
                  the user stated branching is required;
                - "branchDetail": the complete evaluable branching topology the user stated, including every
                  explicit condition and both outcome/actions of every condition; emit null when any condition
                  or outcome is still missing, and emit null unless "branchDecision" is REQUIRED;
                - "branchRule": an object with exactly "condition", "whenTrue" and "whenFalse", preserving
                  the root condition and its immediate true and false successor actions; for nested or
                  multi-stage branching, use the first condition reached from Campaign entry and preserve the
                  complete graph in "branchDetail". Do not emit null merely because later conditions also
                  exist. Emit null unless all three root parts were explicitly supplied and "branchDecision"
                  is REQUIRED;
                - "cadence": one of ONE_TIME, DAILY, or null when the user did not state it;
                - "objectiveEvidence": either null, a bounded quote string, or an object with exactly one
                  field "evidence" containing that bounded quote (at most 1024 characters),
                  copied verbatim from the current user message that states the overall business objective.
                  Set this only when the message states or
                  corrects the Campaign's objective. Set null when the message does not state or correct
                  an objective. Never paraphrase, summarize or invent the objective. Do not ask the user
                  about the objective when one is already confirmed.
                - "schedule": an object of the accepted schedule evidence the user stated, or null when the
                  user stated none. Every field is a string or null: "timeZone" is the
                  canonical timezone identifier, "runAt" is the offset instant of a single execution,
                  "localTime" is the local time-of-day of a repeated execution, and "startDate" and "endDate"
                  are the inclusive calendar window of a repeated execution. A field the user did not state is
                  null. The Trusted absolute turn instant is only the current-time anchor. Interpret every
                  local clock time, relative date and year-omitted date in the user's stated timezone when
                  present and otherwise in the Trusted business timezone for local time. Preserve that local
                  wall-clock meaning when emitting the offset date-time; never interpret a local time as UTC
                  merely because the absolute turn instant is written with a Z suffix. For ONE_TIME, emit the
                  resulting complete offset date-time in "runAt" and leave
                  "localTime", "startDate" and "endDate" null; "localTime" is never a substitute for a
                  ONE_TIME "runAt".
                When the user states a conditional channel choice or fallback, include every explicitly named
                supported channel and set "branchDecision" to REQUIRED. Set "branchDetail" and "branchRule"
                only when the user also stated every evaluable condition and every fallback/outcome. Do not
                infer a condition, an outcome or an unavailable channel.
                Keep Campaign entry audience and Journey branch eligibility semantically separate. A condition
                that decides which Journey branch an already-entered member follows belongs only in
                "branchDetail" and "branchRule"; it is not an "audience" update. Emit a new "audience" only
                when the current message explicitly changes which members may enter the Campaign as a whole.
                A member, customer or buyer cohort explicitly named as the population to inspect, verify,
                compare or analyze is an audience requirement even when the objective requests examination of
                existing records rather than creating a new outbound action. Preserve that stated cohort as
                "audience"; do not discard it
                merely because the user asks a question about the cohort or because later data criteria remain
                unresolved. This rule identifies the population only and never invents a cohort or criterion.
                When the user explicitly states that a concrete identifier, threshold, field or value is needed
                to qualify the stated audience but is not yet available, preserve that constraint verbatim in
                the audience text. This lets the responsible component request the exact missing business input.
                Do not silently drop the qualifier or replace it with a guessed field, value or broader category.
                An event-triggered Campaign is already restricted to the members that fire its registered
                trigger event: that triggering member is the Campaign's subject, not its audience. When
                "campaignType" is EVENT, never emit the triggering member or subject as "audience"; emit
                "audience" only when the current message states a genuine additional restriction on which of
                those triggering members qualify, and emit null when the message states no such restriction.
                If "communicationDecision" is NONE, emit empty "channels", null "channelOperation" and null
                "message". If it is DEFINED, emit only the communication facts changed this turn. Return null
                for every fact the current message did not supply: never copy an accepted fact
                into a field the user did not address this turn, and never invent a fact. Never emit a Tool
                identifier, a Tool argument, a security, lifecycle or provider field, or SQL.
                """.formatted(String.join(", ", channelCodes()));

        public static final String SCHEMA = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["audience", "offerDecision", "offerDetail", "channels", "channelOperation", \
                "communicationDecision", "message", "branchDecision", "branchDetail", "branchRule", \
                "cadence", "schedule", "objectiveEvidence"],
                  "properties": {
                    "audience": {"type": ["string", "null"], "maxLength": %d},
                    "offerDecision": {"enum": ["UNKNOWN", "NONE", "DEFINED", null]},
                    "offerDetail": {"type": ["string", "null"], "maxLength": %d},
                    "communicationDecision": {"enum": ["UNKNOWN", "NONE", "DEFINED", null]},
                    "channels": {"type": "array", "uniqueItems": true, \
                "items": {"enum": [%s]}},
                    "channelOperation": {"enum": ["ADD", "REPLACE", "REMOVE", null]},
                    "message": {"type": ["string", "null"], "maxLength": %d},
                    "branchDecision": {"enum": ["UNKNOWN", "NONE", "REQUIRED", null]},
                    "branchDetail": {"type": ["string", "null"], "maxLength": %d},
                    "branchRule": {
                      "type": ["object", "null"],
                      "additionalProperties": false,
                      "required": ["condition", "whenTrue", "whenFalse"],
                      "properties": {
                        "condition": {"type": "string", "minLength": 1, "maxLength": %d},
                        "whenTrue": {"type": "string", "minLength": 1, "maxLength": %d},
                        "whenFalse": {"type": "string", "minLength": 1, "maxLength": %d}
                      }
                    },
                    "cadence": {"enum": ["ONE_TIME", "DAILY", null]},
                    "objectiveEvidence": {
                      "oneOf": [
                        {"type": "null"},
                        {"type": "string", "minLength": 1, "maxLength": %d},
                        {
                          "type": "object",
                          "additionalProperties": false,
                          "required": ["evidence"],
                          "properties": {
                            "evidence": {"type": "string", "minLength": 1, "maxLength": %d}
                          }
                        }
                      ]
                    },
                    "schedule": {
                      "type": ["object", "null"],
                      "additionalProperties": false,
                      "required": ["timeZone", "runAt", "localTime", "startDate", "endDate"],
                      "properties": {
                        "timeZone": {"type": ["string", "null"], "maxLength": %d},
                        "runAt": {"type": ["string", "null"], "maxLength": %d},
                        "localTime": {"type": ["string", "null"], "maxLength": %d},
                        "startDate": {"type": ["string", "null"], "maxLength": %d},
                        "endDate": {"type": ["string", "null"], "maxLength": %d}
                      }
                    }
                  }
                }
                """.formatted(
                CampaignRequirementDelta.MAX_AUDIENCE_LENGTH,
                CampaignRequirementDelta.MAX_OFFER_DETAIL_LENGTH,
                quotedChannelCodes(),
                CampaignRequirementDelta.MAX_MESSAGE_LENGTH,
                CampaignRequirementDelta.MAX_BRANCH_DETAIL_LENGTH,
                CampaignBranchRule.MAX_PART_LENGTH,
                CampaignBranchRule.MAX_PART_LENGTH,
                CampaignBranchRule.MAX_PART_LENGTH,
                CampaignBusinessObjective.MAX_OBJECTIVE_LENGTH,
                CampaignBusinessObjective.MAX_OBJECTIVE_LENGTH,
                CampaignExecutionSchedule.MAX_TIMEZONE_LENGTH,
                CampaignExecutionSchedule.MAX_INSTANT_LENGTH,
                CampaignExecutionSchedule.MAX_LOCAL_TIME_LENGTH,
                CampaignExecutionSchedule.MAX_DATE_LENGTH,
                CampaignExecutionSchedule.MAX_DATE_LENGTH);

        /** The canonical hash of the versioned instruction and its exact schema. */
        public static final ContentHash HASH =
                ContentHash.sha256((VERSION + "\n" + TEXT + "\n" + SCHEMA).getBytes(StandardCharsets.UTF_8));

        private static String quotedChannelCodes() {
            List<String> quoted = new ArrayList<>();
            for (String code : channelCodes()) {
                quoted.add('"' + code + '"');
            }
            return String.join(", ", quoted);
        }

        private RequirementPrompt() {
        }
    }

    /** Versioned model-owned clarification wording; the host still owns which field is missing. */
    public static final class QuestionPrompt {

        public static final String VERSION = "campaign-requirement-question/1";

        public static final String TEXT = """
                Write one concise, natural-language clarification question for the single missing Campaign
                requirement named by the pending question code. Use the current user's language. Use the
                accepted facts only as conversational context. When registered candidate labels are supplied,
                ask the user to choose only from those candidates. For a missing channel or event with no
                registered candidates, state that none is currently available and ask whether to cancel that
                component or change the Campaign design. Otherwise ask only for the missing requirement. Do not
                invent facts or candidates, and do not mention internal codes, schemas, Skills,
                Tools, providers, security controls, lifecycle actions or SQL. Do not expose the word "Campaign"
                or any internal code, schema name, Skill identifier or machine vocabulary. Do not ask about
                a fact the user has already stated in the accepted facts; the pending question code names a
                genuinely missing requirement, so ask for only that missing business decision. Be concrete
                and specific: name the exact business decision the user must make rather than asking a generic
                template question. Return exactly one JSON object with exactly one field named "question" and no
                other fields.
                """;

        public static final String SCHEMA = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["question"],
                  "properties": {"question": {"type": "string", "minLength": 1, "maxLength": 1000}}
                }
                """;

        private QuestionPrompt() {
        }
    }

    /** Versioned model-owned wording for a host-selected component clarification. */
    public static final class SkillQuestionPrompt {

        public static final String VERSION = "campaign-skill-question/1";

        public static final String TEXT = """
                Write one concise, natural-language clarification question for the unresolved Campaign
                component selected by the host. Use the current user's language. Use accepted facts, the
                pending component issue and question intents only as context. When candidate labels are
                supplied, ask the user to choose from those candidates; otherwise ask only for the missing
                business fact. Do not invent a candidate, configuration value or policy. Do not mention
                internal codes, schemas, Skills, Tools, providers, security controls, lifecycle actions or SQL.
                Do not expose the word "Campaign" or any internal code, schema name, Skill identifier or
                machine vocabulary. Be concrete and specific rather than asking a generic template question.
                Return exactly one JSON object with exactly one field named "question" and no other fields.
                """;

        private SkillQuestionPrompt() {
        }
    }

    /** Versioned model-owned wording for the aggregate of several outstanding component clarifications. */
    public static final class AggregateQuestionPrompt {

        public static final String VERSION = "campaign-aggregate-question/1";

        public static final String TEXT = """
                Write one concise, natural-language question that asks the user for every outstanding Campaign
                component fact listed below, grouped however reads best. Use the current user's language. Ask
                about every listed component exactly once. Do not add, drop or change a listed question intent or
                candidate label, and do not invent a candidate, configuration value or policy. Do not mention
                internal codes, schemas, Skills, Tools, providers, security controls, lifecycle actions or SQL.
                Do not expose the word "Campaign" or any internal code, schema name, Skill identifier or
                machine vocabulary. Be concrete and specific rather than asking a generic template question.
                Return exactly one JSON object with exactly one field named "question" and no other fields.
                """;

        private AggregateQuestionPrompt() {
        }
    }

    /** Versioned model-owned conversational wording for collection outcomes. */
    public static final class StatusPrompt {

        public static final String VERSION = "campaign-requirement-status/1";

        public static final String TEXT = """
                Write one concise natural-language assistant message for the host-owned Campaign requirement
                collection outcome code. Use the current user's language and accepted facts only as context.
                State the outcome accurately without inventing facts or promising execution. Do not mention
                internal codes, schemas, Skills, Tools, providers, security controls, lifecycle actions or SQL.
                Do not expose the word "Campaign" or any internal code, schema name, Skill identifier or
                machine vocabulary. Do not name a requirement the user has already stated in the accepted facts
                as missing, absent or needed. Do not suggest future actions, configuration steps, next-turn
                tasks or remediation paths the user did not request. Do not describe conditions, categories or
                business facts the user did not state as being missing from their request. An outcome where
                the builder cannot proceed states only that fact, without explaining why a hypothetical
                Campaign would need to add components the user never asked for. Return exactly one JSON object
                with exactly one field named "message" and no other fields.
                """;

        public static final String SCHEMA = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["message"],
                  "properties": {"message": {"type": "string", "minLength": 1, "maxLength": 1000}}
                }
                """;

        private StatusPrompt() {
        }
    }

    /** Versioned model-owned wording for a single component-owned clarification. */
    public static final class ComponentWordingPrompt {

        public static final String VERSION = "campaign-component-wording/1";

        public static final String TEXT = """
                Write one concise, natural-language question for a single component-owned Campaign
                clarification. Use the user's language — look only at the current user text as a language
                cue, not as extraction content. State only the missing business decision that the pending
                component issue and question codes name. When candidate labels are supplied, ask the
                user to choose only from those candidates; otherwise ask only for the missing business
                fact. Use the accepted facts only as conversational context. Do not invent a candidate,
                configuration value or policy. Do not use or reference the host fallback wording;
                write an original question. Do not mention internal codes, schemas, Skills, Tools,
                providers, security controls, lifecycle actions or SQL. Do not expose the word
                "Campaign" or any internal code, schema name, Skill identifier or machine vocabulary.
                Be concrete and specific. Return exactly one JSON object with exactly one field named
                "prompt" and no other fields.
                """;

        public static final String SCHEMA = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["prompt"],
                  "properties": {"prompt": {"type": "string", "minLength": 1, "maxLength": 320}}
                }
                """;

        private ComponentWordingPrompt() {
        }
    }

    @Override
    public Optional<String> componentClarificationWording(
            CampaignRequirementBrief accepted,
            CampaignComponentClarification clarification,
            String userText) {
        if (accepted == null || clarification == null) {
            return Optional.empty();
        }
        // 1. Try the model wording boundary.
        Optional<String> modelWording = invokeComponentWording(accepted, clarification, userText);
        if (modelWording.isPresent()) {
            // 2. Validate the model output against the deterministic component guard.
            //    Semantic retention with Chinese keywords, cross-component check and
            //    safe-copy are all enforced here.
            CampaignComponentClarification validated =
                    CampaignComponentClarification.validateAndAcceptWording(
                            clarification, modelWording.get());
            if (!validated.prompt().equals(clarification.prompt())) {
                // Validation accepted the rewording.
                return Optional.of(validated.prompt());
            }
            // Vague or cross-component wording was rejected — fall through to fallback.
        }
        // 3. Model unavailable or output rejected — use Chinese fallback when applicable.
        return chineseFallbackWhenApplicable(clarification, userText);
    }

    /** Invokes the model for one component-owned wording; empty on any transport or parse failure. */
    private Optional<String> invokeComponentWording(
            CampaignRequirementBrief accepted,
            CampaignComponentClarification clarification,
            String userText) {
        byte[] output;
        try {
            output = transport.invokeStructured(new StructuredModelRequest(
                    ComponentWordingPrompt.VERSION + "\n" + ComponentWordingPrompt.TEXT,
                    componentWordingDocument(accepted, clarification, userText),
                    ComponentWordingPrompt.SCHEMA));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (output == null || output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
            return Optional.empty();
        }
        String json = strictUtf8(output);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = CANONICAL_JSON.readTree(json);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("prompt")) {
                return Optional.empty();
            }
            JsonNode promptNode = root.get("prompt");
            if (!promptNode.isTextual()) {
                return Optional.empty();
            }
            String prompt = promptNode.textValue();
            if (prompt.isBlank() || prompt.length() > 1000) {
                return Optional.empty();
            }
            CampaignRoutingVocabulary.requireSafeCopy(prompt, "componentClarificationWording");
            return Optional.of(prompt);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /**
     * Returns the component-owned Chinese deterministic fallback when the model boundary
     * is unavailable and the user text is Chinese, or empty otherwise.
     *
     * <p>The Chinese fallback is component-owned and preserves exact codes and options;
     * no Main Agent rewriting is involved.
     */
    private static Optional<String> chineseFallbackWhenApplicable(
            CampaignComponentClarification clarification, String userText) {
        if (containsChinese(userText)) {
            String chinese = CampaignComponentClarification.chineseComponentFallback(clarification);
            if (chinese != null) {
                return Optional.of(chinese);
            }
        }
        return Optional.empty();
    }

    /**
     * True when the text contains at least one CJK Unified Ideograph character (U+4E00–U+9FFF).
     *
     * <p>This is the same detection used by {@code CampaignConversationAgent.localeCopy} to select
     * between English and Chinese fallback copies. It uses no scenario-specific keywords.
     */
    private static boolean containsChinese(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '一' && c <= '鿿') {
                return true;
            }
        }
        return false;
    }

    /**
     * The bounded document for one component-owned clarification wording request.
     *
     * <p>It carries the component's identity, the stable question codes and options, the host
     * deterministic fallback, a derived language label (zh-CN or en) in place of the raw user text,
     * and only the accepted facts that belong to the clarification's own component. It never
     * carries the raw user message, facts of another component, a Tool argument, a capability
     * payload or a saved-state fact.
     */
    private static String componentWordingDocument(
            CampaignRequirementBrief accepted,
            CampaignComponentClarification clarification,
            String userText) {
        StringBuilder builder = new StringBuilder();
        builder.append("Pending component: ").append(clarification.component().name()).append('\n');
        builder.append("Pending skill: ").append(clarification.skillId()).append('\n');
        builder.append("Pending issue: ").append(clarification.code()).append('\n');
        builder.append("Question intents: ")
                .append(String.join(", ", clarification.questionCodes())).append('\n');
        builder.append("Host fallback wording: ").append(clarification.prompt()).append('\n');
        if (!clarification.options().isEmpty()) {
            builder.append("Candidate labels:\n");
            for (CampaignAnswerOption option : clarification.options()) {
                builder.append("- ").append(option.label()).append('\n');
            }
        }
        builder.append("User preferred language: ")
                .append(containsChinese(userText) ? "zh-CN" : "en").append('\n');
        componentRelevantContext(builder, accepted, clarification.skillId());
        return builder.toString();
    }

    /**
     * Appends only the accepted brief facts that belong to the component identified by the given
     * Skill identifier.
     *
     * <p>The campaign type is always included as shared contextual background. Every other fact is
     * filtered: a Segment component only receives the audience, a Communication component only
     * receives channel/message facts, and so on. No cross-component fact reaches the model.
     */
    private static void componentRelevantContext(
            StringBuilder builder, CampaignRequirementBrief accepted, String skillId) {
        builder.append("Component-relevant accepted facts:\n");
        builder.append("campaignType: ")
                .append(accepted.type() == null ? "none" : accepted.type().name()).append('\n');
        if (CampaignSkillIds.SEGMENT_RULE_BASED.equals(skillId)) {
            builder.append("audience: ").append(orNone(accepted.audience())).append('\n');
        } else if (CampaignSkillIds.PROMOTION_REFERENCE.equals(skillId)) {
            builder.append("offerDecision: ").append(accepted.offerDecision().name()).append('\n');
            builder.append("offerDetail: ").append(orNone(accepted.offerDetail())).append('\n');
        } else if (CampaignSkillIds.GOVERNANCE_LIMIT.equals(skillId)) {
            // Governance is housed under OFFER but only needs the type for context.
        } else if (CampaignSkillIds.MESSAGE_TEMPLATE.equals(skillId)) {
            builder.append("communicationDecision: ")
                    .append(accepted.communicationDecision().name()).append('\n');
            builder.append("channels: ").append(channelNames(accepted.channels())).append('\n');
            builder.append("message: ").append(orNone(accepted.message())).append('\n');
        } else if (CampaignSkillIds.JOURNEY_FLOW.equals(skillId)) {
            builder.append("branchDecision: ").append(accepted.branchDecision().name()).append('\n');
            builder.append("branchDetail: ").append(orNone(accepted.branchDetail())).append('\n');
            builder.append("branchRule: ").append(branchRule(accepted.branchRule())).append('\n');
        } else if (CampaignSkillIds.SCHEDULE_BASIC.equals(skillId)) {
            builder.append("cadence: ")
                    .append(accepted.cadence() == null ? "none" : accepted.cadence().name()).append('\n');
            builder.append("schedule: ").append(schedule(accepted.schedule())).append('\n');
        }
    }

    /** A bounded diagnostic: the pinned contract and the transport only, never a prompt or an output. */
    @Override
    public String toString() {
        return "StructuredCampaignRequirementExtractor[version=" + RequirementPrompt.VERSION + ']';
    }

    /** The one internal, value-free refusal of the strict decoder. */
    private static final class Rejection extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Rejection() {
            super(null, null, false, false);
        }
    }

    private static final Rejection REJECTION = new Rejection();
}
