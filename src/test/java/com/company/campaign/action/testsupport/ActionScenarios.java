package com.company.campaign.action.testsupport;

import com.company.campaign.action.ActionApplicationService;
import com.company.campaign.action.ActionExecution;
import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ActionPolicies;
import com.company.campaign.action.ActionRequestResolver;
import com.company.campaign.action.ActionRuntimePort;
import com.company.campaign.action.ActionWorker;
import com.company.campaign.action.HostActionRuntimePort;
import com.company.campaign.action.InMemoryActionStore;
import com.company.campaign.action.SimulatedActionProvider;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.runtime.ActionHandoff;
import com.company.campaign.runtime.RuntimeWorker;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import com.company.campaign.runtime.testsupport.RuntimeJourneys;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One deterministic T23 scenario: a frozen MESSAGE or REWARD Journey progressed by the accepted T22 worker
 * until it produces its one bounded {@code ACTION_READY} handoff, wired to the real external-action services
 * over deterministic simulated Providers.
 *
 * <p>The frozen definition is built through the real T20 compiler and the accepted T21 record factories, so
 * every pin the action layer re-proves witnesses exactly the frozen material a host would hold. The reward
 * shape additionally declares a real {@code governance.limit} component and real resolved catalog references,
 * so the budget declaration and the catalog identity are re-proved from the frozen Manifest rather than
 * fabricated.
 */
public final class ActionScenarios {

    /** The lease duration every scenario's service takes. */
    public static final Duration LEASE = Duration.ofSeconds(30);

    /** The claim scan bound every scenario's service uses. */
    public static final int CLAIM_SCAN = 256;

    /** The logical identity of the frozen message template component a message shape declares. */
    public static final String TEMPLATE = "tpl";

    /** The logical identity of the frozen promotion component a reward shape declares. */
    public static final String PROMOTION = "prom";

    /** The logical identity of the frozen governance declaration every reward shape declares. */
    public static final String GOVERNANCE = "gov";

    /** The logical identity of a message node inside a message shape. */
    public static final String MESSAGE_NODE = "m";

    /** The logical identity of a reward node inside a reward shape. */
    public static final String REWARD_NODE = "r";

    private ActionScenarios() {
    }

    /** One wired scenario over one frozen shape. */
    public static final class Scenario {

        public final RuntimeJourneyScenario runtime;
        public final ActionRuntimePort port;
        public final InMemoryActionStore store;
        public final ScriptedRecipientResolver recipients;
        public final SimulatedActionProvider messages;
        public final SimulatedActionProvider rewards;
        public final ActionApplicationService service;
        public final ActionWorker worker;

        private Scenario(
                RuntimeJourneyScenario runtime,
                ActionRuntimePort port,
                InMemoryActionStore store,
                ScriptedRecipientResolver recipients,
                SimulatedActionProvider messages,
                SimulatedActionProvider rewards,
                ActionApplicationService service,
                ActionWorker worker) {
            this.runtime = runtime;
            this.port = port;
            this.store = store;
            this.recipients = recipients;
            this.messages = messages;
            this.rewards = rewards;
            this.service = service;
            this.worker = worker;
        }

        /** Every bounded handoff the T22 worker produced for this scenario's action nodes. */
        public List<ActionHandoff> handoffs() {
            return runtime.store.findActionHandoffs(RuntimeJourneyScenario.TENANT, runtime.executionId);
        }

        /** The one bounded handoff the T22 worker produced for the first action node. */
        public ActionHandoff handoff() {
            return handoffs().stream().findFirst().orElseThrow();
        }

        /** The exact business idempotency key of one handoff. */
        public static String idempotencyKey(ActionHandoff handoff) {
            return ActionExecution.idempotencyKey(
                    handoff.tenantId(), handoff.participantId(), handoff.nodeLogicalId(),
                    ActionKind.from(handoff.actionKind()));
        }

        /** The exact business idempotency key of this scenario's first action node. */
        public String idempotencyKey() {
            return idempotencyKey(handoff());
        }

