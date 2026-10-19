package com.company.campaign.dev.campaignagent.testsupport;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentIdentity;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import com.company.campaign.preview.PreviewExecutionContext;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic test host for the real T33 development Segment source.
 *
 * <p>Only the model port and the read-only Preview port are fakes; every other component is the genuine
 * accepted one. No clock, network, database, credential or random identifier is touched, and the fake Preview
 * answers under the exact trusted context the provider pins, so the genuine authoring compile re-proves a
 * coherent card rather than a fabricated one.
 */
public final class DevelopmentSegmentRealHostFixture {

    public static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    public static final String CONVERSATION = "conversation-t33";
    public static final String RUN = "run-t33";

    /**
     * The exact utterance the resolved Intent below is grounded on.
     *
     * <p>It is reused verbatim from the accepted {@code FormalIntentAiToSqlTest}, so the Intent is one that
     * provably resolves, compiles, validates and plans under the frozen Core Loyalty development profile.
     */
    public static final String UTTERANCE =
            "金卡、过去7天实付金额大于1000元，并且过去3周购买过产品名称水杯的会员";

    /** A complete Campaign statement whose audience remains exactly {@link #UTTERANCE}. */
    public static final String COMPLETE_CAMPAIGN_UTTERANCE = UTTERANCE
            + "；不提供优惠，不发送消息，不设置分支，仅执行一次，于2026年10月11日上海时间15:00运行。";

    private static final String HASH_A = "sha256:" + "a".repeat(64);
    private static final String HASH_B = "sha256:" + "b".repeat(64);
    private static final String HASH_C = "sha256:" + "c".repeat(64);

    private DevelopmentSegmentRealHostFixture() {
    }

    /** The fixed development identity the real provider pins. */
    public static DevelopmentCampaignAgentIdentity identity() {
        return new DevelopmentCampaignAgentIdentity(NOW, Set.of(CampaignPermission.values()));
    }

    /** The exact trusted reference a host Preview port must answer under. */
    public static TrustedExecutionContextRef reference() {
        return identity().executionReference();
    }

    public static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ fake provider ports

    /** A bounded Segment intent model that returns its queued raw responses in order and counts its calls. */
    public static final class FakeIntentModel implements SegmentIntentModelPort {

        private final Deque<byte[]> queued = new ArrayDeque<>();
        private final List<SegmentModelRequest> requests = new ArrayList<>();

