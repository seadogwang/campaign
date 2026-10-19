package com.company.campaign.runtime.testsupport;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowMessagePack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowRewardPack;
import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.journey.compiler.JourneyTestFixture;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;

import java.util.List;
import java.util.Map;

/**
 * The frozen Journey shapes a phase-B Runtime test progresses, built through the real T20 compiler inputs.
 *
 * <p>Each shape is exactly a resolved component set plus the frozen topology from the same authoring snapshot,
 * with stable hand-chosen logical identities so a test can name the exact node it means. Nothing here
 * fabricates a production definition: the caller compiles the shape with the accepted T20 compiler and the
 * accepted T19 pack index.
 */
public final class RuntimeJourneys {

    /** The one logical identity of the schedule carrier component every shape declares. */
    public static final String SCHEDULE = "sch";

    /** The logical identity of the message template component a MESSAGE shape references. */
    public static final String TEMPLATE = "tpl";

    /** The logical identity of the promotion component a REWARD shape references. */
    public static final String PROMOTION = "prom";

    private RuntimeJourneys() {
    }

    /** One frozen Journey shape: its resolved components, its topology and its Start identity. */
    public record Shape(
            List<ResolvedComponent> components,
            JourneyTopology topology,
            String startLogicalId) {

        public Shape {
            components = List.copyOf(components);
        }
    }

    /** {@code START -> END}: one Participant that completes without any external action. */
    public static Shape linear() {
        return new Shape(
                flowComponents(start("s"), end("e", FlowEndPack.TERMINAL_END)),
                topology(edge("s-e", "s", "e", JourneyBranchKind.DEFAULT)),
                "s");
    }

    /** {@code START -> EXIT}: one Participant that leaves the Journey. */
    public static Shape exit() {
        return new Shape(
                flowComponents(start("s"), end("x", FlowEndPack.TERMINAL_EXIT)),
                topology(edge("s-x", "s", "x", JourneyBranchKind.DEFAULT)),
                "s");
    }

    /** {@code START -> WAIT -> END}: one frozen Wait node with the declared anchor and duration. */
    public static Shape wait(String duration, String anchor) {
        return new Shape(
                flowComponents(
                        start("s"),
                        waitNode("w", duration, anchor),
                        end("e", FlowEndPack.TERMINAL_END)),
                topology(
                        edge("s-w", "s", "w", JourneyBranchKind.DEFAULT),
                        edge("w-e", "w", "e", JourneyBranchKind.DEFAULT)),
                "s");
    }

    /** {@code START -> CONDITION -> (TRUE: END, FALSE: EXIT)} under one exact typed predicate. */
    public static Shape condition(
            String factCode, String operator, String valueType, String value, String onUnknown) {
        return new Shape(
                flowComponents(
                        start("s"),
                        conditionNode("c", factCode, operator, valueType, value, onUnknown),
                        end("t", FlowEndPack.TERMINAL_END),
                        end("f", FlowEndPack.TERMINAL_EXIT)),
                topology(
                        edge("s-c", "s", "c", JourneyBranchKind.DEFAULT),
                        edge("c-t", "c", "t", JourneyBranchKind.TRUE),
                        edge("c-f", "c", "f", JourneyBranchKind.FALSE)),
                "s");
    }

    /** {@code START -> MESSAGE -> END}, with the exact message template the node references. */
    public static Shape message() {
        return new Shape(
                flowComponents(
                        start("s"),
                        messageNode("m", TEMPLATE),
                        end("e", FlowEndPack.TERMINAL_END),
                        messageTemplate(TEMPLATE)),
                topology(
                        edge("s-m", "s", "m", JourneyBranchKind.DEFAULT),
                        edge("m-e", "m", "e", JourneyBranchKind.DEFAULT)),
                "s");
    }

    /** {@code START -> REWARD -> END}, with the exact promotion reference the node names. */
    public static Shape reward() {
        return new Shape(
                flowComponents(
                        start("s"),
                        rewardNode("r", PROMOTION),
                        end("e", FlowEndPack.TERMINAL_END),
                        promotion(PROMOTION)),
                topology(
                        edge("s-r", "s", "r", JourneyBranchKind.DEFAULT),
                        edge("r-e", "r", "e", JourneyBranchKind.DEFAULT)),
                "s");
    }

