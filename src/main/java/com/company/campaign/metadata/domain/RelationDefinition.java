package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataTexts;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable semantic Relation definition.
 *
 * <p>Relations stay atomic in T04: composition belongs to a future planner. Each relation pins source and
 * target entities with their versions, cardinality, deterministic join scope, optional event-time
 * capability and explicit NULL/empty relation semantics.
 */
public record RelationDefinition(
        String id,
        String displayName,
        List<String> aliases,
        SubjectKind sourceEntity,
        EntityVersionRef sourceVersion,
        SubjectKind targetEntity,
        EntityVersionRef targetVersion,
        RelationCardinality cardinality,
        RelationScope scope,
        RelationEventTimeCapability eventTimeCapability,
        NullSemantics targetNullSemantics,
        EmptySetBehavior emptyRelationSemantics,
        List<String> positiveExamples,
        List<String> negativeExamples,
        String disambiguation
) implements MetadataDefinition {

    public RelationDefinition {
        id = MetadataTexts.requireId(id, "relationId");
        displayName = MetadataTexts.requireDisplayName(displayName, "relationDisplayName");
        aliases = MetadataTexts.canonicalTextList(aliases, "relationAlias");
        MetadataTexts.requireNoCollision(displayName, aliases, "relationAlias");
        sourceEntity = MetadataTexts.requireNonNull(
                sourceEntity, "sourceEntity", MetadataErrorCodes.METADATA_VALUE_INVALID);
        sourceVersion = MetadataTexts.requireNonNull(
                sourceVersion, "sourceVersion", MetadataErrorCodes.METADATA_VALUE_INVALID);
        targetEntity = MetadataTexts.requireNonNull(
                targetEntity, "targetEntity", MetadataErrorCodes.METADATA_VALUE_INVALID);
        targetVersion = MetadataTexts.requireNonNull(
                targetVersion, "targetVersion", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if (sourceEntity != sourceVersion.entity() || targetEntity != targetVersion.entity()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "Relation entities must match their referenced entity versions");
        }
        cardinality = MetadataTexts.requireNonNull(
                cardinality, "cardinality", MetadataErrorCodes.METADATA_VALUE_INVALID);
        scope = MetadataTexts.requireNonNull(scope, "scope", MetadataErrorCodes.METADATA_VALUE_INVALID);
        targetNullSemantics = MetadataTexts.requireNonNull(
                targetNullSemantics, "targetNullSemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        emptyRelationSemantics = MetadataTexts.requireNonNull(
                emptyRelationSemantics, "emptyRelationSemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        positiveExamples = MetadataTexts.canonicalTextList(positiveExamples, "positiveExample");
        negativeExamples = MetadataTexts.canonicalTextList(negativeExamples, "negativeExample");
        MetadataTexts.requireDisjoint(positiveExamples, negativeExamples, "negativeExample");
        disambiguation = MetadataTexts.optionalText(disambiguation, "disambiguation");
    }

    @Override
    public MetadataKind kind() {
        return MetadataKind.RELATION;
    }

    /** Normalized keys for every discoverable name of this definition, used for collision checks. */
    public List<String> discoverableKeys() {
        List<String> keys = new ArrayList<>(aliases.size() + 1);
        keys.add(MetadataTexts.normalizedKey(displayName, "relationDisplayName"));
        aliases.forEach(alias -> keys.add(MetadataTexts.normalizedKey(alias, "relationAlias")));
        return List.copyOf(keys);
    }
}