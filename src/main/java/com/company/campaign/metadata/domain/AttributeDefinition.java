package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataTexts;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable semantic Attribute definition.
 *
 * <p>Attributes describe semantics for resolution, not a physical column: alongside the referenced entity
 * version they pin display name, aliases, allowed operators, enum values, NULL semantics, currency policy
 * for monetary types, the string-comparison semantics required of a STRING attribute and the explanatory
 * examples/disambiguation text.
 */
public record AttributeDefinition(
        String id,
        EntityVersionRef entityVersion,
        SubjectKind subject,
        String displayName,
        SemanticDataType dataType,
        StringSemantics stringSemantics,
        List<String> aliases,
        List<ComparisonOperator> allowedOperators,
        List<EnumValueDefinition> enumValues,
        NullSemantics nullSemantics,
        CurrencySemantics currencySemantics,
        List<String> positiveExamples,
        List<String> negativeExamples,
        String disambiguation
) implements MetadataDefinition {

    public AttributeDefinition {
        id = MetadataTexts.requireId(id, "attributeId");
        entityVersion = MetadataTexts.requireNonNull(
                entityVersion, "entityVersion", MetadataErrorCodes.METADATA_VALUE_INVALID);
        subject = MetadataTexts.requireNonNull(subject, "subject", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if (subject != entityVersion.entity()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "Attribute subject must match its referenced entity");
        }
        displayName = MetadataTexts.requireDisplayName(displayName, "attributeDisplayName");
        dataType = MetadataTexts.requireNonNull(dataType, "dataType", MetadataErrorCodes.METADATA_VALUE_INVALID);
        MetadataVocabulary.requireStringSemanticsConsistency(dataType, stringSemantics);
        aliases = MetadataTexts.canonicalTextList(aliases, "attributeAlias");
        MetadataTexts.requireNoCollision(displayName, aliases, "attributeAlias");
        allowedOperators = MetadataVocabulary.canonicalOperators(allowedOperators, dataType);
        enumValues = MetadataVocabulary.canonicalEnumValues(dataType, enumValues);
        nullSemantics = MetadataTexts.requireNonNull(
                nullSemantics, "nullSemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        currencySemantics = MetadataTexts.requireNonNull(
                currencySemantics, "currencySemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        MetadataVocabulary.requireCurrencyConsistency(dataType, currencySemantics);
        positiveExamples = MetadataTexts.canonicalTextList(positiveExamples, "positiveExample");
        negativeExamples = MetadataTexts.canonicalTextList(negativeExamples, "negativeExample");
        MetadataTexts.requireDisjoint(positiveExamples, negativeExamples, "negativeExample");
        disambiguation = MetadataTexts.optionalText(disambiguation, "disambiguation");
    }

    @Override
    public MetadataKind kind() {
        return MetadataKind.ATTRIBUTE;
    }

    /** Normalized keys for every discoverable name of this definition, used for collision checks. */
    public List<String> discoverableKeys() {
        List<String> keys = new ArrayList<>(aliases.size() + 1);
        keys.add(MetadataTexts.normalizedKey(displayName, "attributeDisplayName"));
        aliases.forEach(alias -> keys.add(MetadataTexts.normalizedKey(alias, "attributeAlias")));
        return List.copyOf(keys);
    }

    }