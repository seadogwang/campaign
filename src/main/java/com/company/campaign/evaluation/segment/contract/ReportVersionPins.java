package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.regex.Pattern;

/**
 * Every non-model version an evaluation run must freeze: resolver, canonicalizer, Metadata, corpus and
 * fixtures.
 *
 * <p>Catalog and corpus versions appear both as a human-readable version and as the content hash the
 * manifest pinned, because a version string alone cannot prove which bytes were evaluated. Resolver and
 * canonicalizer versions are schema versions rather than hashes: they are code, identified by the contract
 * they implement, and their behaviour is covered by the deterministic Gate instead.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReportVersionPins(
        SchemaVersion resolverVersion,
        SchemaVersion canonicalizerVersion,
        String metadataCatalogVersion,
        ContentHash metadataCatalogHash,
        String corpusVersion,
        ContentHash corpusHash,
        String fixtureCatalogVersion,
        ContentHash fixtureCatalogHash
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReportVersionPins {
        if (resolverVersion == null || canonicalizerVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Report version pins must declare the resolver and canonicalizer versions");
        }
        requireVersion("metadataCatalogVersion", metadataCatalogVersion);
        requireVersion("corpusVersion", corpusVersion);
        requireVersion("fixtureCatalogVersion", fixtureCatalogVersion);
        if (metadataCatalogHash == null || corpusHash == null || fixtureCatalogHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Report version pins must declare the Metadata, corpus and fixture content hashes");
        }
    }

    private static void requireVersion(String field, String value) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Report version pin '" + field + "' value '" + value
                            + "' is not a valid reference identifier");
        }
    }
}