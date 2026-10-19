package com.company.campaign.evaluation.action.t23;

/**
 * Stable bounded error codes for the T23 external-action evaluation assets.
 *
 * <p>They mirror the accepted T19, T20, T21 and T22 evaluation codes so an asset loader, a test or a later
 * task module can branch on the same vocabulary regardless of which corpus it is reading.
 */
public final class T23EvaluationErrorCodes {

    /** The evaluation directory does not contain exactly the frozen asset set. */
    public static final String ASSET_SET_INVALID = "T23_EVALUATION_ASSET_SET_INVALID";

    /** An evaluation asset cannot be read. */
    public static final String ASSET_UNREADABLE = "T23_EVALUATION_ASSET_UNREADABLE";

    /** An evaluation asset is not strict canonical JSON, or carries an unknown field. */
    public static final String ASSET_NON_CANONICAL = "T23_EVALUATION_ASSET_NON_CANONICAL";

    /** An evaluation asset's declared hash is not the hash of its bytes. */
    public static final String ASSET_HASH_MISMATCH = "T23_EVALUATION_ASSET_HASH_MISMATCH";

    /** The manifest's own self-hash was not updated with the manifest. */
    public static final String MANIFEST_HASH_MISMATCH = "T23_EVALUATION_MANIFEST_HASH_MISMATCH";

    /** The manifest and the cases document disagree on their frozen identity. */
    public static final String ASSET_IDENTITY_MISMATCH = "T23_EVALUATION_ASSET_IDENTITY_MISMATCH";

    /** The corpus, a case or the manifest is not a bounded machine contract. */
    public static final String CORPUS_INVALID = "T23_EVALUATION_CORPUS_INVALID";

    private T23EvaluationErrorCodes() {
    }
}