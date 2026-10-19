package com.company.campaign.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;

/**
 * The host-owned resolved-or-terminal Segment source of one utterance, for either Campaign host.
 *
 * <p>The Campaign Agent never resolves Segment intent itself: the accepted Segment Agent/authoring path does,
 * and this port is where a host supplies its accepted result. A provider returns {@code null} when it cannot
 * resolve the utterance at all, which makes the Segment Skill report a bounded {@code Unavailable} rather than
 * fabricating a source, and it returns a bounded {@link CampaignSegmentSubmission.Unavailable} when it can
 * describe <em>why</em> it could not resolve honestly. It never parses the utterance with local keywords and
 * never returns a model-hallucinated Segment.
 *
 * <p>The four-argument form also receives the exact continuation evidence of the Segment clarification this
 * conversation already asked, so a later turn can replay the same deterministic resolution through the accepted
 * Segment Agent. The continuation is host/session state; it is never routing-model output and never a client
 * field. A provider that knows only how to resolve a fresh utterance inherits the three-argument behavior
 * unchanged.
 *
 * <p>It is neutral — it lives outside every {@code dev} package — so the production Campaign host supplies its
 * own provider over the production model and the genuine Preview port without importing the development type.
 */
@FunctionalInterface
public interface CampaignSegmentSourceProvider {

    /** The accepted resolved-or-terminal Segment submission of one utterance, or null when none is resolved. */
    CampaignSegmentSubmission source(String conversationId, String runId, String utterance);

    /**
     * The accepted Segment submission of one utterance, replayed under one exact clarification continuation.
     *
     * @param continuation the answered continuation of the pending Segment question, or null for a fresh turn
     */
    default CampaignSegmentSubmission source(
            String conversationId, String runId, String utterance, CampaignSegmentContinuation continuation) {
        return source(conversationId, runId, utterance);
    }

    /**
     * The honest inert provider: nothing is resolved, so the Segment Skill is bounded-unavailable.
     *
     * <p>It returns {@code null} rather than a fabricated submission, because a build using it has <em>no</em>
     * Segment source at all: it does not merely lack an artifact for this utterance.
     */
    static CampaignSegmentSourceProvider none() {
        return (conversationId, runId, utterance) -> null;
    }
}