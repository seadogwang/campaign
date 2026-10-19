package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.security.context.TrustedContextProvider;

/**
 * The one explicit, immutable port through which a host supplies the frozen Segment Agent assets of the
 * production Campaign Agent host.
 *
 * <p>The production host owns the production structured model and the genuine Preview port and binds
 * <em>those exact objects</em> into the formal Segment Agent environment through this port, so the Segment path
 * runs the accepted Segment Agent, authoring integration and Preview chain over the same single model the
 * Campaign host uses — never over a second model, a fixture or a fake Preview. The host supplies only the
 * frozen, host-owned Segment facts: the committed Skill definition, the pinned Metadata snapshot boundary, the
 * accepted authorization and Metadata query services, and the accepted resolution, defaults and validation
 * policies.
 *
 * <p>It carries no model handle, no credential, no Preview implementation and no mutable state of its own: the
 * model and Preview ports are given to {@link #segmentAgentEnvironment} by the host, not held here.
 */
public interface ProductionSegmentAssets {

    /** The committed Segment Skill definition the accepted Segment Agent runs. */
    SegmentSkillDefinition segmentSkill();

    /** The one pinned Metadata snapshot these assets resolve; the same one the Agent environment pins. */
    MetadataSnapshot snapshot();

    /**
     * Builds the one formal Segment Agent environment over the production model and the genuine Preview port.
     *
     * @param modelPort   the one production structured model, bound as the Segment intent model
     * @param previewPort the one genuine Preview port
     * @param identity    the production trusted-context provider of the request boundary
     */
    SegmentAgentEnvironment segmentAgentEnvironment(
            SegmentIntentModelPort modelPort,
            SegmentPreviewPort previewPort,
            TrustedContextProvider identity);
}