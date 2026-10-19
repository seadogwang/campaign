package com.company.campaign.model;

import com.company.campaign.builder.assembly.CampaignToolRequest;
import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignGovernanceInputs;
import com.company.campaign.builder.campaignagent.CampaignJourneyInputs;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CampaignPromotionInputs;
import com.company.campaign.builder.campaignagent.CampaignScheduleInputs;
import com.company.campaign.builder.campaignagent.CampaignSkillInputResolver;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.journey.JourneyEdgeDeclaration;
import com.company.campaign.builder.journey.JourneyGraphDraft;
import com.company.campaign.builder.journey.JourneyNodeDeclaration;
import com.company.campaign.builder.journey.JourneyReferenceDeclaration;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The neutral, Skill-specific typed input resolution boundary over the shared structured model port.
 *
 * <p>A routing model may select a Skill but may never produce a Tool argument, so the host resolves the
 * authoring facts of Message, Promotion, Journey, Schedule and Governance here, through the one bounded neutral
 * {@link StructuredModelPort}. Each Skill has its own versioned prompt and its own strict decoder: a decoder
 * accepts exactly the public authoring fields the accepted input record needs, refuses an unknown field, a
 * duplicate key, an alternate casing, the wrong shape, a non-canonical ordering, an unknown capability or node
 * kind, an over-count or over-size value, a reserved security/lifecycle/provider field and an out-of-bound
 * numeric value, and reuses the accepted {@link CapabilityPayload} and Journey authoring constructors rather
 * than duplicating their semantic rules — an accepted Capability Pack remains the only authority on a
 * declaration's field semantics.
 *
 * <p>A missing required fact, an absent or empty answer and any decoding failure all return the existing
 * {@code unresolved} typed input, so the Skill asks its accepted clarification instead of inventing a template
 * reference, a reward reference, a quantity, a ceiling, a Journey node, a schedule or a governance limit. No
 * raw output from one Skill ever becomes another Skill's input: each method is separate and returns its own
 * closed type.
 *
 * <p>The versioned prompt contracts are the system instruction; the bounded utterance/answer facts are the user
 * document. Together they render the exact prompt text the accepted development slice has always sent, so the
 * neutral port is a pure refactor of that boundary.
 */
public class StructuredSkillInputResolvers implements CampaignSkillInputResolver {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern BRANCH = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    /** A canonical non-negative integral decimal text: digits only, so no sign, exponent or whitespace survives. */
    private static final Pattern INTEGRAL_TEXT = Pattern.compile("^[0-9]{1,18}$");

    /** A canonical non-negative decimal text of at most two minor units: digits only, an optional decimal part. */
    private static final Pattern FACE_VALUE_TEXT = Pattern.compile("^[0-9]{1,18}(\\.[0-9]{1,2})?$");

    private static final int MAX_OUTPUT_BYTES = 32_768;

    /** The canonical hash of one versioned Skill prompt contract: the version line plus the contract text. */
    private static ContentHash contractHash(String version, String text) {
        return ContentHash.sha256((version + "\n" + text).getBytes(StandardCharsets.UTF_8));
    }

    private static final Set<String> MESSAGE_REFERENCE_FIELDS = Set.of("referenceCode", "variables");
    private static final Set<String> MESSAGE_INLINE_FIELDS = Set.of("channel", "body");
    private static final Set<String> MESSAGE_INLINE_COLLECTION_FIELDS = Set.of("messages");
    private static final Set<String> PROMOTION_FIELDS = Set.of("referenceCode", "quantity", "governanceCeiling");
    private static final Set<String> SCHEDULE_FIELDS = Set.of(
            ScheduleBasicVersions.FIELD_FORM, ScheduleBasicVersions.FIELD_ZONE_ID,
            ScheduleBasicVersions.FIELD_RUN_AT, ScheduleBasicVersions.FIELD_LOCAL_TIME,
            ScheduleBasicVersions.FIELD_START_DATE, ScheduleBasicVersions.FIELD_END_DATE);
    private static final Set<String> GOVERNANCE_FIELDS = Set.of(
            GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS,
            GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, GovernanceLimitVersions.FIELD_CURRENCY);
    private static final Set<String> JOURNEY_FIELDS = Set.of("nodes", "edges");
    private static final Set<String> NODE_FIELDS = Set.of("ref", "capabilityCode", "payload");
    private static final Set<String> EDGE_FIELDS = Set.of("edgeId", "fromRef", "toRef", "branchName");

