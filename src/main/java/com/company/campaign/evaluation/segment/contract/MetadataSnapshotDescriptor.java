package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One immutable Metadata snapshot entry in the evaluation Metadata catalog.
 *
 * <p>T03 pins the snapshot's identity, version, content hash and payload path only. The payload itself is
 * opaque bytes here: parsing attributes, metrics, aliases and semantics is T04's contract, and reading
 * them early would create a second, weaker definition of what the snapshot means.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MetadataSnapshotDescriptor(
        String snapshotId,
        String snapshotVersion,
        ContentHash contentHash,
        String payloadPath
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern RELATIVE_JSON = Pattern.compile(EvaluationAssetPatterns.REPOSITORY_RELATIVE_JSON);

    public MetadataSnapshotDescriptor {
        if (snapshotId == null || !REFERENCE_ID.matcher(snapshotId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Metadata snapshot id '" + snapshotId + "' is not a valid reference identifier");
        }
        if (snapshotVersion == null || !REFERENCE_ID.matcher(snapshotVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Metadata snapshot version '" + snapshotVersion + "' is not a valid reference identifier");
        }
        if (contentHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Metadata snapshot '" + snapshotId + "' must declare its content hash");
        }
        if (payloadPath == null || !RELATIVE_JSON.matcher(payloadPath).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Metadata payload path '" + payloadPath + "' is not a repository-relative JSON asset path",
                    Map.of("path", String.valueOf(payloadPath)));
        }
    }
}