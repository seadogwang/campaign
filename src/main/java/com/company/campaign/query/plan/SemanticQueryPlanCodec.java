package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.QueryWire;
import com.company.campaign.query.ast.QueryAstCodec;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one strict canonical serialization and hashing boundary for the Semantic Query Plan.
 *
 * <p>It serializes every semantic field exactly once in a stable, key-sorted canonical JSON document and
 * hashes it through the existing T01 {@link CanonicalJson} / {@link ContentHash} pair — no second hashing
 * primitive is introduced. Arrays (dependency list, window list, filter values, AST children) keep their
 * order, and the planner already fixes every one of those orders deterministically, so the same inputs always
 * produce byte-identical bytes and the same hash.
 *
 * <p>The codec is read-only with respect to plans: it can write and hash a plan, but it has no parse, read or
 * deserialize entry point, so no caller can manufacture a plan from bytes.
 */
public final class SemanticQueryPlanCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** Canonical UTF-8 bytes of one plan. */
    public byte[] write(SemanticQueryPlan plan) {
        requirePresent(plan, "plan");
        return CANONICAL_JSON.write(wire(plan.material()));
    }

    /** Canonical JSON text of one plan; the bytes are always UTF-8. */
    public String writeString(SemanticQueryPlan plan) {
        return new String(write(plan), StandardCharsets.UTF_8);
    }

    /** Canonical content hash of one plan; equals {@link SemanticQueryPlan#planHash()}. */
    public ContentHash hash(SemanticQueryPlan plan) {
        return ContentHash.sha256(write(plan));
    }

    /**
     * The canonical content hash of one plan's Existing Audience Reference dependencies alone.
     *
     * <p>It is the exact sensitivity of the dedicated reference evidence: the hash covers the whole frozen
     * reference-dependency projection of the plan — each referenced pin, its owner, its published and effective
     * DataScope, its dependency graph, its retention observations, its complexity contribution and its depth —
     * and nothing else. A change to any referenced version therefore moves this hash, while a change to a
     * reference-free part of the same plan does not. A plan with no reference at all hashes its own empty
     * dependency list, deterministically and without a special case.
     *
     * <p>The hash is derived from the plan's own frozen dependencies rather than from anything a client, a
     * model or a payload supplied, so a readiness projection that rests on it cannot drift from the plan it
     * claims to describe.
     */
    public ContentHash referenceDependenciesHash(SemanticQueryPlan plan) {
        requirePresent(plan, "plan");
        return ContentHash.sha256(CANONICAL_JSON.write(audienceDependencies(plan.audienceDependencies())));
    }

    static ContentHash hashMaterial(SemanticQueryPlan.Material material) {
        return ContentHash.sha256(writeMaterial(material));
    }

    /** Canonical UTF-8 bytes of one plan material; package-private for the in-package hash-coverage proofs. */
    static byte[] writeMaterial(SemanticQueryPlan.Material material) {
        requirePresent(material, "material");
        return CANONICAL_JSON.write(wire(material));
    }

    private static Map<String, Object> wire(SemanticQueryPlan.Material material) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("planFormatVersion", material.planFormatVersion());
        wire.put("plannerVersion", material.plannerVersion());
        wire.put("compilerVersion", material.compilerVersion());
        wire.put("queryAstVersion", material.queryAstVersion());
        wire.put("dslSchemaVersion", material.dslSchemaVersion().value());
        wire.put("canonicalizerVersion", material.canonicalizerVersion());
        wire.put("subject", material.subject().name());
        wire.put("canonicalAstHash", material.canonicalAstHash().value());
        wire.put("metadataBinding", metadataBinding(material));
        wire.put("predicate", QueryAstCodec.wire(material.predicateRoot()));
        wire.put("dependencies", dependencies(material.dependencies()));
        wire.put("audienceDependencies", audienceDependencies(material.audienceDependencies()));
        wire.put("windowBindings", windowBindings(material.windowBindings()));
        wire.put("currencyBindings", currencyBindings(material.currencyBindings()));
        wire.put("dataScopePredicate", dataScopePredicate(material.dataScopePredicate()));
        wire.put("trustedContext", trustedContext(material.trustedContext()));
        return wire;
    }

    private static Map<String, Object> metadataBinding(SemanticQueryPlan.Material material) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("metadataSnapshotId", material.metadataBinding().snapshotId());
        wire.put("metadataSnapshotVersion", material.metadataBinding().snapshotVersion());
        wire.put("metadataSchemaVersion", material.metadataBinding().metadataSchemaVersion().value());
        wire.put("metadataContentHash", material.metadataBinding().contentHash().value());
        return wire;
    }

    private static List<Object> dependencies(List<ResolvedDependency> dependencies) {
        List<Object> wire = new ArrayList<>(dependencies.size());
        for (ResolvedDependency dependency : dependencies) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", dependency.kind().name());
            switch (dependency) {
                case ResolvedDependency.AttributeDependency attribute -> {
                    entry.put("definition", QueryWire.definition(attribute.definition()));
                    entry.put("entityVersion", entityVersion(attribute.entityVersion()));
                }
                case ResolvedDependency.MetricDependency metric -> {
                    entry.put("definition", QueryWire.metricDefinition(metric.definition()));
                    entry.put("entityVersion", entityVersion(metric.entityVersion()));
                    entry.put("relationId", metric.relationId());
                    entry.put("relation", relationDependency(metric.relation()));
                    entry.put("eventTimeSemantics", metric.eventTimeSemantics() == null
                            ? null : metric.eventTimeSemantics().name());
                    entry.put("eventTimeField", metric.eventTimeField());
                    entry.put("filter", QueryWire.filter(metric.filter()));
                    entry.put("refundTreatment", metric.refundTreatment().name());
                    if (metric.eventSelector() != null) {
                        entry.put("eventSelector", QueryWire.eventSelector(metric.eventSelector()));
                    }
                }
                case ResolvedDependency.RelationDependency relation ->
                        entry.put("relation", relationDependency(relation));
            }
            wire.add(entry);
        }
        return wire;
    }

    /**
     * The frozen Existing Audience Reference dependencies, each covering every fact the plan binds them to.
     *
     * <p>An empty list for a document with no reference. Every field of the dependency appears exactly once, so
     * a changed version pin, catalog hash, effective scope, retention observation, complexity contribution or
     * discovery depth all move the plan hash, while a reordered AST, a repeated site or a differently-named
     * reference node cannot.
     */
    private static List<Object> audienceDependencies(List<AudienceVersionDependency> dependencies) {
        List<Object> wire = new ArrayList<>(dependencies.size());
        for (AudienceVersionDependency dependency : dependencies) {
            Map<String, Object> entry = new LinkedHashMap<>();
            Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("audienceId", dependency.reference().audienceId());
            pin.put("versionId", dependency.reference().versionId());
            pin.put("versionHash", dependency.reference().versionHash().value());
            entry.put("reference", pin);
            entry.put("subject", dependency.subject().name());
            entry.put("ownerTenantId", dependency.ownerTenantId());
            entry.put("ownerProgramId", dependency.ownerProgramId());
            entry.put("canonicalAstHash", dependency.canonicalAstHash().value());
            entry.put("semanticQueryPlanHash", dependency.semanticQueryPlanHash().value());
            Map<String, Object> published = new LinkedHashMap<>();
            published.put("dataScopeId", dependency.publishedDataScope().dataScopeId());
            published.put("dataScopeHash", dependency.publishedDataScope().dataScopeHash().value());
            entry.put("publishedDataScope", published);
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("dataScopeId", dependency.effectiveDataScope().dataScopeId());
            scope.put("dataScopeHash", dependency.effectiveDataScope().dataScopeHash().value());
            entry.put("effectiveDataScope", scope);
            List<Object> direct = new ArrayList<>(dependency.directDependencies().size());
            for (com.company.campaign.segment.reference.AudienceVersionRef dependencyPin
                    : dependency.directDependencies()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("audienceId", dependencyPin.audienceId());
                item.put("versionId", dependencyPin.versionId());
                item.put("versionHash", dependencyPin.versionHash().value());
                direct.add(item);
            }
            entry.put("directDependencies", direct);
            entry.put("dependencyGraphHash", dependency.dependencyGraphHash().value());
            entry.put("publishedAt", instant(dependency.publishedAt()));
            entry.put("availability", dependency.availability().name());
            entry.put("retentionHoldIds", new ArrayList<>(dependency.retentionHoldIds()));
            entry.put("expandedComplexity", dependency.expandedComplexity());
            entry.put("depth", dependency.depth());
            wire.add(entry);
        }
        return wire;
    }

    /** The complete relation projection: canonical reference plus the exact source/target entity versions. */
    private static Map<String, Object> relationDependency(ResolvedDependency.RelationDependency relation) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("reference", QueryWire.relation(relation.reference()));
        wire.put("sourceVersion", entityVersion(relation.sourceVersion()));
        wire.put("targetVersion", entityVersion(relation.targetVersion()));
        return wire;
    }

    private static Map<String, Object> entityVersion(EntityVersionRef version) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("entity", version.entity().name());
        wire.put("schemaVersion", version.schemaVersion().value());
        return wire;
    }

    private static List<Object> windowBindings(List<ResolvedWindowBinding> bindings) {
        List<Object> wire = new ArrayList<>(bindings.size());
        for (ResolvedWindowBinding binding : bindings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scope", binding.scope().name());
            entry.put("path", binding.path());
            entry.put("canonicalWindow", QueryWire.window(binding.canonicalWindow()));
            entry.put("resolvedWindow", resolvedWindow(binding.resolvedWindow()));
            entry.put("includeStart", binding.includeStart());
            entry.put("includeEnd", binding.includeEnd());
            if (binding instanceof ResolvedWindowBinding.MetricWindow metric) {
                entry.put("boundarySemantics", metric.boundarySemantics().name());
                entry.put("eventTimeSemantics", metric.eventTimeSemantics().name());
                // A LIFETIME-only metric with no event-time capability is represented deliberately as an
                // explicit NOT_APPLICABLE semantics with a null field, never as an omitted fact.
                entry.put("eventTimeField", metric.eventTimeField());
            } else if (binding instanceof ResolvedWindowBinding.RelationWindow relation) {
                if (relation.eventTimeSemantics() != null) {
                    entry.put("eventTimeSemantics", relation.eventTimeSemantics().name());
                }
                if (relation.eventTimeField() != null) {
                    entry.put("eventTimeField", relation.eventTimeField());
                }
            }
            wire.add(entry);
        }
        return wire;
    }

    private static Map<String, Object> resolvedWindow(ResolvedWindow window) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", window.kind().name());
        switch (window) {
            case ResolvedWindow.Rolling rolling -> {
                wire.put("start", instant(rolling.start()));
                wire.put("end", instant(rolling.end()));
            }
            case ResolvedWindow.Fixed fixed -> {
                wire.put("start", instant(fixed.start()));
                wire.put("end", instant(fixed.end()));
            }
            case ResolvedWindow.Lifetime lifetime -> {
                wire.put("evaluationAsOf", instant(lifetime.evaluationAsOf()));
                wire.put("dataWatermark", instant(lifetime.dataWatermark()));
            }
            case ResolvedWindow.RelativeToAttribute relative -> {
                wire.put("anchorDefinitionId", relative.anchorDefinitionId());
                wire.put("startOffsetDays", relative.startOffsetDays());
                wire.put("endOffsetDays", relative.endOffsetDays());
            }
            case ResolvedWindow.SelectedEvent selected -> {
                wire.put("selectorId", selected.selectorId());
                List<Object> filters = new ArrayList<>(selected.selectorScopeFilters().size());
                selected.selectorScopeFilters().forEach(filter -> filters.add(QueryWire.metricFilter(filter)));
                wire.put("selectorScopeFilters", filters);
            }
            case ResolvedWindow.RelativeToEvent relative -> {
                wire.put("selectorId", relative.selectorId());
                List<Object> filters = new ArrayList<>(relative.selectorScopeFilters().size());
                relative.selectorScopeFilters().forEach(filter -> filters.add(QueryWire.metricFilter(filter)));
                wire.put("selectorScopeFilters", filters);
                wire.put("startOffsetDays", relative.startOffsetDays());
                wire.put("endOffsetDays", relative.endOffsetDays());
                wire.put("excludeAnchorEvent", relative.excludeAnchorEvent());
            }
        }
        return wire;
    }

    private static String instant(Instant value) {
        return value.toString();
    }

    private static List<Object> currencyBindings(List<CurrencyBinding> bindings) {
        List<Object> wire = new ArrayList<>(bindings.size());
        for (CurrencyBinding binding : bindings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", binding.kind().name());
            entry.put("definitionId", binding.definitionId());
            entry.put("action", binding.action().name());
            if (binding.targetCurrency() != null) {
                entry.put("targetCurrency", binding.targetCurrency());
            }
            entry.put("rateResolution", binding.rateResolution().name());
            wire.add(entry);
        }
        return wire;
    }

    private static Map<String, Object> dataScopePredicate(DataScopePredicate predicate) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", predicate.kind().name());
        wire.put("subject", predicate.subject().name());
        wire.put("dataScopeId", predicate.dataScopeId());
        // Only an effective-intersection requirement carries a scope hash, so a plan without a reference keeps
        // exactly the three-field requirement shape it had before references existed.
        if (predicate.dataScopeHash() != null) {
            wire.put("dataScopeHash", predicate.dataScopeHash().value());
        }
        return wire;
    }

    private static Map<String, Object> trustedContext(TrustedPlanContext context) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("tenantId", context.tenantId());
        wire.put("programId", context.programId());
        wire.put("membershipId", context.membershipId());
        wire.put("userId", context.userId());
        wire.put("dataScopeId", context.dataScopeId());
        wire.put("asOf", instant(context.asOf()));
        wire.put("timezone", context.timezone());
        wire.put("reportingCurrency", context.reportingCurrency());
        wire.put("dataWatermark", instant(context.dataWatermark()));
        return wire;
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new QueryException(
                    QueryErrorCodes.QUERY_INPUT_MISSING, invariant,
                    "A plan codec boundary requires a non-null input.");
        }
    }
}