    private final StructuredModelPort transport;

    public StructuredSkillInputResolvers(StructuredModelPort transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CampaignMessageInputs messageInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        JsonNode root = resolve(MessagePrompt.VERSION, MessagePrompt.TEXT, utterance, answers);
        // The reference shape is tried first, then the inline shape: a document that fits neither — or that
        // mixes the two — is refused rather than guessed at.
        if (exactFields(root, MESSAGE_REFERENCE_FIELDS)) {
            String referenceCode = identifier(root.get("referenceCode"));
            JsonNode variables = root.get("variables");
            if (referenceCode == null || variables == null || !variables.isObject()) {
                return CampaignMessageInputs.unresolved();
            }
            CapabilityPayload payload = payloadOf(variables);
            return payload == null
                    ? CampaignMessageInputs.unresolved()
                    : new CampaignMessageInputs(referenceCode, payload);
        }
        if (exactFields(root, MESSAGE_INLINE_FIELDS)) {
            return inlineMessageInputs(root);
        }
        if (exactFields(root, MESSAGE_INLINE_COLLECTION_FIELDS)) {
            return inlineMessageCollectionInputs(root.get("messages"));
        }
        return CampaignMessageInputs.unresolved();
    }

    /** The complete multi-channel inline shape, or unresolved on any partial or repeated channel. */
    private static CampaignMessageInputs inlineMessageCollectionInputs(JsonNode messages) {
        if (messages == null || !messages.isArray() || messages.isEmpty()
                || messages.size() > CampaignMessageInputs.Channel.values().length) {
            return CampaignMessageInputs.unresolved();
        }
        List<CampaignMessageInputs.InlineDraft> drafts = new ArrayList<>(messages.size());
        Set<CampaignMessageInputs.Channel> channels = new LinkedHashSet<>();
        for (JsonNode message : messages) {
            if (!exactFields(message, MESSAGE_INLINE_FIELDS)) {
                return CampaignMessageInputs.unresolved();
            }
            CampaignMessageInputs single = inlineMessageInputs(message);
            if (!single.isInline() || !channels.add(single.channel())) {
                return CampaignMessageInputs.unresolved();
            }
            drafts.add(single.inline());
        }
        return CampaignMessageInputs.ofInlineDrafts(drafts);
    }

    /** The complete inline shape, or unresolved on an unsupported channel or an absent/oversized body. */
    private static CampaignMessageInputs inlineMessageInputs(JsonNode root) {
        JsonNode channel = root.get("channel");
        JsonNode body = root.get("body");
        if (channel == null || !channel.isTextual() || body == null || !body.isTextual()) {
            return CampaignMessageInputs.unresolved();
        }
        CampaignMessageInputs.Channel resolved = CampaignMessageInputs.Channel.fromCode(channel.textValue());
        String text = body.textValue();
        if (resolved == null || text.isEmpty() || text.length() > CampaignMessageInputs.MAX_BODY_LENGTH) {
            return CampaignMessageInputs.unresolved();
        }
        return CampaignMessageInputs.ofInline(resolved, text);
    }

