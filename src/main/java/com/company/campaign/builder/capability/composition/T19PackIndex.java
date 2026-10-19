package com.company.campaign.builder.capability.composition;

import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The immutable, hash-bound index of the T19 capability packs a composition was compiled against.
 *
 * <p>It is the sole authority the {@link T19CompositionValidator} consults for "is this capability enabled,
 * at this exact contract version, in this exact composition slot, on a runtime this build can author?".
 * Because it is supplied as a frozen input rather than looked up from a mutable registry, a pack that is
 * disabled, one whose contract version moved, and one whose runtime schema is incompatible are all decided
 * against bytes that cannot change under the compile.
 *
 * <p>The index declares one expected runtime schema version. A pack whose declared runtime schema is not
 * that version, or that cannot even project a safe preview, is recorded as incompatible rather than being
 * silently accepted, so {@link #runtimeCompatible(T19PackFacts)} has teeth instead of being trivially true.
 *
 * <p>The relative slot order is frozen here, not read from a file: the five T19 slots occupy the fixed ranks
 * Message (1) &lt; Promotion (2) &lt; Flow (3) &lt; Schedule (4) &lt; Governance (5), and every non-T19
 * (pre-existing Segment) component occupies rank {@link #SEGMENT_RANK} (0). The index refuses a pack that
 * declares any other slot, so an invented T19 slot cannot enter the order.
 *
 * <p>The index holds no catalog, no repository, no provider and no mutable handle. It is immutable, ordered
 * by capability code and carries one canonical content hash over its facts.
 */
public final class T19PackIndex {

    /** The rank every non-T19 (pre-existing Segment) component occupies: before every T19 slot. */
    public static final int SEGMENT_RANK = 0;

    /** Largest permitted number of indexed T19 packs. */
    public static final int MAX_PACKS = 32;

    /** The frozen relative order of the T19 composition slots, after the Segment truth. */
    public static final List<String> T19_SLOT_ORDER = List.of(
            "campaign.message",
            "campaign.promotion",
            "campaign.flow",
            "campaign.schedule",
            "campaign.governance");

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final String expectedRuntimeSchemaVersion;
    private final List<T19PackFacts> packs;
    private final Map<String, T19PackFacts> byCapabilityCode;
    private final Map<String, Integer> slotRanks;
    private final ContentHash indexHash;

    private T19PackIndex(String expectedRuntimeSchemaVersion, List<T19PackFacts> packs) {
        if (expectedRuntimeSchemaVersion == null || expectedRuntimeSchemaVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "A T19 pack index requires the exact runtime schema version it was built against.");
        }
        if (packs == null || packs.size() > MAX_PACKS) {
            throw new IllegalArgumentException("A T19 pack index requires a bounded pack fact list.");
        }
        List<T19PackFacts> copy = new ArrayList<>(packs.size());
        Map<String, T19PackFacts> byCode = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (T19PackFacts facts : packs) {
            if (facts == null) {
                throw new IllegalArgumentException("A T19 pack index must not carry a null pack fact.");
            }
            if (byCode.putIfAbsent(facts.capabilityCode(), facts) != null) {
                throw new IllegalArgumentException(
                        "A T19 pack index must not repeat one capability code.");
            }
            int rank = T19_SLOT_ORDER.indexOf(facts.slot().code());
            if (rank < 0) {
                throw new IllegalArgumentException(
                        "A T19 pack index must declare one of the frozen T19 composition slots.");
            }
            ranks.putIfAbsent(facts.slot().code(), rank + 1);
            copy.add(facts);
        }
        copy.sort(Comparator.comparing(T19PackFacts::capabilityCode));
        this.expectedRuntimeSchemaVersion = expectedRuntimeSchemaVersion;
        this.packs = List.copyOf(copy);
        this.byCapabilityCode = Map.copyOf(byCode);
        this.slotRanks = Map.copyOf(ranks);
        this.indexHash = CANONICAL_JSON.hash(copy);
    }

    /** Builds an index from explicit frozen pack facts. */
    public static T19PackIndex of(String expectedRuntimeSchemaVersion, T19PackFacts... packs) {
        return of(expectedRuntimeSchemaVersion, List.of(packs));
    }

    /** Builds an index from explicit frozen pack facts. */
    public static T19PackIndex of(String expectedRuntimeSchemaVersion, List<T19PackFacts> packs) {
        return new T19PackIndex(expectedRuntimeSchemaVersion, packs);
    }

    /**
     * Builds an index from concrete packs, reading only their frozen facts.
     *
     * <p>This is the production wiring path: the concrete packs supply their own enabled/slot/version/runtime
     * facts, and the index freezes them. The validator never again touches the live pack, so composition is a
     * pure function of the index and the resolved component set.
     */
    public static T19PackIndex ofPacks(String expectedRuntimeSchemaVersion, CapabilityPackContract... packs) {
        Objects.requireNonNull(packs, "packs");
        List<T19PackFacts> facts = new ArrayList<>(packs.length);
        for (CapabilityPackContract pack : packs) {
            facts.add(T19PackFacts.of(pack));
        }
        return of(expectedRuntimeSchemaVersion, facts);
    }

    /** The one exact runtime schema version this index was built against. */
    public String expectedRuntimeSchemaVersion() {
        return expectedRuntimeSchemaVersion;
    }

    /** Every indexed pack fact, in deterministic capability-code order. */
    public List<T19PackFacts> packs() {
        return packs;
    }

    /** The frozen facts of one indexed capability, when the index names it. */
    public Optional<T19PackFacts> facts(String capabilityCode) {
        return Optional.ofNullable(byCapabilityCode.get(capabilityCode));
    }

    /** True when the index names the capability, whether or not it is enabled. */
    public boolean contains(String capabilityCode) {
        return capabilityCode != null && byCapabilityCode.containsKey(capabilityCode);
    }

    /**
     * The frozen rank of a T19 slot code, or {@code -1} when the code is not a frozen T19 slot.
     *
     * <p>Ranks are 1-based and follow {@link #T19_SLOT_ORDER}; {@link #SEGMENT_RANK} (0) is reserved for
     * every non-T19 component and is never returned here.
     */
    public int slotRank(String slotCode) {
        Integer rank = slotRanks.get(slotCode);
        return rank == null ? -1 : rank;
    }

    /**
     * The frozen {@link CompositionSlot} of one indexed slot code, or {@code null} when the index does not
     * occupy it.
     */
    public CompositionSlot slotFor(String slotCode) {
        for (T19PackFacts facts : packs) {
            if (facts.slot().code().equals(slotCode)) {
                return facts.slot();
            }
        }
        return null;
    }

    /**
     * True only when a pack declares exactly this index's runtime schema and can project a safe preview.
     *
     * <p>A pack that names another runtime schema, or that cannot preview at all, is incompatible: the compile
     * must not claim runtime support this build does not have.
     */
    public boolean runtimeCompatible(T19PackFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return expectedRuntimeSchemaVersion.equals(facts.runtimeCompatibility().runtimeSchemaVersion())
                && facts.runtimeCompatibility().previewSupported();
    }

    /** The canonical content hash of the index facts. */
    public ContentHash indexHash() {
        return indexHash;
    }

    /** Every indexed capability code, in deterministic order. */
    public Set<String> capabilityCodes() {
        return byCapabilityCode.keySet();
    }

    @Override
    public String toString() {
        return "T19PackIndex[packs=" + packs.size()
                + ", runtime=" + expectedRuntimeSchemaVersion
                + ", hash=" + indexHash + ']';
    }
}