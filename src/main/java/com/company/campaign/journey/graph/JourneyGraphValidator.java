package com.company.campaign.journey.graph;

import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowMessagePack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowRewardPack;
import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The deterministic T20 Journey graph validator.
 *
 * <p>It derives the canonical node set from exactly the components of a ResolvedCampaignManifest whose
 * enabled T19 Flow capability matches one node kind, and then proves the section-2 graph invariants against a
 * frozen JourneyTopology: one Start, at least one terminal, at most one Reward, endpoint existence, per-kind
 * degrees and branch kinds, no duplicate branch, reachability from Start, acyclicity and termination. It
 * reads no repository, clock, random source, model, network or mutable registry.
 *
 * <p>It validates Flow Message and Flow Reward references only to the extent that they target an existing
 * exact Message or Promotion component. It never re-resolves a mutable catalog pointer: the referenced
 * component normalized payload hash and immutable catalog references are preserved verbatim.
 *
 * <p>Issues are stable, ordered by code then node/edge identity, bounded to a strict maximum and carry fixed
 * prose only: no payload value, template body, reward data, PII or unrestricted exception is ever echoed.
 */
public final class JourneyGraphValidator {

    /** The largest permitted number of derived nodes. */
    public static final int MAX_NODES = 256;

    /** The largest number of issues one evaluation reports. */
    public static final int MAX_ISSUES = 64;

    private static final String MESSAGE_CAPABILITY = "message.template";
    private static final String PROMOTION_CAPABILITY = "promotion.reference";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final List<String> FLOW_CAPABILITIES = List.of(
            FlowPackVersions.START, FlowPackVersions.MESSAGE, FlowPackVersions.WAIT,
            FlowPackVersions.CONDITION, FlowPackVersions.REWARD, FlowPackVersions.END);

    private static final Set<String> FLOW_CAPABILITY_SET = Set.copyOf(FLOW_CAPABILITIES);

    private static final String MESSAGE_NODE_PAYLOAD =
            "A Flow node does not carry the exact configuration its node kind requires.";
    private static final String MESSAGE_START_CARDINALITY =
            "A Journey graph must hold exactly one Start node.";
    private static final String MESSAGE_TERMINAL_MISSING =
            "A Journey graph must hold at least one terminal END or EXIT node.";
    private static final String MESSAGE_REWARD_CARDINALITY =
            "A Journey graph must hold at most one Reward node.";
    private static final String MESSAGE_ENDPOINT_UNKNOWN =
            "A Journey edge endpoint is not a node of the derived node set.";
    private static final String MESSAGE_DEGREE =
            "A Journey node degree or branch kind violates its node-kind contract.";
    private static final String MESSAGE_CONDITION_BRANCHES =
            "A Condition node must have exactly one TRUE and one FALSE out-edge and no DEFAULT.";
    private static final String MESSAGE_DUPLICATE_BRANCH =
            "A Journey node must not have two out-edges of the same branch kind.";
    private static final String MESSAGE_BRANCH_KIND =
            "A non-condition Journey node must not carry a TRUE or FALSE edge.";
    private static final String MESSAGE_UNREACHABLE =
            "Every Journey node must be reachable from the Start node.";
    private static final String MESSAGE_CYCLE =
            "A Journey graph must be acyclic.";
    private static final String MESSAGE_NON_TERMINATING =
            "Every reachable Journey path must terminate at an END or EXIT node.";
    private static final String MESSAGE_WAIT =
            "A Wait node must retain an accepted anchor and a positive bounded duration.";
    private static final String MESSAGE_MESSAGE_REFERENCE =
            "A Flow Message node must reference an existing message.template component.";
    private static final String MESSAGE_REWARD_REFERENCE =
            "A Flow Reward node must reference an existing promotion.reference component.";
    private static final String MESSAGE_TERMINAL_PAYLOAD =
            "A terminal node payload must be one of the two accepted terminal codes.";
    private static final String MESSAGE_NODE_LIMIT = "A Journey graph exceeds the hard node bound.";
    private static final String MESSAGE_NOT_IN_INDEX =
            "A Flow capability is not present in the frozen pack index.";
    private static final String MESSAGE_DISABLED = "A Flow capability pack is disabled for authoring.";
    private static final String MESSAGE_CONTRACT =
            "A Flow component contract version is not the enabled pack exact contract version.";
    private static final String MESSAGE_SLOT =
            "A Flow component composition slot is not the frozen Flow slot.";
    private static final String MESSAGE_RUNTIME =
            "A Flow component pack declares a runtime this build cannot author or preview.";