    @Override
    public CampaignPromotionInputs promotionInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        JsonNode root = resolve(PromotionPrompt.VERSION, PromotionPrompt.TEXT, utterance, answers);
        if (!exactFields(root, PROMOTION_FIELDS)) {
            return CampaignPromotionInputs.unresolved();
        }
        String referenceCode = identifier(root.get("referenceCode"));
        Integer quantity = boundedQuantity(root.get("quantity"));
        BigDecimal ceiling = boundedCeiling(root.get("governanceCeiling"));
        if (referenceCode == null || quantity == null || ceiling == null) {
            return CampaignPromotionInputs.unresolved();
        }
        return new CampaignPromotionInputs(referenceCode, quantity, ceiling);
    }

    @Override
    public CampaignJourneyInputs journeyInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        JsonNode root = resolve(JourneyPrompt.VERSION, JourneyPrompt.TEXT, utterance, answers);
        if (!exactFields(root, JOURNEY_FIELDS)) {
            return CampaignJourneyInputs.unresolved();
        }
        List<JourneyNodeDeclaration> nodes = journeyNodes(root.get("nodes"));
        List<JourneyEdgeDeclaration> edges = journeyEdges(root.get("edges"));
        if (nodes == null || edges == null) {
            return CampaignJourneyInputs.unresolved();
        }
        try {
            // The accepted Journey authoring graph re-proves the duplicate, endpoint and bound rules.
            return new CampaignJourneyInputs(new JourneyGraphDraft(nodes, edges, journeyReferences(nodes)), null);
        } catch (RuntimeException failure) {
            return CampaignJourneyInputs.unresolved();
        }
    }

    private static List<JourneyReferenceDeclaration> journeyReferences(List<JourneyNodeDeclaration> nodes) {
        Set<String> messageRefs = new java.util.TreeSet<>();
        boolean promotion = nodes.stream()
                .anyMatch(node -> FlowPackVersions.REWARD.equals(node.capabilityCode()));
        for (JourneyNodeDeclaration node : nodes) {
            if (!FlowPackVersions.MESSAGE.equals(node.capabilityCode())) {
                continue;
            }
            Object reference = node.payload().fields().get("messageLogicalId");
            if (!(reference instanceof String messageRef) || !messageComponentRefs().contains(messageRef)) {
                throw new IllegalArgumentException("A Journey message must reference a server-owned channel component.");
            }
            messageRefs.add(messageRef);
        }
        List<JourneyReferenceDeclaration> references = new ArrayList<>(messageRefs.size() + 1);
        for (String messageRef : messageRefs) {
            references.add(new JourneyReferenceDeclaration(messageRef, "message.template"));
        }
        if (promotion) {
            references.add(new JourneyReferenceDeclaration("campaign-promotion", "promotion.reference"));
        }
        return List.copyOf(references);
    }

    private static Set<String> messageComponentRefs() {
        Set<String> refs = new LinkedHashSet<>();
        refs.add("campaign-message");
        for (CampaignMessageInputs.Channel channel : CampaignMessageInputs.Channel.values()) {
            refs.add(CampaignMessageInputs.componentRef(channel));
        }
        return Set.copyOf(refs);
    }

    @Override
    public CampaignScheduleInputs scheduleInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        JsonNode root = resolve(SchedulePrompt.VERSION, SchedulePrompt.TEXT, utterance, answers);
        if (root == null || !root.isObject() || root.isEmpty()) {
            return CampaignScheduleInputs.unresolved();
        }
        if (!subsetFields(root, SCHEDULE_FIELDS) || root.get(ScheduleBasicVersions.FIELD_FORM) == null
                || !allText(root)) {
            return CampaignScheduleInputs.unresolved();
        }
        CapabilityPayload declaration = payloadOf(root);
        return declaration == null
                ? CampaignScheduleInputs.unresolved()
                : new CampaignScheduleInputs(declaration);
    }

    @Override
    public CampaignGovernanceInputs governanceInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        JsonNode root = resolve(GovernancePrompt.VERSION, GovernancePrompt.TEXT, utterance, answers);
        if (!exactFields(root, GOVERNANCE_FIELDS)) {
            return CampaignGovernanceInputs.unresolved();
        }
        JsonNode document = normalizedGovernance(root);
        CapabilityPayload declaration = document == null ? null : payloadOf(document);
        return declaration == null
                ? CampaignGovernanceInputs.unresolved()
                : new CampaignGovernanceInputs(declaration);
    }

    /**
     * The four declared Governance fields as one exact document, or null when any one of them is absent, of the
     * wrong shape or outside its frozen bound.
     *
     * <p>The declared reward face value is a value a JSON number cannot spell: a number is free to drop a
     * trailing minor unit, so "exactly two minor units" is a meaning only text can carry, and the canonical
     * decimal text is therefore the form that declaration naturally takes. The decoder reads either form and
     * normalizes whichever it read into the one exact numeric document the accepted capability pack validates,
     * so the same declared value always produces the same payload bytes. A field the decoder read as text is
     * only ever admitted when that text is the canonical spelling of its own value.
     */
    private static JsonNode normalizedGovernance(JsonNode root) {
        Long maxRecipients = boundedLimit(root.get(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS));
        Long maxRewardGrants = boundedLimit(root.get(GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS));
        BigDecimal maxRewardFaceValue =
                boundedFaceValue(root.get(GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE));
        JsonNode currencyNode = root.get(GovernanceLimitVersions.FIELD_CURRENCY);
        if (maxRecipients == null || maxRewardGrants == null || maxRewardFaceValue == null
                || !canonicalCurrency(currencyNode)) {
            return null;
        }
        ObjectNode document = NODES.objectNode();
        document.put(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, maxRecipients.longValue());
        document.put(GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, maxRewardGrants.longValue());
        document.put(GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, maxRewardFaceValue);
        document.put(GovernanceLimitVersions.FIELD_CURRENCY, currencyNode.textValue());
        return document;
    }

    // ------------------------------------------------------------------ transport and document

    /** Resolves one bounded document through the shared transport; null on any failure, so the caller is inert. */
    private JsonNode resolve(
            String version, String contract, String utterance, List<CampaignClarificationAnswer> answers) {
        byte[] output;
        try {
            output = transport.invokeStructured(new StructuredModelRequest(
                    version + "\n" + contract, facts(utterance, answers), ""));
        } catch (RuntimeException failure) {
            return null;
        }
        if (output == null || output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
            return null;
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output))
                    .toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
        try {
            JsonNode root = CANONICAL_JSON.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String facts(String utterance, List<CampaignClarificationAnswer> answers) {
        StringBuilder builder = new StringBuilder();
        builder.append("Utterance: ").append(utterance == null ? "" : utterance).append('\n');
        builder.append("Clarification answers:\n");
        List<CampaignClarificationAnswer> bounded = answers == null ? List.of() : answers;
        for (CampaignClarificationAnswer answer : bounded) {
            builder.append("  - code=").append(answer.code() == null ? "-" : answer.code())
                    .append(", option=").append(answer.optionId() == null ? "-" : answer.optionId())
                    .append(", text=").append(answer.freeText() == null ? "-" : answer.freeText())
                    .append('\n');
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ strict field helpers

    private static boolean exactFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (!subsetFields(node, allowed)) {
            return false;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            seen.add(names.next());
        }
        return seen.containsAll(allowed);
    }

    private static boolean subsetFields(JsonNode node, Set<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean allText(JsonNode node) {
        for (Iterator<JsonNode> values = node.elements(); values.hasNext(); ) {
            JsonNode value = values.next();
            if (!value.isTextual() || value.textValue().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String identifier(JsonNode node) {
        if (node == null || !node.isTextual() || !IDENTIFIER.matcher(node.textValue()).matches()) {
            return null;
        }
        return node.textValue();
    }

    private static Integer boundedQuantity(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.longValue();
        if (value < 1 || value > CampaignToolRequest.MAX_QUANTITY) {
            return null;
        }
        return (int) value;
    }

    private static BigDecimal boundedCeiling(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.scale() > CampaignToolRequest.CEILING_SCALE
                || value.stripTrailingZeros().precision() > CampaignToolRequest.CEILING_PRECISION) {
            return null;
        }
        return value.setScale(CampaignToolRequest.CEILING_SCALE);
    }

    private static Long boundedLimit(JsonNode node) {
        Long value = exactIntegralValue(node);
        if (value == null) {
            return null;
        }
        if (value < 1 || value > GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND) {
            return null;
        }
        return value;
    }

    /**
     * The exact integral value of a JSON integer or of its canonical decimal text, or null for anything else.
     *
     * <p>A declared count is equally exact whether the model writes it as a JSON integer or as text, so both are
     * read; text is admitted only when it is the canonical spelling of its own value, so a sign, an exponent, a
     * decimal point, surrounding whitespace, a leading zero or a non-digit is refused rather than reinterpreted.
     */
    private static Long exactIntegralValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (!node.isTextual() || !INTEGRAL_TEXT.matcher(node.textValue()).matches()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(node.textValue());
            return Long.toString(parsed).equals(node.textValue()) ? parsed : null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static BigDecimal boundedFaceValue(JsonNode node) {
        BigDecimal value = exactDecimalValue(node);
        if (value == null
                || value.scale() > GovernanceLimitVersions.FACE_VALUE_SCALE
                || value.stripTrailingZeros().precision() > GovernanceLimitVersions.FACE_VALUE_PRECISION
                || value.compareTo(GovernanceLimitVersions.MIN_REWARD_FACE_VALUE) < 0
                || value.compareTo(GovernanceLimitVersions.MAX_REWARD_FACE_VALUE) > 0) {
            return null;
        }
        return value.setScale(GovernanceLimitVersions.FACE_VALUE_SCALE);
    }

    /**
     * The exact declared value of a JSON non-integral number or of its canonical decimal text, or null otherwise.
     *
     * <p>Text is admitted only when it is the canonical spelling of its own value, so a sign, an exponent,
     * surrounding whitespace, a leading zero or a stray minor unit is refused rather than reinterpreted. The
     * value's magnitude is still bounded by the caller, which is the only authority on the frozen contract bound.
     */
    private static BigDecimal exactDecimalValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber() && !node.isIntegralNumber()) {
            return node.decimalValue();
        }
        if (!node.isTextual() || !FACE_VALUE_TEXT.matcher(node.textValue()).matches()) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(node.textValue());
            return parsed.toPlainString().equals(node.textValue()) ? parsed : null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static boolean canonicalCurrency(JsonNode node) {
        return node != null && node.isTextual()
                && node.textValue().length() == GovernanceLimitVersions.MAX_CURRENCY_LENGTH
                && node.textValue().equals(node.textValue().toUpperCase(java.util.Locale.ROOT));
    }

    private static CapabilityPayload payloadOf(JsonNode node) {
        try {
            return CapabilityPayload.fromJsonNode(node);
        } catch (RuntimeException failure) {
            // A reserved, unbounded or non-canonical object fails closed exactly like a missing fact.
            return null;
        }
    }

    // ------------------------------------------------------------------ Journey decoding

    private static List<JourneyNodeDeclaration> journeyNodes(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > JourneyGraphDraft.MAX_NODES) {
            return null;
        }
        List<JourneyNodeDeclaration> declarations = new ArrayList<>(node.size());
        Set<String> references = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (!exactFields(element, NODE_FIELDS)) {
                return null;
            }
            String ref = identifier(element.get("ref"));
            JsonNode capability = element.get("capabilityCode");
            JsonNode payload = element.get("payload");
            if (ref == null || capability == null || !capability.isTextual()
                    || !FlowPackVersions.CAPABILITY_CODES.contains(capability.textValue())
                    || payload == null || !payload.isObject()) {
                return null;
            }
            CapabilityPayload values = payloadOf(payload);
            if (values == null) {
                return null;
            }
            if (!references.add(ref)) {
                return null;
            }
            declarations.add(new JourneyNodeDeclaration(ref, capability.textValue(), values));
        }
        declarations.sort(Comparator.comparing(JourneyNodeDeclaration::ref));
        return declarations;
    }

    private static List<JourneyEdgeDeclaration> journeyEdges(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > JourneyGraphDraft.MAX_EDGES) {
            return null;
        }
        List<JourneyEdgeDeclaration> declarations = new ArrayList<>(node.size());
        Set<String> edgeIds = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (!exactFields(element, EDGE_FIELDS)) {
                return null;
            }
            String edgeId = identifier(element.get("edgeId"));
            String fromRef = identifier(element.get("fromRef"));
            String toRef = identifier(element.get("toRef"));
            JsonNode branch = element.get("branchName");
            if (edgeId == null || fromRef == null || toRef == null
                    || branch == null || !branch.isTextual() || !BRANCH.matcher(branch.textValue()).matches()) {
                return null;
            }
            if (!edgeIds.add(edgeId)) {
                return null;
            }
            declarations.add(new JourneyEdgeDeclaration(edgeId, fromRef, toRef, branch.textValue()));
        }
        declarations.sort(Comparator.comparing(JourneyEdgeDeclaration::edgeId));
        return declarations;
    }

    // ------------------------------------------------------------------ versioned prompt contracts

    /** The versioned Message input prompt contract. */
    public static final class MessagePrompt {

        public static final String VERSION = "campaign-skill-message-input/3";
        public static final String TEXT = """
                Resolve the Message Template requirement of one Campaign turn. Return exactly one JSON object
                of exactly one of these two closed shapes, with the fields of that shape only and never a field
                of the other shape:
                - a catalog reference shape {"referenceCode","variables"}: "referenceCode" is the exact bounded
                  Message Template reference the user named and "variables" is a JSON object of the bounded
                  template variable values the user supplied, used only when the user named an existing catalog
                  template;
                - an inline draft collection {"messages":[{"channel","body"},...]} with exactly one item for
                  every delivery channel the user named. "channel" is exactly one canonical machine code from
                  the closed set %s, no channel repeats, and "body" is that channel's user-authored copy,
                  preserved exactly and never invented, paraphrased, translated or extended. When the user
                  explicitly gives the same copy for multiple channels, repeat that exact copy for each item.
                Never return both shapes or a partial shape. Never invent a reference, a variable value, a
                channel or a body; return {} when the user supplied neither shape. Never emit a Tool
                identifier, a Tool argument, a security, lifecycle or provider field, or SQL.
                """.formatted(String.join(", ", channelCodes()));
        public static final ContentHash HASH = contractHash(VERSION, TEXT);

        /** The canonical machine codes of the closed channel vocabulary, in declaration order. */
        private static List<String> channelCodes() {
            List<String> codes = new ArrayList<>();
            for (CampaignMessageInputs.Channel channel : CampaignMessageInputs.Channel.values()) {
                codes.add(channel.name());
            }
            return codes;
        }

        private MessagePrompt() {
        }
    }

    /** The versioned Promotion input prompt contract. */
    public static final class PromotionPrompt {

        public static final String VERSION = "campaign-skill-promotion-input/1";
        public static final String TEXT = """
                Resolve the Promotion requirement of one Campaign turn. Return exactly one JSON object with
                exactly these three fields and no others:
                - "referenceCode": the exact bounded Reward reference the user named, or null when none was named.
                - "quantity": a positive whole reward quantity, or null when none was stated.
                - "governanceCeiling": a non-negative exact reward ceiling amount, or null when none was stated.
                Never invent a reference, a quantity or a ceiling; return {} when nothing was stated. Never emit
                a Tool identifier, a Tool argument, a security, lifecycle or provider field, or SQL.
                """;
        public static final ContentHash HASH = contractHash(VERSION, TEXT);

        private PromotionPrompt() {
        }
    }

    /** The versioned Journey input prompt contract. */
    public static final class JourneyPrompt {

        public static final String VERSION = "campaign-skill-journey-input/6";
        public static final String TEXT = """
                Resolve the Journey topology of one Campaign turn. Return exactly one JSON object with exactly
                these two fields and no others:
                - "nodes": an array of objects {"ref","capabilityCode","payload"}, each "ref" unique,
                  each "capabilityCode" one of %s, each "payload" a JSON object of that node's declared fields.
                - "edges": an array of objects {"edgeId","fromRef","toRef","branchName"}, each "edgeId"
                  unique, each endpoint one declared node "ref", each branch one of DEFAULT, TRUE, FALSE.
                Array order is not semantic; the host canonicalizes nodes by "ref" and edges by "edgeId".
                Use only these exact payload contracts:
                - flow.start: {}.
                - flow.message: {"messageLogicalId":"<server message component>"}. Use campaign-message for a
                  single catalog template; for inline delivery use exactly the matching channel component:
                  campaign-message-sms, campaign-message-email, campaign-message-push,
                  campaign-message-in-app, or campaign-message-wechat.
                - flow.wait: {"duration":"<positive ISO-8601 duration up to P30D>",
                  "anchor":"ENTRY|PREVIOUS_ACCEPTED_ACTION"}.
                - flow.condition: {"factCode":"<bounded lowercase dotted fact>","operator":"EQ|NEQ|LT|LTE|GT|GTE",
                  "valueType":"STRING|INTEGER|DECIMAL|BOOLEAN|DATE|DATETIME","value":"<canonical text>",
                  "onUnknown":"UNKNOWN|ERROR"}.
                - flow.reward: {"promotionLogicalId":"campaign-promotion"}.
                - flow.end: {"terminal":"END|EXIT"}.
                A condition has exactly one TRUE and one FALSE outgoing edge. Every other non-terminal node has
                exactly one DEFAULT outgoing edge. An end node has none. Use a condition only when the user
                supplied its fact, comparison and branch actions; never guess a predicate. References name the
                server-owned Campaign components above, never copy message text, channels, reward codes,
                quantities or limits into a Flow node.
                Campaign audience eligibility is evaluated before flow.start and must never be converted into
                a flow.condition. Treat the accepted branchDecision as authoritative: when it is NONE, emit no
                flow.condition and connect the applicable action nodes linearly with DEFAULT edges; when it is
                REQUIRED, derive every flow.condition only from the accepted branchDetail and branchRule.
                The graph has exactly one flow.start node and at most one flow.reward node. When several paths
                perform the same reward, converge those paths into that one reward node instead of duplicating
                it. Shared waits, messages, rewards and terminals may have multiple incoming edges.
                Never invent a node or an edge; return {} when no graph was described. Never emit a Tool
                identifier, a Tool argument, a security, lifecycle or provider field, or SQL.
                """.formatted(String.join(", ", FlowPackVersions.CAPABILITY_CODES));
        public static final ContentHash HASH = contractHash(VERSION, TEXT);

        private JourneyPrompt() {
        }
    }

    /** The versioned Schedule input prompt contract. */
    public static final class SchedulePrompt {

        public static final String VERSION = "campaign-skill-schedule-input/2";
        public static final String TEXT = """
                Resolve the Schedule declaration of one Campaign turn. Return exactly one JSON object whose
                fields are drawn only from this closed set and no others:
                - "form": exactly one of IMMEDIATE, ONCE, or DAILY;
                - "zoneId": a canonical IANA timezone identifier;
                - "runAt": an ISO-8601 offset date-time, for ONCE only;
                - "localTime": an ISO-8601 local time, for DAILY only;
                - "startDate": an ISO-8601 inclusive start date, for DAILY only;
                - "endDate": an ISO-8601 inclusive end date, for DAILY only.
                Use exactly these shapes:
                - IMMEDIATE: {"form":"IMMEDIATE","zoneId":"<IANA zone>"}
                - ONCE: {"form":"ONCE","zoneId":"<IANA zone>","runAt":"<offset date-time>"}
                - DAILY: {"form":"DAILY","zoneId":"<IANA zone>","localTime":"<local time>",
                  "startDate":"<date>","endDate":"<date>"}
                Every field is a string. Never invent a form or a date; return {} when nothing was described.
                Never emit a Tool identifier, a Tool argument, a security, lifecycle or provider field, or SQL.
                """;
        public static final ContentHash HASH = contractHash(VERSION, TEXT);

        private SchedulePrompt() {
        }
    }

    /** The versioned Governance input prompt contract. */
    public static final class GovernancePrompt {

        public static final String VERSION = "campaign-skill-governance-input/1";
        public static final String TEXT = """
                Resolve the Governance limits of one Campaign turn. Return exactly one JSON object with exactly
                these four fields and no others:
                - "maxRecipients": a positive whole recipient limit;
                - "maxRewardGrants": a positive whole reward-grant limit;
                - "maxRewardFaceValue": an exact non-negative reward face value with exactly two minor units;
                - "currency": a canonical three-letter ISO-4217 currency code.
                Never invent a limit or a currency; return {} when none was stated. Never emit a Tool identifier,
                a Tool argument, a security, lifecycle or provider field, or SQL.
                """;
        public static final ContentHash HASH = contractHash(VERSION, TEXT);

        private GovernancePrompt() {
        }
    }

    /** A bounded diagnostic: the pinned contracts only, never a prompt or an output. */
    @Override
    public String toString() {
        return "StructuredSkillInputResolvers[message=" + MessagePrompt.VERSION
                + ", promotion=" + PromotionPrompt.VERSION
                + ", journey=" + JourneyPrompt.VERSION
                + ", schedule=" + SchedulePrompt.VERSION
                + ", governance=" + GovernancePrompt.VERSION + ']';
    }
}
