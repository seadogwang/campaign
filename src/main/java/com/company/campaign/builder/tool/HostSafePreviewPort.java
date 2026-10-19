package com.company.campaign.builder.tool;

import com.company.campaign.preview.PreviewErrorCodes;
import com.company.campaign.preview.PreviewException;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;

import java.nio.charset.StandardCharsets;

/**
 * The host adapter that runs the genuine T09C Safe Preview behind the typed {@link SegmentPreviewPort}.
 *
 * <p>It encodes exactly the two-field strict request body from the host-owned document and presentation, then
 * delegates to the injected {@link SegmentPreviewService}. The document is written by the accepted T05 codec,
 * so there is one DSL serializer; the service re-decodes it through the same accepted codec. No plan, backend,
 * catalog, fixture, time or identity fact is accepted from the caller.
 */
public final class HostSafePreviewPort implements SegmentPreviewPort {

    private final SegmentPreviewService service;
    private final SegmentDslCodec dslCodec = new SegmentDslCodec();

    public HostSafePreviewPort(SegmentPreviewService service) {
        if (service == null) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, "previewService");
        }
        this.service = service;
    }

    @Override
    public PreviewResponse preview(SegmentDsl document, PreviewPresentation presentation) {
        if (document == null || presentation == null) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_NULL_FIELD, "previewRequest");
        }
        String body = "{\"segmentDsl\":" + dslCodec.writeString(document)
                + ",\"presentation\":{\"includeSample\":" + presentation.includeSample()
                + ",\"includeConditionStats\":" + presentation.includeConditionStats() + "}}";
        return service.preview(body.getBytes(StandardCharsets.UTF_8));
    }
}