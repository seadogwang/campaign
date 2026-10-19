package com.company.campaign.builder.compiler;

import java.util.List;
import java.util.Objects;

/**
 * One value-minimized entry of the semantic diff.
 *
 * <p>An entry is safe to show a user: a change kind, one stable {@code logicalId}, the capability code and a
 * list of bounded field-path codes describing what changed. It never carries an unrestricted payload value,
 * a secret, a tenant or user identifier, or an internal authorization reason.
 */
public record DiffEntry(ChangeKind kind, String logicalId, String capabilityCode, List<String> fieldPaths) {

    public DiffEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(logicalId, "logicalId");
        Objects.requireNonNull(capabilityCode, "capabilityCode");
        fieldPaths = List.copyOf(Objects.requireNonNull(fieldPaths, "fieldPaths"));
    }

    @Override
    public String toString() {
        return "DiffEntry[kind=" + kind + ", logicalId=" + logicalId
                + ", capabilityCode=" + capabilityCode + ", fields=" + fieldPaths + ']';
    }
}