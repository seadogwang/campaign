package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The immutable catalog of Metadata snapshots a corpus may reference.
 *
 * <p>Duplicate snapshot ids are rejected at construction instead of being resolved by "last one wins": two
 * entries with one id would make the snapshot a case is evaluated against depend on iteration order, which
 * is exactly the kind of ambiguity the manifest hash is supposed to eliminate.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MetadataCatalog(
        SchemaVersion schemaVersion,
        String catalogVersion,
        List<MetadataSnapshotDescriptor> snapshots
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public MetadataCatalog {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Metadata catalog must declare a schema version");
        }
        if (catalogVersion == null || !REFERENCE_ID.matcher(catalogVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Metadata catalog version '" + catalogVersion + "' is not a valid reference identifier");
        }
        if (snapshots == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Metadata catalog must declare its snapshot list");
        }
        Set<String> seen = new HashSet<>(snapshots.size());
        for (MetadataSnapshotDescriptor snapshot : snapshots) {
            if (snapshot == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID, "Metadata catalog contains a null snapshot entry");
            }
            if (!seen.add(snapshot.snapshotId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_ID,
                        "Metadata snapshot id '" + snapshot.snapshotId() + "' is declared more than once",
                        Map.of("snapshotId", snapshot.snapshotId()));
            }
        }
        snapshots = ImmutableValues.freezeList(snapshots);
    }
}