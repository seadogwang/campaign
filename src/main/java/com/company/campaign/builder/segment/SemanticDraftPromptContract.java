package com.company.campaign.builder.segment;

import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;

/** Versioned model-facing contract shared by discovery and Metadata-refinement stages. */
public final class SemanticDraftPromptContract {

    static final String RESOURCE = "prompts/segment-semantic-draft-v1.md";
    private static final SegmentPromptContract.LoadedContract CONTRACT =
            SegmentPromptContract.loadResource(RESOURCE, "segment-semantic-draft-prompt");

    public static final String VERSION = CONTRACT.version();
    public static final String TEXT = CONTRACT.text();
    public static final ContentHash HASH = ContentHash.sha256(TEXT.getBytes(StandardCharsets.UTF_8));

    private SemanticDraftPromptContract() {
    }
}
