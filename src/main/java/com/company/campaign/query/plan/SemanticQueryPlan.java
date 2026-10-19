package com.company.campaign.query.plan;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.canonical.CanonicalizerVersions;
import com.company.campaign.segment.canonical.CanonicalWindow;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.ast.QueryAstCodec;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Immutable, hash-bound, non-forgeable Semantic Query Plan.
 *
 * <p>It retains the complete typed Query AST, the exact canonical AST hash and Metadata binding, every
 * resolved Attribute/Metric/Relation dependency with its exact entity versions and event-time facts, the
 * concrete time boundaries derived from the trusted {@code asOf} and timezone, one currency binding per
 * referenced comparison definition, the mandatory DataScope requirement and the full trusted context binding.
 * It contains no SQL text and makes no table or column assumption.
 *
 * <p>It is a trusted compilation artifact, not a container. It has no public constructor, no public factory,
 * no builder and no deserializer, and it cannot be subclassed; the planner that lives in this package is the
 * only producer. Construction re-proves the plan hash and every internal coherence invariant before accepting
 * a self-consistent hash: the DataScope predicate must equal the trusted scope, the predicate must project
 * back to the exact canonical root, the AST references must match the resolved dependencies together with
 * each metric's exact transitive relation and both entity schema versions, every Metric/EXISTS node must own
 * exactly one window binding rebuilt from the shared resolver at its structural path, and every referenced
 * comparison definition must own exactly one currency binding whose action agrees with its canonical
 * semantics. Every collection is defensively copied, so a caller can read but never mutate.
 */
public final class SemanticQueryPlan {

    private static final CanonicalSegmentCodec CANONICAL_CODEC = new CanonicalSegmentCodec();

    private final String planFormatVersion;
    private final String plannerVersion;
    private final String compilerVersion;
    private final String queryAstVersion;
    private final SchemaVersion dslSchemaVersion;
    private final String canonicalizerVersion;
    private final SubjectKind subject;
    private final QueryAst predicateRoot;
    private final ContentHash canonicalAstHash;
    private final MetadataBinding metadataBinding;
    private final List<ResolvedDependency> dependencies;
    private final List<AudienceVersionDependency> audienceDependencies;
    private final List<ResolvedWindowBinding> windowBindings;
    private final List<CurrencyBinding> currencyBindings;
    private final DataScopePredicate dataScopePredicate;
    private final TrustedPlanContext trustedContext;
    private final ContentHash planHash;

    /**
     * The one construction path; package-private so only the T08 planner and its in-package adversarial tests
     * can reach it. Every hash, ordering, coherence and consistency invariant is re-proved here.
     */
    SemanticQueryPlan(
            String planFormatVersion,
            String plannerVersion,
            String compilerVersion,
            String queryAstVersion,
            SchemaVersion dslSchemaVersion,
            String canonicalizerVersion,
            SubjectKind subject,
            QueryAst predicateRoot,
            ContentHash canonicalAstHash,
            MetadataBinding metadataBinding,
            List<ResolvedDependency> dependencies,
            List<AudienceVersionDependency> audienceDependencies,
            List<ResolvedWindowBinding> windowBindings,
            List<CurrencyBinding> currencyBindings,
            DataScopePredicate dataScopePredicate,
            TrustedPlanContext trustedContext,
            ContentHash planHash) {
        this.planFormatVersion = requirePlanVersion(planFormatVersion, PlanVersions.PLAN_FORMAT_VERSION, "planFormat");
        this.plannerVersion = requirePlanVersion(plannerVersion, PlanVersions.PLANNER_VERSION, "plannerVersion");
        this.compilerVersion = requirePlanVersion(compilerVersion, PlanVersions.COMPILER_VERSION, "compilerVersion");
        this.queryAstVersion = requirePlanVersion(queryAstVersion, PlanVersions.QUERY_AST_VERSION, "queryAstVersion");
        if (!SegmentDslSchemaVersions.isSupported(dslSchemaVersion)) {
            throw inconsistent("dslSchemaVersion", "A plan must carry the one supported Segment DSL schema version.");
        }
        if (!CanonicalizerVersions.CANONICALIZER_VERSION.equals(canonicalizerVersion)) {
            throw inconsistent("canonicalizerVersion",
                    "A plan must carry the one supported canonicalizer version.");
        }
        requirePresent(subject, "subject");
        requirePresent(predicateRoot, "predicateRoot");
        requirePresent(canonicalAstHash, "canonicalAstHash");
        requirePresent(metadataBinding, "metadataBinding");
        requirePresent(dataScopePredicate, "dataScopePredicate");
        requirePresent(trustedContext, "trustedContext");
        requirePresent(planHash, "planHash");

        List<ResolvedDependency> rawDependencies = dependencies;
        List<AudienceVersionDependency> rawAudienceDependencies = audienceDependencies;
        List<ResolvedWindowBinding> rawWindowBindings = windowBindings;
        List<CurrencyBinding> rawCurrencyBindings = currencyBindings;
        dependencies = guarded("dependencies", () -> canonicalDependencies(rawDependencies));
        audienceDependencies = guarded("audienceDependencies",
                () -> AudienceVersionDependency.canonical(rawAudienceDependencies));
        windowBindings = guarded("windowBindings", () -> canonicalWindowBindings(rawWindowBindings));
        currencyBindings = guarded("currencyBindings", () -> canonicalCurrencyBindings(rawCurrencyBindings));

        if (dataScopePredicate.subject() != subject) {
            throw inconsistent("dataScopeSubject",
                    "A plan's DataScope requirement must target the plan's root subject.");
        }
        requireDataScopeShape(dataScopePredicate, audienceDependencies);

        this.dslSchemaVersion = dslSchemaVersion;
        this.canonicalizerVersion = canonicalizerVersion;
        this.subject = subject;
        this.predicateRoot = predicateRoot;
        this.canonicalAstHash = canonicalAstHash;
        this.metadataBinding = metadataBinding;
        this.dependencies = dependencies;
        this.audienceDependencies = audienceDependencies;
        this.windowBindings = windowBindings;
        this.currencyBindings = currencyBindings;
        this.dataScopePredicate = dataScopePredicate;
        this.trustedContext = trustedContext;

        // An unexpected internal failure while re-proving coherence or hashing must surface as the one bounded
        // plan-inconsistency code; a genuine QueryException always keeps its own stable code.
        guarded("planCoherence", () -> {
            requireCoherence();
            return null;
        });

        ContentHash expected = guarded("planHash", () -> SemanticQueryPlanCodec.hashMaterial(material()));
        if (!planHash.equals(expected)) {
            throw inconsistent("planHash", "The declared plan hash is not the canonical hash of the plan.");
        }
        this.planHash = planHash;
    }

