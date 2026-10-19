package com.company.campaign.builder.capability.composition;

import com.company.campaign.builder.compiler.AuthoringPolicyValidator;
import com.company.campaign.builder.compiler.AuthoringValidationRequest;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Issue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 section 5: the deterministic composition policy validator.
 *
 * <p>It is usable as an {@link AuthoringPolicyValidator}, validates exact enabled contract/slot agreement
 * through a supplied immutable {@link T19PackIndex}, checks cross-component Flow references, cardinality,
 * reward/governance currency and exposure, the relative slot order and the Segment-reference prohibition, and
 * reports stable, ordered, bounded issue lists — all while never validating the Journey graph, edges or
 * reachability, which belong to T20.
 */
class T19CompositionValidatorTest {

    private static final String RUNTIME = "campaign-runtime/1.0";

    private static final CompositionSlot MESSAGE_SLOT = new CompositionSlot("campaign.message", false, false);
    private static final CompositionSlot PROMOTION_SLOT = new CompositionSlot("campaign.promotion", false, false);
    private static final CompositionSlot FLOW_SLOT = new CompositionSlot("campaign.flow", true, false);
    private static final CompositionSlot SCHEDULE_SLOT = new CompositionSlot("campaign.schedule", false, true);
    private static final CompositionSlot GOVERNANCE_SLOT = new CompositionSlot("campaign.governance", false, true);
    private static final CompositionSlot SEGMENT_SLOT = new CompositionSlot("campaign.segment", false, false);

    private static final T19PackIndex INDEX = index(RUNTIME, RUNTIME, true);

    // ------------------------------------------------------------------ facts and component helpers

    private static T19PackFacts fact(
            String capability, CompositionSlot slot, boolean enabled, String runtime) {
        return new T19PackFacts(capability, "1.0", slot, enabled, new RuntimeCompatibility(runtime, true, false));
    }

    private static T19PackIndex index(String expectedRuntime, String packRuntime, boolean enabled) {
        List<T19PackFacts> facts = new ArrayList<>();
        facts.add(fact("message.template", MESSAGE_SLOT, enabled, packRuntime));
        facts.add(fact("promotion.reference", PROMOTION_SLOT, enabled, packRuntime));
        for (String flow : List.of("flow.start", "flow.message", "flow.wait", "flow.condition",
                "flow.reward", "flow.end")) {
            facts.add(fact(flow, FLOW_SLOT, enabled, packRuntime));
        }
        facts.add(fact("schedule.basic", SCHEDULE_SLOT, enabled, packRuntime));
        facts.add(fact("governance.limit", GOVERNANCE_SLOT, enabled, packRuntime));
        return T19PackIndex.of(expectedRuntime, facts);
    }

    private static ResolvedComponent component(
            String logicalId, String capability, CompositionSlot slot, Map<String, Object> value) {
        return new ResolvedComponent(logicalId, capability, "1.0", slot, CapabilityPayload.of(value), List.of());
    }

    private static ResolvedComponent segment(String logicalId) {
        return component(logicalId, "segment.rule-based", SEGMENT_SLOT, Map.of());
    }

    private static ResolvedComponent message(String logicalId) {
        return component(logicalId, "message.template", MESSAGE_SLOT, Map.of());
    }

