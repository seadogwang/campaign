package com.company.campaign.builder.authoring;

/**
 * Stable machine-readable failure codes owned by the T16 Segment authoring integration.
 *
 * <p>These values are part of the wire contract: a host, an API error envelope or a later task branches on
 * them, so an existing code is never renamed or reused for a different meaning and a new failure mode gets a
 * new code. Every code carries fixed prose plus, at most, a closed-set invariant name; no code ever echoes a
 * payload value, a DSL document, a member identifier, a tenant/program/user value, a provider or model output
 * or a thrown cause.
 */
public final class SegmentAuthoringErrorCodes {

    /** The embedded envelope is absent, not one bounded JSON object, or carries an unknown/missing field. */
    public static final String ENVELOPE_INVALID = "SEGMENT_AUTHORING_ENVELOPE_INVALID";

    /** The embedded typed Segment DSL document could not be parsed or re-proved through the strict T05 codec. */
    public static final String DSL_INVALID = "SEGMENT_AUTHORING_DSL_INVALID";

    /** A declared pin disagrees with the value a re-run of the accepted deterministic boundary derived. */
    public static final String EVIDENCE_MISMATCH = "SEGMENT_AUTHORING_EVIDENCE_MISMATCH";

    /** The Metadata Snapshot the document pins is absent, foreign, mixed or stale. */
    public static final String METADATA_MISMATCH = "SEGMENT_AUTHORING_METADATA_MISMATCH";

    /** The trusted time pins of the envelope disagree with the trusted context of the compile. */
    public static final String TIME_PIN_MISMATCH = "SEGMENT_AUTHORING_TIME_PIN_MISMATCH";

    /** The server-owned Preview evidence the document claims is absent, stale or not the one it pins. */
    public static final String PREVIEW_MISMATCH = "SEGMENT_AUTHORING_PREVIEW_MISMATCH";

    /** The compile pinned no typed frozen input bundle for the capability, so no accepted artifact is provable. */
    public static final String INPUT_MISSING = "SEGMENT_AUTHORING_INPUT_MISSING";

    /** The compile pinned no readiness fact for the capability, so it can only be development evidence. */
    public static final String READINESS_MISSING = "SEGMENT_AUTHORING_READINESS_MISSING";

    /** A readiness fact exists but contradicts itself or does not cover the capability the compile resolves. */
    public static final String READINESS_INVALID = "SEGMENT_AUTHORING_READINESS_INVALID";

    /** The shared submission is not a bounded, self-consistent authoring request. */
    public static final String REQUEST_INVALID = "SEGMENT_AUTHORING_REQUEST_INVALID";

    /** A source result reached a terminal non-resolved outcome and therefore carries no Proposal at all. */
    public static final String TERMINAL_OUTCOME = "SEGMENT_AUTHORING_TERMINAL_OUTCOME";

    /** A source result and the typed artifact supplied beside it do not describe the same resolved Segment. */
    public static final String SOURCE_MISMATCH = "SEGMENT_AUTHORING_SOURCE_MISMATCH";

    /**
     * An EDIT does not address an existing {@code segment.rule-based} component at the supported contract
     * version, or a refinement was submitted without an existing Segment to refine.
     */
    public static final String EDIT_TARGET_INVALID = "SEGMENT_AUTHORING_EDIT_TARGET_INVALID";

    /** A refinement's base DSL hash is not the current Segment component's own resolved DSL hash. */
    public static final String REFINEMENT_BASE_STALE = "SEGMENT_AUTHORING_REFINEMENT_BASE_STALE";

    /** A compiled result carries a Proposal patch and a Plan that are not the compile of one another. */
    public static final String RESULT_UNBOUND = "SEGMENT_AUTHORING_RESULT_UNBOUND";

    /** The fail-closed production activation guard rejected a non-ACTIVE or malformed readiness projection. */
    public static final String ACTIVATION_REJECTED = "SEGMENT_AUTHORING_ACTIVATION_REJECTED";

    /**
     * A Segment that depends on referenced immutable Audience Versions reached the contract through no
     * host-owned reference context, so its exact referenced versions cannot be resolved.
     */
    public static final String REFERENCE_CONTEXT_MISSING = "SEGMENT_AUTHORING_REFERENCE_CONTEXT_MISSING";

    private SegmentAuthoringErrorCodes() {
    }
}