    // ------------------------------------------------------------------ components

    public static ResolvedComponent start(String id) {
        return flow(id, FlowPackVersions.START, CapabilityPayload.empty());
    }

    public static ResolvedComponent end(String id, String terminal) {
        return flow(id, FlowPackVersions.END,
                CapabilityPayload.of(Map.of(FlowEndPack.TERMINAL_FIELD, terminal)));
    }

    public static ResolvedComponent messageNode(String id, String templateLogicalId) {
        return flow(id, FlowPackVersions.MESSAGE,
                CapabilityPayload.of(Map.of(FlowMessagePack.REFERENCE_FIELD, templateLogicalId)));
    }

    public static ResolvedComponent rewardNode(String id, String promotionLogicalId) {
        return flow(id, FlowPackVersions.REWARD,
                CapabilityPayload.of(Map.of(FlowRewardPack.REFERENCE_FIELD, promotionLogicalId)));
    }

    public static ResolvedComponent waitNode(String id, String duration, String anchor) {
        return flow(id, FlowPackVersions.WAIT, CapabilityPayload.of(Map.of(
                FlowWaitPack.DURATION_FIELD, duration,
                FlowWaitPack.ANCHOR_FIELD, anchor)));
    }

    public static ResolvedComponent conditionNode(
            String id, String factCode, String operator, String valueType, String value, String onUnknown) {
        return flow(id, FlowPackVersions.CONDITION, CapabilityPayload.of(Map.of(
                FlowConditionPack.FACT_CODE_FIELD, factCode,
                FlowConditionPack.OPERATOR_FIELD, operator,
                FlowConditionPack.VALUE_TYPE_FIELD, valueType,
                FlowConditionPack.VALUE_FIELD, value,
                FlowConditionPack.ON_UNKNOWN_FIELD, onUnknown)));
    }

    public static ResolvedComponent messageTemplate(String id) {
        return new ResolvedComponent(id, JourneyTestFixture.MESSAGE_TEMPLATE,
                FlowPackVersions.CONTRACT_VERSION, new CompositionSlot("campaign.message", true, false),
                CapabilityPayload.of(Map.of("body", "welcome")), List.of());
    }

    public static ResolvedComponent promotion(String id) {
        return new ResolvedComponent(id, JourneyTestFixture.PROMOTION_REFERENCE,
                FlowPackVersions.CONTRACT_VERSION, new CompositionSlot("campaign.promotion", true, false),
                CapabilityPayload.of(Map.of("faceValue", "10")), List.of());
    }

    /** The one {@code schedule.basic} carrier every shape declares so a schedule can be frozen for it. */
    public static ResolvedComponent schedule() {
        return new ResolvedComponent(SCHEDULE, ScheduleBasicVersions.CAPABILITY_CODE,
                ScheduleBasicVersions.CONTRACT_VERSION, ScheduleBasicVersions.SLOT,
                CapabilityPayload.of(Map.of(
                        ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                        ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai")),
                List.of());
    }

    private static ResolvedComponent flow(String id, String capability, CapabilityPayload payload) {
        return new ResolvedComponent(id, capability, FlowPackVersions.CONTRACT_VERSION,
                FlowPackVersions.SLOT, payload, List.of());
    }

    /** The flow components of one shape together with its one schedule carrier. */
    private static List<ResolvedComponent> flowComponents(ResolvedComponent... components) {
        List<ResolvedComponent> all = new java.util.ArrayList<>(List.of(components));
        all.add(schedule());
        return List.copyOf(all);
    }

    // ------------------------------------------------------------------ topology

    public static JourneyTopology topology(JourneyTopologyEdge... edges) {
        return JourneyTopology.of(JourneyTopology.TOPOLOGY_FORMAT_VERSION, edges);
    }

    public static JourneyTopologyEdge edge(
            String id, String from, String to, JourneyBranchKind branch) {
        return new JourneyTopologyEdge(id, from, to, branch);
    }
}