        /**
         * Advances the deterministic clock past every bounded backoff and every lease, then runs one step.
         *
         * <p>A step ingests any outstanding handoff, claims one due action task and processes it.
         */
        public com.company.campaign.action.ActionOutcome step(String workerId) {
            runtime.clock.advance(Duration.ofMinutes(1));
            return worker.runOnce(RuntimeJourneyScenario.TENANT, workerId).orElseThrow();
        }

        /** The stored action of one business key. */
        public com.company.campaign.action.ActionExecution action(String key) {
            return store.findActionByKey(RuntimeJourneyScenario.TENANT, key).orElseThrow();
        }

        /** The stored action of this scenario's first action node. */
        public com.company.campaign.action.ActionExecution action() {
            return action(idempotencyKey());
        }
    }

    /** One frozen {@code START -> MESSAGE -> END} scenario with the T22 handoff already produced. */
    public static Scenario message() {
        return scenario(messageShape(), "member-message-1");
    }

    /** One message scenario whose exact Participant cannot resolve a recipient target. */
    public static Scenario messageWithUnresolvableRecipient() {
        Scenario scenario = message();
        scenario.recipients.refusing(scenario.handoff().participantId());
        return scenario;
    }

    /** One frozen {@code START -> REWARD -> END} scenario with the T22 handoff already produced. */
    public static Scenario reward() {
        return scenario(rewardShape(100, "1000000.00", "CNY"), "member-reward-1");
    }

    /** One reward scenario at one exact frozen grant and face-value limit. */
    public static Scenario rewardWithLimits(int maxGrants, String maxFaceValue, String currency) {
        return scenario(rewardShape(maxGrants, maxFaceValue, currency), "member-reward-1");
    }

    /** One reward scenario at one exact frozen limit over an exact enrolled member set. */
    public static Scenario reward(
            int maxGrants, String maxFaceValue, String currency, List<String> members) {
        return scenario(rewardShape(maxGrants, maxFaceValue, currency), members);
    }

    /** One message scenario over an exact enrolled member set. */
    public static Scenario message(List<String> members) {
        return scenario(messageShape(), members);
    }

    /** One reward scenario without the frozen governance declaration, so a reward can never be submitted. */
    public static Scenario rewardWithoutGovernance() {
        return scenario(shape(
                        List.of(
                                flow("s", FlowPackVersions.START, Map.of()),
                                flow(REWARD_NODE, FlowPackVersions.REWARD,
                                        Map.of("promotionLogicalId", PROMOTION)),
                                endNode("e"),
                                promotion(PROMOTION, "10.00", 2)),
                        REWARD_NODE),
                "member-reward-1");
    }

    // ------------------------------------------------------------------ shapes

    /** One frozen {@code START -> MESSAGE -> END} shape with a real message template component. */
    public static RuntimeJourneys.Shape messageShape() {
        return shape(
                List.of(
                        flow("s", FlowPackVersions.START, Map.of()),
                        flow(MESSAGE_NODE, FlowPackVersions.MESSAGE, Map.of("messageLogicalId", TEMPLATE)),
                        endNode("e"),
                        template(TEMPLATE)),
                MESSAGE_NODE);
    }

    /** One frozen {@code START -> REWARD -> END} shape with a real promotion and governance declaration. */
    public static RuntimeJourneys.Shape rewardShape(int maxGrants, String maxFaceValue, String currency) {
        return shape(
                List.of(
                        flow("s", FlowPackVersions.START, Map.of()),
                        flow(REWARD_NODE, FlowPackVersions.REWARD, Map.of("promotionLogicalId", PROMOTION)),
                        endNode("e"),
                        promotion(PROMOTION, "10.00", 2),
                        governance(maxGrants, maxFaceValue, currency)),
                REWARD_NODE);
    }

    private static RuntimeJourneys.Shape shape(List<ResolvedComponent> components, String actionNode) {
        List<ResolvedComponent> all = new ArrayList<>(components);
        all.add(RuntimeJourneys.schedule());
        return new RuntimeJourneys.Shape(
                all,
                RuntimeJourneys.topology(
                        RuntimeJourneys.edge("s-a", "s", actionNode, JourneyBranchKind.DEFAULT),
                        RuntimeJourneys.edge("a-e", actionNode, "e", JourneyBranchKind.DEFAULT)),
                "s");
    }

