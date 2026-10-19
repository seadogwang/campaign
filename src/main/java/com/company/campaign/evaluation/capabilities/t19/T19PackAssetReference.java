package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * One pack entry in the T19 evaluation manifest: which capability, at which contract version, in which slot,
 * pins which fixture document, at which hash.
 *
 * <p>The manifest names a pack by its canonical capability code and exact contract version — never by a
 * mutable registry handle or a display name — and pins the fixture document by repository-relative path and
 * content hash, so the corpus and the pack can never disagree about which payload each fixture exercises.
 */
public record T19PackAssetReference(
        String capabilityCode,
        String contractVersion,
        String slot,
        String assetId,
        String assetVersion,
        String fixturesPath,
        ContentHash fixturesHash) {

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");
    private static final Pattern SLOT = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");
    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public T19PackAssetReference {
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 pack reference requires a canonical capability code.");
        }
        if (contractVersion == null || !VERSION.matcher(contractVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 pack reference requires an exact contract version.");
        }
        if (slot == null || !SLOT.matcher(slot).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 pack reference requires a canonical composition slot.");
        }
        if (assetId == null || !ID.matcher(assetId).matches()
                || assetVersion == null || !VERSION.matcher(assetVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 pack reference requires a bounded evaluation asset identity and version.");
        }
        if (fixturesPath == null || fixturesPath.isBlank()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_MALFORMED,
                    "A T19 pack reference requires a fixture document reference.");
        }
        if (fixturesHash == null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_HASH_MISMATCH,
                    "A T19 pack reference requires the content hash of its fixture document.");
        }
    }
}