    /** Validates a Manifest and topology against a frozen pack index. */
    public JourneyGraphResult validate(
            ResolvedCampaignManifest manifest, JourneyTopology topology, T19PackIndex index) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(index, "index");

        List<Issue> issues = new ArrayList<>();
        Map<String, ResolvedComponent> componentsByLogicalId = new HashMap<>();
        for (ResolvedComponent component : manifest.components()) {
            componentsByLogicalId.putIfAbsent(component.logicalId(), component);
        }

        List<JourneyNode> nodes = deriveNodes(manifest, index, componentsByLogicalId, issues);
        if (nodes.size() > MAX_NODES) {
            issues.add(error(JourneyGraphErrorCodes.NODE_LIMIT_EXCEEDED, null, MESSAGE_NODE_LIMIT));
        }
        Map<String, JourneyNode> byLogicalId = new LinkedHashMap<>();
        for (JourneyNode node : nodes) {
            byLogicalId.put(node.logicalId(), node);
        }

        validateCardinality(nodes, issues);
        List<JourneyTopologyEdge> edges = topology.edges();
        validateEndpointExistence(byLogicalId, edges, issues);
        validateDegrees(byLogicalId, edges, issues);

        String startNodeLogicalId = uniqueStart(nodes);
        validateReachability(byLogicalId, edges, startNodeLogicalId, issues);
        validateAcyclic(byLogicalId, edges, issues);
        List<String> terminals = terminals(nodes);
        validateTermination(byLogicalId, edges, startNodeLogicalId, terminals, issues);

