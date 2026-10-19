package com.company.campaign.journey.graph;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One canonical Journey node derived from one resolved, enabled T19 Flow component.
 *
 * <p>It carries exactly the facts that determine node identity and semantics: the stable logical identifier,
 * the derived {@link JourneyNodeKind}, the exact capability and contract version, the already-normalized
 * payload hash and the canonical hash of the component's exact immutable catalog references. It never carries
 * the payload itself, a template body, a reward description, a provider response or a re-resolved catalog
 * pointer; the referenced content stays in the source Manifest under its own stable identity.
 */
public record JourneyNode(
        String logicalId,
        JourneyNodeKind kind,
        String capabilityCode,
        String contractVersion,
        ContentHash payloadHash,
        ContentHash catalogReferenceHash) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");

    public JourneyNode {
        if (logicalId == null || !IDENTIFIER.matcher(logicalId).matches()) {
            throw new IllegalArgumentException("logicalId must be a bounded canonical identifier");
        }
        Objects.requireNonNull(kind, "kind");
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new IllegalArgumentException("capabilityCode must be a bounded canonical capability code");
        }
        if (contractVersion == null || !VERSION.matcher(contractVersion).matches()) {
            throw new IllegalArgumentException("contractVersion must be a bounded version token");
        }
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(catalogReferenceHash, "catalogReferenceHash");
    }

    /** True when this node terminates a path. */
    public boolean isTerminal() {
        return kind.isTerminal();
    }

    /** Deterministic canonical order: by stable logical identifier. */
    public static int order(JourneyNode left, JourneyNode right) {
        return left.logicalId.compareTo(right.logicalId);
    }

    /** Redacted diagnostics: only already-safe machine facts. */
    @Override
    public String toString() {
        return "JourneyNode[logicalId=" + logicalId
                + ", kind=" + kind
                + ", capabilityCode=" + capabilityCode
                + ", contractVersion=" + contractVersion
                + ", payloadHash=" + payloadHash + ']';
    }
}
