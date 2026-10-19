package com.company.campaign.dev.campaignagent;

import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import com.company.campaign.model.StructuredSkillInputResolvers;

import java.util.Objects;

/**
 * The development, Skill-specific typed input resolution boundary.
 *
 * <p>Its behavior is exactly the accepted development behavior — it is now a typed subclass of the neutral
 * {@link StructuredSkillInputResolvers}, so the same versioned prompt contracts and strict decoders serve the
 * development host and the production Campaign host without either importing the other. It keeps its
 * development name and its development-only transport type, so the opt-in development host and its tests are
 * source- and binary-compatible; the versioned nested prompt contracts are inherited unchanged.
 */
public final class DevelopmentSkillInputResolvers extends StructuredSkillInputResolvers {

    public DevelopmentSkillInputResolvers(DevelopmentStructuredModelPort transport) {
        super(Objects.requireNonNull(transport, "transport"));
    }

    /** A bounded diagnostic: the pinned contracts only, never a prompt or an output. */
    @Override
    public String toString() {
        return "DevelopmentSkillInputResolvers[" + super.toString() + ']';
    }
}