        return new JourneyGraphResult(nodes, edges, startNodeLogicalId, terminals, bounded(issues));
    }

    // ------------------------------------------------------------------ node derivation

    private List<JourneyNode> deriveNodes(
            ResolvedCampaignManifest manifest,
            T19PackIndex index,
            Map<String, ResolvedComponent> byLogicalId,
            List<Issue> issues) {
        List<JourneyNode> nodes = new ArrayList<>();
        for (ResolvedComponent component : manifest.components()) {
            String capabilityCode = component.capabilityCode();
            if (!FLOW_CAPABILITY_SET.contains(capabilityCode)) {
                continue;
            }
            if (!capabilityAdmissible(component, index, issues)) {
                continue;
            }
            JourneyNodeKind kind = nodeKind(component, issues);
            if (kind == null) {
                continue;
            }
            if (kind == JourneyNodeKind.WAIT) {
                validateWait(component, issues);
            }
            if (kind == JourneyNodeKind.MESSAGE) {
                validateReference(component, FlowMessagePack.REFERENCE_FIELD, MESSAGE_CAPABILITY,
                        byLogicalId, JourneyGraphErrorCodes.MESSAGE_REFERENCE_UNRESOLVED,
                        MESSAGE_MESSAGE_REFERENCE, issues);
            }
            if (kind == JourneyNodeKind.REWARD) {
                validateReference(component, FlowRewardPack.REFERENCE_FIELD, PROMOTION_CAPABILITY,
                        byLogicalId, JourneyGraphErrorCodes.REWARD_REFERENCE_UNRESOLVED,
                        MESSAGE_REWARD_REFERENCE, issues);
            }
            nodes.add(new JourneyNode(
                    component.logicalId(),
                    kind,
                    component.capabilityCode(),
                    component.contractVersion(),
                    component.value().contentHash(),
                    catalogReferenceHash(component)));
        }
        nodes.sort(JourneyNode::order);
        return List.copyOf(nodes);
    }

    /**
     * True only when the component capability is enabled in the frozen index at its exact contract version
     * and frozen Flow slot on a runtime this build can author.
     */
    private static boolean capabilityAdmissible(
            ResolvedComponent component, T19PackIndex index, List<Issue> issues) {
        String capabilityCode = component.capabilityCode();
        T19PackFacts facts = index.facts(capabilityCode).orElse(null);
        if (facts == null) {
            issues.add(error(JourneyGraphErrorCodes.CAPABILITY_NOT_IN_INDEX,
                    component.logicalId(), MESSAGE_NOT_IN_INDEX));
            return false;
        }
        boolean admissible = true;
        if (!facts.enabled()) {
            issues.add(error(JourneyGraphErrorCodes.CAPABILITY_DISABLED,
                    component.logicalId(), MESSAGE_DISABLED));
            admissible = false;
        }
        if (!facts.contractVersion().equals(component.contractVersion())) {
            issues.add(error(JourneyGraphErrorCodes.CAPABILITY_CONTRACT_MISMATCH,
                    component.logicalId(), MESSAGE_CONTRACT));
            admissible = false;
        }
        if (!facts.slot().equals(component.slot())
                || !FlowPackVersions.SLOT_CODE.equals(component.slot().code())) {
            issues.add(error(JourneyGraphErrorCodes.CAPABILITY_SLOT_MISMATCH,
                    component.logicalId(), MESSAGE_SLOT));
            admissible = false;
        }
        if (!index.runtimeCompatible(facts)
                || !FlowPackVersions.RUNTIME_SCHEMA_VERSION.equals(
                        facts.runtimeCompatibility().runtimeSchemaVersion())) {
            issues.add(error(JourneyGraphErrorCodes.CAPABILITY_RUNTIME_INCOMPATIBLE,
                    component.logicalId(), MESSAGE_RUNTIME));
            admissible = false;
        }
        return admissible;
    }

    private static JourneyNodeKind nodeKind(ResolvedComponent component, List<Issue> issues) {
        String capabilityCode = component.capabilityCode();
        if (FlowPackVersions.START.equals(capabilityCode)) {
            return JourneyNodeKind.START;
        }
        if (FlowPackVersions.MESSAGE.equals(capabilityCode)) {
            return JourneyNodeKind.MESSAGE;
        }
        if (FlowPackVersions.WAIT.equals(capabilityCode)) {
            return JourneyNodeKind.WAIT;
        }
        if (FlowPackVersions.CONDITION.equals(capabilityCode)) {
            return JourneyNodeKind.CONDITION;
        }
        if (FlowPackVersions.REWARD.equals(capabilityCode)) {
            return JourneyNodeKind.REWARD;
        }
        if (FlowPackVersions.END.equals(capabilityCode)) {
            String terminal = text(component, FlowEndPack.TERMINAL_FIELD);
            if (FlowEndPack.TERMINAL_END.equals(terminal)) {
                return JourneyNodeKind.END;
            }
            if (FlowEndPack.TERMINAL_EXIT.equals(terminal)) {
                return JourneyNodeKind.EXIT;
            }
            issues.add(error(JourneyGraphErrorCodes.TERMINAL_PAYLOAD_INVALID,
                    component.logicalId(), MESSAGE_TERMINAL_PAYLOAD));
            return null;
        }
        issues.add(error(JourneyGraphErrorCodes.NODE_PAYLOAD_INVALID,
                component.logicalId(), MESSAGE_NODE_PAYLOAD));
        return null;
    }

    private static void validateWait(ResolvedComponent component, List<Issue> issues) {
        String anchor = text(component, FlowWaitPack.ANCHOR_FIELD);
        String durationText = text(component, FlowWaitPack.DURATION_FIELD);
        boolean valid = anchor != null && FlowWaitPack.ANCHORS.contains(anchor);
        if (valid && durationText != null) {
            try {
                Duration duration = Duration.parse(durationText);
                valid = !duration.isZero() && !duration.isNegative()
                        && duration.getSeconds() <= FlowWaitPack.MAX_WAIT_SECONDS;
            } catch (DateTimeParseException rejected) {
                valid = false;
            }
        } else {
            valid = false;
        }
        if (!valid) {
            issues.add(error(JourneyGraphErrorCodes.WAIT_CONFIG_INVALID,
                    component.logicalId(), MESSAGE_WAIT));
        }
    }

    private static void validateReference(
            ResolvedComponent component,
            String field,
            String requiredCapability,
            Map<String, ResolvedComponent> byLogicalId,
            String code,
            String message,
            List<Issue> issues) {
        String reference = text(component, field);
        ResolvedComponent target = reference == null ? null : byLogicalId.get(reference);
        if (target == null || !requiredCapability.equals(target.capabilityCode())) {
            issues.add(error(code, component.logicalId(), message));
        }
    }

    private static ContentHash catalogReferenceHash(ResolvedComponent component) {
        return CANONICAL_JSON.hash(component.references());
    }

    // ------------------------------------------------------------------ cardinality

    private static void validateCardinality(List<JourneyNode> nodes, List<Issue> issues) {
        List<JourneyNode> starts = of(nodes, JourneyNodeKind.START);
        if (starts.size() != 1) {
            issues.add(error(JourneyGraphErrorCodes.START_CARDINALITY,
                    starts.isEmpty() ? null : starts.get(0).logicalId(), MESSAGE_START_CARDINALITY));
        }
        List<String> terminals = terminals(nodes);
        if (terminals.isEmpty()) {
            issues.add(error(JourneyGraphErrorCodes.TERMINAL_MISSING, null, MESSAGE_TERMINAL_MISSING));
        }
        List<JourneyNode> rewards = of(nodes, JourneyNodeKind.REWARD);
        if (rewards.size() > 1) {
            issues.add(error(JourneyGraphErrorCodes.REWARD_CARDINALITY,
                    rewards.get(1).logicalId(), MESSAGE_REWARD_CARDINALITY));
        }
    }

    private static String uniqueStart(List<JourneyNode> nodes) {
        for (JourneyNode node : nodes) {
            if (node.kind() == JourneyNodeKind.START) {
                return node.logicalId();
            }
        }
        return null;
    }

    private static List<String> terminals(List<JourneyNode> nodes) {
        List<String> ids = new ArrayList<>();
        for (JourneyNode node : nodes) {
            if (node.isTerminal()) {
                ids.add(node.logicalId());
            }
        }
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------ edges

    private static void validateEndpointExistence(
            Map<String, JourneyNode> byLogicalId, List<JourneyTopologyEdge> edges, List<Issue> issues) {
        for (JourneyTopologyEdge edge : edges) {
            if (!byLogicalId.containsKey(edge.fromNodeLogicalId())
                    || !byLogicalId.containsKey(edge.toNodeLogicalId())) {
                issues.add(error(JourneyGraphErrorCodes.EDGE_ENDPOINT_UNKNOWN,
                        edge.logicalId(), MESSAGE_ENDPOINT_UNKNOWN));
            }
        }
    }

    private static void validateDegrees(
            Map<String, JourneyNode> byLogicalId, List<JourneyTopologyEdge> edges, List<Issue> issues) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<JourneyTopologyEdge>> outgoing = new LinkedHashMap<>();
        for (JourneyTopologyEdge edge : edges) {
            outgoing.computeIfAbsent(edge.fromNodeLogicalId(), key -> new ArrayList<>()).add(edge);
            incoming.merge(edge.toNodeLogicalId(), 1, Integer::sum);
        }
        for (JourneyNode node : byLogicalId.values()) {
            List<JourneyTopologyEdge> out = outgoing.getOrDefault(node.logicalId(), List.of());
            int in = incoming.getOrDefault(node.logicalId(), 0);
            switch (node.kind()) {
                case START -> {
                    if (in != 0 || out.size() != 1 || out.get(0).branch() != JourneyBranchKind.DEFAULT) {
                        issues.add(error(JourneyGraphErrorCodes.NODE_DEGREE,
                                node.logicalId(), MESSAGE_DEGREE));
                    }
                }
                case MESSAGE, WAIT, REWARD -> {
                    if (out.size() != 1 || out.get(0).branch() != JourneyBranchKind.DEFAULT) {
                        issues.add(error(JourneyGraphErrorCodes.NODE_DEGREE,
                                node.logicalId(), MESSAGE_DEGREE));
                    }
                }
                case CONDITION -> validateConditionBranches(node.logicalId(), out, issues);
                case END, EXIT -> {
                    if (!out.isEmpty()) {
                        issues.add(error(JourneyGraphErrorCodes.NODE_DEGREE,
                                node.logicalId(), MESSAGE_DEGREE));
                    }
                }
                default -> issues.add(error(JourneyGraphErrorCodes.NODE_DEGREE,
                        node.logicalId(), MESSAGE_DEGREE));
            }
            validateDuplicateAndKind(node, out, issues);
        }
    }

    private static void validateConditionBranches(
            String logicalId, List<JourneyTopologyEdge> out, List<Issue> issues) {
        boolean hasTrue = false;
        boolean hasFalse = false;
        boolean hasDefault = false;
        for (JourneyTopologyEdge edge : out) {
            switch (edge.branch()) {
                case TRUE -> hasTrue = true;
                case FALSE -> hasFalse = true;
                case DEFAULT -> hasDefault = true;
            }
        }
        if (out.size() != 2 || !hasTrue || !hasFalse || hasDefault) {
            issues.add(error(JourneyGraphErrorCodes.CONDITION_BRANCHES, logicalId, MESSAGE_CONDITION_BRANCHES));
        }
    }

    private static void validateDuplicateAndKind(
            JourneyNode node, List<JourneyTopologyEdge> out, List<Issue> issues) {
        Set<JourneyBranchKind> seen = new HashSet<>();
        for (JourneyTopologyEdge edge : out) {
            if (!seen.add(edge.branch())) {
                issues.add(error(JourneyGraphErrorCodes.DUPLICATE_BRANCH,
                        node.logicalId(), MESSAGE_DUPLICATE_BRANCH));
            }
            if (!node.kind().isCondition() && edge.branch().isConditionArm()) {
                issues.add(error(JourneyGraphErrorCodes.BRANCH_KIND_INVALID,
                        node.logicalId(), MESSAGE_BRANCH_KIND));
            }
        }
    }

    // ------------------------------------------------------------------ reachability, acyclicity, termination

    private static void validateReachability(
            Map<String, JourneyNode> byLogicalId,
            List<JourneyTopologyEdge> edges,
            String startNodeLogicalId,
            List<Issue> issues) {
        if (startNodeLogicalId == null) {
            return;
        }
        Map<String, List<String>> adjacency = adjacency(edges);
        Set<String> reachable = traverse(startNodeLogicalId, adjacency);
        for (JourneyNode node : byLogicalId.values()) {
            if (!reachable.contains(node.logicalId())) {
                issues.add(error(JourneyGraphErrorCodes.UNREACHABLE_NODE,
                        node.logicalId(), MESSAGE_UNREACHABLE));
            }
        }
    }

    private static void validateAcyclic(
            Map<String, JourneyNode> byLogicalId, List<JourneyTopologyEdge> edges, List<Issue> issues) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = adjacency(edges);
        for (String id : byLogicalId.keySet()) {
            indegree.put(id, 0);
        }
        for (JourneyTopologyEdge edge : edges) {
            if (byLogicalId.containsKey(edge.fromNodeLogicalId())
                    && byLogicalId.containsKey(edge.toNodeLogicalId())) {
                indegree.merge(edge.toNodeLogicalId(), 1, Integer::sum);
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            visited++;
            for (String next : adjacency.getOrDefault(id, List.of())) {
                int remaining = indegree.merge(next, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(next);
                }
            }
        }
        if (visited != byLogicalId.size()) {
            String smallest = null;
            for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
                if (entry.getValue() > 0 && (smallest == null || entry.getKey().compareTo(smallest) < 0)) {
                    smallest = entry.getKey();
                }
            }
            issues.add(error(JourneyGraphErrorCodes.CYCLE, smallest, MESSAGE_CYCLE));
        }
    }

    private static void validateTermination(
            Map<String, JourneyNode> byLogicalId,
            List<JourneyTopologyEdge> edges,
            String startNodeLogicalId,
            List<String> terminals,
            List<Issue> issues) {
        if (startNodeLogicalId == null || terminals.isEmpty()) {
            return;
        }
        Map<String, List<String>> adjacency = adjacency(edges);
        Set<String> reachable = traverse(startNodeLogicalId, adjacency);

        Map<String, List<String>> reverse = new HashMap<>();
        for (JourneyTopologyEdge edge : edges) {
            reverse.computeIfAbsent(edge.toNodeLogicalId(), key -> new ArrayList<>())
                    .add(edge.fromNodeLogicalId());
        }
        Set<String> canTerminate = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String terminal : terminals) {
            if (canTerminate.add(terminal)) {
                queue.add(terminal);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String previous : reverse.getOrDefault(current, List.of())) {
                if (canTerminate.add(previous)) {
                    queue.add(previous);
                }
            }
        }
        for (JourneyNode node : byLogicalId.values()) {
            if (reachable.contains(node.logicalId()) && !canTerminate.contains(node.logicalId())) {
                issues.add(error(JourneyGraphErrorCodes.NON_TERMINATING,
                        node.logicalId(), MESSAGE_NON_TERMINATING));
            }
        }
    }

    private static Map<String, List<String>> adjacency(List<JourneyTopologyEdge> edges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (JourneyTopologyEdge edge : edges) {
            adjacency.computeIfAbsent(edge.fromNodeLogicalId(), key -> new ArrayList<>())
                    .add(edge.toNodeLogicalId());
        }
        return adjacency;
    }

    private static Set<String> traverse(String start, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        if (visited.add(start)) {
            queue.add(start);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    // ------------------------------------------------------------------ helpers

    private static List<JourneyNode> of(List<JourneyNode> nodes, JourneyNodeKind kind) {
        List<JourneyNode> matches = new ArrayList<>();
        for (JourneyNode node : nodes) {
            if (node.kind() == kind) {
                matches.add(node);
            }
        }
        return matches;
    }

    private static String text(ResolvedComponent component, String field) {
        Object value = component.value().fields().get(field);
        return value instanceof String text ? text : null;
    }

    private static Issue error(String code, String nodeId, String message) {
        return new Issue(code, IssueSeverity.ERROR, null, nodeId, null, message, Map.of());
    }

    private static List<Issue> bounded(List<Issue> issues) {
        List<Issue> ordered = new ArrayList<>(issues);
        ordered.sort(Comparator
                .comparing(Issue::code)
                .thenComparing(Issue::nodeId, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                .thenComparing(Issue::message));
        if (ordered.size() > MAX_ISSUES) {
            return List.copyOf(ordered.subList(0, MAX_ISSUES));
        }
        return List.copyOf(ordered);
    }

    @Override
    public String toString() {
        return "JourneyGraphValidator[maxNodes=" + MAX_NODES + ", maxIssues=" + MAX_ISSUES + ']';
    }
}
