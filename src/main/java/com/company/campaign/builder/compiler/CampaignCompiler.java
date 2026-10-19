package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.PlanRef;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.journey.graph.JourneyGraphResult;
import com.company.campaign.journey.graph.JourneyGraphValidator;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The deterministic Campaign compiler: the single bridge between a strict
 * {@link CampaignProposalPatch} and the authoritative Campaign state.
 *
 * <p>It merges the proposal with a trusted Current campaign, resolves every capability and catalog reference
 * against explicit frozen inputs, type-checks composition, runs the frozen authoring policy, and produces
 * one complete, immutable, non-forgeable Manifest, one minimal semantic diff and ChangeSet, and one
 * server-stored Plan. It never calls a model, never reads a clock, a random source, a default locale or a
 * mutable registry, and it invokes the authorization service exactly once per compile.
 *
 * <p>Omission inherits and never deletes; deletion is only ever the effect of an explicit {@code DELETE};
 * relocation is only ever the effect of an explicit {@code MOVE}; an existing object keeps its stable
 * identity, and a new object receives one deterministic plan-scoped identity that the caller never chose.
 */
public final class CampaignCompiler {

    /** The server-resolved resource identifier used when authoring a Campaign that does not exist yet. */
    public static final String CREATE_RESOURCE_ID = "campaign-draft-create";

    /** Maximum issues one capability contract may report before the compile fails closed. */
    static final int MAX_CONTRACT_ISSUES = 64;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final AuthorizationService authorizationService;

