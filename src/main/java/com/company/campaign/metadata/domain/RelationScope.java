package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

/**
 * Deterministic relation-scope/join semantics, containing no SQL text.
 *
 * <p>The declared {@link RelationJoinKind} constrains which key fields must be present:
 * <ul>
 *   <li>{@code CORRELATED_BY_SOURCE_KEY} requires both a source and a target key field;</li>
 *   <li>{@code TARGET_FILTERED} requires a target key field and no source key field;</li>
 *   <li>{@code GLOBAL} requires neither.</li>
 * </ul>
 * This is enough for a future {@code EXISTS} planner to stay scope-correct without this task emitting SQL.
 */
public record RelationScope(RelationJoinKind joinKind, String sourceJoinField, String targetJoinField) {

    public RelationScope {
        joinKind = MetadataTexts.requireNonNull(
                joinKind, "joinKind", MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        switch (joinKind) {
            case CORRELATED_BY_SOURCE_KEY -> {
                sourceJoinField = MetadataTexts.requireId(sourceJoinField, "sourceJoinField");
                targetJoinField = MetadataTexts.requireId(targetJoinField, "targetJoinField");
            }
            case TARGET_FILTERED -> {
                requireAbsent(sourceJoinField, "sourceJoinField");
                targetJoinField = MetadataTexts.requireId(targetJoinField, "targetJoinField");
            }
            case GLOBAL -> {
                requireAbsent(sourceJoinField, "sourceJoinField");
                requireAbsent(targetJoinField, "targetJoinField");
            }
        }
    }

    private static void requireAbsent(String value, String field) {
        if (value != null) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "Join semantics must not declare '" + field + "'");
        }
    }
}