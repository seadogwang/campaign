package com.company.campaign.builder.compiler;

import java.util.Objects;

/**
 * One pinned capability/contract-version pair used by a resolved Manifest.
 *
 * <p>The pin set is what a later Preview, Save or Publish re-verifies: if the frozen catalog or the
 * capability contract version moved, the artifact is stale rather than silently reinterpreted.
 */
public record CapabilityPin(String capabilityCode, String contractVersion) {

    public CapabilityPin {
        Objects.requireNonNull(capabilityCode, "capabilityCode");
        Objects.requireNonNull(contractVersion, "contractVersion");
    }

    /** Deterministic order: capability code, then contract version. */
    public static int order(CapabilityPin left, CapabilityPin right) {
        int code = left.capabilityCode.compareTo(right.capabilityCode);
        return code != 0 ? code : left.contractVersion.compareTo(right.contractVersion);
    }
}