    /** The plan document format version. */
    public String planFormatVersion() {
        return planFormatVersion;
    }

    /** The planner version that produced this plan. */
    public String plannerVersion() {
        return plannerVersion;
    }

    /** The query compiler version that produced this plan. */
    public String compilerVersion() {
        return compilerVersion;
    }

    /** The Query AST discriminator pin; the AST format and compiler share this one authoritative version. */
    public String queryAstVersion() {
        return queryAstVersion;
    }

    /** The Segment DSL schema version the source document declared. */
    public SchemaVersion dslSchemaVersion() {
        return dslSchemaVersion;
    }

    /** The canonicalizer version that produced the source canonical AST. */
    public String canonicalizerVersion() {
        return canonicalizerVersion;
    }

    /** The root subject. */
    public SubjectKind subject() {
        return subject;
    }

    /** The complete typed Query AST predicate root; never flattened to a filter list. */
    public QueryAst predicateRoot() {
        return predicateRoot;
    }

    /** The canonical AST content hash of the accepted T07 artifact. */
    public ContentHash canonicalAstHash() {
        return canonicalAstHash;
    }

    /** The exact Metadata binding the plan is pinned to (snapshot ID, catalog version, schema, content hash). */
    public MetadataBinding metadataBinding() {
        return metadataBinding;
    }

    /** The exact pinned Metadata Snapshot ID. */
    public String metadataSnapshotId() {
        return metadataBinding.snapshotId();
    }

    /** The exact pinned Metadata catalog/snapshot version. */
    public String metadataCatalogVersion() {
        return metadataBinding.snapshotVersion();
    }

    /** The pinned Metadata schema version. */
    public SchemaVersion metadataSchemaVersion() {
        return metadataBinding.metadataSchemaVersion();
    }

    /** The pinned Metadata content hash. */
    public ContentHash metadataContentHash() {
        return metadataBinding.contentHash();
    }

    /** Resolved Attribute, Metric and Relation dependencies in deterministic, duplicate-free order. */
    public List<ResolvedDependency> dependencies() {
        return dependencies;
    }

    /**
     * The frozen Existing Audience Reference dependencies in deterministic, duplicate-free order.
     *
     * <p>Empty for a document with no reference, which is what keeps such a plan's material unchanged. Each
     * entry freezes the exact immutable pin, the catalog hashes it was published with, the trusted effective
     * DataScope, the retention and availability evidence observed at plan time and its complexity contribution.
     */
    public List<AudienceVersionDependency> audienceDependencies() {
        return audienceDependencies;
    }

    /** True when this plan depends on at least one immutable Audience Version. */
    public boolean hasAudienceReferences() {
        return !audienceDependencies.isEmpty();
    }

    /** Concrete time windows in strictly ascending structural-path order. */
    public List<ResolvedWindowBinding> windowBindings() {
        return windowBindings;
    }

