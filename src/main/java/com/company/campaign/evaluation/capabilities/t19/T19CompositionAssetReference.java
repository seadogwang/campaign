package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * The manifest entry pinning the one complete-composition fixture document by identity and content hash.
 */
public record T19CompositionAssetReference(
        String assetId,
        String assetVersion,
        String path,
        ContentHash contentHash) {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");
    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public T19CompositionAssetReference {
        if (assetId == null || !ID.matcher(assetId).matches()
                || assetVersion == null || !VERSION.matcher(assetVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition asset reference requires a bounded identity and version.");
        }
        if (path == null || path.isBlank()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_MALFORMED,
                    "A composition asset reference requires a document reference.");
        }
        if (contentHash == null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_HASH_MISMATCH,
                    "A composition asset reference requires the content hash of its document.");
        }
    }
}