package com.company.campaign.builder.segment;

/**
 * The only evidence classification this development slice may ever produce.
 *
 * <p>The slice is a development validation harness. It can never create a Gate {@code PASSED}, an
 * {@code AI_READY} decision, a Save, a Publish or Runtime evidence, and every result it returns is stamped with
 * this one classification.
 */
public final class DevelopmentEvidence {

    /** The single evidence classification of this slice. */
    public static final String DEVELOPMENT_ONLY = "DEVELOPMENT_ONLY";

    private DevelopmentEvidence() {
    }
}