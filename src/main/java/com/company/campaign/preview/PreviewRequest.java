package com.company.campaign.preview;

import com.company.campaign.segment.dsl.contract.SegmentDsl;

/**
 * The complete decoded Preview request: the accepted T05 Segment DSL document plus the safe presentation
 * flags.
 *
 * <p>It has no field for a plan, SQL, catalog/storage identifier, fixture path or identifier, FX input,
 * authorization result, tenant/program/user/permission/DataScope, {@code asOf}, timezone, watermark,
 * snapshot override or backend selection. There is nothing to bind such a fact to, so a forged same-named
 * JSON field is rejected by the strict codec rather than silently ignored.
 */
public record PreviewRequest(SegmentDsl segmentDsl, PreviewPresentation presentation) {

    public PreviewRequest {
        if (segmentDsl == null) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_NULL_FIELD, "segmentDsl");
        }
        if (presentation == null) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_NULL_FIELD, "presentation");
        }
    }
}