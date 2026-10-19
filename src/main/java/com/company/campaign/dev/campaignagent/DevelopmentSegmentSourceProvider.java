package com.company.campaign.dev.campaignagent;

import com.company.campaign.campaignagent.CampaignSegmentSourceProvider;

/**
 * The host-owned resolved-or-terminal Segment source of one utterance, for the development host.
 *
 * <p>It is the accepted development interface, now extending the neutral {@link CampaignSegmentSourceProvider},
 * so the development host and the production Campaign host share one neutral type while the development host
 * keeps its clearly named development boundary. The honest {@link #none()} provider is retained unchanged, so
 * the development configuration's explicit "no Segment source at all" wiring is byte-for-byte the same.
 */
@FunctionalInterface
public interface DevelopmentSegmentSourceProvider extends CampaignSegmentSourceProvider {

    /**
     * The honest inert provider: nothing is resolved, so the Segment Skill is bounded-unavailable.
     *
     * <p>It returns {@code null} rather than a fabricated submission, because this build has <em>no</em> Segment
     * source at all: it does not merely lack an artifact for this utterance.
     */
    static DevelopmentSegmentSourceProvider none() {
        return (conversationId, runId, utterance) -> null;
    }
}