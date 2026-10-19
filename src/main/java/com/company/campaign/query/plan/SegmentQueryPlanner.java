package com.company.campaign.query.plan;

import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.EventSelectorBinding;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.MetricFilterDimension;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.RelationEventTimeCapability;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.segment.canonical.CanonicalAudienceVersionRef;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricFilter;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.canonical.CanonicalWindow;
import com.company.campaign.segment.canonical.CanonicalWindowKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.ast.QueryAstCompiler;
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.EffectiveDataScope;
import com.company.campaign.segment.reference.ReferenceCandidate;
import com.company.campaign.segment.reference.ReferenceExecutionContext;
import com.company.campaign.segment.reference.ReferenceGraph;
import com.company.campaign.segment.reference.ReferenceGraphOutcome;
import com.company.campaign.segment.reference.ReferenceGraphResolver;
import com.company.campaign.segment.reference.ResolvedAudienceReference;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The deterministic T08 planner: one genuine T07 result plus its exact Snapshot and trusted context produce
 * one immutable, fully pinned Semantic Query Plan.
 *
 * <p>Every trusted fact that must never come from DSL or model input — tenant, program, membership, DataScope,
 * {@code asOf}, timezone, reporting currency, watermark, Metadata pins and versions — is read only from the
 * {@link TrustedExecutionContext} and the exact {@link MetadataSnapshot}, never from the canonical AST.
 *
 * <p>The planner re-proves the whole boundary rather than trusting it: the result must be a genuine success
 * with a canonical artifact whose hash it recomputes, every Metadata pin must match the supplied snapshot,
 * the snapshot owner must match the context, and the context's own snapshot pin must name the same snapshot.
 *
 * <p>It also reauthorizes the planning context: a validation result proves only that the principal obtained
 * at validation time could use the referenced resources. Planning therefore authorizes every distinct
 * resolved Attribute, Metric and Relation dependency (including a metric's transitive relation) through the
 * mandatory T02 {@link AuthorizationService} against the exact context and the snapshot-owned tenant/program,
 * and fails closed with one stable non-disclosing code before any plan is produced. Authorization flags are
 * never read from the plan call, the result or the payload. Any failure is a stable bounded
 * {@link QueryException} that echoes no identifier or operand.
 *
 * <p>The planner is immutable and thread-safe.
 */
public final class SegmentQueryPlanner {

    private static final CanonicalSegmentCodec CANONICAL_CODEC = new CanonicalSegmentCodec();
    private static final ReferenceGraphResolver REFERENCE_RESOLVER = new ReferenceGraphResolver();

    private final AuthorizationService authorizationService;

    /**
     * Creates a planner backed by the supplied T02 authorization boundary.
     *
     * <p>The service is mandatory: there is no no-argument constructor, so a planner that cannot authorize
     * cannot exist and no caller can plan without reauthorizing the exact trusted context.
     */
    public SegmentQueryPlanner(AuthorizationService authorizationService) {
        if (authorizationService == null) {
            throw new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, "authorizationService",
                    "The Semantic Query Planner requires an authorization service.");
        }
        this.authorizationService = authorizationService;
    }

    /**
     * Compiles one accepted T07 result into a Semantic Query Plan without reference-resolution ports.
     *
     * <p>This is the accepted non-reference entry point and its behaviour is unchanged. A result that carries
     * a resolved reference graph cannot be planned here: there is no trusted port to re-resolve it against, so
     * the call fails closed with {@link QueryErrorCodes#QUERY_REFERENCE_CONTEXT_REQUIRED} rather than freezing
     * an unproven dependency. Use {@link #plan(SegmentValidationResult, MetadataSnapshot, TrustedExecutionContext,
     * ReferenceExecutionContext)} for a reference-bearing document.
     */
    public SemanticQueryPlan plan(
            SegmentValidationResult result,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context) {
        return plan(result, snapshot, context, null);
    }

    /**
     * Compiles one accepted T07 result into a Semantic Query Plan, resolving any Existing Audience Reference.
     *
     * <p>Everything a reference-free document does is unchanged: the same AST, the same dependencies, the same
     * windows, the same currency bindings and the caller's own DataScope requirement, byte for byte. The
     * trusted reference context is not merely ignored for such a document — it is never consulted, so a host
     * that supplies ports which throw cannot affect a plan that has no reference at all.
     *
     * <p>A reference-bearing document is resolved <em>again</em>, independently of the accepted validation
     * result: the exact pin of every site is re-resolved through the host catalog, re-authorized as a
     * {@code SEGMENT} through the T02 boundary, re-checked for owner tenant, program relation, exact bytes,
     * availability and subject, and re-intersected for an effective DataScope. The re-resolved dependency graph
     * hash must equal the accepted validation-time graph hash exactly, so an owner, permission, policy,
     * availability, scope or version drift between validation and planning rejects planning with one stable
     * non-disclosing code. Only then are the dependencies, the effective scope and the retention observations
     * frozen into the plan.
     *
     * @param referenceContext the host-owned reference ports, or {@code null} for a document with no reference
     */
    public SemanticQueryPlan plan(
            SegmentValidationResult result,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            ReferenceExecutionContext referenceContext) {
        requirePresent(result, "validationResult");
        requirePresent(snapshot, "metadataSnapshot");
        requirePresent(context, "trustedContext");

        CanonicalSegment canonical = result.canonicalSegment();
        MetadataBinding binding = result.metadataBinding();
        if (!result.valid() || canonical == null || result.canonicalAstHash() == null || binding == null) {
            throw invalid("validationResult",
                    "A Semantic Query Plan requires a genuine successful validation result.");
        }
        requireCanonicalHash(result.canonicalAstHash(), canonical);
        requireBindingPins(binding, snapshot);
        requireOwner(snapshot, context);

        TrustedExecutionContextRef reference = context.snapshotRef();
        if (reference == null || !Objects.equals(snapshot.snapshotId(), reference.metadataSnapshotId())) {
            throw new QueryException(QueryErrorCodes.QUERY_CONTEXT_SNAPSHOT_MISMATCH, "contextSnapshotPin",
                    "The trusted context pins a different Metadata Snapshot than the plan resolves against.");
        }
        if (reference.asOf() == null || reference.dataWatermark() == null || reference.timezone() == null
                || reference.dataWatermark().isAfter(reference.asOf())) {
            throw new QueryException(QueryErrorCodes.QUERY_TIME_CONTEXT_INVALID, "timeContext",
                    "The trusted time context is missing or self-contradictory.");
        }

        QueryAst ast = QueryAstCompiler.compile(canonical);
        Resolution resolution = new Resolution(
                snapshot, reference.asOf(), reference.dataWatermark(), reference.reportingCurrency());
        PlanStructure.Sites sites = PlanStructure.collect(ast, canonical.subject());
        for (PlanStructure.AttributeSite site : sites.attributes()) {
            resolution.addAttribute(site.node());
        }
        for (PlanStructure.MetricSite site : sites.metrics()) {
            resolution.addMetric(site.node(), site.path());
        }
        for (PlanStructure.ExpressionMetricSite site : sites.expressionMetrics()) {
            resolution.addExpressionMetric(site);
        }
        for (PlanStructure.ExistsSite site : sites.exists()) {
            resolution.addExists(site.node(), site.path());
        }

        List<ResolvedDependency> dependencies = resolution.dependencies();
        authorizeDependencies(dependencies, snapshot, context);

        ReferenceFacts facts = resolveReferences(result, sites, context, referenceContext);
        List<AudienceVersionDependency> audienceDependencies = facts.dependencies();
        DataScopePredicate dataScopePredicate = facts.dataScopePredicate(canonical.subject(), context);

        TrustedPlanContext planContext = new TrustedPlanContext(
                context.tenantId(),
                context.programId(),
                context.membership().membershipId(),
                context.userId(),
                context.dataScopeId(),
                reference.asOf(),
                reference.timezone().getId(),
                reference.reportingCurrency(),
                reference.dataWatermark());
        List<CurrencyBinding> currencyBindings = resolution.currencyBindings();

        ContentHash planHash = SemanticQueryPlanCodec.hashMaterial(new SemanticQueryPlan.Material(
                PlanVersions.PLAN_FORMAT_VERSION,
                PlanVersions.PLANNER_VERSION,
                PlanVersions.COMPILER_VERSION,
                PlanVersions.QUERY_AST_VERSION,
                canonical.schemaVersion(),
                canonical.canonicalizerVersion(),
                canonical.subject(),
                ast,
                result.canonicalAstHash(),
                binding,
                dependencies,
                audienceDependencies,
                resolution.windowBindings(),
                currencyBindings,
                dataScopePredicate,
                planContext));
        return new SemanticQueryPlan(
                PlanVersions.PLAN_FORMAT_VERSION,
                PlanVersions.PLANNER_VERSION,
                PlanVersions.COMPILER_VERSION,
                PlanVersions.QUERY_AST_VERSION,
                canonical.schemaVersion(),
                canonical.canonicalizerVersion(),
                canonical.subject(),
                ast,
                result.canonicalAstHash(),
                binding,
                dependencies,
                audienceDependencies,
                resolution.windowBindings(),
                currencyBindings,
                dataScopePredicate,
                planContext,
                planHash);
    }

    // ------------------------------------------------------------------ reference resolution

    /**
     * The frozen reference facts of one plan: the audience dependencies in canonical order and, when the
     * document references anything, the host-computed effective DataScope the plan must be evaluated under.
     */
    private record ReferenceFacts(
            List<AudienceVersionDependency> dependencies,
            EffectiveDataScope effectiveDataScope) {

        /** The DataScope requirement this plan must carry: the caller's scope, or the effective intersection. */
        DataScopePredicate dataScopePredicate(SubjectKind subject, TrustedExecutionContext context) {
            if (effectiveDataScope == null) {
                return new DataScopePredicate(
                        DataScopePredicate.Requirement.SUBJECT_IN_DATASCOPE, subject, context.dataScopeId());
            }
            return new DataScopePredicate(
                    DataScopePredicate.Requirement.SUBJECT_IN_EFFECTIVE_DATASCOPE,
                    subject,
                    effectiveDataScope.dataScopeId(),
                    effectiveDataScope.dataScopeHash());
        }
    }

    /**
     * Independently re-resolves, reauthorizes and freezes every reference the accepted AST names.
     *
     * <p>This is the planner's own proof, not a copy of the validator's. It re-runs the one accepted T07
     * reference-graph resolver over candidates re-derived from the compiled AST and the trusted context, so
     * ownership, the program relation, the {@code SEGMENT} authorization, the exact bytes, availability,
     * subject and the effective-scope intersection are all proved again. The re-resolved graph must be clean
     * and its hash must equal the accepted validation-time hash exactly: anything else is drift, and drift
     * rejects planning rather than being absorbed.
     */
    private ReferenceFacts resolveReferences(
            SegmentValidationResult result,
            PlanStructure.Sites sites,
            TrustedExecutionContext context,
            ReferenceExecutionContext referenceContext) {
        if (sites.references().isEmpty()) {
            if (result.referenceGraph() != null) {
                throw new QueryException(QueryErrorCodes.QUERY_VALIDATION_RESULT_INVALID, "referenceGraph",
                        "A validation result must not carry a reference graph for a document with no reference.");
            }
            return new ReferenceFacts(List.of(), null);
        }
        if (result.referenceGraph() == null) {
            throw new QueryException(QueryErrorCodes.QUERY_VALIDATION_RESULT_INVALID, "referenceGraph",
                    "A reference-bearing document requires a resolved validation-time reference graph.");
        }
        if (referenceContext == null) {
            throw new QueryException(QueryErrorCodes.QUERY_REFERENCE_CONTEXT_REQUIRED, "referenceContext",
                    "Planning an Existing Audience Reference requires the host's trusted reference ports.");
        }

        List<ReferenceCandidate> candidates = new ArrayList<>(sites.references().size());
        for (PlanStructure.ReferenceSite site : sites.references()) {
            CanonicalAudienceVersionRef pin = site.node().reference();
            candidates.add(new ReferenceCandidate(
                    site.path(),
                    new AudienceVersionRef(pin.audienceId(), pin.versionId(), pin.versionHash()),
                    site.scopeSubject()));
        }

        ReferenceGraphOutcome outcome;
        try {
            outcome = REFERENCE_RESOLVER.resolve(
                    candidates, context, authorizationService, referenceContext.asValidationContext(null));
        } catch (RuntimeException exception) {
            throw referenceDrift("referenceResolution");
        }
        if (!outcome.clean()) {
            throw referenceDrift("referenceResolution");
        }
        ReferenceGraph graph = outcome.graph();
        if (!graph.graphHash().equals(result.referenceGraph().graphHash())) {
            throw referenceDrift("referenceGraphHash");
        }

        List<AudienceVersionDependency> dependencies = new ArrayList<>(graph.versions().size());
        for (ResolvedAudienceReference resolved : graph.versions()) {
            List<AudienceRetentionHold> holds;
            try {
                holds = referenceContext.retentionPort().activeHolds(resolved.reference());
            } catch (RuntimeException exception) {
                throw referenceDrift("referenceRetention");
            }
            if (holds == null) {
                // A port that answers nothing is an unavailable answer, never an unretained one.
                throw referenceDrift("referenceRetention");
            }
            dependencies.add(AudienceVersionDependency.of(resolved, holds));
        }
        return new ReferenceFacts(
                AudienceVersionDependency.canonical(dependencies), effectiveScope(referenceContext, context, graph));
    }

    /**
     * The host-computed intersection of the caller scope with every referenced effective scope.
     *
     * <p>An intersection is never a choice between inputs and never a concatenation: each step is handed to the
     * trusted port, which must answer with a scope described by its own identity and content hash, or with
     * nothing. The fold starts from the caller scope and pins it as the base of every step, so the result is
     * genuinely narrower than, or equal to, the caller's own scope and can never be wider.
     */
    private static EffectiveDataScope effectiveScope(
            ReferenceExecutionContext referenceContext, TrustedExecutionContext context, ReferenceGraph graph) {
        Optional<EffectiveDataScope> caller;
        try {
            caller = referenceContext.dataScopePort().callerScope(
                    context.tenantId(), context.programId(), context.userId(), context.dataScopeId());
        } catch (RuntimeException exception) {
            throw referenceDrift("callerDataScope");
        }
        if (caller == null || caller.isEmpty() || !caller.get().dataScopeId().equals(context.dataScopeId())) {
            throw referenceDrift("callerDataScope");
        }
        EffectiveDataScope base = caller.get();
        EffectiveDataScope effective = base;
        for (ResolvedAudienceReference resolved : graph.versions()) {
            Optional<EffectiveDataScope> next;
            try {
                next = referenceContext.dataScopePort()
                        .intersect(base, effective, resolved.effectiveDataScope());
            } catch (RuntimeException exception) {
                throw referenceDrift("effectiveDataScope");
            }
            if (next == null || next.isEmpty()) {
                throw referenceDrift("effectiveDataScope");
            }
            effective = next.get();
        }
        return effective;
    }

    private static QueryException referenceDrift(String invariant) {
        return new QueryException(QueryErrorCodes.QUERY_REFERENCE_DRIFT, invariant,
                "A referenced audience version drifted between validation and planning.");
    }

    // ------------------------------------------------------------------ boundary checks

    /**
     * Re-proves that a declared canonical AST hash really is the canonical hash of the supplied artifact.
     *
     * <p>Package-private so the public planner and its in-package boundary tests exercise the exact same
     * comparison; a genuine T07 result always passes, and a forged or stale pin fails closed without echoing
     * the pin or the artifact.
     */
    static void requireCanonicalHash(ContentHash declared, CanonicalSegment canonical) {
        if (declared == null || canonical == null || !CANONICAL_CODEC.hash(canonical).equals(declared)) {
            throw new QueryException(QueryErrorCodes.QUERY_CANONICAL_HASH_MISMATCH, "canonicalAstHash",
                    "The declared canonical AST hash is not the canonical hash of the supplied artifact.");
        }
    }

    private static void requireBindingPins(MetadataBinding binding, MetadataSnapshot snapshot) {
        if (!Objects.equals(binding.snapshotId(), snapshot.snapshotId())
                || !Objects.equals(binding.snapshotVersion(), snapshot.snapshotVersion())
                || !Objects.equals(binding.metadataSchemaVersion(), snapshot.schemaVersion())
                || !Objects.equals(binding.contentHash(), snapshot.contentHash())) {
            throw new QueryException(QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH, "metadataBinding",
                    "A Metadata binding pin does not match the supplied Metadata Snapshot.");
        }
    }

    private static void requireOwner(MetadataSnapshot snapshot, TrustedExecutionContext context) {
        if (!Objects.equals(snapshot.tenantId(), context.tenantId())
                || !Objects.equals(snapshot.programId(), context.programId())) {
            throw new QueryException(QueryErrorCodes.QUERY_METADATA_OWNER_MISMATCH, "metadataOwner",
                    "The Metadata Snapshot owner does not match the trusted execution context.");
        }
    }

    // ------------------------------------------------------------------ authorization

    /**
     * Authorizes every distinct resolved dependency for the exact trusted context being planned.
     *
     * <p>It is called only after the exact pinned dependencies are resolved and before any plan material is
     * produced. Each dependency is authorized with the supplied trusted context and the snapshot-owned
     * tenant/program; a metric's transitive relation is already a distinct {@code RELATION} dependency, so it
     * is authorized even when no EXISTS node references it. Any non-{@code ALLOWED} disposition, a null
     * decision or a thrown boundary failure fails closed with the one stable, non-disclosing T08 code — no
     * T02 reason, resource ID, scope value or thrown cause ever escapes.
     *
     * <p>A disposition of {@code ALLOWED} is not by itself an approval: {@link AuthorizationDecision} is a
     * public value that a buggy adapter can mis-shape, so a decision is accepted only when its disposition and
     * {@code allowed()} agree, its reason is the accepted {@code ALLOWED} reason, its resource kind is the
     * requested kind and its required permission is exactly the permission that kind requires.
     */
    private void authorizeDependencies(
            List<ResolvedDependency> dependencies,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context) {
        for (ResolvedDependency dependency : dependencies) {
            ResourceKind kind = dependency.kind().resourceKind();
            AuthorizationRequest request = AuthorizationRequest.of(
                    context, kind, dependency.id(), snapshot.tenantId(), snapshot.programId());
            AuthorizationDecision decision;
            try {
                decision = authorizationService.authorize(request);
            } catch (RuntimeException exception) {
                // A boundary that throws is an unavailable decision, never an approval.
                throw authorizationDenied();
            }
            if (!isExactApproval(decision, kind)) {
                throw authorizationDenied();
            }
        }
    }

    /** True only for a well-formed approval of exactly the requested resource kind and its fixed permission. */
    private static boolean isExactApproval(AuthorizationDecision decision, ResourceKind expectedKind) {
        if (decision == null
                || decision.disposition() != AuthorizationDisposition.ALLOWED
                || !decision.allowed()
                || decision.reason() != AuthorizationReason.ALLOWED
                || decision.resourceKind() != expectedKind) {
            return false;
        }
        return decision.requiredPermission() == expectedKind.requiredPermission();
    }

    private static QueryException authorizationDenied() {
        return new QueryException(QueryErrorCodes.QUERY_AUTHORIZATION_DENIED, "dependencyAuthorization",
                "A resolved plan dependency is not authorized for the trusted context.");
    }

    // ------------------------------------------------------------------ resolution state

    /** All mutable planning state; confined to one {@link #plan} call. */
    private static final class Resolution {

        private final Map<String, MetadataDefinition> definitionsById = new LinkedHashMap<>();
        private final Map<String, EventSelectorDefinition> selectorsById = new LinkedHashMap<>();
        private final Instant asOf;
        private final Instant dataWatermark;
        private final String reportingCurrency;

        private final Map<String, ResolvedDependency> dependencies = new LinkedHashMap<>();
        private final List<ResolvedWindowBinding> windows = new ArrayList<>();
        private final Map<String, CurrencyBinding> currencyBindings = new LinkedHashMap<>();

        Resolution(MetadataSnapshot snapshot, Instant asOf, Instant dataWatermark, String reportingCurrency) {
            this.asOf = asOf;
            this.dataWatermark = dataWatermark;
            this.reportingCurrency = reportingCurrency;
            for (AttributeDefinition definition : snapshot.attributes()) {
                definitionsById.put(definition.id(), definition);
            }
            for (MetricDefinition definition : snapshot.metrics()) {
                definitionsById.put(definition.id(), definition);
            }
            for (RelationDefinition definition : snapshot.relations()) {
                definitionsById.put(definition.id(), definition);
            }
            for (EventSelectorDefinition selector : snapshot.eventSelectors()) {
                selectorsById.put(selector.id(), selector);
            }
        }

        void addAttribute(QueryAst.Attribute attribute) {
            if (!(definitionsById.get(attribute.definition().id()) instanceof AttributeDefinition resolved)) {
                throw new QueryException(QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH, "attributeResolution",
                        "An attribute could not be resolved from the exact pinned Metadata Snapshot.");
            }
            recordCurrency(MetadataKind.ATTRIBUTE, attribute.definition().dataType(),
                    attribute.definition().currencySemantics(), attribute.definition().id());
            addDependency(new ResolvedDependency.AttributeDependency(
                    attribute.definition(), resolved.entityVersion()));
        }

        void addMetric(QueryAst.Metric metric, String path) {
            MetricDefinition resolved = requireMetric(metric);
            ResolvedDependency.RelationDependency relation =
                    relationDependency(requireRelation(resolved.relationId()));
            recordCurrency(MetadataKind.METRIC, metric.definition().resultType(),
                    metric.definition().currencySemantics(), metric.definition().id());
            EventSelectorDefinition eventSelector = eventSelector(metric.window(), resolved);
            addDependency(new ResolvedDependency.MetricDependency(
                    metric.definition(), resolved.entityVersion(), resolved.relationId(), relation,
                    resolved.eventTimeField(), resolved.eventTimeSemantics(),
                    resolved.filter(), resolved.refundTreatment(), eventSelector));
            addDependency(relation);
            for (CanonicalMetricFilter filter : metric.scopeFilters()) {
                addMetricScopeFilter(filter, resolved, relation);
            }
            if (eventSelector != null) {
                addEventSelectorDependencies(metric, eventSelector, relation);
            }
            addRelativeWindowAnchor(metric, resolved);
            windows.add(metricWindow(metric, resolved, path));
        }

        void addExpressionMetric(PlanStructure.ExpressionMetricSite site) {
            MetricDefinition resolved = requireMetric(site.definition());
            ResolvedDependency.RelationDependency relation =
                    relationDependency(requireRelation(resolved.relationId()));
            recordCurrency(
                    MetadataKind.METRIC,
                    site.definition().resultType(),
                    site.definition().currencySemantics(),
                    site.definition().id());
            addDependency(new ResolvedDependency.MetricDependency(
                    site.definition(), resolved.entityVersion(), resolved.relationId(), relation,
                    resolved.eventTimeField(), resolved.eventTimeSemantics(),
                    resolved.filter(), resolved.refundTreatment(), null));
            addDependency(relation);
            windows.add(metricWindow(site.definition(), site.window(), resolved, site.path()));
        }

        private EventSelectorDefinition eventSelector(
                CanonicalWindow window, MetricDefinition metric) {
            String selectorId = switch (window) {
                case CanonicalWindow.SelectedEventWindow selected -> selected.selectorId();
                case CanonicalWindow.RelativeToEventWindow relative -> relative.selectorId();
                default -> null;
            };
            if (selectorId == null) {
                return null;
            }
            EventSelectorDefinition selector = selectorsById.get(selectorId);
            if (selector == null || selector.subject() != metric.subject()
                    || selector.bindingFor(metric.relationId()) == null) {
                throw new QueryException(
                        QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH,
                        "eventSelector",
                        "An event selector could not be resolved from the pinned Metadata Snapshot.");
            }
            return selector;
        }

        private void addEventSelectorDependencies(
                QueryAst.Metric metric,
                EventSelectorDefinition selector,
                ResolvedDependency.RelationDependency relation) {
            EventSelectorBinding binding = selector.bindingFor(relation.id());
            java.util.LinkedHashSet<String> attributeIds = new java.util.LinkedHashSet<>();
            attributeIds.add(binding.identityAttributeId());
            binding.orderBy().forEach(order -> attributeIds.add(order.attributeId()));
            binding.fixedFilter().entries().forEach(entry -> attributeIds.add(entry.key()));
            List<CanonicalMetricFilter> selectorFilters = switch (metric.window()) {
                case CanonicalWindow.SelectedEventWindow selected -> selected.selectorScopeFilters();
                case CanonicalWindow.RelativeToEventWindow relative -> relative.selectorScopeFilters();
                default -> List.of();
            };
            selectorFilters.forEach(filter -> attributeIds.add(filter.definition().id()));
            for (String attributeId : attributeIds) {
                MetadataDefinition found = definitionsById.get(attributeId);
                if (!(found instanceof AttributeDefinition attribute)
                        || attribute.subject() != relation.targetVersion().entity()
                        || !attribute.entityVersion().equals(relation.targetVersion())) {
                    throw new QueryException(
                            QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH,
                            "eventSelectorAttribute",
                            "An event-selector Attribute could not be resolved from the pinned Metadata Snapshot.");
                }
                addDependency(new ResolvedDependency.AttributeDependency(
                        canonicalAttribute(attribute), attribute.entityVersion()));
            }
        }

        private void addRelativeWindowAnchor(QueryAst.Metric metric, MetricDefinition metricDefinition) {
            if (!(metric.window() instanceof CanonicalWindow.RelativeToAttributeWindow relative)) {
                return;
            }
            MetadataDefinition found = definitionsById.get(relative.anchorDefinitionId());
            if (!(found instanceof AttributeDefinition anchor)
                    || anchor.subject() != metricDefinition.subject()
                    || !anchor.entityVersion().equals(metricDefinition.entityVersion())
                    || (anchor.dataType() != SemanticDataType.DATE
                    && anchor.dataType() != SemanticDataType.TIMESTAMP)) {
                throw new QueryException(
                        QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH,
                        "relativeWindowAnchor",
                        "A relative Metric window requires a temporal Attribute on the metric subject.");
            }
            addDependency(new ResolvedDependency.AttributeDependency(
                    canonicalAttribute(anchor), anchor.entityVersion()));
        }

        private void addMetricScopeFilter(
                CanonicalMetricFilter filter,
                MetricDefinition metric,
                ResolvedDependency.RelationDependency relation) {
            MetadataDefinition found = definitionsById.get(filter.definition().id());
            if (!(found instanceof AttributeDefinition attribute)
                    || !attributeConsistent(filter, attribute)
                    || !attribute.entityVersion().equals(relation.targetVersion())) {
                throw new QueryException(
                        QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH,
                        "metricScopeFilterResolution",
                        "A Metric scope filter could not be resolved from the exact pinned Metadata Snapshot.");
            }
            MetricFilterDimension dimension = metric.scopeFilterDimensions().stream()
                    .filter(candidate -> candidate.attributeId().equals(attribute.id()))
                    .findFirst()
                    .orElse(null);
            if (dimension == null || !dimension.allowedOperators().contains(filter.operator())) {
                throw unsupported(
                        "metricScopeFilter",
                        "A Metric scope filter is not allowlisted by the resolved Metric definition.");
            }
            recordCurrency(
                    MetadataKind.ATTRIBUTE,
                    filter.definition().dataType(),
                    filter.definition().currencySemantics(),
                    filter.definition().id());
            addDependency(new ResolvedDependency.AttributeDependency(
                    filter.definition(), attribute.entityVersion()));
        }

        private static boolean attributeConsistent(
                CanonicalMetricFilter filter, AttributeDefinition definition) {
            return filter.definition().subject() == definition.subject()
                    && filter.definition().dataType() == definition.dataType()
                    && filter.definition().nullSemantics() == definition.nullSemantics()
                    && filter.definition().currencySemantics() == definition.currencySemantics()
                    && Objects.equals(filter.definition().stringSemantics(), definition.stringSemantics());
        }

        void addExists(QueryAst.Exists exists, String path) {
            ResolvedDependency.RelationDependency relation =
                    relationDependency(requireRelation(exists.relation().id()));
            if (!relation.reference().equals(exists.relation())) {
                throw new QueryException(QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH, "relationResolution",
                        "A relation could not be resolved from the exact pinned Metadata Snapshot.");
            }
            addDependency(relation);
            windows.add(relationWindow(exists, relation.reference(), path));
        }

        List<ResolvedDependency> dependencies() {
            List<ResolvedDependency> ordered = new ArrayList<>(dependencies.values());
            ordered.sort(ResolvedDependency.ORDER);
            return List.copyOf(ordered);
        }

        List<ResolvedWindowBinding> windowBindings() {
            List<ResolvedWindowBinding> ordered = new ArrayList<>(windows);
            ordered.sort(ResolvedWindowBinding.ORDER);
            return List.copyOf(ordered);
        }

        /** One currency binding per referenced Attribute/Metric comparison definition, deterministically ordered. */
        List<CurrencyBinding> currencyBindings() {
            List<CurrencyBinding> ordered = new ArrayList<>(currencyBindings.values());
            ordered.sort(CurrencyBinding.ORDER);
            return List.copyOf(ordered);
        }

        // -------------------------------------------------------------- dependency helpers

        private void addDependency(ResolvedDependency dependency) {
            String key = dependency.kind().name() + "|" + dependency.id();
            ResolvedDependency existing = dependencies.get(key);
            if (existing == null) {
                dependencies.put(key, dependency);
            } else if (!existing.equals(dependency)) {
                throw unsupported("dependencyConflict",
                        "Two references to one definition disagree about its resolved semantics.");
            }
        }

        private void recordCurrency(
                MetadataKind kind, SemanticDataType dataType, CurrencySemantics currencySemantics, String definitionId) {
            if (dataType == SemanticDataType.MONEY) {
                if (currencySemantics == null || !currencySemantics.isExplicitPolicy()) {
                    throw unsupported("monetaryPolicy",
                            "A monetary definition must pin an explicit currency policy.");
                }
            } else if (currencySemantics != null && currencySemantics.isExplicitPolicy()) {
                throw unsupported("nonMonetaryPolicy",
                        "A non-monetary definition must not pin a currency policy.");
            }
            CurrencyBinding binding;
            if (currencySemantics == CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY) {
                binding = new CurrencyBinding(kind, definitionId,
                        CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY, reportingCurrency,
                        CurrencyBinding.RateResolution.DEFERRED);
            } else if (currencySemantics == CurrencySemantics.SINGLE_CURRENCY_ONLY) {
                binding = new CurrencyBinding(kind, definitionId,
                        CurrencyBinding.Action.SINGLE_CURRENCY_ONLY, null,
                        CurrencyBinding.RateResolution.NOT_APPLICABLE);
            } else {
                binding = new CurrencyBinding(kind, definitionId,
                        CurrencyBinding.Action.NO_CONVERSION, null,
                        CurrencyBinding.RateResolution.NOT_APPLICABLE);
            }
            String key = kind.name() + "|" + definitionId;
            CurrencyBinding existing = currencyBindings.get(key);
            if (existing == null) {
                currencyBindings.put(key, binding);
            } else if (!existing.equals(binding)) {
                throw unsupported("currencyConflict",
                        "Two references to one definition disagree about its currency policy.");
            }
        }

        private MetricDefinition requireMetric(QueryAst.Metric metric) {
            return requireMetric(metric.definition());
        }

        private MetricDefinition requireMetric(
                com.company.campaign.segment.canonical.CanonicalMetricDefinition definitionReference) {
            MetadataDefinition found = definitionsById.get(definitionReference.id());
            if (!(found instanceof MetricDefinition definition)
                    || !metricConsistent(definitionReference, definition)) {
                throw new QueryException(QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH, "metricResolution",
                        "A metric could not be resolved from the exact pinned Metadata Snapshot.");
            }
            return definition;
        }

        private RelationDefinition requireRelation(String relationId) {
            MetadataDefinition found = definitionsById.get(relationId);
            if (!(found instanceof RelationDefinition definition)) {
                throw new QueryException(QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH, "relationResolution",
                        "A metric's relation could not be resolved from the exact pinned Metadata Snapshot.");
            }
            return definition;
        }

        private static boolean metricConsistent(QueryAst.Metric metric, MetricDefinition definition) {
            return metricConsistent(metric.definition(), definition);
        }

        private static boolean metricConsistent(
                com.company.campaign.segment.canonical.CanonicalMetricDefinition reference,
                MetricDefinition definition) {
            return reference.aggregation() == definition.aggregation()
                    && reference.resultType() == definition.resultType()
                    && reference.emptySetBehavior() == definition.emptySet().behavior()
                    && Objects.equals(reference.emptySetValue(), definition.emptySet().value());
        }

        // -------------------------------------------------------------- window resolution

        /**
         * Builds a metric window through the shared resolver.
         *
         * <p>A bounded window requires a pinned boundary semantics and a declared event-time capability before
         * the shared resolver runs; a LIFETIME window carries the metric's event-time capability as declared
         * (possibly absent) so the plan always states whether the metric had an event time at all.
         */
        private ResolvedWindowBinding.MetricWindow metricWindow(
                QueryAst.Metric metric, MetricDefinition definition, String path) {
            return metricWindow(metric.definition(), metric.window(), definition, path);
        }

        private ResolvedWindowBinding.MetricWindow metricWindow(
                com.company.campaign.segment.canonical.CanonicalMetricDefinition reference,
                CanonicalWindow canonical,
                MetricDefinition definition,
                String path) {
            WindowBoundarySemantics boundary = reference.windowBoundarySemantics();
            EventTimeSemantics semantics = definition.eventTimeSemantics();
            String field = definition.eventTimeField();
            if (canonical.kind() != CanonicalWindowKind.LIFETIME) {
                if (boundary == WindowBoundarySemantics.NOT_APPLICABLE) {
                    throw unsupported("metricBoundary",
                            "A bounded metric window requires a pinned boundary semantics.");
                }
                if (semantics == null || semantics == EventTimeSemantics.NOT_APPLICABLE
                        || field == null || field.isBlank()) {
                    throw unsupported("metricEventTime",
                            "A bounded metric window requires a declared event-time capability.");
                }
            }
            return PlanWindows.metricWindow(path, canonical, boundary, semantics, field, asOf, dataWatermark);
        }

        private ResolvedWindowBinding.RelationWindow relationWindow(
                QueryAst.Exists exists, CanonicalRelationReference reference, String path) {
            CanonicalWindow canonical = exists.window();
            EventTimeSemantics semantics = reference.eventTimeSemantics();
            String field = reference.eventTimeField();
            if (canonical.kind() != CanonicalWindowKind.LIFETIME
                    && (semantics == null || semantics == EventTimeSemantics.NOT_APPLICABLE
                            || field == null || field.isBlank())) {
                throw unsupported("relationEventTime",
                        "A bounded relation window requires a declared relation event-time capability.");
            }
            return PlanWindows.relationWindow(path, canonical, semantics, field, asOf, dataWatermark);
        }
    }

    // ------------------------------------------------------------------ shared mapping

    /**
     * Maps a T04 Relation definition to the canonical relation reference the T07 canonicalizer would emit.
     *
     * <p>A metric's relation never appears as an EXISTS node, so its reference has to be rebuilt from the
     * exact pinned Snapshot; this is the same projection the accepted canonicalizer performs.
     */
    /** Builds the exact relation dependency (canonical reference plus both entity schema versions). */
    private static ResolvedDependency.RelationDependency relationDependency(RelationDefinition definition) {
        return new ResolvedDependency.RelationDependency(
                relationReference(definition), definition.sourceVersion(), definition.targetVersion());
    }

    private static CanonicalRelationReference relationReference(RelationDefinition definition) {
        RelationEventTimeCapability capability = definition.eventTimeCapability();
        return new CanonicalRelationReference(
                definition.id(),
                definition.sourceEntity(),
                definition.targetEntity(),
                definition.cardinality(),
                definition.scope().joinKind(),
                definition.scope().sourceJoinField(),
                definition.scope().targetJoinField(),
                definition.targetNullSemantics(),
                definition.emptyRelationSemantics(),
                capability == null ? null : capability.semantics(),
                capability == null ? null : capability.eventTimeField());
    }

    private static CanonicalDefinition canonicalAttribute(AttributeDefinition definition) {
        return new CanonicalDefinition(
                definition.kind(), definition.id(), definition.subject(), definition.dataType(),
                definition.nullSemantics(), definition.currencySemantics(), definition.stringSemantics());
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, invariant,
                    "The Semantic Query Planner requires a non-null input.");
        }
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_VALIDATION_RESULT_INVALID, invariant, message);
    }

    private static QueryException unsupported(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_UNSUPPORTED_SEMANTIC_COMBINATION, invariant, message);
    }
}
