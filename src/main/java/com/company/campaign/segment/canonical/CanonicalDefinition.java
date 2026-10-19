package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;

/**
 * The semantic identity of an Attribute definition inside the canonical AST.
 *
 * <p>It keeps the stable definition ID together with every Metadata-owned decision that changes what a
 * comparison means: subject, data type, NULL semantics, currency policy and — for a STRING — the frozen
 * case/normalization/locale contract. Display names, aliases and examples are deliberately absent, because
 * they are presentation and never semantic hash material.
 */
public record CanonicalDefinition(
        MetadataKind kind,
        String id,
        SubjectKind subject,
        SemanticDataType dataType,
        NullSemantics nullSemantics,
        CurrencySemantics currencySemantics,
        StringSemantics stringSemantics
) {
    public CanonicalDefinition {
        CanonicalInvariants.requirePresent(kind, "kind");
        CanonicalInvariants.requirePresent(subject, "subject");
        CanonicalInvariants.requirePresent(dataType, "dataType");
        CanonicalInvariants.requirePresent(nullSemantics, "nullSemantics");
        CanonicalInvariants.requirePresent(currencySemantics, "currencySemantics");
        CanonicalInvariants.requireReferenceId(id, "definitionId");
        CanonicalInvariants.requireAttributeDefinition(kind, dataType, currencySemantics, stringSemantics);
    }
}