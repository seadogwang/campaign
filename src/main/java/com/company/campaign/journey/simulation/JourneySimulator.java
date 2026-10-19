package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyNode;
import com.company.campaign.journey.graph.JourneyTopologyEdge;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure, bounded, virtual-clock T20 Journey simulator.
 *
 * <p>It is a pure function of one explicit {@link SimulationRequest}: it reads no system clock, no repository,
 * no random source, no ThreadLocal, no model, no network and no Provider, and it creates no Execution,
 * Participant, Action record, budget reservation, runtime task, publication confirmation or external side
 * effect. A WAIT advances a declared virtual instant by its exact duration; a MESSAGE or REWARD records only a
 * virtual action code; a START advances with no action; an end node terminates the path.
 *
 * <p>A known Condition predicate selects its deterministic TRUE or FALSE arm. An UNKNOWN predicate forks the
 * path deterministically into both arms and records, on each arm, the unknown fact and branch as a bounded
 * machine assumption. It never invents a false result. A predicate that fails closed, and an explicit fact
 * error, terminate the path with an explicit simulated error; they never branch.
 *
 * <p>The simulator enforces the configured hard limits (no greater than 64 paths, 256 steps per path and 256
 * total actions). Exhausting a limit fails closed with a bounded issue and reports no path: it never truncates
 * silently. The same explicit inputs always produce byte-identical canonical material.
 */
public final class JourneySimulator {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    /** Simulates one Journey from its explicit frozen inputs. */
    public SimulationResult simulate(SimulationRequest request) {
        if (request == null) {
            throw new SimulationException(SimulationErrorCodes.INPUT_MISSING, "request",
                    "The simulation request was absent.");
        }
        JourneyManifest journey = request.journey();
        SimulationPolicy policy = request.policy();

        Map<String, JourneyNode> nodes = new HashMap<>();
        for (JourneyNode node : journey.nodes()) {
            nodes.put(node.logicalId(), node);
        }
        Map<String, ResolvedComponent> components = new HashMap<>();
        for (ResolvedComponent component : request.sourceManifest().components()) {
            components.putIfAbsent(component.logicalId(), component);
        }
        Map<String, List<JourneyTopologyEdge>> outgoing = new HashMap<>();
        for (JourneyTopologyEdge edge : journey.edges()) {
            outgoing.computeIfAbsent(edge.fromNodeLogicalId(), key -> new ArrayList<>()).add(edge);
        }

        List<PathBuild> finished = new ArrayList<>();
        ArrayDeque<PathBuild> pending = new ArrayDeque<>();
        pending.add(PathBuild.start(journey.startNodeLogicalId(), request.virtualStartInstant()));

        try {
            while (!pending.isEmpty()) {
                PathBuild path = pending.poll();
                advance(path, request, nodes, components, outgoing, policy, pending, finished);
                if (path.status != null) {
                    finished.add(path);
                }
            }
        } catch (SimulationFailure failure) {
            return failedClosed(request, failure.issue);
        }

        int totalActions = 0;
        for (PathBuild path : finished) {
            totalActions += path.actions.size();
        }
        if (totalActions > policy.maxActions()) {
            return failedClosed(request, issue(SimulationErrorCodes.ACTION_LIMIT_EXCEEDED,
                    "A simulation exceeded the hard total action bound."));
        }

        List<SimulatedPath> paths = new ArrayList<>(finished.size());
        for (PathBuild path : finished) {
            paths.add(path.toPath());
        }
        paths.sort(Comparator.comparing(SimulatedPath::orderKey));
        return report(request, paths, List.of());
    }

