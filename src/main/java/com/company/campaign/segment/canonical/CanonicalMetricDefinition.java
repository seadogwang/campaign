package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataScalarValue;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;

/**
 * The semantic identity of a Metric definition inside the canonical AST.
 *
 * <p>Beyond an ordinary definition it pins exactly the aggregate decisions the v1.3 spec requires to survive
 * canonicalization distinctly: the aggregate function, the empty-set behavior with its value when one is
 * declared, the event-time semantics and the bounded-window boundary semantics. Two metrics whose aggregate,
 * empty-set or boundary behavior differ therefore never share a canonical hash, even if their IDs collide.
 */
public record CanonicalMetricDefinition(
        String id,
        SubjectKind subject,
        SemanticDataType resultType,
        NullSemantics nullSemantics,
        CurrencySemantics currencySemantics,
        StringSemantics stringSemantics,
        AggregateFunction aggregation,
        EmptySetBehavior emptySetBehavior,
        MetadataScalarValue emptySetValue,
        EventTimeSemantics eventTimeSemantics,
        WindowBoundarySemantics windowBoundarySemantics
) {
    public CanonicalMetricDefinition {
        CanonicalInvariants.requirePresent(subject, "subject");
        CanonicalInvariants.requirePresent(resultType, "resultType");
        CanonicalInvariants.requirePresent(nullSemantics, "nullSemantics");
        CanonicalInvariants.requirePresent(currencySemantics, "currencySemantics");
        CanonicalInvariants.requirePresent(aggregation, "aggregation");
        CanonicalInvariants.requirePresent(emptySetBehavior, "emptySetBehavior");
        CanonicalInvariants.requirePresent(windowBoundarySemantics, "windowBoundarySemantics");
        CanonicalInvariants.requireReferenceId(id, "metricDefinitionId");
        CanonicalInvariants.requireMetricDefinition(
                resultType, currencySemantics, stringSemantics, emptySetBehavior, emptySetValue);
    }
}