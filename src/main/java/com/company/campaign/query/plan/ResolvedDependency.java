package com.company.campaign.query.plan;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricDefinition;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.util.Comparator;
import java.util.Objects;

/**
 * One resolved Attribute, Metric or Relation dependency of a plan, in a closed deterministic order.
 *
 * <p>An attribute dependency keeps the T07 canonical definition plus the exact {@link EntityVersionRef} the
 * definition was resolved from, so a later compiler never has to re-read Metadata to discover which entity
 * schema version the comparison meant.
 *
 * <p>A metric dependency adds every fact that is intentionally absent from the canonical node and can only
 * come from the exact pinned Metadata Snapshot: the exact {@code EntityVersionRef}, the complete referenced
 * {@link RelationDependency} (canonical relation reference plus both entity schema versions), the
 * bounded-window event-time field and semantics (deliberately {@code null} for a LIFETIME-only metric whose
 * event-time capability is absent), the immutable filter and the refund treatment. Embedding the complete
 * relation dependency lets the construction boundary re-prove relation contents and versions exactly, without
 * re-reading Metadata.
 *
 * <p>A relation dependency keeps the canonical relation reference <em>and</em> the exact source/target
 * {@link EntityVersionRef}s, so cardinality, join scope, event-time capability, target NULL semantics,
 * empty-relation behavior and both entity schema versions are all carried as executable facts.
 *
 * <p>Dependencies are duplicate-free by {@code (kind, id)} and ordered by kind then stable ID, so the same
 * semantic input always yields the same sequence regardless of tree order or snapshot insertion order.
 */
public sealed interface ResolvedDependency
        permits ResolvedDependency.AttributeDependency, ResolvedDependency.MetricDependency,
        ResolvedDependency.RelationDependency {

    /** The deterministic dependency order: kind, then stable ID. */
    Comparator<ResolvedDependency> ORDER =
            Comparator.comparing((ResolvedDependency dependency) -> dependency.kind().ordinal())
                    .thenComparing(ResolvedDependency::id);

    /** The dependency kind. */
    MetadataKind kind();

    /** The stable definition or relation ID. */
    String id();

    /** A resolved attribute definition plus the exact entity schema version it was read from. */
    record AttributeDependency(CanonicalDefinition definition, EntityVersionRef entityVersion)
            implements ResolvedDependency {
        public AttributeDependency {
            requirePresent(definition, "attributeDefinition");
            requirePresent(entityVersion, "attributeEntityVersion");
            if (entityVersion.entity() != definition.subject()) {
                throw invalid("attributeEntityVersion",
                        "An attribute dependency's entity version must name the definition's subject entity.");
            }
        }

        @Override
        public MetadataKind kind() {
            return MetadataKind.ATTRIBUTE;
        }

        @Override
        public String id() {
            return definition.id();
        }
    }

    /** A resolved metric definition plus the Snapshot-only facts a planner needs to evaluate it. */
    record MetricDependency(
            CanonicalMetricDefinition definition,
            EntityVersionRef entityVersion,
            String relationId,
            RelationDependency relation,
            String eventTimeField,
            EventTimeSemantics eventTimeSemantics,
            MetadataFilter filter,
            RefundTreatment refundTreatment,
            EventSelectorDefinition eventSelector) implements ResolvedDependency {
        public MetricDependency {
            requirePresent(definition, "metricDefinition");
            requirePresent(entityVersion, "metricEntityVersion");
            if (entityVersion.entity() != definition.subject()) {
                throw invalid("metricEntityVersion",
                        "A metric dependency's entity version must name the definition's subject entity.");
            }
            if (relationId == null || relationId.isBlank()) {
                throw invalid("metricRelationId", "A metric dependency requires its referenced relation.");
            }
            requirePresent(relation, "metricRelation");
            if (!relationId.equals(relation.id())) {
                throw invalid("metricRelationProjection",
                        "A metric dependency's relation projection must be the relation it references.");
            }
            if ((eventTimeField == null) != (eventTimeSemantics == null)) {
                throw invalid("metricEventTime",
                        "A metric dependency's event-time field and semantics are declared together.");
            }
            if (eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE) {
                throw invalid("metricEventTime",
                        "A metric dependency must resolve a usable event-time semantics or none at all.");
            }
            if (!Objects.equals(eventTimeSemantics, definition.eventTimeSemantics())) {
                throw invalid("metricEventTime",
                        "A metric dependency's event-time semantics must equal its canonical definition's.");
            }
            requirePresent(filter, "metricFilter");
            requirePresent(refundTreatment, "refundTreatment");
        }

        public MetricDependency(
                CanonicalMetricDefinition definition,
                EntityVersionRef entityVersion,
                String relationId,
                RelationDependency relation,
                String eventTimeField,
                EventTimeSemantics eventTimeSemantics,
                MetadataFilter filter,
                RefundTreatment refundTreatment) {
            this(definition, entityVersion, relationId, relation, eventTimeField, eventTimeSemantics,
                    filter, refundTreatment, null);
        }

        @Override
        public MetadataKind kind() {
            return MetadataKind.METRIC;
        }

        @Override
        public String id() {
            return definition.id();
        }
    }

    /** A resolved relation reference together with the exact source and target entity schema versions. */
    record RelationDependency(
            CanonicalRelationReference reference,
            EntityVersionRef sourceVersion,
            EntityVersionRef targetVersion) implements ResolvedDependency {
        public RelationDependency {
            requirePresent(reference, "relationReference");
            requirePresent(sourceVersion, "relationSourceVersion");
            requirePresent(targetVersion, "relationTargetVersion");
            if (sourceVersion.entity() != reference.sourceEntity()) {
                throw invalid("relationSourceVersion",
                        "A relation dependency's source version must name its reference's source entity.");
            }
            if (targetVersion.entity() != reference.targetEntity()) {
                throw invalid("relationTargetVersion",
                        "A relation dependency's target version must name its reference's target entity.");
            }
        }

        @Override
        public MetadataKind kind() {
            return MetadataKind.RELATION;
        }

        @Override
        public String id() {
            return reference.id();
        }
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw invalid(invariant, "A resolved dependency requires a non-null input.");
        }
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }
}
