package com.company.campaign.evaluation.runtime.t22;

/**
 * Stable bounded error codes for the T22 Runtime evaluation assets.
 *
 * <p>They mirror the accepted T19, T20 and T21 evaluation codes so an asset loader, a test or a later task
 * module can branch on the same vocabulary regardless of which corpus it is reading.
 */
public final class T22EvaluationErrorCodes {

    /** The evaluation directory does not contain exactly the frozen asset set. */
    public static final String ASSET_SET_INVALID = "T22_EVALUATION_ASSET_SET_INVALID";

    /** An evaluation asset cannot be read. */
    public static final String ASSET_UNREADABLE = "T22_EVALUATION_ASSET_UNREADABLE";

    /** An evaluation asset is not strict canonical JSON, or carries an unknown field. */
    public static final String ASSET_NON_CANONICAL = "T22_EVALUATION_ASSET_NON_CANONICAL";

    /** An evaluation asset's declared hash is not the hash of its bytes. */
    public static final String ASSET_HASH_MISMATCH = "T22_EVALUATION_ASSET_HASH_MISMATCH";

    /** The manifest's own self-hash was not updated with the manifest. */
    public static final String MANIFEST_HASH_MISMATCH = "T22_EVALUATION_MANIFEST_HASH_MISMATCH";

    /** The manifest and the cases document disagree on their frozen identity. */
    public static final String ASSET_IDENTITY_MISMATCH = "T22_EVALUATION_ASSET_IDENTITY_MISMATCH";

    /** The corpus, a case or the manifest is not a bounded machine contract. */
    public static final String CORPUS_INVALID = "T22_EVALUATION_CORPUS_INVALID";

    private T22EvaluationErrorCodes() {
    }
}