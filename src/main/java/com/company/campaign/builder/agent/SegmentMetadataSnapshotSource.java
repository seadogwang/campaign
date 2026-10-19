package com.company.campaign.builder.agent;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * The injected host port that resolves the one exact permitted T04 Metadata snapshot of a run.
 *
 * <p>The snapshot is a trusted host input, never a request field. The adapter verifies that the resolved
 * snapshot identifier equals the trusted context pin and refuses the run otherwise, so a drifting "current"
 * snapshot can never be substituted for the pinned one.
 */
public interface SegmentMetadataSnapshotSource {

    /** Resolves the exact permitted snapshot for the trusted context, or null when none is available. */
    MetadataSnapshot resolve(TrustedExecutionContext context);
}