package com.company.campaign.builder.segment;

import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;

/** Versioned model-facing contract for a clarified typed Metric expression. */
public final class MetricExpressionPromptContract {

    static final String RESOURCE = "prompts/metric-expression-v1.md";
    private static final SegmentPromptContract.LoadedContract CONTRACT =
            SegmentPromptContract.loadResource(RESOURCE, "metric-expression-prompt");

    public static final String VERSION = CONTRACT.version();
    public static final String TEXT = CONTRACT.text();
    public static final ContentHash HASH = ContentHash.sha256(TEXT.getBytes(StandardCharsets.UTF_8));

    private MetricExpressionPromptContract() {
    }
}