    // ------------------------------------------------------------------ components

    private static ResolvedComponent endNode(String id) {
        return flow(id, FlowPackVersions.END, Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END));
    }

    private static ResolvedComponent flow(String id, String capability, Map<String, Object> fields) {
        return new ResolvedComponent(
                id, capability, FlowPackVersions.CONTRACT_VERSION, FlowPackVersions.SLOT,
                CapabilityPayload.of(new LinkedHashMap<>(fields)), List.of());
    }

    private static ResolvedComponent template(String id) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("templateLogicalId", id);
        fields.put("templateVersionId", id + "-v1");
        fields.put("templateContentHash", hash("template-" + id));
        fields.put("channel", "EMAIL");
        fields.put("variables", List.of());
        return new ResolvedComponent(
                id, MessageTemplatePack.CAPABILITY_CODE, MessageTemplatePack.CONTRACT_VERSION,
                new CompositionSlot("campaign.message", true, false), CapabilityPayload.of(fields),
                List.of(new ResolvedCatalogReference(id + "-ref", "MESSAGE_TEMPLATE", id, id + "-v1")));
    }

    private static ResolvedComponent promotion(String id, String faceValue, int quantity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rewardLogicalId", "reward-" + id);
        fields.put("rewardVersionId", "reward-" + id + "-v1");
        fields.put("rewardContentHash", hash("reward-" + id));
        fields.put("kind", "POINTS");
        fields.put("currency", "CNY");
        fields.put("faceValue", new BigDecimal(faceValue));
        fields.put("quantity", quantity);
        return new ResolvedComponent(
                id, PromotionReferencePack.CAPABILITY_CODE, PromotionReferencePack.CONTRACT_VERSION,
                new CompositionSlot("campaign.promotion", false, false), CapabilityPayload.of(fields),
                List.of(new ResolvedCatalogReference(
                        id + "-ref", "REWARD", "reward-" + id, "reward-" + id + "-v1")));
    }

    private static ResolvedComponent governance(int maxGrants, String maxFaceValue, String currency) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 100_000);
        fields.put(GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, maxGrants);
        fields.put(GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, maxFaceValue);
        fields.put(GovernanceLimitVersions.FIELD_CURRENCY, currency);
        return new ResolvedComponent(
                GOVERNANCE, GovernanceLimitVersions.CAPABILITY_CODE,
                GovernanceLimitVersions.CONTRACT_VERSION, GovernanceLimitVersions.SLOT,
                CapabilityPayload.of(fields), List.of());
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ wiring

    private static Scenario scenario(RuntimeJourneys.Shape shape, String member) {
        return scenario(shape, List.of(member));
    }

    private static Scenario scenario(RuntimeJourneys.Shape shape, List<String> members) {
        RuntimeJourneyScenario runtime = RuntimeJourneyScenario.of(shape, members);
        // The accepted T22 worker produces exactly one bounded handoff per Participant and then stops.
        RuntimeWorker runtimeWorker = runtime.worker();
        for (int index = 0; index < members.size(); index++) {
            runtimeWorker.process(runtimeWorker.claimNext("t22-worker").task());
            runtimeWorker.process(runtimeWorker.claimNext("t22-worker").task());
        }

        ActionRuntimePort port =
                new HostActionRuntimePort(runtime.store, runtime.definitions, runtime.campaignStates);
        InMemoryActionStore store = new InMemoryActionStore(runtime.store);
        ScriptedRecipientResolver recipients = new ScriptedRecipientResolver();
        SimulatedActionProvider messages = SimulatedActionProvider.message(runtime.clock);
        SimulatedActionProvider rewards = SimulatedActionProvider.reward(runtime.clock);
        ActionApplicationService service = new ActionApplicationService(
                port, store, new ActionRequestResolver(port, recipients), ActionPolicies.defaults(),
                messages, rewards, runtime.clock, LEASE, CLAIM_SCAN);
        ActionWorker worker = new ActionWorker(port, service, runtime.clock, 64);
        return new Scenario(runtime, port, store, recipients, messages, rewards, service, worker);
    }
}
