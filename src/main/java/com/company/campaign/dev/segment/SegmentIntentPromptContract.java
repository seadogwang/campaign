package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.SegmentPromptContract;
import com.company.campaign.shared.canonical.ContentHash;

/** Fixed, versioned model-facing contract for the formal Segment Intent v1.3 wire shape. */
public final class SegmentIntentPromptContract {

    public static final String VERSION = SegmentPromptContract.VERSION;
    public static final String TEXT = SegmentPromptContract.TEXT;
    public static final ContentHash HASH = SegmentPromptContract.HASH;

    private SegmentIntentPromptContract() {
    }
}
