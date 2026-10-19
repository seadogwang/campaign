package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A catalog reference resolved to one stable, immutable entity version.
 *
 * <p>A Manifest never carries a display name, a "latest" alias or an unresolved code: every reference a
 * capability contract used is pinned here to the stable {@code logicalId}/{@code versionId} pair the frozen
 * catalog owned at compile time. The pair is what a later Preview, Save or Publish re-verifies.
 */
public record ResolvedCatalogReference(
        String referenceCode,
        String entityKind,
        String logicalId,
        String versionId) {

    private static final Pattern CODE = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public ResolvedCatalogReference {
        if (referenceCode == null || !CODE.matcher(referenceCode).matches()) {
            throw new IllegalArgumentException("referenceCode must be a bounded canonical identifier");
        }
        if (entityKind == null || !KIND.matcher(entityKind).matches()) {
            throw new IllegalArgumentException("entityKind must be a bounded uppercase machine code");
        }
        if (logicalId == null || !CODE.matcher(logicalId).matches()) {
            throw new IllegalArgumentException("logicalId must be a bounded canonical identifier");
        }
        if (versionId == null || !CODE.matcher(versionId).matches()) {
            throw new IllegalArgumentException("versionId must be a bounded canonical identifier");
        }
    }

    /** Deterministic order: entity kind, then reference code, then the pinned version. */
    public static int order(ResolvedCatalogReference left, ResolvedCatalogReference right) {
        int kind = left.entityKind.compareTo(right.entityKind);
        if (kind != 0) {
            return kind;
        }
        int code = left.referenceCode.compareTo(right.referenceCode);
        if (code != 0) {
            return code;
        }
        int logical = left.logicalId.compareTo(right.logicalId);
        return logical != 0 ? logical : left.versionId.compareTo(right.versionId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResolvedCatalogReference reference
                && Objects.equals(referenceCode, reference.referenceCode)
                && Objects.equals(entityKind, reference.entityKind)
                && Objects.equals(logicalId, reference.logicalId)
                && Objects.equals(versionId, reference.versionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(referenceCode, entityKind, logicalId, versionId);
    }

    @Override
    public String toString() {
        return "ResolvedCatalogReference[kind=" + entityKind + ", logicalId=" + logicalId
                + ", versionId=" + versionId + ']';
    }
}