    private static ResolvedComponent promotion(String logicalId, String currency, String faceValue, int quantity) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("currency", currency);
        value.put("faceValue", new BigDecimal(faceValue));
        value.put("quantity", quantity);
        return component(logicalId, "promotion.reference", PROMOTION_SLOT, value);
    }

    private static ResolvedComponent governance(
            String logicalId, String currency, long maxGrants, String maxFaceValue) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("currency", currency);
        value.put("maxRecipients", 1000L);
        value.put("maxRewardGrants", maxGrants);
        value.put("maxRewardFaceValue", new BigDecimal(maxFaceValue));
        return component(logicalId, "governance.limit", GOVERNANCE_SLOT, value);
    }

    private static ResolvedComponent flowMessage(String logicalId, String messageLogicalId) {
        return component(logicalId, "flow.message", FLOW_SLOT, Map.of("messageLogicalId", messageLogicalId));
    }

    private static ResolvedComponent flowReward(String logicalId, String promotionLogicalId) {
        return component(logicalId, "flow.reward", FLOW_SLOT, Map.of("promotionLogicalId", promotionLogicalId));
    }

    private static ResolvedComponent flow(String logicalId, String capability) {
        return component(logicalId, capability, FLOW_SLOT, Map.of());
    }

    private static ResolvedComponent schedule(String logicalId) {
        return component(logicalId, "schedule.basic", SCHEDULE_SLOT, Map.of());
    }

    private static List<String> codes(List<Issue> issues) {
        List<String> codes = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            codes.add(issue.code());
        }
        return codes;
    }

    private List<Issue> validate(T19PackIndex index, List<ResolvedComponent> components) {
        AuthoringValidationRequest request = new AuthoringValidationRequest(
                BuilderFixtures.CAMPAIGN, BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                components, BuilderFixtures.context());
        return new T19CompositionValidator(index).validate(request);
    }

    private List<Issue> validate(List<ResolvedComponent> components) {
        return validate(INDEX, components);
    }

    private static List<ResolvedComponent> complete() {
        List<ResolvedComponent> components = new ArrayList<>();
        components.add(segment("segment-001"));
        components.add(message("message-001"));
        components.add(promotion("promotion-001", "CNY", "5.00", 2));
        components.add(flow("flow-start-001", "flow.start"));
        components.add(flowMessage("flow-message-001", "message-001"));
        components.add(flowReward("flow-reward-001", "promotion-001"));
        components.add(schedule("schedule-001"));
        components.add(governance("governance-001", "CNY", 10L, "100.00"));
        return components;
    }

    // ------------------------------------------------------------------ the accepted composition

    @Test
    void aCompleteCompositionPassesWithNoIssues() {
        assertThat(validate(complete())).isEmpty();
    }

    @Test
    void itIsUsableAsAnAuthoringPolicyValidatorAndIsDeterministic() {
        AuthoringPolicyValidator validator = new T19CompositionValidator(INDEX);
        AuthoringValidationRequest request = new AuthoringValidationRequest(
                BuilderFixtures.CAMPAIGN, BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                complete(), BuilderFixtures.context());
        List<Issue> first = validator.validate(request);
        List<Issue> second = validator.validate(request);
        assertThat(first).isEqualTo(second).isEmpty();
    }

    @Test
    void aPartialCompositionIsValidWhenItsOwnDependenciesAreSatisfied() {
        // A lone Message component, and a Message + Flow Message pair, must not require a Reward or Schedule.
        assertThat(validate(List.of(message("message-001")))).isEmpty();
        assertThat(validate(List.of(message("message-001"), flowMessage("flow-message-001", "message-001"))))
                .isEmpty();
    }

    // ------------------------------------------------------------------ catalog/enabled/contract/slot/runtime

    @Test
    void aDisabledPackIsRejected() {
        T19PackIndex disabled = index(RUNTIME, RUNTIME, false);
        assertThat(codes(validate(disabled, List.of(message("message-001")))))
                .containsExactly(T19CompositionErrorCodes.CAPABILITY_NOT_ENABLED);
    }

    @Test
    void anUnknownT19CapabilityInTheCatalogIsRejected() {
        T19PackIndex sparse = T19PackIndex.of(RUNTIME, fact("message.template", MESSAGE_SLOT, true, RUNTIME));
        assertThat(codes(validate(sparse, List.of(message("message-001"), schedule("schedule-001")))))
                .contains(T19CompositionErrorCodes.CAPABILITY_NOT_IN_CATALOG);
    }

    @Test
    void aContractVersionMismatchIsRejected() {
        ResolvedComponent wrongVersion = new ResolvedComponent(
                "message-001", "message.template", "2.0", MESSAGE_SLOT,
                CapabilityPayload.of(Map.of()), List.of());
        assertThat(codes(validate(List.of(wrongVersion))))
                .containsExactly(T19CompositionErrorCodes.CONTRACT_VERSION_MISMATCH);
    }

    @Test
    void aSlotMismatchIsRejected() {
        ResolvedComponent wrongSlot = new ResolvedComponent(
                "message-001", "message.template", "1.0", FLOW_SLOT,
                CapabilityPayload.of(Map.of()), List.of());
        assertThat(codes(validate(List.of(wrongSlot)))).contains(T19CompositionErrorCodes.SLOT_MISMATCH);
    }

    @Test
    void anIncompatibleRuntimeSchemaIsRejected() {
        T19PackIndex incompatible = index(RUNTIME, "campaign-runtime/2.0", true);
        List<Issue> issues = validate(incompatible, List.of(message("message-001")));
        assertThat(codes(issues)).containsExactly(T19CompositionErrorCodes.RUNTIME_INCOMPATIBLE);
    }

    @Test
    void aT19ComponentCarryingASegmentReferenceIsRejected() {
        ResolvedComponent withSegmentReference = new ResolvedComponent(
                "message-001", "message.template", "1.0", MESSAGE_SLOT, CapabilityPayload.of(Map.of()),
                List.of(new ResolvedCatalogReference("seg-ref-1", "SEGMENT_RULE", "segment-001", "segment-001-v1")));
        assertThat(codes(validate(List.of(withSegmentReference))))
                .containsExactly(T19CompositionErrorCodes.SEGMENT_REFERENCE);
    }

    // ------------------------------------------------------------------ cardinality

    @Test
    void moreThanOneStartRewardScheduleOrGovernanceIsRejected() {
        assertThat(codes(validate(List.of(flow("flow-start-001", "flow.start"),
                flow("flow-start-002", "flow.start")))))
                .containsExactly(T19CompositionErrorCodes.START_CARDINALITY);
        assertThat(codes(validate(List.of(schedule("schedule-001"), schedule("schedule-002")))))
                .containsExactly(T19CompositionErrorCodes.SCHEDULE_CARDINALITY);
        assertThat(codes(validate(List.of(governance("governance-001", "CNY", 10L, "100.00"),
                governance("governance-002", "CNY", 10L, "100.00")))))
                .containsExactly(T19CompositionErrorCodes.GOVERNANCE_CARDINALITY);
    }

    @Test
    void moreThanOneRewardNodeIsRejected() {
        List<ResolvedComponent> components = new ArrayList<>();
        components.add(message("message-001"));
        components.add(promotion("promotion-001", "CNY", "5.00", 1));
        components.add(flowReward("flow-reward-001", "promotion-001"));
        components.add(flowReward("flow-reward-002", "promotion-001"));
        components.add(governance("governance-001", "CNY", 10L, "100.00"));
        assertThat(codes(validate(components))).contains(T19CompositionErrorCodes.REWARD_CARDINALITY);
    }

    // ------------------------------------------------------------------ references

    @Test
    void aFlowMessageOrRewardReferenceToANonExistentComponentIsRejected() {
        assertThat(codes(validate(List.of(message("message-001"), flowMessage("flow-message-001", "message-999")))))
                .containsExactly(T19CompositionErrorCodes.MESSAGE_REFERENCE_UNRESOLVED);
        assertThat(codes(validate(List.of(message("message-001"),
                promotion("promotion-001", "CNY", "5.00", 1),
                flowMessage("flow-message-001", "message-001"),
                flowReward("flow-reward-001", "promotion-999"),
                governance("governance-001", "CNY", 10L, "100.00")))))
                .containsExactly(T19CompositionErrorCodes.REWARD_REFERENCE_UNRESOLVED);
    }

    @Test
    void aFlowMessageReferenceToTheWrongCapabilityIsRejected() {
        // The logical id exists but names a schedule, not a message.template.
        assertThat(codes(validate(List.of(schedule("schedule-001"), flowMessage("flow-message-001", "schedule-001")))))
                .contains(T19CompositionErrorCodes.MESSAGE_REFERENCE_UNRESOLVED);
    }

    // ------------------------------------------------------------------ reward / governance

    @Test
    void aRewardFlowWithoutGovernanceIsRejected() {
        List<ResolvedComponent> components = new ArrayList<>();
        components.add(message("message-001"));
        components.add(promotion("promotion-001", "CNY", "5.00", 1));
        components.add(flowReward("flow-reward-001", "promotion-001"));
        assertThat(codes(validate(components))).containsExactly(T19CompositionErrorCodes.GOVERNANCE_REQUIRED);
    }

    @Test
    void aCurrencyMismatchBetweenRewardAndGovernanceIsRejected() {
        List<ResolvedComponent> components = complete();
        components.set(2, promotion("promotion-001", "USD", "5.00", 2));
        assertThat(codes(validate(components)))
                .containsExactly(T19CompositionErrorCodes.REWARD_CURRENCY_MISMATCH);
    }

    @Test
    void conservativeGrantAndFaceValueExposureMustBeCovered() {
        List<ResolvedComponent> grantsExceeded = complete();
        grantsExceeded.set(7, governance("governance-001", "CNY", 1L, "100.00"));
        assertThat(codes(validate(grantsExceeded)))
                .containsExactly(T19CompositionErrorCodes.REWARD_GRANTS_EXCEEDED);

        List<ResolvedComponent> faceExceeded = complete();
        faceExceeded.set(7, governance("governance-001", "CNY", 10L, "4.00"));
        assertThat(codes(validate(faceExceeded)))
                .containsExactly(T19CompositionErrorCodes.REWARD_FACE_VALUE_EXCEEDED);
    }

    // ------------------------------------------------------------------ relative slot order

    @Test
    void theRelativeManifestSlotOrderIsEnforced() {
        List<ResolvedComponent> outOfOrder = complete();
        // Move governance before the schedule: Governance(5) then Schedule(4) is a decrease.
        ResolvedComponent governance = outOfOrder.remove(7);
        outOfOrder.add(6, governance);
        assertThat(codes(validate(outOfOrder))).contains(T19CompositionErrorCodes.SLOT_ORDER_INVALID);
    }

    @Test
    void aT19ComponentBeforeTheSegmentTruthIsRejected() {
        List<ResolvedComponent> components = new ArrayList<>();
        components.add(message("message-001"));
        components.add(segment("segment-001"));
        assertThat(codes(validate(components))).contains(T19CompositionErrorCodes.SLOT_ORDER_INVALID);
    }

    // ------------------------------------------------------------------ ordering and bound

    @Test
    void issuesAreReportedInAStableOrderedBoundedList() {
        // Several independent violations must come back in one deterministic code order.
        List<ResolvedComponent> components = new ArrayList<>();
        components.add(flow("flow-start-001", "flow.start"));
        components.add(flow("flow-start-002", "flow.start"));
        components.add(schedule("schedule-001"));
        components.add(schedule("schedule-002"));
        List<Issue> issues = validate(components);
        List<String> codes = codes(issues);
        assertThat(codes).isSorted();
        assertThat(codes).containsExactly(
                T19CompositionErrorCodes.SCHEDULE_CARDINALITY,
                T19CompositionErrorCodes.START_CARDINALITY);
        assertThat(issues.size()).isLessThanOrEqualTo(T19CompositionValidator.MAX_ISSUES);
    }

    @Test
    void aNullRequestFailsClosedWithOneBoundedIssue() {
        List<Issue> issues = new T19CompositionValidator(INDEX).validate(null);
        assertThat(codes(issues)).containsExactly(T19CompositionErrorCodes.INPUT_MISSING);
    }

    // ------------------------------------------------------------------ index invariants

    @Test
    void theIndexRejectsDuplicateCapabilitiesAndUnknownSlots() {
        T19PackFacts message = fact("message.template", MESSAGE_SLOT, true, RUNTIME);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> T19PackIndex.of(RUNTIME, message, message))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> T19PackIndex.of(RUNTIME,
                                fact("message.template", new CompositionSlot("campaign.unknown", false, false),
                                        true, RUNTIME)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIndexRanksSlotsInTheFrozenOrder() {
        assertThat(INDEX.slotRank("campaign.message")).isEqualTo(1);
        assertThat(INDEX.slotRank("campaign.promotion")).isEqualTo(2);
        assertThat(INDEX.slotRank("campaign.flow")).isEqualTo(3);
        assertThat(INDEX.slotRank("campaign.schedule")).isEqualTo(4);
        assertThat(INDEX.slotRank("campaign.governance")).isEqualTo(5);
        assertThat(INDEX.slotRank("campaign.unknown")).isEqualTo(-1);
        assertThat(INDEX.slotFor("campaign.flow")).isEqualTo(FLOW_SLOT);
    }
}