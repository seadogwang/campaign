package com.company.campaign.evaluation.capabilities.t19;

/**
 * The bounded, stable machine codes the T19 capability evaluation-asset loader reports.
 *
 * <p>Every code is a closed constant naming one deterministic failure of the loader. No code echoes a
 * rejected value, a payload body, a template text, a reward secret, a credential, a member identifier or a
 * provider response: the corpus and its reports are contract fixtures, never evidence about a member.
 */
public final class T19EvaluationErrorCodes {

    /** An asset reference is empty, malformed, absolute, uses backslashes or contains a parent segment. */
    public static final String PATH_MALFORMED = "T19_EVALUATION_PATH_MALFORMED";

    /** An asset reference resolves outside the configured evaluation root. */
    public static final String PATH_OUTSIDE_ROOT = "T19_EVALUATION_PATH_OUTSIDE_ROOT";

    /** An asset file could not be read. */
    public static final String ASSET_UNREADABLE = "T19_EVALUATION_ASSET_UNREADABLE";

    /** An asset is not its own canonical serialization. */
    public static final String ASSET_NON_CANONICAL = "T19_EVALUATION_ASSET_NON_CANONICAL";

    /** A declared content hash does not match the bytes of the asset it pins. */
    public static final String ASSET_HASH_MISMATCH = "T19_EVALUATION_ASSET_HASH_MISMATCH";

    /** The manifest's declared hash is absent or does not match its own content. */
    public static final String MANIFEST_HASH_MISMATCH = "T19_EVALUATION_MANIFEST_HASH_MISMATCH";

    /** A fixture document's declared identity disagrees with the manifest entry that references it. */
    public static final String ASSET_IDENTITY_MISMATCH = "T19_EVALUATION_ASSET_IDENTITY_MISMATCH";

    /** A manifest declares one capability more than once. */
    public static final String DUPLICATE_CAPABILITY = "T19_EVALUATION_DUPLICATE_CAPABILITY";

    /** A fixture document declares no fixture of one polarity. */
    public static final String FIXTURE_POLARITY = "T19_EVALUATION_FIXTURE_POLARITY";

    /** A fixture document repeats one fixture id across either polarity. */
    public static final String FIXTURE_DUPLICATE = "T19_EVALUATION_FIXTURE_DUPLICATE";

    /** The manifest declares a schema version this build does not support. */
    public static final String SCHEMA_UNSUPPORTED = "T19_EVALUATION_SCHEMA_UNSUPPORTED";

    private T19EvaluationErrorCodes() {
    }
}