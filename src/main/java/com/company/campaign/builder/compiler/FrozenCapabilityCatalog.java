package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The explicit, immutable capability catalog a compile is pinned to.
 *
 * <p>Every capability contract and its composition metadata is a frozen input supplied by the host. The
 * compiler never reads a mutable or "latest" registry, so the same catalog version and hash always describe
 * the same contract set — and a change to any contract, version or slot changes the catalog hash and
 * therefore every downstream artifact hash.
 *
 * <p>Contracts are stored in deterministic capability-code order and a duplicate capability is refused
 * outright, so "which contract applies" can never depend on list position or on which duplicate won.
 */
public final class FrozenCapabilityCatalog {

    private static final Pattern VERSION = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final List<CapabilityContract> contracts;
    private final Map<String, CapabilityContract> byCode;
    private final List<CapabilityPin> pins;

    public FrozenCapabilityCatalog(
            String catalogVersion,
            ContentHash catalogHash,
            List<CapabilityContract> contracts) {
        if (catalogVersion == null || !VERSION.matcher(catalogVersion).matches()) {
            throw frozenInput("catalogVersion", "A frozen catalog requires a bounded canonical version.");
        }
        Objects.requireNonNull(catalogHash, "catalogHash");
        this.catalogVersion = catalogVersion;
        this.catalogHash = catalogHash;

        List<CapabilityContract> sorted = new ArrayList<>();
        if (contracts != null) {
            for (CapabilityContract contract : contracts) {
                sorted.add(Objects.requireNonNull(contract, "contract"));
            }
        }
        Map<String, CapabilityContract> index = new LinkedHashMap<>();
        List<CapabilityPin> pinList = new ArrayList<>();
        for (CapabilityContract contract : sorted) {
            String code = contract.capabilityCode();
            String version = contract.contractVersion();
            // Read every frozen accessor exactly once here; a contract's metadata is a fact of one version.
            CompositionSlot slot = contract.slot();
            contract.enabled();
            contract.runtimeCompatibility();
            if (code == null || !Pattern.compile("^[a-z][a-z0-9.-]{0,63}$").matcher(code).matches()) {
                throw frozenInput("capabilityCode", "A frozen capability requires a bounded canonical code.");
            }
            if (version == null || !VERSION.matcher(version).matches()) {
                throw frozenInput("contractVersion", "A frozen capability requires a bounded contract version.");
            }
            Objects.requireNonNull(slot, "slot");
            if (index.putIfAbsent(code, contract) != null) {
                throw frozenInput("duplicateCapability", "A frozen catalog must not repeat one capability code.");
            }
            pinList.add(new CapabilityPin(code, version));
        }
        List<CapabilityContract> ordered = new ArrayList<>(index.values());
        ordered.sort((left, right) -> left.capabilityCode().compareTo(right.capabilityCode()));
        pinList.sort(CapabilityPin::order);
        this.contracts = List.copyOf(ordered);
        this.byCode = Map.copyOf(index);
        this.pins = List.copyOf(pinList);
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public ContentHash catalogHash() {
        return catalogHash;
    }

    /** Every frozen contract, in deterministic capability-code order. */
    public List<CapabilityContract> contracts() {
        return contracts;
    }

    /** Every capability/contract-version pin the catalog declares, in deterministic order. */
    public List<CapabilityPin> pins() {
        return pins;
    }

    /** The frozen contract for a capability code, or empty when the catalog does not declare it. */
    public Optional<CapabilityContract> find(String capabilityCode) {
        return Optional.ofNullable(capabilityCode == null ? null : byCode.get(capabilityCode));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrozenCapabilityCatalog catalog
                && Objects.equals(catalogVersion, catalog.catalogVersion)
                && Objects.equals(catalogHash, catalog.catalogHash)
                && Objects.equals(pins, catalog.pins);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalogVersion, catalogHash, pins);
    }

    @Override
    public String toString() {
        return "FrozenCapabilityCatalog[version=" + catalogVersion
                + ", capabilities=" + contracts.size()
                + ", hash=" + catalogHash + ']';
    }

    private static CompilerException frozenInput(String invariant, String message) {
        return new CompilerException(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID, invariant, message);
    }
}