    /**
     * Exactly one currency binding per referenced Attribute/Metric comparison definition, in deterministic
     * kind-then-ID order. A mixed-policy segment keeps its distinct policies; a relation has no binding.
     */
    public List<CurrencyBinding> currencyBindings() {
        return currencyBindings;
    }

    /** The mandatory DataScope requirement, kept separate from the user predicate tree. */
    public DataScopePredicate dataScopePredicate() {
        return dataScopePredicate;
    }

    /** The trusted context binding (tenant, program, membership, scope, asOf, timezone, currency, watermark). */
    public TrustedPlanContext trustedContext() {
        return trustedContext;
    }

    /** The canonical plan hash, computed from strict canonical bytes. */
    public ContentHash planHash() {
        return planHash;
    }

    /** The exact material this plan's {@link #planHash()} covers. */
    Material material() {
        return new Material(
                planFormatVersion,
                plannerVersion,
                compilerVersion,
                queryAstVersion,
                dslSchemaVersion,
                canonicalizerVersion,
                subject,
                predicateRoot,
                canonicalAstHash,
                metadataBinding,
                dependencies,
                audienceDependencies,
                windowBindings,
                currencyBindings,
                dataScopePredicate,
                trustedContext);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SemanticQueryPlan plan)) {
            return false;
        }
        return Objects.equals(planFormatVersion, plan.planFormatVersion)
                && Objects.equals(plannerVersion, plan.plannerVersion)
                && Objects.equals(compilerVersion, plan.compilerVersion)
                && Objects.equals(queryAstVersion, plan.queryAstVersion)
                && Objects.equals(dslSchemaVersion, plan.dslSchemaVersion)
                && Objects.equals(canonicalizerVersion, plan.canonicalizerVersion)
                && subject == plan.subject
                && Objects.equals(predicateRoot, plan.predicateRoot)
                && Objects.equals(canonicalAstHash, plan.canonicalAstHash)
                && Objects.equals(metadataBinding, plan.metadataBinding)
                && Objects.equals(dependencies, plan.dependencies)
                && Objects.equals(audienceDependencies, plan.audienceDependencies)
                && Objects.equals(windowBindings, plan.windowBindings)
                && Objects.equals(currencyBindings, plan.currencyBindings)
                && Objects.equals(dataScopePredicate, plan.dataScopePredicate)
                && Objects.equals(trustedContext, plan.trustedContext)
                && Objects.equals(planHash, plan.planHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                planFormatVersion, plannerVersion, compilerVersion, queryAstVersion, dslSchemaVersion,
                canonicalizerVersion, subject, predicateRoot, canonicalAstHash, metadataBinding, dependencies,
                audienceDependencies, windowBindings, currencyBindings, dataScopePredicate, trustedContext,
                planHash);
    }

    @Override
    public String toString() {
        return "SemanticQueryPlan["
                + "plannerVersion=" + plannerVersion
                + ", subject=" + subject
                + ", metadataSnapshotId=" + metadataBinding.snapshotId()
                + ", dependencyCount=" + dependencies.size()
                + ", audienceDependencyCount=" + audienceDependencies.size()
                + ", windowBindingCount=" + windowBindings.size()
                + ", currencyBindingCount=" + currencyBindings.size()
                + ", planHash=" + planHash
                + ']';
    }

    // ------------------------------------------------------------------ coherence

    /**
     * Rejects every internally contradictory material before the declared hash is accepted.
     *
     * <p>A valid declared hash is only self-consistency; this re-proves that the DataScope predicate names the
     * trusted scope, that the predicate projects back to the exact canonical root, that the AST references and
     * their resolved dependencies agree (including each metric's exact transitive relation projection and both
     * relation entity versions), that every Metric and EXISTS node owns exactly one window binding rebuilt
     * from the shared resolver at its structural path, and that every referenced comparison definition owns
     * exactly one currency binding matching its canonical semantics.
     */
    private void requireCoherence() {
        if (!dataScopePredicate.isEffectiveIntersection()
                && !Objects.equals(dataScopePredicate.dataScopeId(), trustedContext.dataScopeId())) {
            throw inconsistent("dataScopeId",
                    "A plan's DataScope requirement must equal its trusted context DataScope.");
        }

        CanonicalSegment reconstructed;
        try {
            reconstructed = new CanonicalSegment(
                    canonicalizerVersion,
                    dslSchemaVersion,
                    subject,
                    metadataBinding,
                    PlanStructure.canonicalProjection(predicateRoot));
        } catch (QueryException exception) {
            // A genuine QueryException already carries a stable bounded code; never relabel it.
            throw exception;
        } catch (RuntimeException exception) {
            throw inconsistent("canonicalProjection",
                    "A plan's predicate cannot be projected onto a canonical root.");
        }
        if (!CANONICAL_CODEC.hash(reconstructed).equals(canonicalAstHash)) {
            throw inconsistent("canonicalAstHash",
                    "A plan's canonical AST hash must be the canonical hash of its own predicate.");
        }
        // The AST must serialize exactly as the canonical predicate: a reordered commutative group can share
        // the canonical root hash while carrying different AST bytes, which is still contradictory material.
        if (!Arrays.equals(new QueryAstCodec().write(predicateRoot), CANONICAL_CODEC.writeNode(reconstructed.predicate()))) {
            throw inconsistent("canonicalProjection",
                    "A plan's predicate must serialize exactly as its canonical root.");
        }

        PlanStructure.Sites sites = PlanStructure.collect(predicateRoot, subject);

        requireReferenceCoherence(sites);

        Map<String, ResolvedDependency.AttributeDependency> attributes = new LinkedHashMap<>();
        Map<String, ResolvedDependency.MetricDependency> metrics = new LinkedHashMap<>();
        Map<String, ResolvedDependency.RelationDependency> relations = new LinkedHashMap<>();
        for (ResolvedDependency dependency : dependencies) {
            switch (dependency) {
                case ResolvedDependency.AttributeDependency attribute ->
                        attributes.putIfAbsent(attribute.id(), attribute);
                case ResolvedDependency.MetricDependency metric -> metrics.putIfAbsent(metric.id(), metric);
                case ResolvedDependency.RelationDependency relation ->
                        relations.putIfAbsent(relation.id(), relation);
            }
        }

        Set<String> referencedAttributes = new HashSet<>();
        for (PlanStructure.AttributeSite site : sites.attributes()) {
            QueryAst.Attribute node = site.node();
            referencedAttributes.add(node.definition().id());
            ResolvedDependency.AttributeDependency dependency = attributes.get(node.definition().id());
            if (dependency == null || !dependency.definition().equals(node.definition())) {
                throw inconsistent("attributeDependency",
                        "Every attribute reference must have its exact resolved dependency.");
            }
        }
        for (PlanStructure.MetricSite site : sites.metrics()) {
            ResolvedDependency.MetricDependency metricDependency = metrics.get(site.node().definition().id());
            if (site.node().window() instanceof CanonicalWindow.RelativeToAttributeWindow relative) {
                referencedAttributes.add(relative.anchorDefinitionId());
                ResolvedDependency.AttributeDependency dependency = attributes.get(relative.anchorDefinitionId());
                if (dependency == null || metricDependency == null
                        || dependency.definition().subject() != site.node().definition().subject()
                        || !dependency.entityVersion().equals(metricDependency.entityVersion())
                        || (dependency.definition().dataType() != SemanticDataType.DATE
                        && dependency.definition().dataType() != SemanticDataType.TIMESTAMP)) {
                    throw inconsistent("relativeWindowAnchor",
                            "Every relative Metric window requires its exact temporal Attribute dependency.");
                }
            }
            for (com.company.campaign.segment.canonical.CanonicalMetricFilter filter
                    : site.node().scopeFilters()) {
                referencedAttributes.add(filter.definition().id());
                ResolvedDependency.AttributeDependency dependency = attributes.get(filter.definition().id());
                if (dependency == null || !dependency.definition().equals(filter.definition())) {
                    throw inconsistent("metricScopeFilterDependency",
                            "Every Metric scope filter must have its exact Attribute dependency.");
                }
            }
            String selectorId = null;
            List<com.company.campaign.segment.canonical.CanonicalMetricFilter> selectorFilters = List.of();
            if (site.node().window() instanceof CanonicalWindow.SelectedEventWindow selected) {
                selectorId = selected.selectorId();
                selectorFilters = selected.selectorScopeFilters();
            } else if (site.node().window() instanceof CanonicalWindow.RelativeToEventWindow relative) {
                selectorId = relative.selectorId();
                selectorFilters = relative.selectorScopeFilters();
            }
            if (selectorId != null) {
                if (metricDependency == null || metricDependency.eventSelector() == null
                        || !selectorId.equals(metricDependency.eventSelector().id())) {
                    throw inconsistent("eventSelectorDependency",
                            "Every event window requires its exact Metadata selector dependency.");
                }
                com.company.campaign.metadata.domain.EventSelectorBinding selectorBinding =
                        metricDependency.eventSelector().bindingFor(metricDependency.relationId());
                if (selectorBinding == null) {
                    throw inconsistent("eventSelectorBinding",
                            "An event selector must support the Metric relation.");
                }
                java.util.LinkedHashSet<String> selectorAttributeIds = new java.util.LinkedHashSet<>();
                selectorAttributeIds.add(selectorBinding.identityAttributeId());
                selectorBinding.orderBy().forEach(order -> selectorAttributeIds.add(order.attributeId()));
                selectorBinding.fixedFilter().entries().forEach(entry -> selectorAttributeIds.add(entry.key()));
                selectorFilters.forEach(filter -> selectorAttributeIds.add(filter.definition().id()));
                for (String attributeId : selectorAttributeIds) {
                    referencedAttributes.add(attributeId);
                    if (!attributes.containsKey(attributeId)) {
                        throw inconsistent("eventSelectorAttributeDependency",
                                "Every event selector Attribute requires its exact dependency.");
                    }
                }
            } else if (metricDependency != null && metricDependency.eventSelector() != null) {
                throw inconsistent("eventSelectorDependency",
                        "A plan must not carry an unrelated event selector dependency.");
            }
        }
        for (PlanStructure.ExpressionMetricSite site : sites.expressionMetrics()) {
            if (site.window() instanceof CanonicalWindow.RelativeToAttributeWindow
                    || site.window() instanceof CanonicalWindow.SelectedEventWindow
                    || site.window() instanceof CanonicalWindow.RelativeToEventWindow) {
                throw inconsistent(
                        "metricExpressionWindow",
                        "A Metric expression must use one common rolling, fixed or lifetime window.");
            }
        }
        if (!attributes.keySet().equals(referencedAttributes)) {
            throw inconsistent("attributeDependency", "A plan must not carry an unrelated attribute dependency.");
        }

        Set<String> referencedMetrics = new HashSet<>();
        for (PlanStructure.MetricSite site : sites.metrics()) {
            QueryAst.Metric node = site.node();
            referencedMetrics.add(node.definition().id());
            ResolvedDependency.MetricDependency dependency = metrics.get(node.definition().id());
            if (dependency == null || !dependency.definition().equals(node.definition())) {
                throw inconsistent("metricDependency",
                        "Every metric reference must have its exact resolved dependency.");
            }
        }
        for (PlanStructure.ExpressionMetricSite site : sites.expressionMetrics()) {
            referencedMetrics.add(site.definition().id());
            ResolvedDependency.MetricDependency dependency = metrics.get(site.definition().id());
            if (dependency == null || !dependency.definition().equals(site.definition())) {
                throw inconsistent(
                        "metricExpressionDependency",
                        "Every Metric expression reference must have its exact resolved dependency.");
            }
        }
        if (!metrics.keySet().equals(referencedMetrics)) {
            throw inconsistent("metricDependency", "A plan must not carry an unrelated metric dependency.");
        }

        // An EXISTS relation must equal its exact resolved canonical reference, not merely share its ID; a
        // metric must embed its exact transitive relation projection and that projection must equal the
        // matching RELATION dependency completely, entity versions included.
        Set<String> expectedRelations = new HashSet<>();
        for (PlanStructure.ExistsSite site : sites.exists()) {
            String relationId = site.node().relation().id();
            expectedRelations.add(relationId);
            ResolvedDependency.RelationDependency dependency = relations.get(relationId);
            if (dependency == null || !dependency.reference().equals(site.node().relation())) {
                throw inconsistent("relationDependency",
                        "Every EXISTS relation must equal its exact resolved relation reference.");
            }
        }
        for (ResolvedDependency.MetricDependency metric : metrics.values()) {
            ResolvedDependency.RelationDependency transitive = relations.get(metric.relationId());
            if (transitive == null || !transitive.equals(metric.relation())) {
                throw inconsistent("metricRelationDependency",
                        "Every metric must embed the exact relation dependency it references.");
            }
            expectedRelations.add(metric.relationId());
        }
        if (!relations.keySet().equals(expectedRelations)) {
            throw inconsistent("relationDependency", "A plan must not carry an unrelated relation dependency.");
        }

        // Rebuild every expected binding from the AST, the resolved dependency and the trusted context through
        // the one shared resolver, then require full equality: kind, path, canonical window, concrete start/end
        // or lifetime pins, inclusivity and event-time field/semantics.
        Map<String, ResolvedWindowBinding> windowsByPath = new LinkedHashMap<>();
        for (ResolvedWindowBinding binding : windowBindings) {
            windowsByPath.put(binding.path(), binding);
        }
        Set<String> expectedPaths = new HashSet<>();
        for (PlanStructure.MetricSite site : sites.metrics()) {
            expectedPaths.add(site.path());
            ResolvedDependency.MetricDependency dependency = metrics.get(site.node().definition().id());
            ResolvedWindowBinding expected = PlanWindows.metricWindow(
                    site.path(),
                    site.node().window(),
                    dependency.definition().windowBoundarySemantics(),
                    dependency.eventTimeSemantics(),
                    dependency.eventTimeField(),
                    trustedContext.asOf(),
                    trustedContext.dataWatermark());
            if (!expected.equals(windowsByPath.get(site.path()))) {
                throw inconsistent("metricWindowBinding",
                        "Every metric node requires its exactly reconstructed window binding at its path.");
            }
        }
        for (PlanStructure.ExpressionMetricSite site : sites.expressionMetrics()) {
            expectedPaths.add(site.path());
            ResolvedDependency.MetricDependency dependency = metrics.get(site.definition().id());
            ResolvedWindowBinding expected = PlanWindows.metricWindow(
                    site.path(),
                    site.window(),
                    dependency.definition().windowBoundarySemantics(),
                    dependency.eventTimeSemantics(),
                    dependency.eventTimeField(),
                    trustedContext.asOf(),
                    trustedContext.dataWatermark());
            if (!expected.equals(windowsByPath.get(site.path()))) {
                throw inconsistent(
                        "metricExpressionWindowBinding",
                        "Every Metric expression leaf requires its reconstructed window binding.");
            }
        }
        for (PlanStructure.ExistsSite site : sites.exists()) {
            expectedPaths.add(site.path());
            ResolvedDependency.RelationDependency dependency = relations.get(site.node().relation().id());
            ResolvedWindowBinding expected = PlanWindows.relationWindow(
                    site.path(),
                    site.node().window(),
                    dependency.reference().eventTimeSemantics(),
                    dependency.reference().eventTimeField(),
                    trustedContext.asOf(),
                    trustedContext.dataWatermark());
            if (!expected.equals(windowsByPath.get(site.path()))) {
                throw inconsistent("relationWindowBinding",
                        "Every EXISTS node requires its exactly reconstructed window binding at its path.");
            }
        }
        if (!windowsByPath.keySet().equals(expectedPaths)) {
            throw inconsistent("windowPaths", "A plan must not bind a window to a path that owns no node.");
        }

        Map<String, CurrencyBinding> currencyByKey = new LinkedHashMap<>();
        for (CurrencyBinding binding : currencyBindings) {
            currencyByKey.put(currencyKey(binding.kind(), binding.definitionId()), binding);
        }
        Set<String> expectedCurrencyKeys = new HashSet<>();
        for (PlanStructure.AttributeSite site : sites.attributes()) {
            requireCurrencyBinding(currencyByKey, expectedCurrencyKeys, MetadataKind.ATTRIBUTE,
                    site.node().definition().id(), site.node().definition().dataType(),
                    site.node().definition().currencySemantics());
        }
        for (PlanStructure.MetricSite site : sites.metrics()) {
            requireCurrencyBinding(currencyByKey, expectedCurrencyKeys, MetadataKind.METRIC,
                    site.node().definition().id(), site.node().definition().resultType(),
                    site.node().definition().currencySemantics());
            for (com.company.campaign.segment.canonical.CanonicalMetricFilter filter
                    : site.node().scopeFilters()) {
                requireCurrencyBinding(currencyByKey, expectedCurrencyKeys, MetadataKind.ATTRIBUTE,
                        filter.definition().id(), filter.definition().dataType(),
                        filter.definition().currencySemantics());
            }
        }
        for (PlanStructure.ExpressionMetricSite site : sites.expressionMetrics()) {
            requireCurrencyBinding(
                    currencyByKey,
                    expectedCurrencyKeys,
                    MetadataKind.METRIC,
                    site.definition().id(),
                    site.definition().resultType(),
                    site.definition().currencySemantics());
        }
        if (!currencyByKey.keySet().equals(expectedCurrencyKeys)) {
            throw inconsistent("currencyBindings", "A plan must not carry an unrelated currency binding.");
        }
    }

    /**
     * Rejects a DataScope requirement that does not match the plan's audience dependency shape.
     *
     * <p>A plan with no reference must require exactly the caller's own scope: binding anything else would let
     * a plan without a reference claim a narrower scope than the caller proved. A plan with at least one
     * reference must require the host-computed effective intersection and must carry its exact content hash,
     * because the caller's own scope alone is strictly wider than what the referenced versions allow.
     */
    private static void requireDataScopeShape(
            DataScopePredicate predicate, List<AudienceVersionDependency> audienceDependencies) {
        if (audienceDependencies.isEmpty()) {
            if (predicate.isEffectiveIntersection()) {
                throw inconsistent("dataScopeRequirement",
                        "A plan without an audience dependency must require the caller's own DataScope.");
            }
            return;
        }
        if (!predicate.isEffectiveIntersection()) {
            throw inconsistent("dataScopeRequirement",
                    "A plan with an audience dependency must require the effective DataScope intersection.");
        }
    }

    /**
     * Re-proves that the AST's reference sites and the frozen audience dependencies describe the same graph.
     *
     * <p>Every site must have its exact frozen dependency, so a reference can never be evaluated without
     * frozen, authorized material behind it. And the dependency set must be exactly the transitive closure of
     * the site pins over the frozen direct-dependency lists: a version reachable only through another version
     * is legitimate, but a dependency that no site reaches — directly or transitively — would silently narrow
     * the plan's reach for nothing and is contradictory material.
     *
     * <p>Each site's subject is re-derived from the tree shape by the shared walk, so a reference beneath an
     * {@code EXISTS} is judged against the relation's target entity exactly as the resolver judged it.
     */
    private void requireReferenceCoherence(PlanStructure.Sites sites) {
        Map<String, AudienceVersionDependency> byPin = new LinkedHashMap<>();
        for (AudienceVersionDependency dependency : audienceDependencies) {
            byPin.putIfAbsent(dependency.reference().pinKey(), dependency);
        }
        Deque<String> pending = new ArrayDeque<>();
        for (PlanStructure.ReferenceSite site : sites.references()) {
            com.company.campaign.segment.canonical.CanonicalAudienceVersionRef pin = site.node().reference();
            String key = referenceKey(pin.audienceId(), pin.versionId(), pin.versionHash().value());
            AudienceVersionDependency dependency = byPin.get(key);
            if (dependency == null) {
                throw inconsistent("audienceDependency",
                        "Every audience reference must have its exact frozen dependency.");
            }
            if (dependency.subject() != site.scopeSubject()) {
                throw inconsistent("audienceDependencySubject",
                        "An audience dependency's subject must be the subject its site is evaluated in.");
            }
            pending.add(key);
        }
        Set<String> reachable = new HashSet<>();
        while (!pending.isEmpty()) {
            String key = pending.poll();
            if (!reachable.add(key)) {
                continue;
            }
            AudienceVersionDependency dependency = byPin.get(key);
            if (dependency == null) {
                throw inconsistent("audienceDependency",
                        "A frozen direct dependency must itself be frozen in the plan.");
            }
            for (com.company.campaign.segment.reference.AudienceVersionRef pin : dependency.directDependencies()) {
                pending.add(referenceKey(pin.audienceId(), pin.versionId(), pin.versionHash().value()));
            }
        }
        if (!byPin.keySet().equals(reachable)) {
            throw inconsistent("audienceDependency",
                    "A plan must not freeze an audience dependency that no reference reaches.");
        }
    }

    private static String referenceKey(String audienceId, String versionId, String versionHash) {
        return audienceId + "/" + versionId + "@" + versionHash;
    }

    private void requireCurrencyBinding(
            Map<String, CurrencyBinding> currencyByKey,
            Set<String> expectedKeys,
            MetadataKind kind,
            String definitionId,
            SemanticDataType dataType,
            CurrencySemantics semantics) {
        String key = currencyKey(kind, definitionId);
        expectedKeys.add(key);
        CurrencyBinding binding = currencyByKey.get(key);
        if (binding == null) {
            throw inconsistent("currencyBinding",
                    "Every referenced comparison definition requires exactly one currency binding.");
        }
        if (dataType == SemanticDataType.MONEY && semantics == null) {
            throw inconsistent("currencyBindingAction",
                    "A monetary definition requires an explicit currency policy binding.");
        }
        CurrencyBinding.Action expectedAction;
        if (semantics == CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY) {
            expectedAction = CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY;
        } else if (semantics == CurrencySemantics.SINGLE_CURRENCY_ONLY) {
            expectedAction = CurrencyBinding.Action.SINGLE_CURRENCY_ONLY;
        } else {
            expectedAction = CurrencyBinding.Action.NO_CONVERSION;
        }
        if (binding.action() != expectedAction) {
            throw inconsistent("currencyBindingAction",
                    "A currency binding's action must agree with its definition's canonical semantics.");
        }
        if (expectedAction == CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY
                && (!Objects.equals(binding.targetCurrency(), trustedContext.reportingCurrency())
                        || binding.rateResolution() != CurrencyBinding.RateResolution.DEFERRED)) {
            throw inconsistent("currencyBindingTarget",
                    "A conversion binding must target the trusted reporting currency and defer its rate.");
        }
        if (expectedAction != CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY
                && (binding.targetCurrency() != null
                        || binding.rateResolution() != CurrencyBinding.RateResolution.NOT_APPLICABLE)) {
            throw inconsistent("currencyBindingTarget",
                    "A non-conversion binding must not name a target or defer a rate.");
        }
    }

    private static String currencyKey(MetadataKind kind, String definitionId) {
        return kind.name() + "|" + definitionId;
    }

    // ------------------------------------------------------------------ normalization

    private static List<ResolvedDependency> canonicalDependencies(List<ResolvedDependency> dependencies) {
        if (dependencies == null) {
            throw inconsistent("dependencies", "A plan requires its resolved dependency list.");
        }
        List<ResolvedDependency> copy = new ArrayList<>(dependencies.size());
        String previous = null;
        Set<String> keys = new HashSet<>();
        for (ResolvedDependency dependency : dependencies) {
            if (dependency == null) {
                throw inconsistent("dependencies", "A plan must not carry a null dependency.");
            }
            String key = dependency.kind().name() + "|" + dependency.id();
            if (!keys.add(key)) {
                throw inconsistent("duplicateDependency", "A plan must not repeat one resolved dependency.");
            }
            if (previous != null && compareKeys(previous, key) >= 0) {
                throw inconsistent("dependencyOrder",
                        "A plan's dependencies must be in deterministic kind-then-ID order.");
            }
            previous = key;
            copy.add(dependency);
        }
        return List.copyOf(copy);
    }

    private static int compareKeys(String left, String right) {
        // Kind rank is the metadata kind ordinal, encoded as its name; ordering here mirrors ResolvedDependency.ORDER.
        String[] leftParts = left.split("\\|", 2);
        String[] rightParts = right.split("\\|", 2);
        int kind = Integer.compare(kindRank(leftParts[0]), kindRank(rightParts[0]));
        return kind != 0 ? kind : leftParts[1].compareTo(rightParts[1]);
    }

    private static int kindRank(String kindName) {
        return switch (kindName) {
            case "ATTRIBUTE" -> 0;
            case "METRIC" -> 1;
            case "RELATION" -> 2;
            default -> 3;
        };
    }

    private static List<ResolvedWindowBinding> canonicalWindowBindings(List<ResolvedWindowBinding> bindings) {
        if (bindings == null) {
            throw inconsistent("windowBindings", "A plan requires its resolved window binding list.");
        }
        List<ResolvedWindowBinding> copy = new ArrayList<>(bindings.size());
        Set<String> paths = new HashSet<>();
        ResolvedWindowBinding previous = null;
        for (ResolvedWindowBinding binding : bindings) {
            if (binding == null) {
                throw inconsistent("windowBindings", "A plan must not carry a null window binding.");
            }
            if (!paths.add(binding.path())) {
                throw inconsistent("windowPath", "A plan must not bind two windows to one structural path.");
            }
            if (previous != null && ResolvedWindowBinding.ORDER.compare(previous, binding) >= 0) {
                throw inconsistent("windowPathOrder",
                        "A plan's window bindings must be in strictly ascending structural-path order.");
            }
            previous = binding;
            copy.add(binding);
        }
        return List.copyOf(copy);
    }

    private static List<CurrencyBinding> canonicalCurrencyBindings(List<CurrencyBinding> bindings) {
        if (bindings == null) {
            throw inconsistent("currencyBindings", "A plan requires its currency binding list.");
        }
        List<CurrencyBinding> copy = new ArrayList<>(bindings.size());
        Set<String> keys = new HashSet<>();
        CurrencyBinding previous = null;
        for (CurrencyBinding binding : bindings) {
            if (binding == null) {
                throw inconsistent("currencyBindings", "A plan must not carry a null currency binding.");
            }
            String key = currencyKey(binding.kind(), binding.definitionId());
            if (!keys.add(key)) {
                throw inconsistent("duplicateCurrencyBinding",
                        "A plan must not repeat one definition's currency binding.");
            }
            if (previous != null && CurrencyBinding.ORDER.compare(previous, binding) >= 0) {
                throw inconsistent("currencyBindingOrder",
                        "A plan's currency bindings must be in deterministic kind-then-ID order.");
            }
            previous = binding;
            copy.add(binding);
        }
        return List.copyOf(copy);
    }

    private static String requirePlanVersion(String value, String expected, String invariant) {
        if (!expected.equals(value)) {
            throw inconsistent(invariant, "A plan must carry the one supported version discriminator.");
        }
        return value;
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw inconsistent(invariant, "A plan requires a non-null input.");
        }
    }

    private static QueryException inconsistent(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }

    /** Runs an internal construction step, converting an unexpected runtime failure into the bounded code. */
    private static <T> T guarded(String invariant, Supplier<T> action) {
        try {
            return action.get();
        } catch (QueryException exception) {
            // A genuine QueryException already carries a stable bounded code; never relabel it.
            throw exception;
        } catch (RuntimeException exception) {
            throw inconsistent(invariant, "A plan invariant could not be evaluated.");
        }
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(
            String planFormatVersion,
            String plannerVersion,
            String compilerVersion,
            String queryAstVersion,
            SchemaVersion dslSchemaVersion,
            String canonicalizerVersion,
            SubjectKind subject,
            QueryAst predicateRoot,
            ContentHash canonicalAstHash,
            MetadataBinding metadataBinding,
            List<ResolvedDependency> dependencies,
            List<AudienceVersionDependency> audienceDependencies,
            List<ResolvedWindowBinding> windowBindings,
            List<CurrencyBinding> currencyBindings,
            DataScopePredicate dataScopePredicate,
            TrustedPlanContext trustedContext) {
    }
}
