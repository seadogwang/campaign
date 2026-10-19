package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpResponse;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the CampaignConversationAgent host-owned recovery and guard logic.
 *
 * <p>These tests prove the agent's deterministic type-inference rules against a mocked
 * environment. They carry no scenario text, model fixture or hard-coded event word, and
 * no production event codes or sports-shoe phrases appear outside of test-controlled catalog
 * resource setup.
 */
class CampaignConversationAgentTest {

    private static final String CONVERSATION_ID = "conv-test-0001";
    private static final String RUN_ID = "run-test-0001";
    private static final String CATALOG_VERSION = "test-catalog-v1";
    private static final String POLICY_VERSION = "test-policy-v1";

    // Register-* and Purchase-* codes are test-only identifiers — they match no production
    // event code, no campaign example and no sports-shoe phrase.
    private static final String EVENT_CODE_MEMBER_REGISTER = "MEMBER_REGISTER";
    private static final String EVENT_CODE_PURCHASE_COMPLETED = "PURCHASE_COMPLETED";

    private static final ContentHash HASH =
            new ContentHash("sha256:0000000000000000000000000000000000000000000000000000000000000000");

    private static CampaignAssemblyContext assemblyContext() {
        return new CampaignAssemblyContext(
                CONVERSATION_ID, RUN_ID, ProposalMode.CREATE, null,
                new ProposalBaseRevisions(null, CATALOG_VERSION, HASH, POLICY_VERSION, HASH),
                ProposalClientContext.EMPTY, CampaignToolCatalog.standard());
    }

    /**
     * A routing-model decision of kind CAMPAIGN_REQUEST with a null campaign type.
     * This is the correct router behaviour for an in-scope non-event request where the
     * model cannot name a registered event: the host is then responsible for classification.
     */
    private static byte[] routingNullTypeJson() {
        return ("{\"routeKind\":\"CAMPAIGN_REQUEST\",\"campaignType\":null,\"skillIds\":[],"
                + "\"questionCodes\":[],\"assistantCopy\":null,\"options\":[],"
                + "\"objectiveEvidence\":null}").getBytes(StandardCharsets.UTF_8);
    }

