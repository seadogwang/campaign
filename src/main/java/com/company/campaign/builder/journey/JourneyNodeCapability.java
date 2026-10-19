package com.company.campaign.builder.journey;

/**
 * One closed, bounded fact about an accepted Flow node capability.
 *
 * <p>It carries only what the node catalog may publish: the exact capability code, the closed node kind it
 * derives, whether that kind is terminal and the exact contract version. It carries no payload schema, no
 * template, no reward data and no identity, so projecting it can disclose nothing.
 */
public record JourneyNodeCapability(
        String capabilityCode,
        String nodeKind,
        boolean terminal,
        String contractVersion) {

    public JourneyNodeCapability {
        if (capabilityCode == null || nodeKind == null || contractVersion == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.CATALOG_INVALID);
        }
    }

    /** Redacted diagnostics: only the already-safe machine facts. */
    @Override
    public String toString() {
        return "JourneyNodeCapability[capabilityCode=" + capabilityCode
                + ", nodeKind=" + nodeKind
                + ", terminal=" + terminal
                + ", contractVersion=" + contractVersion + ']';
    }
}