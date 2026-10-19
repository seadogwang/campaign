package com.company.campaign.builder.proposal;

import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single strict, immutable Campaign authoring request.
 *
 * <p>This is the only structure a model or a Visual Builder may produce. It binds one Proposal schema
 * version — {@link ProposalSchemaVersions#CURRENT} when newly written, or the legacy
 * {@link ProposalSchemaVersions#LEGACY_V1_3} when reading an accepted pre-T31 body — one mode, the exact base
 * revisions it was written against, an ordered operation list and bounded client presentation context. It
 * carries no trusted execution field of any kind. A legacy {@code 1.3} version may not carry a
 * {@code journeyTopology} mutation at all; only {@code 1.4} may.
 *
 * <p>Omission means inherit and never deletion; deletion is representable only by {@code DELETE}; ordered
 * relocation is representable only by {@code MOVE}. An existing object is addressed by its stable
 * {@code logicalId}, a new object by a {@code clientRef} that only the compiler turns into a
 * {@code logicalId}.
 *
 * <p>An object may own at most one data operation and at most one MOVE. A duplicate operation, a
 * contradictory {@code DELETE} combined with an upsert or a move, a self-anchor and a cyclic set of relative
 * anchors are all refused here, before any merge.
 *
 * <p>The record exposes only immutable values, and {@link #canonicalBytes()} is a pure function of them: an
 * identical request produces byte-identical material and an identical {@link #proposalHash()}, on every
 * machine and at every wall-clock moment.
 */
public record CampaignProposalPatch(
        SchemaVersion schemaVersion,
        ProposalMode mode,
        String campaignId,
        ProposalBaseRevisions baseRevisions,
        List<ProposalOperation> operations,
        ProposalClientContext clientContext,
        JourneyTopologyMutation journeyTopology) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /**
     * The accepted T15 shape: a request that says nothing about the Campaign's Journey topology.
     *
     * <p>An absent mutation honestly means "inherit the current topology", so this overload is kept for every
     * accepted call site and is covered by the canonical material exactly as before: the optional mutation is
     * omitted from the bytes entirely rather than written as a null or an empty object.
     */
    public CampaignProposalPatch(
            SchemaVersion schemaVersion,
            ProposalMode mode,
            String campaignId,
            ProposalBaseRevisions baseRevisions,
            List<ProposalOperation> operations,
            ProposalClientContext clientContext) {
        this(schemaVersion, mode, campaignId, baseRevisions, operations, clientContext, null);
    }

    public CampaignProposalPatch {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(baseRevisions, "baseRevisions");
        Objects.requireNonNull(clientContext, "clientContext");
        if (!ProposalSchemaVersions.isSupported(schemaVersion)) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED);
        }
        if (ProposalSchemaVersions.isLegacy(schemaVersion) && journeyTopology != null) {
            // The same Proposal-version/topology invariant the strict codec proves, so a directly constructed
            // model cannot carry a topology the legacy 1.3 wire shape has no member for.
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED);
        }
        if (mode == ProposalMode.CREATE) {
            if (campaignId != null || baseRevisions.campaignRevision() != null) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID);
            }
        } else {
            if (campaignId == null || !IDENTIFIER.matcher(campaignId).matches()) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID);
            }
            if (baseRevisions.campaignRevision() == null) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
            }
        }
        if (operations == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
        }
        List<ProposalOperation> copy = new ArrayList<>(operations.size());
        for (ProposalOperation operation : operations) {
            copy.add(Objects.requireNonNull(operation, "operation"));
        }
        requireConsistentOperations(copy);
        operations = List.copyOf(copy);
    }

    /** Re-proves every identity, uniqueness, contradiction and ordering rule of the operation list. */
    private static void requireConsistentOperations(List<ProposalOperation> operations) {
        Map<String, ProposalOperationKind> dataOperations = new HashMap<>();
        Set<String> moveTargets = new HashSet<>();
        Set<String> declaredClientRefs = new LinkedHashSet<>();
        Set<String> usedLogicalIds = new HashSet<>();

        for (ProposalOperation operation : operations) {
            String value = operation.target().value();
            if (operation.target().isLogicalId()) {
                usedLogicalIds.add(value);
            }
            switch (operation.kind()) {
                case UPSERT -> {
                    if (dataOperations.put(value, ProposalOperationKind.UPSERT) != null) {
                        throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    }
                    if (!operation.target().isLogicalId()) {
                        if (!declaredClientRefs.add(value)) {
                            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CLIENT_REF_CONFLICT);
                        }
                    }
                }
                case DELETE -> {
                    if (dataOperations.put(value, ProposalOperationKind.DELETE) != null) {
                        throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    }
                    if (moveTargets.contains(value)) {
                        // Deleting and relocating the same object is contradictory, not an update, and the
                        // contradiction must not depend on which of the two operations was written first.
                        throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    }
                }
                case MOVE -> {
                    if (!moveTargets.add(value)) {
                        throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    }
                    if (dataOperations.get(value) == ProposalOperationKind.DELETE) {
                        // The MOVE-first ordering of the same contradiction.
                        throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    }
                }
            }
            ProposalMoveAnchor anchor = operation.anchor();
            if (anchor != null && anchor.target().equals(value)) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
            }
        }

        // A clientRef may only name an object this proposal declares as new, and it must not be spelled like
        // any logicalId used anywhere in the proposal: the compiler resolves anchor values against both sets.
        for (ProposalOperation operation : operations) {
            if (!operation.target().isLogicalId() && !declaredClientRefs.contains(operation.target().value())) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CLIENT_REF_CONFLICT);
            }
        }
        for (String clientRef : declaredClientRefs) {
            if (usedLogicalIds.contains(clientRef)) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CLIENT_REF_CONFLICT);
            }
        }

        requireAcyclicOrdering(operations);
    }

    /**
     * Rejects a relative-anchor set that cannot be satisfied by one acyclic object ordering.
     *
     * <p>{@code x before y} becomes the precedence edge {@code x -> y}; {@code x after y} becomes
     * {@code y -> x}. A cycle in that graph — including the {@code x before y} plus {@code y before x} pair —
     * is a contradiction rather than a reordering.
     */
    private static void requireAcyclicOrdering(List<ProposalOperation> operations) {
        Map<String, Set<String>> edges = new HashMap<>();
        for (ProposalOperation operation : operations) {
            ProposalMoveAnchor anchor = operation.anchor();
            if (anchor == null) {
                continue;
            }
            String from = anchor.kind() == MoveAnchorKind.BEFORE ? operation.target().value() : anchor.target();
            String to = anchor.kind() == MoveAnchorKind.BEFORE ? anchor.target() : operation.target().value();
            edges.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to);
            edges.computeIfAbsent(to, key -> new LinkedHashSet<>());
        }
        Set<String> visited = new HashSet<>();
        Set<String> inProgress = new HashSet<>();
        for (String node : new LinkedHashSet<>(edges.keySet())) {
            if (hasCycle(node, edges, visited, inProgress)) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ORDER_CYCLE);
            }
        }
    }

    private static boolean hasCycle(
            String node,
            Map<String, Set<String>> edges,
            Set<String> visited,
            Set<String> inProgress) {
        if (inProgress.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        inProgress.add(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            if (node.equals(next) || hasCycle(next, edges, visited, inProgress)) {
                return true;
            }
        }
        inProgress.remove(node);
        return false;
    }

    public boolean isCreate() {
        return mode == ProposalMode.CREATE;
    }

    /** Every {@code clientRef} this proposal declares as new, in first-declaration order. */
    public List<String> clientRefs() {
        List<String> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProposalOperation operation : operations) {
            ProposalIdentity target = operation.target();
            if (operation.kind() == ProposalOperationKind.UPSERT
                    && !target.isLogicalId()
                    && seen.add(target.clientRef())) {
                refs.add(target.clientRef());
            }
        }
        return List.copyOf(refs);
    }

    /** True when this request carries a Journey-topology mutation at all. */
    public boolean hasJourneyTopology() {
        return journeyTopology != null;
    }

    /** The canonical UTF-8 wire form of this proposal; a pure function of the model. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(new CanonicalPatch(
                schemaVersion.value(),
                mode.name(),
                campaignId,
                new CanonicalBaseRevisions(
                        baseRevisions.campaignRevision(),
                        baseRevisions.catalogVersion(),
                        baseRevisions.catalogHash(),
                        baseRevisions.policyVersion(),
                        baseRevisions.policyHash()),
                operations.stream().map(CampaignProposalPatch::canonicalOperation).toList(),
                clientContext.locale() == null
                        ? null
                        : new CanonicalClientContext(clientContext.locale().value()),
                canonicalTopology()));
    }

    /** The canonical text form of this proposal. */
    public String canonicalText() {
        return new String(canonicalBytes(), StandardCharsets.UTF_8);
    }

    /** The content hash of {@link #canonicalBytes()}. */
    public ContentHash proposalHash() {
        return ContentHash.sha256(canonicalBytes());
    }

    @Override
    public String toString() {
        return "CampaignProposalPatch[mode=" + mode
                + ", campaignId=" + campaignId
                + ", catalogVersion=" + baseRevisions.catalogVersion()
                + ", policyVersion=" + baseRevisions.policyVersion()
                + ", operations=" + operations.size()
                + ", journeyTopology=" + (journeyTopology == null ? "-" : journeyTopology.action())
                + ", proposalHash=" + proposalHash() + ']';
    }

    /**
     * The canonical projection of the optional topology mutation.
     *
     * <p>An absent mutation contributes nothing at all, so an accepted proposal that says nothing about a
     * topology keeps its exact accepted bytes and hash; a replacement contributes its action, format version and
     * ordered typed edges; and a removal contributes its action alone. The three cases therefore have three
     * distinct canonical forms rather than collapsing into one another.
     */
    private CanonicalTopology canonicalTopology() {
        if (journeyTopology == null) {
            return null;
        }
        List<CanonicalTopologyEdge> edges = new ArrayList<>(journeyTopology.edges().size());
        for (ProposalTopologyEdge edge : journeyTopology.edges()) {
            edges.add(new CanonicalTopologyEdge(
                    edge.edgeId(),
                    new CanonicalEndpoint(edge.from().logicalId(), edge.from().clientRef()),
                    new CanonicalEndpoint(edge.to().logicalId(), edge.to().clientRef()),
                    edge.branch().name()));
        }
        return new CanonicalTopology(journeyTopology.action().name(), journeyTopology.topologyFormatVersion(),
                List.copyOf(edges));
    }

    private static CanonicalOperation canonicalOperation(ProposalOperation operation) {
        ProposalMoveAnchor anchor = operation.anchor();
        return new CanonicalOperation(
                operation.kind().name(),
                operation.capabilityCode(),
                operation.target().logicalId(),
                operation.target().clientRef(),
                operation.value(),
                anchor == null || anchor.kind() != MoveAnchorKind.BEFORE ? null : anchor.target(),
                anchor == null || anchor.kind() != MoveAnchorKind.AFTER ? null : anchor.target());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalPatch(
            String schemaVersion,
            String mode,
            String campaignId,
            CanonicalBaseRevisions baseRevisions,
            List<CanonicalOperation> operations,
            CanonicalClientContext clientContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) CanonicalTopology journeyTopology) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalTopology(
            String action,
            String topologyFormatVersion,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CanonicalTopologyEdge> edges) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalTopologyEdge(
            String edgeId,
            CanonicalEndpoint from,
            CanonicalEndpoint to,
            String branch) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalEndpoint(String logicalId, String clientRef) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalBaseRevisions(
            Long campaignRevision,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalOperation(
            String op,
            String capability,
            String logicalId,
            String clientRef,
            CapabilityPayload value,
            String before,
            String after) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CanonicalClientContext(String locale) {
    }
}