    public CampaignCompiler(AuthorizationService authorizationService) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
    }

    /** Compiles one request into a complete Plan. */
    public CampaignPlan compile(CompileRequest request) {
        if (request == null) {
            throw inputMissing("request");
        }
        CampaignProposalPatch patch = request.patch();
        TrustedCurrentCampaign current = request.current();
        FrozenCapabilityCatalog catalog = request.catalog();
        FrozenAuthoringPolicy policy = request.policy();
        CompileInstants instants = request.instants();
        TrustedExecutionContext context = request.context();

        requireTarget(patch, current, context, catalog, policy);
        requireAuthorized(patch, current, context);

        Merge merge = merge(patch, current, catalog, policy, context, request.capabilityContext());
        String campaignId = patch.isCreate() ? null : current.campaignId();

        List<Issue> issues = policy.evaluate(new AuthoringValidationRequest(
                campaignId, context.tenantId(), context.programId(), merge.components(), context));

        // The topology is resolved from compiler-owned canonical inputs only: the declared endpoints are turned
        // into the same final identities the merge produced, and the result is validated against the complete
        // post-merge component set before it is frozen into the Manifest.
        JourneyTopology journeyTopology = requireJourneyTopology(
                patch, current, merge.components(), merge.bindings(), request.journeyPackIndex());

        ResolvedCampaignManifest manifest = ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION,
                CompilerVersions.COMPILER_VERSION,
                patch.mode(),
                campaignId,
                current == null ? null : current.campaignRevision(),
                current == null ? null : current.draft().draftId(),
                current == null ? null : current.draft().revision(),
                current == null ? null : current.sourcePublished(),
                merge.components(),
                merge.pins(),
                catalog.catalogVersion(),
                catalog.catalogHash(),
                policy.policyVersion(),
                policy.policyHash(),
                journeyTopology);

        requireValidJourneyGraph(manifest, journeyTopology, request.journeyPackIndex());

        DiffResult diffResult = diff(current, manifest, merge.componentIndex());

        return CampaignPlan.create(
                CompilerVersions.PLAN_FORMAT_VERSION,
                CompilerVersions.COMPILER_VERSION,
                instants.compiledAt(),
                instants.expiresAt(),
                context.tenantId(),
                context.programId(),
                context.userId(),
                campaignId,
                current == null ? null : current.campaignRevision(),
                current == null ? null : current.draft().draftId(),
                current == null ? null : current.draft().revision(),
                current == null ? null : current.sourcePublished(),
                patch.proposalHash(),
                manifest.manifestHash(),
                manifest.capabilityPins(),
                catalog.catalogVersion(),
                catalog.catalogHash(),
                policy.policyVersion(),
                policy.policyHash(),
                manifest,
                issues,
                diffResult.diff(),
                diffResult.changeSet(),
                merge.bindings(),
                merge.cards(),
                merge.capabilityPreviewCards(),
                RiskSummary.empty());
    }

    /**
     * Compiles one request and stores the resulting Plan.
     *
     * <p>Only a genuine compiler-produced Plan reaches the store, and the caller receives only a
     * {@link PlanRef}: no Manifest, ChangeSet or diff leaves this boundary.
     */
    public PlanRef compileAndStore(CompileRequest request, CampaignPlanStore store) {
        if (store == null) {
            throw inputMissing("planStore");
        }
        return store.store(compile(request));
    }

    // ------------------------------------------------------------------ trusted target and pins

    private static void requireTarget(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            TrustedExecutionContext context,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy) {
        if (patch.isCreate()) {
            if (current != null) {
                // A CREATE must not be merged onto an existing Campaign.
                throw targetUnavailable();
            }
        } else {
            if (current == null
                    || !patch.campaignId().equals(current.campaignId())
                    || !context.tenantId().equals(current.tenantId())
                    || !context.programId().equals(current.programId())
                    || current.campaignRevision() != patch.baseRevisions().campaignRevision()) {
                // Absent, stale, foreign and contradictory Current all share one non-disclosing failure.
                throw targetUnavailable();
            }
            if (current.sourcePublished() != null && current.draft().draftId().equals(
                    current.sourcePublished().publishedId())) {
                throw targetUnavailable();
            }
        }
        if (!catalog.catalogVersion().equals(patch.baseRevisions().catalogVersion())
                || !catalog.catalogHash().equals(patch.baseRevisions().catalogHash())
                || !policy.policyVersion().equals(patch.baseRevisions().policyVersion())
                || !policy.policyHash().equals(patch.baseRevisions().policyHash())) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_BASE_REVISIONS_STALE,
                    "baseRevisions",
                    "The proposal was written against a different frozen catalog or policy.");
        }
    }

    // ------------------------------------------------------------------ authorization

    private void requireAuthorized(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            TrustedExecutionContext context) {
        String resourceId = patch.isCreate() ? CREATE_RESOURCE_ID : current.campaignId();
        AuthorizationDecision decision;
        try {
            decision = authorizationService.authorize(AuthorizationRequest.of(
                    context, ResourceKind.CAMPAIGN, resourceId, context.tenantId(), context.programId()));
        } catch (RuntimeException exception) {
            throw authorizationDenied();
        }
        if (decision == null
                || decision.disposition() != AuthorizationDisposition.ALLOWED
                || decision.reason() != AuthorizationReason.ALLOWED
                || decision.resourceKind() != ResourceKind.CAMPAIGN
                || decision.requiredPermission() != CampaignPermission.CAMPAIGN_EDIT) {
            throw authorizationDenied();
        }
    }

    // ------------------------------------------------------------------ merge

    private Merge merge(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context,
            CapabilityCompileContext compileContext) {
        LinkedHashMap<String, ResolvedComponent> components = new LinkedHashMap<>();
        LinkedHashMap<String, List<PlanPreviewCard>> cardsByComponent = new LinkedHashMap<>();
        LinkedHashMap<String, List<CapabilityPlanPreviewCard>> capabilityCardsByComponent = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Set<String> existingIds = new HashSet<>();
        if (current != null) {
            for (ResolvedComponent component : current.components()) {
                components.put(component.logicalId(), component);
                order.add(component.logicalId());
                existingIds.add(component.logicalId());
            }
        }

        LinkedHashMap<String, String> bindings = assignLogicalIds(patch, existingIds);
        ClientRefBindingView bindingView = bindingView(patch, bindings);

        for (ProposalOperation operation : patch.operations()) {
            if (operation.kind() != ProposalOperationKind.UPSERT) {
                continue;
            }
            String logicalId = resolveUpsertTarget(operation, bindings, existingIds);
            CapabilityContract contract = requireContract(catalog, operation.capabilityCode());
            ResolvedUnit unit = resolve(
                    contract, logicalId, operation.value(), catalog, policy, context, compileContext,
                    bindingView);
            if (!components.containsKey(logicalId)) {
                order.add(logicalId);
            }
            components.put(logicalId, unit.component());
            List<PlanPreviewCard> cards = new ArrayList<>();
            for (PlanPreviewCard card : unit.cards()) {
                if (!card.componentLogicalId().equals(logicalId)) {
                    // A contract must bind every card it contributes to the component it resolved.
                    throw contractInvalid("capabilityContractCardBinding");
                }
                cards.add(card);
            }
            // A re-resolution of one component replaces its cards outright: a stale card set never survives.
            cardsByComponent.put(logicalId, List.copyOf(cards));
            capabilityCardsByComponent.put(
                    logicalId, boundCapabilityCards(contract, unit.component(), unit.capabilityPreviewCards()));
        }

        for (ProposalOperation operation : patch.operations()) {
            if (operation.kind() != ProposalOperationKind.DELETE) {
                continue;
            }
            String logicalId = operation.target().value();
            if (!components.containsKey(logicalId)) {
                // DELETE must address an object that exists; a repeated delete therefore fails closed too.
                throw logicalIdUnknown();
            }
            components.remove(logicalId);
            order.remove(logicalId);
        }

        for (ProposalOperation operation : patch.operations()) {
            if (operation.kind() != ProposalOperationKind.MOVE) {
                continue;
            }
            String mover = resolveMoveTarget(operation, bindings, components.keySet());
            String anchor = resolveAnchor(operation.anchor().target(), bindings, components.keySet());
            order.remove(mover);
            int index = order.indexOf(anchor);
            if (index < 0) {
                throw anchorUnresolved();
            }
            if (operation.anchor().kind() == MoveAnchorKind.BEFORE) {
                order.add(index, mover);
            } else {
                order.add(index + 1, mover);
            }
        }

        List<ResolvedComponent> resolved = new ArrayList<>(order.size());
        List<PlanPreviewCard> cards = new ArrayList<>();
        List<CapabilityPlanPreviewCard> capabilityCards = new ArrayList<>();
        for (String logicalId : order) {
            resolved.add(components.get(logicalId));
            // Cards follow the final manifest order, so a deleted, reordered or replaced component can never
            // leave a stale card behind and two runs of the same input always emit the same card sequence.
            cards.addAll(cardsByComponent.getOrDefault(logicalId, List.of()));
            capabilityCards.addAll(capabilityCardsByComponent.getOrDefault(logicalId, List.of()));
        }
        requireComposition(resolved, catalog);
        return new Merge(resolved, components, List.copyOf(bindings.entrySet()).stream()
                .map(entry -> new ClientRefBinding(entry.getKey(), entry.getValue()))
                .toList(), List.copyOf(cards), List.copyOf(capabilityCards));
    }

    /**
     * Re-proves every generic capability preview card one contract contributed against the component it just
     * resolved, and returns them in the order the contract declared.
     *
     * <p>A card that names another component, another capability version, a payload the compiler never
     * normalized, a catalog reference set that does not exist, a runtime schema the resolved contract version
     * does not declare, or a card repeated for one component, fails the whole compile closed: a partially
     * projected component is never merged into a Plan.
     */
    private static List<CapabilityPlanPreviewCard> boundCapabilityCards(
            CapabilityContract contract,
            ResolvedComponent component,
            List<CapabilityPlanPreviewCard> cards) {
        List<CapabilityPlanPreviewCard> bound = new ArrayList<>(cards.size());
        Set<String> identities = new HashSet<>();
        for (CapabilityPlanPreviewCard card : cards) {
            if (card == null) {
                throw contractInvalid("capabilityContractCapabilityCards");
            }
            if (!identities.add(CapabilityPreviewCards.cardIdentity(card))) {
                // One resolved component contributes at most one card of a given kind.
                throw contractInvalid("capabilityCardDuplicate");
            }
            String invariant = CapabilityPreviewCards.bindingInvariant(card, component);
            if (invariant != null) {
                throw contractInvalid(invariant);
            }
            if (!card.runtimeCompatibility().equals(contract.runtimeCompatibility())) {
                // The card must declare the runtime compatibility of the exact contract version it projects.
                throw contractInvalid("capabilityCardBindingRuntime");
            }
            bound.add(card);
        }
        return List.copyOf(bound);
    }

    /**
     * Assigns every declared {@code clientRef} exactly one deterministic plan-scoped {@code logicalId}.
     *
     * <p>The identifier is derived from compiler-owned canonical inputs only — the compiler version, the
     * proposal hash and the reference itself — so the same proposal always yields the same identity, and a
     * derived identifier that already exists is a detected collision rather than a silent overwrite.
     */
    private static LinkedHashMap<String, String> assignLogicalIds(
            CampaignProposalPatch patch,
            Set<String> existingIds) {
        LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
        Set<String> used = new HashSet<>(existingIds);
        for (String clientRef : patch.clientRefs()) {
            if (existingIds.contains(clientRef)) {
                // A clientRef must not be spelled like an object that already exists in the campaign.
                throw clientRefConflict();
            }
            ContentHash derived = ContentHash.sha256(
                    ("logicalId\u0000" + CompilerVersions.COMPILER_VERSION + '\u0000'
                            + patch.proposalHash().value() + '\u0000' + clientRef)
                            .getBytes(StandardCharsets.UTF_8));
            String logicalId = derived.value().substring(CanonicalJson.HASH_PREFIX.length());
            if (!used.add(logicalId)) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_LOGICAL_ID_COLLISION,
                        "logicalIdCollision",
                        "A derived plan-scoped identity collided with an existing identity.");
            }
            bindings.put(clientRef, logicalId);
        }
        return bindings;
    }

    /**
     * The compiler-owned view of every reference this proposal declares as new, with its declared capability.
     *
     * <p>It is built from the compiler's own binding list and the proposal's own declared capabilities, so the
     * view states exactly which references exist and what each one is, and nothing a payload could claim.
     */
    private static ClientRefBindingView bindingView(
            CampaignProposalPatch patch, LinkedHashMap<String, String> bindings) {
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (ProposalOperation operation : patch.operations()) {
            if (operation.kind() == ProposalOperationKind.UPSERT && !operation.target().isLogicalId()) {
                capabilities.put(operation.target().clientRef(), operation.capabilityCode());
            }
        }
        List<ClientRefBinding> ordered = new ArrayList<>(bindings.size());
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            ordered.add(new ClientRefBinding(entry.getKey(), entry.getValue()));
        }
        return ClientRefBindingView.of(ordered, capabilities);
    }

    // ------------------------------------------------------------------ Journey topology

    /**
     * Resolves the Campaign's Journey topology from the compiler-owned canonical inputs.
     *
     * <p>An omitted mutation inherits the trusted Current topology and never clears it; a removal is valid only
     * when the merged Campaign holds no Flow node at all; and a replacement resolves every declared endpoint to
     * the final identity the merge produced and freezes a genuine T20 topology from the result. A replacement
     * without the frozen pack index the graph must be decided against fails closed rather than being frozen
     * unproven.
     */
    private static JourneyTopology requireJourneyTopology(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            List<ResolvedComponent> components,
            List<ClientRefBinding> bindings,
            T19PackIndex index) {
        JourneyTopologyMutation mutation = patch.journeyTopology();
        if (mutation == null) {
            // An omission inherits the trusted Current topology. An inherited graph is still re-proved against
            // the complete post-merge component set, so it needs the same frozen index a replacement does.
            return requireJourneyIndex(current == null ? null : current.journeyTopology(), index);
        }
        if (mutation.removes()) {
            for (ResolvedComponent component : components) {
                if (FlowPackVersions.CAPABILITY_CODES.contains(component.capabilityCode())) {
                    throw new CompilerException(
                            CompilerErrorCodes.COMPILER_TOPOLOGY_REMOVE_INVALID,
                            "journeyTopology",
                            "A Campaign that still holds a Flow node cannot have its topology removed.");
                }
            }
            return null;
        }
        if (index == null) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_TOPOLOGY_INPUT_MISSING,
                    "journeyPackIndex",
                    "A Journey topology replacement requires the frozen pack index it is validated against.");
        }
        Map<String, String> boundIds = new LinkedHashMap<>();
        for (ClientRefBinding binding : bindings) {
            boundIds.put(binding.clientRef(), binding.logicalId());
        }
        Set<String> present = new LinkedHashSet<>();
        for (ResolvedComponent component : components) {
            present.add(component.logicalId());
        }
        List<JourneyTopologyEdge> edges = new ArrayList<>(mutation.edges().size());
        for (ProposalTopologyEdge edge : mutation.edges()) {
            edges.add(new JourneyTopologyEdge(
                    edge.edgeId(),
                    resolveTopologyEndpoint(edge.from(), boundIds, present),
                    resolveTopologyEndpoint(edge.to(), boundIds, present),
                    edge.branch()));
        }
        try {
            return JourneyTopology.of(mutation.topologyFormatVersion(), edges);
        } catch (RuntimeException rejected) {
            // A topology that is not representable — a repeated edge, a self-edge or an over-bound set — is a
            // refusal, never a partially frozen graph.
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_TOPOLOGY_INVALID,
                    "journeyTopology",
                    "The resolved Journey topology is not a representable T20 topology.");
        }
    }

    /**
     * Resolves one declared endpoint to the final identity of the merged component set.
     *
     * <p>An existing endpoint is its stable {@code logicalId}; a declared endpoint is the compiler-assigned
     * identity of the reference it names. An endpoint that resolves to nothing the merged Campaign owns — an
     * undeclared reference, a deleted component or an identity that never existed — fails closed.
     */
    /**
     * Requires the frozen pack index whenever a topology must be re-proved.
     *
     * <p>An absent index cannot be substituted by a mutable registry, so a compile that must decide a graph
     * without one fails closed instead of freezing an unproven topology.
     */
    private static JourneyTopology requireJourneyIndex(JourneyTopology topology, T19PackIndex index) {
        if (topology != null && index == null) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_TOPOLOGY_INPUT_MISSING,
                    "journeyPackIndex",
                    "A carried Journey topology requires the frozen pack index it is re-proved against.");
        }
        return topology;
    }

    private static String resolveTopologyEndpoint(
            ProposalIdentity endpoint, Map<String, String> boundIds, Set<String> present) {
        String logicalId = endpoint.isLogicalId()
                ? endpoint.logicalId()
                : boundIds.get(endpoint.clientRef());
        if (logicalId == null || !present.contains(logicalId)) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_TOPOLOGY_ENDPOINT_UNRESOLVED,
                    "journeyTopology",
                    "A Journey topology endpoint does not name a component the merged Campaign owns.");
        }
        return logicalId;
    }

    /** Re-proves the complete merged node and edge set through the accepted T20 graph semantics. */
    private static void requireValidJourneyGraph(
            ResolvedCampaignManifest manifest, JourneyTopology topology, T19PackIndex index) {
        if (topology == null) {
            return;
        }
        JourneyGraphResult graph = new JourneyGraphValidator().validate(manifest, topology, index);
        if (!graph.isValid()) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_TOPOLOGY_GRAPH_INVALID,
                    "journeyTopology",
                    "The complete merged Journey graph is not valid.");
        }
    }

    private static String resolveUpsertTarget(
            ProposalOperation operation,
            Map<String, String> bindings,
            Set<String> existingIds) {
        if (!operation.target().isLogicalId()) {
            return bindings.get(operation.target().clientRef());
        }
        String logicalId = operation.target().logicalId();
        if (!existingIds.contains(logicalId)) {
            // An upsert of an unknown logicalId is not a create: a create uses a clientRef.
            throw logicalIdUnknown();
        }
        return logicalId;
    }

    private static String resolveMoveTarget(
            ProposalOperation operation,
            Map<String, String> bindings,
            Set<String> present) {
        if (!operation.target().isLogicalId()) {
            String logicalId = bindings.get(operation.target().clientRef());
            if (logicalId == null || !present.contains(logicalId)) {
                throw anchorUnresolved();
            }
            return logicalId;
        }
        String logicalId = operation.target().logicalId();
        if (!present.contains(logicalId)) {
            throw anchorUnresolved();
        }
        return logicalId;
    }

    private static String resolveAnchor(
            String anchorValue,
            Map<String, String> bindings,
            Set<String> present) {
        String mapped = bindings.getOrDefault(anchorValue, anchorValue);
        if (!present.contains(mapped)) {
            throw anchorUnresolved();
        }
        return mapped;
    }

    // ------------------------------------------------------------------ capability resolution

    private static CapabilityContract requireContract(FrozenCapabilityCatalog catalog, String capabilityCode) {
        CapabilityContract contract = catalog.find(capabilityCode).orElseThrow(() -> new CompilerException(
                CompilerErrorCodes.COMPILER_CAPABILITY_UNKNOWN,
                "capability",
                "The named capability is not present in the frozen catalog."));
        if (!contract.enabled()) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_CAPABILITY_DISABLED,
                    "capability",
                    "The named capability is disabled by the frozen catalog.");
        }
        return contract;
    }

    private static ResolvedUnit resolve(
            CapabilityContract contract,
            String logicalId,
            CapabilityPayload payload,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context,
            CapabilityCompileContext compileContext,
            ClientRefBindingView bindingView) {
        CapabilityResolution resolution;
        try {
            resolution = contract.resolve(new CapabilityRequest(
                    logicalId, payload, catalog, policy, context, compileContext, bindingView));
        } catch (RuntimeException exception) {
            throw contractInvalid("capabilityContract");
        }
        if (resolution instanceof CapabilityResolution.Failed failed
                && (failed.issues() == null
                        || failed.issues().size() > MAX_CONTRACT_ISSUES
                        || failed.issues().stream().anyMatch(Objects::isNull))) {
            throw contractInvalid("capabilityContractIssues");
        }
        if (!(resolution instanceof CapabilityResolution.Resolved resolved)) {
            // A failed resolution, or any other shape, fails the compile closed; no partial component exists.
            throw contractInvalid("capabilityContractResult");
        }
        try {
            ResolvedComponent component = new ResolvedComponent(
                    logicalId,
                    contract.capabilityCode(),
                    contract.contractVersion(),
                    contract.slot(),
                    resolved.value(),
                    resolved.references());
            return new ResolvedUnit(component, resolved.cards(), resolved.capabilityPreviewCards());
        } catch (RuntimeException exception) {
            throw contractInvalid("capabilityContractComponent");
        }
    }

    /**
     * Generic composition type checking and frozen-fact re-verification of the complete component set.
     *
     * <p>Every component's capability must still exist in the frozen catalog at the exact pinned contract
     * version, must still be enabled, and must still declare the same composition slot; one slot code must not
     * be declared two different ways; a singleton slot must hold at most one component; and one reference code
     * must not resolve to two different entity versions, which would make the Manifest ambiguous. These checks
     * cover inherited, untouched components exactly as they cover the ones the proposal names.
     */
    private static void requireComposition(List<ResolvedComponent> components, FrozenCapabilityCatalog catalog) {
        Map<String, CompositionSlot> slotByCode = new HashMap<>();
        Map<String, Integer> countBySlot = new HashMap<>();
        Map<String, String> referenceTargets = new HashMap<>();
        for (ResolvedComponent component : components) {
            CapabilityContract contract = catalog.find(component.capabilityCode()).orElseThrow(() -> new CompilerException(
                    CompilerErrorCodes.COMPILER_CAPABILITY_UNKNOWN,
                    "carriedCapability",
                    "A carried component's capability is not present in the frozen catalog."));
            if (!contract.contractVersion().equals(component.contractVersion())) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_CAPABILITY_VERSION_MISMATCH,
                        "carriedContractVersion",
                        "A carried component's contract version is not the one the frozen catalog declares.");
            }
            if (!contract.enabled()) {
                // An untouched, inherited component is re-verified exactly like a named one: a capability
                // that the frozen catalog has since disabled must not be carried into a new Manifest.
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_CAPABILITY_DISABLED,
                        "carriedCapabilityEnabled",
                        "A carried component's capability is disabled by the frozen catalog.");
            }
            if (!contract.slot().equals(component.slot())) {
                throw slotViolation("carriedCompositionSlot");
            }
            CompositionSlot existing = slotByCode.putIfAbsent(component.slot().code(), component.slot());
            if (existing != null && !existing.equals(component.slot())) {
                throw slotViolation("slotDeclaration");
            }
            countBySlot.merge(component.slot().code(), 1, Integer::sum);
            for (ResolvedCatalogReference reference : component.references()) {
                String key = reference.entityKind() + '|' + reference.referenceCode();
                String target = reference.logicalId() + '|' + reference.versionId();
                String previous = referenceTargets.putIfAbsent(key, target);
                if (previous != null && !previous.equals(target)) {
                    throw new CompilerException(
                            CompilerErrorCodes.COMPILER_REFERENCE_UNRESOLVED,
                            "ambiguousReference",
                            "One catalog reference resolved to two different entity versions.");
                }
            }
        }
        for (Map.Entry<String, Integer> entry : countBySlot.entrySet()) {
            CompositionSlot slot = slotByCode.get(entry.getKey());
            if (slot.singleton() && entry.getValue() > 1) {
                throw slotViolation("singletonSlot");
            }
        }
    }

    // ------------------------------------------------------------------ semantic diff and ChangeSet

    private static DiffResult diff(
            TrustedCurrentCampaign current,
            ResolvedCampaignManifest manifest,
            Map<String, ResolvedComponent> resolvedById) {
        List<ResolvedComponent> currentComponents = current == null ? List.of() : current.components();
        Map<String, ResolvedComponent> currentById = new LinkedHashMap<>();
        for (ResolvedComponent component : currentComponents) {
            currentById.put(component.logicalId(), component);
        }

        List<ResolvedComponent> added = new ArrayList<>();
        List<ResolvedComponent> updated = new ArrayList<>();
        List<ResolvedComponent> deleted = new ArrayList<>();
        // The relocation baseline is the surviving components in their CURRENT order, then the new ones in
        // manifest order; that is exactly the state a host reaches before it applies the move set.
        List<String> survivors = new ArrayList<>();
        for (ResolvedComponent component : currentComponents) {
            if (manifest.component(component.logicalId()) == null) {
                deleted.add(component);
            } else {
                survivors.add(component.logicalId());
            }
        }
        for (ResolvedComponent component : manifest.components()) {
            ResolvedComponent previous = currentById.get(component.logicalId());
            if (previous == null) {
                added.add(component);
            } else if (!component.hasSameSemantics(previous)) {
                updated.add(component);
            }
        }

        List<String> baseline = new ArrayList<>(survivors);
        for (ResolvedComponent component : added) {
            baseline.add(component.logicalId());
        }
        List<String> finalOrder = manifest.components().stream().map(ResolvedComponent::logicalId).toList();
        List<ManifestOrdering.MoveInstruction> moves = ManifestOrdering.minimumMoves(baseline, finalOrder);

        List<ChangeSetNode> nodes = new ArrayList<>();
        List<DiffEntry> entries = new ArrayList<>();
        for (ResolvedComponent component : added) {
            nodes.add(new ChangeSetNode.Add(
                    component.logicalId(),
                    component.capabilityCode(),
                    component.contractVersion(),
                    component.slot(),
                    component.value(),
                    component.references()));
            entries.add(new DiffEntry(
                    ChangeKind.ADD, component.logicalId(), component.capabilityCode(), List.of("component")));
        }
        for (ResolvedComponent component : updated) {
            ResolvedComponent previous = currentById.get(component.logicalId());
            List<AssetChange> changes = semanticChanges(previous, component);
            nodes.add(new ChangeSetNode.Update(component.logicalId(), changes));
            entries.add(new DiffEntry(
                    ChangeKind.UPDATE,
                    component.logicalId(),
                    component.capabilityCode(),
                    changes.stream().map(AssetChange::fieldPath).toList()));
        }
        for (ManifestOrdering.MoveInstruction move : moves) {
            nodes.add(new ChangeSetNode.Move(move.logicalId(), move.anchorKind(), move.anchorLogicalId()));
            ResolvedComponent component = resolvedById.get(move.logicalId());
            entries.add(new DiffEntry(
                    ChangeKind.MOVE, move.logicalId(), component.capabilityCode(), List.of("order")));
        }
        for (ResolvedComponent component : deleted) {
            nodes.add(new ChangeSetNode.Delete(component.logicalId(), component.capabilityCode()));
            entries.add(new DiffEntry(
                    ChangeKind.DELETE, component.logicalId(), component.capabilityCode(), List.of("component")));
        }
        return new DiffResult(
                new SemanticDiff(entries), new ChangeSet(CompilerVersions.CHANGESET_FORMAT_VERSION, nodes));
    }

    /** Only facts that actually changed, in a fixed field order, so an update is never padded. */
    private static List<AssetChange> semanticChanges(ResolvedComponent previous, ResolvedComponent target) {
        List<AssetChange> changes = new ArrayList<>();
        if (!previous.capabilityCode().equals(target.capabilityCode())) {
            changes.add(new AssetChange.CapabilityChanged(target.capabilityCode()));
        }
        if (!previous.contractVersion().equals(target.contractVersion())) {
            changes.add(new AssetChange.ContractVersionChanged(target.contractVersion()));
        }
        if (!previous.slot().equals(target.slot())) {
            changes.add(new AssetChange.SlotChanged(target.slot()));
        }
        if (!previous.value().equals(target.value())) {
            changes.add(new AssetChange.ValueChanged(target.value()));
        }
        if (!previous.references().equals(target.references())) {
            changes.add(new AssetChange.ReferencesChanged(target.references()));
        }
        return changes;
    }

    // ------------------------------------------------------------------ failures

    private static CompilerException inputMissing(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_INPUT_MISSING, invariant, "A required compiler input is absent.");
    }

    private static CompilerException targetUnavailable() {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_TARGET_UNAVAILABLE,
                "target",
                "The campaign target of this proposal is not available to the trusted context.");
    }

    private static CompilerException authorizationDenied() {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_AUTHORIZATION_DENIED,
                "authorization",
                "Campaign Draft authoring is not authorized for this trusted context.");
    }

    private static CompilerException logicalIdUnknown() {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_LOGICAL_ID_UNKNOWN,
                "logicalId",
                "An operation names an object the campaign does not own.");
    }

    private static CompilerException anchorUnresolved() {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_ANCHOR_UNRESOLVED,
                "anchor",
                "A move anchor does not name an object the merged campaign owns.");
    }

    private static CompilerException clientRefConflict() {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_LOGICAL_ID_COLLISION,
                "clientRef",
                "A client reference collides with an identity the campaign already owns.");
    }

    private static CompilerException contractInvalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                invariant,
                "A capability contract did not produce a usable resolution.");
    }

    private static CompilerException slotViolation(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_COMPOSITION_SLOT_VIOLATION,
                invariant,
                "A composition slot rule is not satisfied.");
    }

    /** One resolved component together with every preview card its contract contributed. */
    private record ResolvedUnit(
            ResolvedComponent component,
            List<PlanPreviewCard> cards,
            List<CapabilityPlanPreviewCard> capabilityPreviewCards) {
    }

    /** The merged component collection plus its pins, the {@code clientRef -> logicalId} mapping and cards. */
    private record Merge(
            List<ResolvedComponent> components,
            Map<String, ResolvedComponent> componentIndex,
            List<ClientRefBinding> bindings,
            List<PlanPreviewCard> cards,
            List<CapabilityPlanPreviewCard> capabilityPreviewCards) {

        List<CapabilityPin> pins() {
            List<CapabilityPin> pins = new ArrayList<>(new LinkedHashSet<>(
                    components.stream()
                            .map(component -> new CapabilityPin(
                                    component.capabilityCode(), component.contractVersion()))
                            .toList()));
            pins.sort(CapabilityPin::order);
            return List.copyOf(pins);
        }
    }

    /** The compiler-produced semantic diff and internal ChangeSet pair. */
    private record DiffResult(SemanticDiff diff, ChangeSet changeSet) {
    }
}