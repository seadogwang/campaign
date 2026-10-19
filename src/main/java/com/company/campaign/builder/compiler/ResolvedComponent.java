package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One completely resolved component of a {@link ResolvedCampaignManifest}.
 *
 * <p>Every component carries its stable {@code logicalId}, the exact capability and contract version that
 * produced it, the frozen composition slot it occupies, its normalized canonical payload and every catalog
 * reference resolved to a stable {@code logicalId}/{@code versionId} pair. There is no operation kind, no
 * {@code clientRef}, no unresolved code, no label synonym, no authorization fact and no trusted
 * tenant/user value anywhere in it.
 */
public record ResolvedComponent(
        String logicalId,
        String capabilityCode,
        String contractVersion,
        CompositionSlot slot,
        CapabilityPayload value,
        List<ResolvedCatalogReference> references) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public ResolvedComponent {
        requireIdentifier(logicalId, "logicalId");
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new IllegalArgumentException("capabilityCode must be a bounded canonical capability code");
        }
        requireIdentifier(contractVersion, "contractVersion");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(value, "value");
        references = canonicalReferences(references);
    }

    /** True when the two components agree on every semantic fact the Manifest hash covers. */
    public boolean hasSameSemantics(ResolvedComponent other) {
        Objects.requireNonNull(other, "other");
        return Objects.equals(capabilityCode, other.capabilityCode)
                && Objects.equals(contractVersion, other.contractVersion)
                && Objects.equals(slot, other.slot)
                && value.equals(other.value)
                && references.equals(other.references);
    }

    /**
     * References in deterministic entity-kind/code/logicalId/version order with no duplicate resolution.
     *
     * <p>The input order is a contract implementation detail, so it is normalized here rather than trusted:
     * two contracts that resolved the same references in a different order must produce identical Manifest
     * bytes.
     */
    static List<ResolvedCatalogReference> canonicalReferences(List<ResolvedCatalogReference> references) {
        if (references == null) {
            throw new IllegalArgumentException("references must not be null");
        }
        List<ResolvedCatalogReference> copy = new ArrayList<>(references.size());
        for (ResolvedCatalogReference reference : references) {
            copy.add(Objects.requireNonNull(reference, "reference"));
        }
        copy.sort(ResolvedCatalogReference::order);
        Set<String> seen = new HashSet<>();
        for (ResolvedCatalogReference reference : copy) {
            String key = reference.entityKind() + '|' + reference.referenceCode() + '|'
                    + reference.logicalId() + '|' + reference.versionId();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("references must not repeat one resolution");
            }
        }
        return List.copyOf(copy);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded canonical identifier");
        }
    }

    @Override
    public String toString() {
        return "ResolvedComponent[logicalId=" + logicalId
                + ", capabilityCode=" + capabilityCode
                + ", contractVersion=" + contractVersion
                + ", slot=" + slot.code()
                + ", references=" + references.size()
                + ", valueHash=" + value.contentHash()
                + ']';
    }
}