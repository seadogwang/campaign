package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RelationCardinality;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SubjectKind;

/**
 * The semantic identity of a Relation inside the canonical AST.
 *
 * <p>A canonical EXISTS keeps its relation identity, source/target subject and deterministic join scope, so
 * two EXISTS nodes over different relations — or over the same relation with a different join scope — never
 * collapse into one. Display names and aliases are absent.
 */
public record CanonicalRelationReference(
        String id,
        SubjectKind sourceEntity,
        SubjectKind targetEntity,
        RelationCardinality cardinality,
        RelationJoinKind joinKind,
        String sourceJoinField,
        String targetJoinField,
        NullSemantics targetNullSemantics,
        EmptySetBehavior emptyRelationSemantics,
        EventTimeSemantics eventTimeSemantics,
        String eventTimeField
) {
    public CanonicalRelationReference {
        CanonicalInvariants.requirePresent(sourceEntity, "sourceEntity");
        CanonicalInvariants.requirePresent(targetEntity, "targetEntity");
        CanonicalInvariants.requirePresent(cardinality, "cardinality");
        CanonicalInvariants.requirePresent(joinKind, "joinKind");
        CanonicalInvariants.requirePresent(targetNullSemantics, "targetNullSemantics");
        CanonicalInvariants.requirePresent(emptyRelationSemantics, "emptyRelationSemantics");
        CanonicalInvariants.requireReferenceId(id, "relationId");
        CanonicalInvariants.requireRelationReference(
                joinKind, sourceJoinField, targetJoinField, eventTimeSemantics, eventTimeField);
    }
}