        public FakeIntentModel enqueue(String json) {
            queued.add(json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** Enqueues one raw, deliberately malformed response. */
        public FakeIntentModel enqueueRaw(String raw) {
            queued.add(raw.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            requests.add(request);
            return queued.poll();
        }

        public int calls() {
            return requests.size();
        }

        public SegmentModelRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }
    }

    /**
     * A bounded transport that is <em>both</em> the shared structured port and the Segment intent model.
     *
     * <p>It is exactly the shape the opt-in configuration requires when it builds the real development Segment
     * source, so a test can prove one selected model object serves both boundaries.
     */
    public static final class FakeTransport
            implements DevelopmentStructuredModelPort, SegmentIntentModelPort {

        private final Deque<byte[]> routes = new ArrayDeque<>();
        private final Deque<byte[]> intents = new ArrayDeque<>();
        private final Deque<byte[]> requirements = new ArrayDeque<>();
        private final Deque<byte[]> questions = new ArrayDeque<>();
        private final Map<String, byte[]> skills = new LinkedHashMap<>();
        private final List<String> prompts = new ArrayList<>();
        private int intentCalls;

        public FakeTransport route(byte[] decision) {
            routes.add(decision.clone());
            return this;
        }

        public void reset() {
            routes.clear();
            intents.clear();
            requirements.clear();
            questions.clear();
            skills.clear();
            prompts.clear();
            intentCalls = 0;
        }

        public FakeTransport intent(String json) {
            intents.add(json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public FakeTransport requirement(String json) {
            requirements.add(json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public FakeTransport question(String question) {
            questions.add(("{\"question\":\"" + question + "\"}").getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public FakeTransport skill(String marker, String json) {
            skills.put(marker, json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public byte[] invokeStructured(String prompt) {
            prompts.add(prompt);
            if (prompt.contains("bounded first-stage router of a Campaign builder")) {
                byte[] decision = routes.poll();
                return decision == null ? "{}".getBytes(StandardCharsets.UTF_8) : decision.clone();
            }
            if (prompt.contains("campaign-requirement-extract/")) {
                byte[] document = requirements.poll();
                return document == null ? "{}".getBytes(StandardCharsets.UTF_8) : document.clone();
            }
            if (prompt.contains("campaign-requirement-question/")) {
                byte[] document = questions.poll();
                return document == null ? "{}".getBytes(StandardCharsets.UTF_8) : document.clone();
            }
            for (Map.Entry<String, byte[]> skill : skills.entrySet()) {
                if (prompt.contains(skill.getKey())) {
                    return skill.getValue().clone();
                }
            }
            return "{}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            intentCalls++;
            return intents.poll();
        }

        public List<String> prompts() {
            return List.copyOf(prompts);
        }

        /** How many Segment intent calls this transport served. */
        public int intentCalls() {
            return intentCalls;
        }
    }

    /**
     * A typed Safe Preview port returning one acceptor-coherent response under the pinned trusted context.
     *
     * <p>It never returns a sample, because the committed Skill declares {@code sampleOutputPermitted=false}.
     */
    public static final class FakePreviewPort implements SegmentPreviewPort {

        private final TrustedContextProvider identity;
        private int matchedCount;
        private int calls;
        private final List<PreviewPresentation> presentations = new ArrayList<>();

        public FakePreviewPort(TrustedContextProvider identity, int matchedCount) {
            this.identity = identity;
            this.matchedCount = matchedCount;
        }

        public FakePreviewPort withMatchedCount(int count) {
            this.matchedCount = count;
            return this;
        }

        public void reset() {
            calls = 0;
            presentations.clear();
        }

        @Override
        public PreviewResponse preview(SegmentDsl document, PreviewPresentation presentation) {
            calls++;
            presentations.add(presentation);
            TrustedExecutionContextRef reference = identity.executionReference();
            return new PreviewResponse(
                    "OK", HASH_A, HASH_B, HASH_C, matchedCount, null, null,
                    new PreviewExecutionContext(
                            reference.metadataSnapshotId(),
                            reference.asOf().toString(),
                            reference.timezone().getId(),
                            reference.dataWatermark().toString()));
        }

        public int calls() {
            return calls;
        }

        public List<PreviewPresentation> presentations() {
            return List.copyOf(presentations);
        }
    }

    // ------------------------------------------------------------------ genuine intent documents

    /** A partial Campaign brief carrying only the audience exercised by this host fixture. */
    public static String requirementJson() {
        return "{\"audience\":\"" + UTTERANCE + "\",\"offerDecision\":null,\"offerDetail\":null,"
                + "\"communicationDecision\":null,\"channels\":[],\"channelOperation\":null,"
                + "\"message\":null,\"branchDecision\":null,\"branchDetail\":null,\"branchRule\":null,"
                + "\"cadence\":null,\"schedule\":null,\"objectiveEvidence\":null}";
    }

    /** A complete no-action Campaign brief that engages Segment, the minimal Journey and one Schedule. */
    public static String completeRequirementJson() {
        return "{\"audience\":\"" + UTTERANCE + "\",\"offerDecision\":\"NONE\",\"offerDetail\":null,"
                + "\"communicationDecision\":\"NONE\",\"channels\":[],\"channelOperation\":null,"
                + "\"message\":null,\"branchDecision\":\"NONE\",\"branchDetail\":null,"
                + "\"branchRule\":null,\"cadence\":\"ONE_TIME\",\"schedule\":{"
                + "\"timeZone\":\"Asia/Shanghai\",\"runAt\":\"2026-10-11T15:00:00+08:00\","
                + "\"localTime\":null,\"startDate\":null,\"endDate\":null},\"objectiveEvidence\":null}";
    }

    /** The accepted Intent that resolves, compiles and previews under the frozen development profile. */
    public static String resolvedIntentJson() {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + clause("tier", "CURRENT_TIER", "ATTRIBUTE", "EQUAL",
                        "{\"kind\":\"ENUM\",\"raw\":\"金卡\"}", null, null,
                        null, "金卡", "member.current_tier") + ","
                + clause("spend", "PAID_PURCHASE_AMOUNT", "METRIC", "GREATER_THAN",
                        "{\"kind\":\"MONEY\",\"raw\":\"1000\"}",
                        "{\"kind\":\"ROLLING\",\"amount\":7,\"unit\":\"DAY\"}",
                        "{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"}",
                        "\"aggregation\":\"SUM\",", "过去7天实付金额大于1000元",
                        "order.paid_amount_sum") + ","
                + clause("quantity", "PURCHASED_ITEM_QUANTITY", "METRIC", "GREATER_THAN",
                        "{\"kind\":\"INTEGER\",\"raw\":\"0\"}",
                        "{\"kind\":\"ROLLING\",\"amount\":3,\"unit\":\"WEEK\"}", null,
                        "\"aggregation\":\"SUM\",\"scopeFilters\":[{"
                                + "\"filterId\":\"product\",\"concept\":\"PRODUCT_NAME\","
                                + "\"domain\":\"PRODUCT\",\"identifierKind\":\"NAME\","
                                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"raw\":\"水杯\"},"
                                + "\"provenance\":" + span("产品名称水杯") + ","
                                + "\"candidateHints\":[\"order_item.product_name\"],\"assumptions\":[]}],",
                        "过去3周购买过产品名称水杯", "order_item.quantity_sum")
                + "],\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"tier\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"spend\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"quantity\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    /** The same Intent with one recorded unsupported requirement, which the Agent terminates as UNSUPPORTED. */
    public static String unsupportedIntentJson() {
        return resolvedIntentJson().replace(
                "\"unsupportedRequirements\":[]", "\"unsupportedRequirements\":[\"RAW_SQL\"]");
    }

    private static String clause(
            String id,
            String concept,
            String kind,
            String operator,
            String value,
            String window,
            String currency,
            String additional,
            String source,
            String candidate) {
        StringBuilder json = new StringBuilder("{\"clauseId\":\"").append(id)
                .append("\",\"concept\":\"").append(concept)
                .append("\",\"definitionKind\":\"").append(kind)
                .append("\",\"negated\":false,\"operator\":\"").append(operator)
                .append("\",\"value\":").append(value).append(',');
        if (window != null) {
            json.append("\"window\":").append(window).append(',');
        }
        if (currency != null) {
            json.append("\"currency\":").append(currency).append(',');
        }
        if (additional != null) {
            json.append(additional);
        }
        return json.append("\"provenance\":").append(span(source)).append(',')
                .append("\"candidateHints\":[\"").append(candidate)
                .append("\"],\"assumptions\":[]}").toString();
    }

    private static String span(String source) {
        int start = UTTERANCE.indexOf(source);
        return "{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source + "\",\"start\":" + start
                + ",\"end\":" + (start + source.length()) + "}";
    }
}
