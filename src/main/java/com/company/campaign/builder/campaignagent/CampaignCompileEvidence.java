package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.CapabilityCompileContext;

import java.util.Objects;

/**
 * One host-owned frozen compile evidence, bound to a proposal hash.
 *
 * <p>It carries the two typed frozen inputs a Campaign Plan compile needs beyond the standard
 * catalog/policy/instants/context: the capability compile context and the frozen Journey pack index.
 * Every field is host-owned and immutable; nothing here is read from a model output, a client payload
 * or a capability envelope.
 */
public record CampaignCompileEvidence(
        CapabilityCompileContext capabilityContext,
        T19PackIndex journeyPackIndex) {

    public CampaignCompileEvidence {
        Objects.requireNonNull(capabilityContext, "capabilityContext");
        Objects.requireNonNull(journeyPackIndex, "journeyPackIndex");
    }
}