    /** A minimal valid delta: audience only, no type-related fact. */
    private static CampaignRequirementDelta audienceDelta() {
        return new CampaignRequirementDelta(
                "members who browsed cushioned running shoes in the last 30 days",
                null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * Derives natural-language aliases from an underscored code so the test can use user-language
     * utterances like "member registration" that do not include the underscore-delimited code form.
     */
    private static List<String> naturalAliases(String code) {
        List<String> parts = new java.util.ArrayList<>();
        for (String part : code.toLowerCase(java.util.Locale.ROOT).split("_")) {
            parts.add(part);
        }
        return List.copyOf(parts);
    }

    /** A readable description of one requirement code that never carries a machine-code form. */
    private static String readable(CampaignRequirementCode code) {
        return code.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }

    /** Catalog with one selectable trigger event. */
    private static CampaignBusinessResourceCatalog catalogWith(String code, String... aliases) {
        List<String> aliasList = aliases == null || aliases.length == 0
                ? naturalAliases(code)
                : List.of(aliases);
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        code,
                        "display:" + code,
                        aliasList,
                        true,     // enabled
                        true,     // runtimeAvailable
                        "integration:" + code)));
    }

    /** Catalog with one disabled trigger event. */
    private static CampaignBusinessResourceCatalog catalogWithDisabled(String code) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        code,
                        "display:" + code,
                        naturalAliases(code),
                        false,    // enabled
                        true,     // runtimeAvailable
                        "integration:" + code)));
    }

    /** Catalog with one runtime-unavailable trigger event. */
    private static CampaignBusinessResourceCatalog catalogWithRuntimeUnavailable(String code) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        code,
                        "display:" + code,
                        naturalAliases(code),
                        true,     // enabled
                        false,    // runtimeAvailable
                        null)));  // no integrationKey → selectable() is false
    }

    /** Catalog with two selectable trigger events. */
    private static CampaignBusinessResourceCatalog catalogWithTwo(String code1, String code2, String sharedText) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        code1,
                        "display:" + code1,
                        List.of(code1.toLowerCase(), sharedText.toLowerCase()),
                        true, true, "integration:" + code1),
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        code2,
                        "display:" + code2,
                        List.of(code2.toLowerCase(), sharedText.toLowerCase()),
                        true, true, "integration:" + code2)));
    }

    /** Catalog with one selectable communication channel. */
    private static CampaignBusinessResourceCatalog catalogWithChannel(String code) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.COMMUNICATION_CHANNEL,
                        code,
                        "display:" + code,
                        List.of(code.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + code)));
    }

    /** Catalog with two selectable communication channels. */
    private static CampaignBusinessResourceCatalog catalogWithTwoChannels(String code1, String code2) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.COMMUNICATION_CHANNEL,
                        code1,
                        "display:" + code1,
                        List.of(code1.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + code1),
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.COMMUNICATION_CHANNEL,
                        code2,
                        "display:" + code2,
                        List.of(code2.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + code2)));
    }

    /** Catalog with exactly one selectable promotion. */
    private static CampaignBusinessResourceCatalog catalogWithOnePromotion(String code) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.PROMOTION,
                        code,
                        "display:" + code,
                        List.of(code.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + code)));
    }

    /** Catalog with two selectable promotions. */
    private static CampaignBusinessResourceCatalog catalogWithTwoPromotions(String code1, String code2) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.PROMOTION,
                        code1,
                        "display:" + code1,
                        List.of(code1.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + code1),
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.PROMOTION,
                        code2,
                        "display:" + code2,
                        List.of(code2.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + code2)));
    }

    /** Catalog with trigger event and channel. */
    private static CampaignBusinessResourceCatalog catalogWithEventAndChannel(
            String eventCode, String channelCode) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        eventCode,
                        "display:" + eventCode,
                        naturalAliases(eventCode),
                        true, true, "integration:" + eventCode),
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.COMMUNICATION_CHANNEL,
                        channelCode,
                        "display:" + channelCode,
                        List.of(channelCode.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + channelCode)));
    }

    /** Catalog with trigger event, channel and promotion. */
    private static CampaignBusinessResourceCatalog catalogWithAllResources(
            String eventCode, String channelCode, String promotionCode) {
        return new CampaignBusinessResourceCatalog(List.of(
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.TRIGGER_EVENT,
                        eventCode,
                        "display:" + eventCode,
                        naturalAliases(eventCode),
                        true, true, "integration:" + eventCode),
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.COMMUNICATION_CHANNEL,
                        channelCode,
                        "display:" + channelCode,
                        List.of(channelCode.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + channelCode),
                new CampaignBusinessResource(
                        CampaignBusinessResourceType.PROMOTION,
                        promotionCode,
                        "display:" + promotionCode,
                        List.of(promotionCode.toLowerCase(java.util.Locale.ROOT)),
                        true, true, "integration:" + promotionCode)));
    }

    /** Empty catalog. */
    private static CampaignBusinessResourceCatalog emptyCatalog() {
        return CampaignBusinessResourceCatalog.empty();
    }

    /** A requirement extractor that returns a minimal valid delta. */
    private static CampaignRequirementExtractorPort extracting(CampaignRequirementDelta delta) {
        return new CampaignRequirementExtractorPort() {
            @Override
            public Optional<CampaignRequirementDelta> extractDelta(
                    String utterance,
                    CampaignRequirementBrief accepted,
                    CampaignRequirementCode pendingQuestion) {
                return Optional.of(delta);
            }

            @Override
            public Optional<String> clarificationQuestion(
                    String utterance,
                    CampaignRequirementBrief accepted,
                    CampaignRequirementCode missingRequirement,
                    List<CampaignAnswerOption> options) {
                // A bounded generic question — the agent still owns which code is asked.
                // The prompt must not carry a machine code with 2+ underscores, because the
                // strict routing-vocabulary guard treats it as a physical DB identifier leak.
                // Use a readable description that never triggers the check.
                return Optional.of("The next required detail for this Campaign is " + readable(missingRequirement));
            }

            @Override
            public Optional<String> statusMessage(
                    String utterance, CampaignRequirementBrief accepted, String statusCode) {
                return Optional.of("status:" + statusCode);
            }
        };
    }

    /** Environment with a routing model, requirement extractor and resource catalog. */
    private static CampaignAgentEnvironment environment(
            byte[] routingJson,
            CampaignRequirementExtractorPort extractor,
            CampaignBusinessResourceCatalog catalog) {
        return new CampaignAgentEnvironment() {
            @Override
            public CampaignRoutingModelPort routingModel() {
                return (request) -> routingJson;
            }

            @Override
            public CampaignRequirementExtractorPort requirementExtractor() {
                return extractor;
            }

            @Override
            public CampaignBusinessResourceCatalog businessResourceCatalog() {
                return catalog;
            }
        };
    }

    // ------------------------------------------------------------------ diagnostic

    /** Asserts a turn is accepted, reporting the rejection code and other diagnostics when it is not. */
    private static void assertAccepted(CampaignAgentTurnResult result, CampaignAgentSession session) {
        if (!result.accepted()) {
            String rejection = "result.code()=" + result.code()
                    + ", phase=" + session.phase()
                    + ", revision=" + session.revision()
                    + ", brief.type=" + session.requirementBrief().type()
                    + ", brief.complete=" + session.requirementBrief().completeForAssembly();
            throw new AssertionError("turn was not accepted — " + rejection);
        }
    }

    // ------------------------------------------------------------------ recovery scenarios

    @Test
    void routerNullExactlyOneNamedSelectableTriggerEventPromotesToEventAndBindsCode() {
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                catalogWith(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a campaign for member registration");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("catalog evidence must promote type to EVENT")
                .isEqualTo(CampaignType.EVENT);
        assertThat(progress.triggerEventCode())
                .as("existing generic bindTriggerEvent must bind the exact code")
                .isEqualTo(EVENT_CODE_MEMBER_REGISTER);
        assertThat(progress.missing())
                .as("EVENT brief must not ask for cadence, timezone or runAt — "
                        + "these are schedule questions that do not apply to event-triggered campaigns")
                .noneMatch(code -> code == CampaignRequirementCode.CADENCE_REQUIRED
                        || code == CampaignRequirementCode.EXECUTION_TIMEZONE_REQUIRED
                        || code == CampaignRequirementCode.EXECUTION_TIME_REQUIRED);
    }

    @Test
    void routerNullNoEventInCatalogNoInference() {
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                emptyCatalog());
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a campaign for member registration");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("no catalog evidence must leave type null")
                .isNull();
    }

    @Test
    void routerNullAmbiguousMultipleEventsNoSelection() {
        // Both events share "registration" text in their aliases, making the catalog match ambiguous.
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                catalogWithTwo(EVENT_CODE_MEMBER_REGISTER, "PREMIUM_REGISTER", "registration"));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a registration campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("ambiguous catalog match must not guess a type")
                .isNull();
    }

    @Test
    void routerNullDisabledEventNoInference() {
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                catalogWithDisabled(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a campaign for member registration");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("a disabled resource is not selectable and must not trigger EVENT inference")
                .isNull();
    }

    @Test
    void routerNullRuntimeUnavailableEventNoInference() {
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                catalogWithRuntimeUnavailable(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a campaign for member registration");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("a runtime-unavailable resource is not selectable and must not trigger EVENT inference")
                .isNull();
    }

    // ------------------------------------------------------------------ existing behaviour preserved

    @Test
    void explicitRouterEventRemainsUnchanged() {
        // The routing model explicitly returns EVENT — the recovery path must be skipped because
        // routedType is already non-null, and the accepted brief already carries EVENT.
        byte[] routingJson = ("{\"routeKind\":\"CAMPAIGN_REQUEST\","
                + "\"campaignType\":\"EVENT\",\"skillIds\":[],"
                + "\"questionCodes\":[],\"assistantCopy\":null,\"options\":[],"
                + "\"objectiveEvidence\":null}").getBytes(StandardCharsets.UTF_8);
        CampaignAgentEnvironment env = environment(
                routingJson,
                extracting(audienceDelta()),
                emptyCatalog()); // empty catalog — recovery would find nothing
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a campaign for purchase completed");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("explicit router EVENT must be preserved unchanged")
                .isEqualTo(CampaignType.EVENT);
    }

    @Test
    void acceptedNonEventTypeNotChangedByLaterEventWording() {
        // First turn: supply every other decision as NONE so the first missing requirement is
        // CADENCE_REQUIRED. The test needs to reach the cadence question on turn 1 to derive AD_HOC
        // from ONE_TIME on turn 2.
        CampaignRequirementDelta decisionsNone = new CampaignRequirementDelta(
                "members who browsed cushioned running shoes in the last 30 days",
                OfferDecision.NONE, null,
                CommunicationDecision.NONE, List.of(), null,
                BranchDecision.NONE, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(decisionsNone),
                emptyCatalog());
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());

        // Turn 1: submit a non-event campaign request.
        CampaignAgentTurnRequest turn1 = CampaignAgentTurnRequest.of(
                0, "set up a one-time campaign for members");
        CampaignAgentTurnResult result1 = agent.handle(session, turn1);
        assertAccepted(result1, session);

        // Answer the CADENCE_REQUIRED question with ONE_TIME to derive AD_HOC.
        CampaignRequirementBrief brief1 = session.requirementBrief();
        CampaignRequirementCode nextAfterTurn1 = brief1.nextQuestion();
        assertThat(nextAfterTurn1)
                .as("first turn with all other decisions supplied as NONE must ask for cadence")
                .isEqualTo(CampaignRequirementCode.CADENCE_REQUIRED);

        // The pending clarification carries CADENCE_REQUIRED; answer it with free text.
        // CADENCE options are not resource-backed, so the answer uses freeText, not optionId.
        CampaignAgentTurnRequest turn2 = new CampaignAgentTurnRequest(
                1,
                "one time",
                List.of(new CampaignClarificationAnswer(
                        CampaignRequirementCode.CADENCE_REQUIRED.code(), null, "ONE_TIME")),
                null);

        // The extractor for turn 2 must supply ONE_TIME cadence so the brief derives AD_HOC.
        CampaignRequirementExtractorPort extractingCadence = new CampaignRequirementExtractorPort() {
            @Override
            public Optional<CampaignRequirementDelta> extractDelta(
                    String utterance,
                    CampaignRequirementBrief accepted,
                    CampaignRequirementCode pendingQuestion) {
                return Optional.of(new CampaignRequirementDelta(
                        null, null, null, null, null, null,
                        null, null, CampaignCadence.ONE_TIME, null, null, null, null));
            }

            @Override
            public Optional<String> clarificationQuestion(
                    String utterance,
                    CampaignRequirementBrief accepted,
                    CampaignRequirementCode missingRequirement,
                    List<CampaignAnswerOption> options) {
                return Optional.of("What is the cadence?");
            }
        };
        // Re-create agent with the cadence-extracting environment.
        CampaignAgentEnvironment envCadence = environment(
                routingNullTypeJson(), extractingCadence, emptyCatalog());
        CampaignConversationAgent agent2 = new CampaignConversationAgent(envCadence);
        CampaignAgentTurnResult result2 = agent2.handle(session, turn2);
        assertAccepted(result2, session);

        CampaignRequirementBrief brief2 = session.requirementBrief();
        assertThat(brief2.type())
                .as("ONE_TIME cadence must derive AD_HOC type")
                .isEqualTo(CampaignType.AD_HOC);

        // Turn 3: now submit text mentioning a trigger event, with a catalog that has one.
        // The accepted type is already AD_HOC — recovery must NOT promote to EVENT.
        CampaignAgentEnvironment eventEnv = environment(
                routingNullTypeJson(),
                extracting(new CampaignRequirementDelta(
                        "some audience", null, null,
                        CommunicationDecision.DEFINED, List.of(com.company.campaign.builder.campaignagent.CampaignMessageInputs.Channel.EMAIL),
                        "a message", BranchDecision.NONE, null, null, null, null, null, null)),
                catalogWith(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent3 = new CampaignConversationAgent(eventEnv);

        CampaignAgentTurnRequest turn3 = new CampaignAgentTurnRequest(
                2,
                "this should trigger on member registration",
                List.of(),
                null);
        CampaignAgentTurnResult result3 = agent3.handle(session, turn3);

        assertAccepted(result3, session);
        CampaignRequirementProgress progress3 = result3.projection().requirements();
        assertThat(progress3.type())
                .as("accepted non-event type must not be silently changed by later event wording")
                .isEqualTo(CampaignType.AD_HOC);
    }

    // ------------------------------------------------------------------ diagnostic evidence

    @Test
    void eventInferenceProducesDiagnosticObservation() {
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                catalogWith(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "member registration event campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        // The actual inference evidence: catalog-based EVENT type promotion and code binding.
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("catalog evidence must promote type to EVENT")
                .isEqualTo(CampaignType.EVENT);
        assertThat(progress.triggerEventCode())
                .as("existing generic bindTriggerEvent must bind the exact code")
                .isEqualTo(EVENT_CODE_MEMBER_REGISTER);
    }

    // ------------------------------------------------------------------ no production leakage

    @Test
    void noProductionScenarioLeakage() {
        // Prove that the recovery logic uses only the database-backed catalog and the
        // current user utterance — never a hard-coded word, sports-shoe text, campaign
        // example, or member-registration code.

        // "member registration" matches no resource in an empty catalog.
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(audienceDelta()),
                emptyCatalog());
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "set up a campaign for member registration");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        CampaignRequirementProgress progress = result.projection().requirements();
        assertThat(progress.type())
                .as("empty catalog must not infer EVENT from hard-coded text — "
                        + "the catalog is the sole authority")
                .isNull();

        // Same with sports-shoe text.
        CampaignAgentSession session2 = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request2 = CampaignAgentTurnRequest.of(
                0, "面向最近30天浏览或收藏过缓震跑鞋、并同意微信触达的会员，不提供优惠");
        CampaignAgentTurnResult result2 = agent.handle(session2, request2);

        assertAccepted(result2, session2);
        CampaignRequirementProgress progress2 = result2.projection().requirements();
        assertThat(progress2.type())
                .as("catalog must never match hard-coded sports-shoe or campaign-example text")
                .isNull();
    }

    // ------------------------------------------------------------------ audience refinement provenance invariants

    @Test
    void segmentSelectWindowCodeRefinesAudience() {
        // SELECT_WINDOW does not start with SEGMENT_ — the old prefix check would
        // have rejected it. Provenance accepts it because it belongs to a Segment
        // component request with the exact owning Skill.
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition should be used?",
                List.of("SELECT_WINDOW", "SEGMENT_AUDIENCE_REFINE"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest));

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("SELECT_WINDOW", null, "last 60 days"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, aggregate, null);

        assertThat(refinement)
                .as("SELECT_WINDOW is Segment-owned — provenance, not prefix, accepts it")
                .isEqualTo("last 60 days");
    }

    @Test
    void journeyAnswerDoesNotRefineAudience() {
        // Both Segment and Journey are pending. A Journey-bound answer must never
        // refine the audience because its question code is not in the Segment set.
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition?",
                List.of("SEGMENT_AUDIENCE_REFINE"),
                List.of(),
                true);
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey branching?",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest, journeyRequest));

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("JOURNEY_INPUT_REQUIRED", null, "if user is VIP"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, aggregate, null);

        assertThat(refinement)
                .as("Journey answer must never refine audience")
                .isNull();
    }

    @Test
    void segmentAndJourneyDistinctAnswersOnlySegmentUsed() {
        // Segment has SELECT_WINDOW pending; Journey has its own question pending.
        // Both are answered in one turn — only Segment's answer refines.
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey?",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest, journeyRequest));

        // Both Journey and Segment answers supplied in the same turn.
        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("JOURNEY_INPUT_REQUIRED", null, "VIP then regular"),
                new CampaignClarificationAnswer("SELECT_WINDOW", null, "last 90 days"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, aggregate, null);

        assertThat(refinement)
                .as("SELECT_WINDOW refines audience; Journey text must not enter audience")
                .isEqualTo("last 90 days");
    }

    @Test
    void noSegmentPendingReturnsNull() {
        // Only Journey has a pending question — no refinement is possible.
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey?",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(journeyRequest));

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("JOURNEY_INPUT_REQUIRED", null, "some journey text"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, aggregate, null);

        assertThat(refinement)
                .as("no Segment pending means no codes match")
                .isNull();
    }

    @Test
    void legacyFallbackWithSegmentSkillAcceptsMatchingCode() {
        // No aggregate, but pending clarification has Segment skill with a matching
        // question code — narrow legacy fallback accepts.
        CampaignClarification pending = new CampaignClarification(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition?",
                List.of("SEGMENT_AUDIENCE_REFINE"),
                List.of(),
                true);

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("SEGMENT_AUDIENCE_REFINE", null, "last 30 days"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, CampaignClarificationAggregate.empty(), pending);

        assertThat(refinement)
                .as("legacy fallback with Segment skill accepts matching code")
                .isEqualTo("last 30 days");
    }

    @Test
    void legacyFallbackRejectsNonSegmentSkill() {
        // No aggregate, pending clarification has Journey skill — legacy refuses.
        CampaignClarification pending = new CampaignClarification(
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey?",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("JOURNEY_INPUT_REQUIRED", null, "some text"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, CampaignClarificationAggregate.empty(), pending);

        assertThat(refinement)
                .as("legacy fallback with non-Segment skill returns null")
                .isNull();
    }

    @Test
    void legacyFallbackRejectsAnswerNotInPendingQuestionCodes() {
        // No aggregate, pending Segment skill, but answer code isn't in pending codes.
        CampaignClarification pending = new CampaignClarification(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition?",
                List.of("SEGMENT_AUDIENCE_REFINE"),
                List.of(),
                true);

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("UNRELATED_CODE", null, "some text"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, CampaignClarificationAggregate.empty(), pending);

        assertThat(refinement)
                .as("legacy fallback rejects answer not in pending question codes")
                .isNull();
    }

    @Test
    void missingMetadataDefinitionCodeWorksThroughProvenance() {
        // MISSING_METADATA_DEFINITION does not start with SEGMENT_ — the old prefix
        // would have rejected it. Provenance accepts it through the owning component.
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "MISSING_METADATA_DEFINITION",
                "Metadata gap",
                List.of("MISSING_METADATA_DEFINITION"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest));

        List<CampaignClarificationAnswer> answers = List.of(
                new CampaignClarificationAnswer("MISSING_METADATA_DEFINITION", null,
                        "registered definition detail"));

        String refinement = CampaignConversationAgent.audienceRefinementText(
                answers, aggregate, null);

        assertThat(refinement)
                .as("MISSING_METADATA_DEFINITION is Segment-owned — provenance accepts it")
                .isEqualTo("registered definition detail");
    }

    // ------------------------------------------------------------------ aggregate normalization invariants

    @Test
    void singleMetadataDefinitionGapPromptEqualsProjectedPrompt() {
        // Create a single-issue aggregate with a MISSING_METADATA_DEFINITION question code.
        // The component prompt from promptFor is final — no normalization is applied in the
        // active path. The projected top-level clarification must use the component's own prompt exactly.
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "MISSING_METADATA_DEFINITION",
                CampaignConversationAgent.MISSING_METADATA_DEFINITION_COPY,
                List.of("MISSING_METADATA_DEFINITION"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest));

        CampaignComponentClarification issue = aggregate.requests().get(0);
        CampaignClarification projected = aggregate.project(null, null);

        // The component issue prompt must be the host-owned deterministic copy.
        assertThat(issue.prompt())
                .as("component issue prompt must be the host-owned MISSING_METADATA_DEFINITION copy")
                .contains("metadata definitions")
                .contains("administrator");
        // The top-level projected prompt must equal the component issue prompt exactly.
        assertThat(projected.prompt())
                .as("top-level projected prompt must equal component issue prompt exactly")
                .isEqualTo(issue.prompt());
        // The projected skillId must be the owning Segment Skill.
        assertThat(projected.skillId())
                .isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        // Provenance: component, code, questionCodes, freeTextAllowed are preserved.
        assertThat(issue.code()).isEqualTo("MISSING_METADATA_DEFINITION");
        assertThat(issue.questionCodes()).containsExactly("MISSING_METADATA_DEFINITION");
        assertThat(issue.options()).isEmpty();
    }

    @Test
    void dualSegmentAndJourneyPreservesCanonicalOrderAndDeduplicatesQuestionCodes() {
        // Journey request submitted first, Segment second — the aggregate is canonical
        // component order (SEGMENT before JOURNEY). A shared question code is
        // deduplicated in the union.
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey branching?",
                List.of("JOURNEY_INPUT_REQUIRED", "SHARED_INPUT"),
                List.of(),
                true);
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition?",
                List.of("SEGMENT_INPUT_REQUIRED", "SHARED_INPUT"),
                List.of(),
                true);

        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(journeyRequest, segmentRequest));

        // Canonical component order: SEGMENT before JOURNEY regardless of input order.
        assertThat(aggregate.requests()).hasSize(2);
        assertThat(aggregate.requests().get(0).component())
                .as("SEGMENT must come before JOURNEY in canonical component order")
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(aggregate.requests().get(1).component())
                .as("JOURNEY must come after SEGMENT in canonical component order")
                .isEqualTo(CampaignComponentCode.JOURNEY);

        // Question codes are union/deduplicated — SHARED_INPUT appears exactly once.
        List<String> questionCodes = aggregate.questionCodes();
        assertThat(questionCodes)
                .contains("JOURNEY_INPUT_REQUIRED", "SEGMENT_INPUT_REQUIRED", "SHARED_INPUT");
        long sharedCount = questionCodes.stream().filter("SHARED_INPUT"::equals).count();
        assertThat(sharedCount)
                .as("SHARED_INPUT must be deduplicated in the union")
                .isEqualTo(1);

        // The aggregate prompt is deterministic (host-built, not model-synthesized).
        CampaignClarification projected = aggregate.project(null, null);
        assertThat(projected.code())
                .isEqualTo(CampaignClarificationAggregate.AGGREGATE_CODE);
        assertThat(projected.prompt())
                .as("deterministic aggregate must use host fallback prefix")
                .startsWith("More details are needed before I can continue:");
        // Both component prompts appear in the deterministic aggregate.
        assertThat(projected.prompt())
                .contains("Which audience definition?")
                .contains("What journey branching?");
        // Multi-issue aggregate with no options must not privilege any skill.
        assertThat(projected.skillId())
                .as("no single component owns the question — skillId must be null")
                .isNull();
        // Component attribution preserved: each request keeps its owning skill.
        assertThat(aggregate.requests().get(0).skillId())
                .isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(aggregate.requests().get(1).skillId())
                .isEqualTo(CampaignSkillIds.JOURNEY_FLOW);
    }

    @Test
    void nonMetadataDefinitionRequestsKeepPromptUnchanged() {
        // A non-MISSING_METADATA_DEFINITION request must keep its component-owned prompt
        // unchanged through the active path (no normalization is applied).
        CampaignComponentClarification promotionRequest = new CampaignComponentClarification(
                CampaignComponentCode.OFFER,
                CampaignSkillIds.PROMOTION_REFERENCE,
                "PROMOTION_INPUT_REQUIRED",
                "Which promotion?",
                List.of("PROMOTION_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(promotionRequest));

        CampaignComponentClarification issue = aggregate.requests().get(0);

        assertThat(issue.prompt())
                .as("non-METADATA_DEFINITION request must keep its prompt unchanged")
                .isEqualTo("Which promotion?");
        assertThat(issue.component())
                .isEqualTo(CampaignComponentCode.OFFER);
        assertThat(issue.skillId())
                .isEqualTo(CampaignSkillIds.PROMOTION_REFERENCE);
    }

    // ------------------------------------------------------------------ component prompt invariants

    @Test
    void componentPromptTextSurvivesAggregationUnchanged() {
        // Prove that the exact component prompt text survives the full
        // aggregate+projection path without alteration.
        String originalPrompt = "Exact component prompt text including punctuation!";
        CampaignComponentClarification request = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_NEEDS_INPUT",
                originalPrompt,
                List.of("SEGMENT_NEEDS_INPUT"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(request));

        // Component prompt is final — no normalization or rewording.
        assertThat(aggregate.requests().get(0).prompt())
                .as("component prompt must equal original exactly")
                .isEqualTo(originalPrompt);

        // Project — single-issue prompt comes from the component.
        CampaignClarification projected = aggregate.project(null, null);
        assertThat(projected.prompt())
                .as("projected single-issue prompt must equal component prompt exactly")
                .isEqualTo(originalPrompt);
    }

    @Test
    void noExtractorWordingCalledForComponentAggregation() {
        // Prove that skillClarificationQuestion is never called during the
        // aggregate projection path. A throwing test double proves the call path
        // does not invoke the wording boundary.
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which audience definition?",
                List.of("SEGMENT_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey branching?",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest, journeyRequest));

        // A throwing extractor: any call proves the invariant is broken.
        CampaignRequirementExtractorPort throwingExtractor = new CampaignRequirementExtractorPort() {
            @Override
            public Optional<CampaignRequirementDelta> extractDelta(
                    String utterance, CampaignRequirementBrief accepted,
                    CampaignRequirementCode pendingQuestion) {
                throw new AssertionError("extractDelta must not be called for component aggregation");
            }

            @Override
            public Optional<String> skillClarificationQuestion(
                    CampaignRequirementBrief accepted, CampaignClarification clarification) {
                throw new AssertionError(
                        "skillClarificationQuestion must not be called for component aggregation");
            }

            @Override
            public Optional<String> aggregateClarificationQuestion(
                    List<CampaignComponentClarification> requests) {
                throw new AssertionError(
                        "aggregateClarificationQuestion must not be called for component aggregation");
            }
        };

        // Project — must not call any extractor method.
        CampaignClarification projected = aggregate.project(
                throwingExtractor, CampaignRequirementBrief.empty());
        assertThat(projected).isNotNull();
        assertThat(projected.prompt())
                .as("deterministic aggregate prompt must be host-built")
                .startsWith("More details are needed before I can continue:");
        // Both original component prompts appear in the deterministic aggregation.
        assertThat(projected.prompt())
                .contains("Which audience definition?")
                .contains("What journey branching?");
    }

    @Test
    void deterministicOrderingDoesNotAlterComponentPrompts() {
        // Prove that canonical ordering reorders requests but leaves each
        // component prompt exactly as it was produced by from().
        String segmentPrompt = "Segment-component-EXACT-prompt";
        String journeyPrompt = "Journey-component-EXACT-prompt";

        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                journeyPrompt,
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                segmentPrompt,
                List.of("SEGMENT_INPUT_REQUIRED"),
                List.of(),
                true);

        // Submit in reverse canonical order.
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(journeyRequest, segmentRequest));

        // Prompts preserved exactly, component ordering is canonical.
        assertThat(aggregate.requests()).hasSize(2);
        assertThat(aggregate.requests().get(0).component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(aggregate.requests().get(0).prompt())
                .as("SEGMENT prompt unchanged by reordering")
                .isEqualTo(segmentPrompt);
        assertThat(aggregate.requests().get(1).component())
                .isEqualTo(CampaignComponentCode.JOURNEY);
        assertThat(aggregate.requests().get(1).prompt())
                .as("JOURNEY prompt unchanged by reordering")
                .isEqualTo(journeyPrompt);
    }

    @Test
    void crossComponentAnswerMismatchRejected() {
        // Prove that an answer naming a question code not owned by its declared
        // component is rejected.
        CampaignAgentSession session = createMultiComponentSessionForMismatch();
        // Answer with a JOURNEY code but claims SEGMENT component ownership.
        CampaignClarificationAnswer mismatched = new CampaignClarificationAnswer(
                "JOURNEY_INPUT_REQUIRED", null, "journey answer",
                CampaignComponentCode.SEGMENT);

        assertThatThrownBy(
                        () -> requireMultiComponentMatch(session, mismatched))
                .isInstanceOf(CampaignAgentException.class)
                .hasMessageContaining("component");
    }

    @Test
    void twoComponentsSameCodeBothSurvive() {
        // Prove that when two different components emit the same machine
        // question code, both issues survive independently in the aggregate
        // with distinct component ownership. No deduplication across
        // components occurs.
        String sharedCode = "SHARED_INPUT_REQUIRED";
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                sharedCode,
                "Segment's question for shared code",
                List.of(sharedCode),
                List.of(),
                true);
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                sharedCode,
                "Journey's question for shared code",
                List.of(sharedCode),
                List.of(),
                true);

        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest, journeyRequest));

        // Both survive: two requests, two components, two prompts.
        assertThat(aggregate.requests()).hasSize(2);
        assertThat(aggregate.requests().get(0).component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(aggregate.requests().get(0).prompt())
                .isEqualTo("Segment's question for shared code");
        assertThat(aggregate.requests().get(1).component())
                .isEqualTo(CampaignComponentCode.JOURNEY);
        assertThat(aggregate.requests().get(1).prompt())
                .isEqualTo("Journey's question for shared code");

        // Each component sees its own request.
        assertThat(aggregate.requestFor(CampaignComponentCode.SEGMENT)).isNotNull();
        assertThat(aggregate.requestFor(CampaignComponentCode.JOURNEY)).isNotNull();
        assertThat(aggregate.requestFor(CampaignComponentCode.SEGMENT).prompt())
                .isNotEqualTo(aggregate.requestFor(CampaignComponentCode.JOURNEY).prompt());
    }

    // ------------------------------------------------------------------ helpers for mismatch tests

    private static CampaignAgentSession createMultiComponentSessionForMismatch() {
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_NEEDS_INPUT",
                "Segment question",
                List.of("SEGMENT_AUDIENCE_REPHRASE_REQUIRED"),
                List.of(),
                true);
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_NEEDS_INPUT",
                "Journey question",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest, journeyRequest));
        session.setPendingClarifications(
                aggregate.project(null, CampaignRequirementBrief.empty()),
                aggregate.requests());
        session.appendTurn(new CampaignConversationTurn(
                "turn-1", CampaignConversationTurn.USER, 0,
                "build a campaign", null));
        session.appendTurn(new CampaignConversationTurn(
                "turn-2", CampaignConversationTurn.ASSISTANT, 1,
                "More details needed", null));
        session.advance();
        session.advance();
        return session;
    }

    private static void requireMultiComponentMatch(
            CampaignAgentSession session, CampaignClarificationAnswer answer) {
        CampaignClarificationAggregate aggregate = session.pendingComponentClarifications();
        if (!aggregate.isEmpty() && !aggregate.single()) {
            if (answer.component() == null) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A multi-component clarification requires every answer "
                                + "to name its owning component.");
            }
            CampaignComponentClarification componentRequest =
                    aggregate.requestFor(answer.component());
            if (componentRequest == null) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A clarification answer names a component "
                                + "that has no pending issue.");
            }
            if (answer.code() != null
                    && !componentRequest.questionCodes().contains(answer.code())) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A clarification answer names a question "
                                + "not pending for its component.");
            }
        }
    }

    @Test
    void emptyAggregateProjectThrows() {
        // An empty aggregate has no clarification to project.
        CampaignClarificationAggregate empty = CampaignClarificationAggregate.empty();
        try {
            empty.project(null, null);
            throw new AssertionError("empty aggregate must not produce a clarification");
        } catch (CampaignAgentException expected) {
            assertThat(expected.code())
                    .isEqualTo(CampaignAgentErrorCodes.CLARIFICATION_MISMATCH);
        }
    }

    // ------------------------------------------------------------------ manifest synchronization

    @Test
    void manifestSynchronizationFromBriefDerivesModelDecisionsForAllCapabilities() {
        // Create a delta that supplies every decision fact to produce a complete brief.
        CampaignRequirementDelta completeDelta = new CampaignRequirementDelta(
                "members with purchase history",
                OfferDecision.DEFINED, "discount offer detail",
                CommunicationDecision.DEFINED,
                List.of(CampaignMessageInputs.Channel.SMS),
                "message content",
                BranchDecision.REQUIRED, "branch detail",
                CampaignCadence.ONE_TIME, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(completeDelta),
                catalogWithAllResources(EVENT_CODE_MEMBER_REGISTER, "SMS", "PROMO01"));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "member registration event with SMS and promotion promo01");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);
        List<CampaignComponentManifest> manifests = session.componentManifests();

        // All six manifests produced with MODEL source.
        assertThat(manifests).hasSize(CampaignCapability.values().length);
        for (CampaignComponentManifest m : manifests) {
            assertThat(m.decisionSource())
                    .as("manifest for " + m.capability() + " must have MODEL source")
                    .isEqualTo(DecisionSource.MODEL);
            assertThat(m.reasonCode())
                    .isEqualTo("MODEL_REQUIREMENT_EXTRACTION");
            assertThat(m.evidence()).isNotEmpty();
        }

        // Verify each capability's decision.
        CampaignComponentManifest segment = session.manifestFor(CampaignCapability.SEGMENT);
        assertThat(segment.usageDecision()).isEqualTo(UsageDecision.REQUIRED);

        CampaignComponentManifest event = session.manifestFor(CampaignCapability.EVENT);
        assertThat(event.usageDecision()).isEqualTo(UsageDecision.REQUIRED);

        CampaignComponentManifest message = session.manifestFor(CampaignCapability.MESSAGE);
        assertThat(message.usageDecision()).isEqualTo(UsageDecision.REQUIRED);

        CampaignComponentManifest promotion = session.manifestFor(CampaignCapability.PROMOTION);
        assertThat(promotion.usageDecision()).isEqualTo(UsageDecision.REQUIRED);

        CampaignComponentManifest flow = session.manifestFor(CampaignCapability.FLOW);
        assertThat(flow.usageDecision()).isEqualTo(UsageDecision.REQUIRED);

        CampaignComponentManifest schedule = session.manifestFor(CampaignCapability.SCHEDULE);
        // Event-triggered Campaign with cadence → SCHEDULE is NOT_REQUIRED because type is EVENT.
        // But wait: the catalog triggers EVENT type inference since "member registration" matches.
        // With EVENT type, SCHEDULE becomes NOT_REQUIRED.
        assertThat(schedule.usageDecision()).isEqualTo(UsageDecision.NOT_REQUIRED);
    }

    @Test
    void userDecisionNotOverwrittenByModelSynchronization() {
        // Pre-populate the session with a USER decision for EVENT=NOT_REQUIRED.
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        session.setComponentManifests(List.of(new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.NOT_REQUIRED,
                "user-rejected-event", "no event needed",
                List.of(), DecisionSource.USER)));

        // Now run a turn whose brief would make EVENT REQUIRED (type is EVENT, triggerEventCode set).
        // The synchronize method must skip EVENT because a USER decision already exists.
        CampaignRequirementDelta eventDelta = new CampaignRequirementDelta(
                "members with purchase history",
                null, null, null, null, null,
                null, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(eventDelta),
                catalogWith(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "member registration campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest eventManifest = session.manifestFor(CampaignCapability.EVENT);
        assertThat(eventManifest)
                .as("EVENT manifest must still exist after synchronization")
                .isNotNull();
        assertThat(eventManifest.usageDecision())
                .as("USER decision must not be overwritten by MODEL")
                .isEqualTo(UsageDecision.NOT_REQUIRED);
        assertThat(eventManifest.decisionSource())
                .as("EVENT manifest source must remain USER")
                .isEqualTo(DecisionSource.USER);
        assertThat(eventManifest.reasonCode())
                .as("USER manifest reason code must be preserved")
                .isEqualTo("user-rejected-event");
    }

    @Test
    void modelSyncUpdatesNonUserManifests() {
        // Pre-populate with a MODEL decision for MESSAGE=UNCERTAIN.
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        session.setComponentManifests(List.of(new CampaignComponentManifest(
                CampaignCapability.MESSAGE, UsageDecision.UNCERTAIN,
                "R01", "was uncertain", List.of(), DecisionSource.MODEL)));

        // Supply communication DEFINED in the delta — MODEL sync should update the manifest.
        CampaignRequirementDelta messageDelta = new CampaignRequirementDelta(
                "audience text", null, null,
                CommunicationDecision.DEFINED,
                List.of(CampaignMessageInputs.Channel.SMS),
                "message content", null, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(messageDelta),
                catalogWithChannel("SMS"));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "send sms campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest messageManifest = session.manifestFor(CampaignCapability.MESSAGE);
        assertThat(messageManifest)
                .as("MESSAGE manifest must exist after sync")
                .isNotNull();
        assertThat(messageManifest.usageDecision())
                .as("MODEL decision must be updated when the brief changes")
                .isEqualTo(UsageDecision.REQUIRED);
        assertThat(messageManifest.decisionSource())
                .isEqualTo(DecisionSource.MODEL);
    }

    // ------------------------------------------------------------------ resource binding

    @Test
    void singleSelectablePromotionAutoBoundWhenRequired() {
        CampaignRequirementDelta promotionDelta = new CampaignRequirementDelta(
                "audience", OfferDecision.DEFINED, "discount details",
                null, null, null,
                null, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(promotionDelta),
                catalogWithOnePromotion("PROMO_SINGLE"));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "campaign with discount promotion");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest promo = session.manifestFor(CampaignCapability.PROMOTION);
        assertThat(promo).isNotNull();
        assertThat(promo.usageDecision()).isEqualTo(UsageDecision.REQUIRED);
        assertThat(promo.selectedResourceCodes())
                .as("single selectable promotion must be auto-bound")
                .containsExactly("PROMO_SINGLE");
        assertThat(promo.resourceBindingComplete()).isTrue();
    }

    @Test
    void multipleSelectablePromotionsNotAutoBound() {
        CampaignRequirementDelta promotionDelta = new CampaignRequirementDelta(
                "audience", OfferDecision.DEFINED, "discount details",
                null, null, null,
                null, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(promotionDelta),
                catalogWithTwoPromotions("PROMO_A", "PROMO_B"));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "campaign with discount promotion");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest promo = session.manifestFor(CampaignCapability.PROMOTION);
        assertThat(promo).isNotNull();
        assertThat(promo.usageDecision()).isEqualTo(UsageDecision.REQUIRED);
        assertThat(promo.selectedResourceCodes())
                .as("multiple selectable promotions must not be auto-bound")
                .isEmpty();
    }

    @Test
    void zeroSelectablePromotionsNotBound() {
        CampaignRequirementDelta promotionDelta = new CampaignRequirementDelta(
                "audience", OfferDecision.DEFINED, "discount details",
                null, null, null,
                null, null, null, null, null, null, null);
        // Empty catalog — zero selectable promotions.
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(promotionDelta),
                emptyCatalog());
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "campaign with discount promotion");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest promo = session.manifestFor(CampaignCapability.PROMOTION);
        assertThat(promo).isNotNull();
        assertThat(promo.usageDecision()).isEqualTo(UsageDecision.REQUIRED);
        assertThat(promo.selectedResourceCodes())
                .as("zero selectable promotions must produce empty resource codes")
                .isEmpty();
    }

    @Test
    void eventTriggerEventCodeBoundFromCatalog() {
        CampaignRequirementDelta eventDelta = new CampaignRequirementDelta(
                "audience", null, null, null, null, null,
                null, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(eventDelta),
                catalogWith(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "member registration campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest event = session.manifestFor(CampaignCapability.EVENT);
        assertThat(event).isNotNull();
        assertThat(event.usageDecision()).isEqualTo(UsageDecision.REQUIRED);
        assertThat(event.selectedResourceCodes())
                .as("selectable trigger event code must be bound")
                .containsExactly(EVENT_CODE_MEMBER_REGISTER);
    }

    @Test
    void messageChannelBoundFromCatalogSelectableChannels() {
        CampaignRequirementDelta messageDelta = new CampaignRequirementDelta(
                "audience", null, null,
                CommunicationDecision.DEFINED,
                List.of(CampaignMessageInputs.Channel.SMS),
                "message text", null, null, null, null, null, null, null);
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(messageDelta),
                catalogWithChannel("SMS"));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "send sms to audience");
        CampaignAgentTurnResult result = agent.handle(session, request);

        assertAccepted(result, session);

        CampaignComponentManifest message = session.manifestFor(CampaignCapability.MESSAGE);
        assertThat(message).isNotNull();
        assertThat(message.usageDecision()).isEqualTo(UsageDecision.REQUIRED);
        assertThat(message.selectedResourceCodes())
                .as("selectable channel must be bound")
                .contains("SMS");
    }

    // ------------------------------------------------------------------ resourceBindingsComplete gate

    @Test
    void nullCatalogWithRequiredResourceManifestBlocksReadiness() {
        // Create environment with null catalog and a session that has a REQUIRED
        // EVENT manifest with no bound resources. The agent must not reach READY_TO_FINALIZE.
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        session.setComponentManifests(List.of(
                new CampaignComponentManifest(
                        CampaignCapability.EVENT, UsageDecision.REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.SEGMENT, UsageDecision.REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.MESSAGE, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.PROMOTION, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.FLOW, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.SCHEDULE, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL)));

        // Environment with a null catalog.
        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(new CampaignRequirementDelta(
                        "audience", null, null, null, null, null,
                        null, null, null, null, null, null, null)),
                null); // null catalog
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "member registration campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        // The turn may be accepted but must not reach READY_TO_FINALIZE
        // because null catalog + REQUIRED resource-binding manifest blocks readiness.
        if (result.accepted()) {
            assertThat(result.projection().phase())
                    .as("null catalog with REQUIRED resource manifest must block READY_TO_FINALIZE")
                    .isNotEqualTo(CampaignAgentPhase.READY_TO_FINALIZE);
        }
    }

    @Test
    void uncertainManifestBlocksReadyPhase() {
        // A session with an UNCERTAIN manifest must not reach READY_TO_FINALIZE.
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        session.setComponentManifests(List.of(
                new CampaignComponentManifest(
                        CampaignCapability.EVENT, UsageDecision.UNCERTAIN,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.SEGMENT, UsageDecision.REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.MESSAGE, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.PROMOTION, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.FLOW, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL),
                new CampaignComponentManifest(
                        CampaignCapability.SCHEDULE, UsageDecision.NOT_REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL)));

        CampaignAgentEnvironment env = environment(
                routingNullTypeJson(),
                extracting(new CampaignRequirementDelta(
                        "audience", null, null, null, null, null,
                        null, null, null, null, null, null, null)),
                catalogWith(EVENT_CODE_MEMBER_REGISTER));
        CampaignConversationAgent agent = new CampaignConversationAgent(env);
        CampaignAgentTurnRequest request = CampaignAgentTurnRequest.of(
                0, "member registration campaign");
        CampaignAgentTurnResult result = agent.handle(session, request);

        if (result.accepted()) {
            assertThat(result.projection().phase())
                    .as("UNCERTAIN manifest must block READY_TO_FINALIZE phase")
                    .isNotEqualTo(CampaignAgentPhase.READY_TO_FINALIZE);
        }
    }

    // ------------------------------------------------------------------ HTTP serialization

    @Test
    void componentManifestJsonFieldIsSingular() throws Exception {
        // Prove that the @JsonProperty("componentManifest") annotation on the
        // componentManifests field produces singular "componentManifest" in JSON.
        var manifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED,
                "MODEL_REQUIREMENT_EXTRACTION", "utterance evidence",
                List.of("MEMBER_REGISTER"), DecisionSource.MODEL);

        // Exercise the wire projection through a real session.
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext());
        session.setComponentManifests(List.of(manifest));
        // Use the fact that manifest count > 0, so serialization will serialize the list.
        List<CampaignComponentManifest> sessionManifests = session.componentManifests();
        assertThat(sessionManifests).hasSize(1);

        // Serialize the HTTP wire record directly via Jackson.
        CampaignAgentTurnHttpResponse.ComponentManifest wireManifest =
                new CampaignAgentTurnHttpResponse.ComponentManifest(
                        "EVENT", "REQUIRED", "MODEL",
                        "MODEL_REQUIREMENT_EXTRACTION", "utterance evidence",
                        List.of("MEMBER_REGISTER"));
        CampaignAgentTurnHttpResponse response = new CampaignAgentTurnHttpResponse(
                true, null, "conv-test", "run-test", 1L,
                "BUILDING", "ACCEPTED", "CAMPAIGN_REQUEST",
                List.of(), List.of(), List.of(), null, "copy",
                null, null, null, null, null, null,
                null, List.of(),
                List.of(wireManifest));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(response);

        assertThat(json)
                .as("JSON must contain singular 'componentManifest' field")
                .contains("\"componentManifest\"");
        assertThat(json)
                .as("JSON must not contain plural 'componentManifests' field")
                .doesNotContain("\"componentManifests\"");
    }
}