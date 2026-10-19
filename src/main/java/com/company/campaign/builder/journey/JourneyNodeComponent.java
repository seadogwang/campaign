package com.company.campaign.builder.journey;

import com.company.campaign.builder.compiler.CapabilityPayload;

/**
 * One normalized Journey node: its declaration reference, its exact Flow capability and the canonical payload
 * its accepted T19 Flow pack produced.
 *
 * <p>The payload is the accepted normalized value, so the reference it carries is the exact reference the
 * declaration stated — the adapter preserves references rather than rewriting them. The component is a host
 * value used to build the staged operations; it is never a model-visible projection.
 */
public record JourneyNodeComponent(
        String ref,
        String capabilityCode,
        CapabilityPayload normalized) {

    public JourneyNodeComponent {
        if (ref == null || capabilityCode == null || normalized == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_INVALID);
        }
    }

    /** Redacted diagnostics: the reference, capability and payload hash only, never a payload value. */
    @Override
    public String toString() {
        return "JourneyNodeComponent[ref=" + ref
                + ", capabilityCode=" + capabilityCode
                + ", payloadHash=" + normalized.contentHash() + ']';
    }
}