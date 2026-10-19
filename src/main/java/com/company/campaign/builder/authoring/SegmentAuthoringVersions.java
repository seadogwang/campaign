package com.company.campaign.builder.authoring;

import com.company.campaign.builder.compiler.PlanPreviewCard;

/**
 * Version discriminators and closed codes owned by the T16 Segment authoring integration.
 *
 * <p>Every value here is part of a hash material or of a frozen catalog pin, so a change to any of them is a
 * new capability contract version rather than a silent reinterpretation of an already-produced Manifest, Plan
 * or card.
 */
public final class SegmentAuthoringVersions {

    /** The one capability code this integration implements. */
    public static final String CAPABILITY_CODE = "segment.rule-based";

    /**
     * The exact contract version of the T16 rule-based Segment capability.
     *
     * <p>The frozen catalog pins this string; a bootstrap that supplies a different contract version pins a
     * different capability and a different catalog hash.
     */
    public static final String CONTRACT_VERSION = "1.0";

    /** The singleton Campaign audience/segment composition slot the capability occupies. */
    public static final String SLOT_CODE = "campaign.audience.segment";

    /** The closed envelope format version embedded in every normalized Segment capability payload. */
    public static final String ENVELOPE_FORMAT_VERSION = "segment-authoring-envelope/1.0";

    /** The frozen Runtime schema version the capability declares compatibility with. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /** The one plan preview card kind a resolved rule-based Segment contributes. */
    public static final String PREVIEW_CARD_KIND = PlanPreviewCard.SEGMENT_PREVIEW;

    /** The integration API version stamped into every bounded terminal outcome. */
    public static final String INTEGRATION_VERSION = "segment-authoring-integration/1.0";

    /**
     * The dedicated capability/Gate identity of the T18 Existing Audience Reference extension.
     *
     * <p>It is deliberately a different identity from {@link #CAPABILITY_CODE}: an approval of
     * {@code segment.rule-based} must never activate the reference extension, because the reference extension
     * has its own corpus, its own deterministic inputs and its own model inputs.
     */
    public static final String REFERENCE_CAPABILITY_CODE = "segment.existing-audience-reference";

    /** The reference-readiness states the authoring path may report today. */
    public static final String REFERENCE_EXPERIMENTAL = SegmentAuthoringReferencePins.EXPERIMENTAL;

    /** The reference-readiness state of material produced without a real-model, human-reviewed run. */
    public static final String REFERENCE_DEVELOPMENT_ONLY = SegmentAuthoringReferencePins.DEVELOPMENT_ONLY;

    private SegmentAuthoringVersions() {
    }
}