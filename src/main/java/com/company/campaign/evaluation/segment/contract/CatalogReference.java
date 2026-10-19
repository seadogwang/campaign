package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reference from the corpus manifest to one immutable catalog file.
 *
 * <p>The version and the content hash are both pinned. The version names the catalog for humans and
 * reports; the hash is what actually proves the loader received the reviewed bytes. A manifest that
 * pinned only the version could be satisfied by any catalog carrying that version string.
 *
 * <p>{@code path} is repository-relative. It is shape-checked here and resolved, normalized and contained
 * by the loader, because only the loader knows the configured evaluation root.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CatalogReference(
        String catalogVersion,
        ContentHash contentHash,
        String path
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern RELATIVE_JSON = Pattern.compile(EvaluationAssetPatterns.REPOSITORY_RELATIVE_JSON);

    public CatalogReference {
        if (catalogVersion == null || !REFERENCE_ID.matcher(catalogVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Catalog version '" + catalogVersion + "' is not a valid reference identifier");
        }
        if (contentHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Catalog reference must declare the content hash of the catalog it points at");
        }
        if (path == null || !RELATIVE_JSON.matcher(path).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Catalog path '" + path + "' is not a repository-relative JSON asset path",
                    Map.of("path", String.valueOf(path)));
        }
    }
}