    /**
     * Advances one path from its current node until it terminates or forks.
     *
     * <p>Every node visit is one bounded step; the loop is bounded by the hard step maximum, so even a
     * defensive malformed input cannot spin here. A fork enqueues two child paths and returns; a terminal or an
     * error records the outcome and returns.
     */
    private void advance(
            PathBuild path,
            SimulationRequest request,
            Map<String, JourneyNode> nodes,
            Map<String, ResolvedComponent> components,
            Map<String, List<JourneyTopologyEdge>> outgoing,
            SimulationPolicy policy,
            ArrayDeque<PathBuild> pending,
            List<PathBuild> finished) {
        while (true) {
            JourneyNode node = nodes.get(path.currentNodeId);
            if (node == null) {
                throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                        "A simulated path reached a node the Journey does not own.");
            }
            if (path.visited.size() >= policy.maxStepsPerPath()) {
                throw failure(SimulationErrorCodes.STEP_LIMIT_EXCEEDED,
                        "A simulated path exceeded the hard step bound.");
            }
            path.visited.add(node.logicalId());
            path.entryInstants.add(path.currentInstant);

            switch (node.kind()) {
                case START -> path.currentNodeId = successor(outgoing, node, JourneyBranchKind.DEFAULT);
                case MESSAGE -> {
                    path.actions.add(new SimulatedAction(
                            SimulatedActionKind.MESSAGE, node.logicalId(), path.currentInstant));
                    path.currentNodeId = successor(outgoing, node, JourneyBranchKind.DEFAULT);
                }
                case REWARD -> {
                    path.actions.add(new SimulatedAction(
                            SimulatedActionKind.REWARD, node.logicalId(), path.currentInstant));
                    path.currentNodeId = successor(outgoing, node, JourneyBranchKind.DEFAULT);
                }
                case WAIT -> {
                    path.currentInstant = waitAdvance(path, component(components, node));
                    path.currentNodeId = successor(outgoing, node, JourneyBranchKind.DEFAULT);
                }
                case CONDITION -> {
                    ConditionPredicate predicate = conditionPredicate(component(components, node));
                    ConditionEvaluation evaluation = evaluator.evaluate(predicate, request.scenarioFacts());
                    switch (evaluation.verdict()) {
                        case TRUE -> path.currentNodeId = successor(outgoing, node, JourneyBranchKind.TRUE);
                        case FALSE -> path.currentNodeId = successor(outgoing, node, JourneyBranchKind.FALSE);
                        case UNKNOWN -> {
                            fork(path, node, predicate.factCode(), outgoing, policy, pending, finished);
                            return;
                        }
                        case ERROR -> {
                            path.status = SimulatedPathStatus.ERRORED;
                            path.terminalNodeLogicalId = node.logicalId();
                            path.errorReasonCode = evaluation.reasonCode();
                            return;
                        }
                        default -> throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                                "A condition evaluation produced an unrepresentable verdict.");
                    }
                }
                case END -> {
                    path.status = SimulatedPathStatus.COMPLETED;
                    path.terminalNodeLogicalId = node.logicalId();
                    return;
                }
                case EXIT -> {
                    path.status = SimulatedPathStatus.EXITED;
                    path.terminalNodeLogicalId = node.logicalId();
                    return;
                }
                default -> throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                        "A simulated path reached an unsupported node kind.");
            }
        }
    }

    /** Splits one unknown-fact path into its deterministic TRUE and FALSE arms, recording each assumption. */
    private void fork(
            PathBuild path,
            JourneyNode node,
            String factCode,
            Map<String, List<JourneyTopologyEdge>> outgoing,
            SimulationPolicy policy,
            ArrayDeque<PathBuild> pending,
            List<PathBuild> finished) {
        if (pending.size() + finished.size() + 2 > policy.maxPaths()) {
            throw failure(SimulationErrorCodes.PATH_LIMIT_EXCEEDED,
                    "A deterministic unknown fork would exceed the hard path bound.");
        }
        String trueTarget = successor(outgoing, node, JourneyBranchKind.TRUE);
        String falseTarget = successor(outgoing, node, JourneyBranchKind.FALSE);
        pending.add(path.fork(node.logicalId(), factCode, JourneyBranchKind.TRUE, trueTarget));
        pending.add(path.fork(node.logicalId(), factCode, JourneyBranchKind.FALSE, falseTarget));
    }

    private static String successor(
            Map<String, List<JourneyTopologyEdge>> outgoing, JourneyNode node, JourneyBranchKind branch) {
        for (JourneyTopologyEdge edge : outgoing.getOrDefault(node.logicalId(), List.of())) {
            if (edge.branch() == branch) {
                return edge.toNodeLogicalId();
            }
        }
        throw failure(SimulationErrorCodes.EDGE_MISSING,
                "A Journey node required an outgoing edge the topology does not carry.");
    }

    /** Advances the virtual clock of a Wait node by its exact declared duration from its declared anchor. */
    private static Instant waitAdvance(PathBuild path, ResolvedComponent component) {
        String durationText = text(component, FlowWaitPack.DURATION_FIELD);
        String anchor = text(component, FlowWaitPack.ANCHOR_FIELD);
        if (durationText == null || anchor == null || !FlowWaitPack.ANCHORS.contains(anchor)) {
            throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                    "A Wait node does not carry its exact anchor and duration.");
        }
        Duration duration;
        try {
            duration = Duration.parse(durationText);
        } catch (DateTimeParseException rejected) {
            throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                    "A Wait node does not carry its exact anchor and duration.");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                    "A Wait node does not carry its exact anchor and duration.");
        }
        Instant entry = path.currentInstant;
        Instant base = FlowWaitPack.ANCHOR_PREVIOUS_ACCEPTED_ACTION.equals(anchor)
                ? path.previousAcceptedActionInstant(entry)
                : entry;
        return base.plus(duration);
    }

    private static ConditionPredicate conditionPredicate(ResolvedComponent component) {
        if (component == null || !FlowPackVersions.CONDITION.equals(component.capabilityCode())) {
            throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                    "A Condition node does not carry the exact Flow Condition payload.");
        }
        return ConditionPredicate.fromFields(component.value().fields());
    }

    private static ResolvedComponent component(
            Map<String, ResolvedComponent> components, JourneyNode node) {
        ResolvedComponent component = components.get(node.logicalId());
        if (component == null || !node.capabilityCode().equals(component.capabilityCode())) {
            throw failure(SimulationErrorCodes.NODE_CONFIG_INVALID,
                    "A Journey node does not carry the exact configuration its node kind requires.");
        }
        return component;
    }

    private static String text(ResolvedComponent component, String field) {
        if (component == null) {
            return null;
        }
        Object value = component.value().fields().get(field);
        return value instanceof String string ? string : null;
    }

    private static SimulationResult report(
            SimulationRequest request, List<SimulatedPath> paths, List<Issue> issues) {
        JourneyManifest journey = request.journey();
        return new SimulationResult(
                SimulationVersions.ENVIRONMENT_SIMULATED,
                SimulationVersions.SIMULATION_FORMAT_VERSION,
                request.simulatorVersion(),
                SimulationVersions.RUNTIME_SCHEMA_VERSION,
                journey.contentHash(),
                journey.topologyHash(),
                journey.sourceManifestHash(),
                request.scenarioFacts().scenarioHash(),
                request.virtualStartInstant(),
                request.virtualTimezone(),
                paths,
                issues);
    }

    private static SimulationResult failedClosed(SimulationRequest request, Issue issue) {
        return report(request, List.of(), List.of(issue));
    }

    private static Issue issue(String code, String message) {
        return new Issue(code, IssueSeverity.ERROR, null, null, null, message, Map.of());
    }

    private static SimulationFailure failure(String code, String message) {
        return new SimulationFailure(issue(code, message));
    }

    @Override
    public String toString() {
        return "JourneySimulator[version=" + SimulationVersions.SIMULATOR_VERSION + ']';
    }

    /** The mutable working state of one path under construction; it never escapes the simulator. */
    private static final class PathBuild {

        private String currentNodeId;
        private Instant currentInstant;
        private SimulatedPathStatus status;
        private String terminalNodeLogicalId;
        private String errorReasonCode;
        private final List<String> visited = new ArrayList<>();
        private final List<Instant> entryInstants = new ArrayList<>();
        private final List<SimulatedAction> actions = new ArrayList<>();
        private final List<SimulatedAssumption> assumptions = new ArrayList<>();

        private PathBuild(String currentNodeId, Instant currentInstant) {
            this.currentNodeId = currentNodeId;
            this.currentInstant = currentInstant;
        }

        static PathBuild start(String startNodeLogicalId, Instant start) {
            return new PathBuild(startNodeLogicalId, start);
        }

        /** A child path that continues one branch of an unknown fork with the assumption recorded. */
        PathBuild fork(String nodeLogicalId, String factCode, JourneyBranchKind branch, String nextNodeId) {
            PathBuild child = new PathBuild(nextNodeId, currentInstant);
            child.visited.addAll(visited);
            child.entryInstants.addAll(entryInstants);
            child.actions.addAll(actions);
            child.assumptions.addAll(assumptions);
            child.assumptions.add(new SimulatedAssumption(nodeLogicalId, factCode, branch));
            return child;
        }

        /** The instant of the most recent recorded action, or the named fallback when none was recorded. */
        Instant previousAcceptedActionInstant(Instant fallback) {
            if (actions.isEmpty()) {
                return fallback;
            }
            return actions.get(actions.size() - 1).virtualInstant();
        }

        SimulatedPath toPath() {
            return new SimulatedPath(
                    status, terminalNodeLogicalId, visited, entryInstants, actions, assumptions,
                    errorReasonCode);
        }
    }

    /** Internal fail-closed signal carrying the bounded issue that explains the exhaustion. */
    private static final class SimulationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Issue issue;

        SimulationFailure(Issue issue) {
            super("The journey simulation failed closed.");
            this.issue = issue;
        }
    }
}
