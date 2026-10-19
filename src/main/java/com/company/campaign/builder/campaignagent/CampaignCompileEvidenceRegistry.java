package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.CapabilityCompileContext;

import java.util.Optional;

/**
 * A host-owned, optional, per-build bridge between the conversation flow and the plan compile service.
 *
 * <p>The registry stages the frozen compile evidence when a conversation accepts a Segment Ready result
 * (keyed by host-owned conversationId + runId, overwriting any prior staging for that pair), then binds
 * that evidence to the final proposal hash during finalization. The plan compile service looks it up by
 * proposal hash. Neither side knows the other's implementation; a host that does not wire one is
 * unaffected.
 *
 * <p>A host owns the lifecycle: a production host would wire its own durable version, and a development
 * host wires a bounded in-memory one that evicts stale entries. The interface carries no scenario fact,
 * no shoe term, no lifecycle name and no evaluation specifics: it's just "a conversation stages evidence,
 * then binds it to a proposal hash," and a build that only runs components without a segment/Journey is
 * never asked to provide them.
 */
public interface CampaignCompileEvidenceRegistry {

    /**
     * Stages the host-owned compile evidence by conversation+run identity, overwriting any prior
     * staged entry for the same pair. This is called when a Segment Ready result is accepted during
     * a build turn, before the proposal hash is known.
     *
     * @param conversationId  the host-owned conversation identity
     * @param runId           the host-owned run identity
     * @param context         the capability compile context
     * @param packIndex       the frozen Journey pack index
     */
    void stage(String conversationId, String runId, CapabilityCompileContext context, T19PackIndex packIndex);

    /**
     * Binds the staged compile evidence for one conversation+run pair to the final proposal hash,
     * moving it so the plan compile service can look it up. Fails closed with
     * {@link CampaignAgentException} when no staged evidence exists for the given pair.
     *
     * @param conversationId  the host-owned conversation identity
     * @param runId           the host-owned run identity
     * @param proposalHash    the canonical proposal hash to bind to
     */
    void bind(String conversationId, String runId, String proposalHash);

    /**
     * Looks up the host-owned compile evidence for one proposal hash.
     *
     * @param proposalHash  the canonical proposal hash
     * @return the frozen evidence, or empty when none is bound
     */
    Optional<CampaignCompileEvidence> lookup(String proposalHash);

    /**
     * Removes the bound evidence for one proposal hash, if any.
     *
     * @param proposalHash  the canonical proposal hash
     */
    void remove(String proposalHash);
}
