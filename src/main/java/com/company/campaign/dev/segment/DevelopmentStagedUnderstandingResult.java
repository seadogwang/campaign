package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.SemanticDraft;
import com.company.campaign.builder.segment.StagedClarificationBatch;

/** Development-only evidence from both staged model calls and host clarification aggregation. */
public record DevelopmentStagedUnderstandingResult(
        SemanticDraft discoveryDraft,
        SemanticDraft refinedDraft,
        StagedClarificationBatch clarificationBatch,
        String promptContractVersion,
        String promptContractHash) {
}
