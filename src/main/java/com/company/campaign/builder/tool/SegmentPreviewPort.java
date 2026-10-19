package com.company.campaign.builder.tool;

import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.segment.dsl.contract.SegmentDsl;

/**
 * The injected, typed read-only Safe Preview port the Agent composes.
 *
 * <p>It accepts a host-owned DSL document and host-owned presentation flags and returns the genuine T09C
 * response. It deliberately has no parameter for a Preview request body, a plan, a backend, a fixture, a
 * storage catalog, an {@code asOf}, a timezone, a watermark, a tenant or a DataScope, so a caller cannot build
 * its own Preview request or choose a backend. The trusted context and backend are resolved inside the
 * implementation from injected host services.
 */
public interface SegmentPreviewPort {

    /** Runs one genuine T09C Safe Preview over the host-owned document and presentation. */
    PreviewResponse preview(SegmentDsl document, PreviewPresentation presentation);
}