package com.company.campaign.builder.capability.catalog;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The explicit, immutable, hash-bound catalog of Reward Versions a compile is pinned to.
 *
 * <p>Every reward version is a frozen host-owned input. Resolution accepts only a bounded reference code and
 * yields the one exact version that code names; there is no {@code latest} lookup, no mutable current
 * pointer and no fallback, so the same catalog version always describes the same reward set and the same
 * reference always pins the same {@code logicalId}/{@code versionId}/content hash.
 *
 * <p>Entries are stored in deterministic reference-code order. A repeated reference code, a repeated
 * {@code logicalId}/{@code versionId} identity and a hash conflict — the same identity pinned to two
 * different content hashes — all fail closed, so "which reward applies" can never depend on list position
 * or on which duplicate happened to win.
 */
public final class RewardCatalog {

    /** The one stable entity kind every Reward reference resolves under. */
    public static final String ENTITY_KIND = "REWARD";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern VERSION = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final List<RewardVersion> versions;
    private final Map<String, RewardVersion> byReferenceCode;

    /**
     * Builds one catalog and stamps it with its own canonical content hash.
     *
     * <p>The hash covers the catalog version and every version entry, so a change to any reward version
     * changes the catalog hash and therefore every artifact that pins it.
     */
    public static RewardCatalog of(String catalogVersion, List<RewardVersion> versions) {
        RewardCatalog unhashed = new RewardCatalog(catalogVersion, null, versions);
        return unhashed.withCatalogHash(unhashed.recomputeCatalogHash());
    }

    public RewardCatalog(String catalogVersion, ContentHash catalogHash, List<RewardVersion> versions) {
        if (catalogVersion == null || !VERSION.matcher(catalogVersion).matches()) {
            throw CatalogSafety.invalid("rewardCatalogVersion");
        }
        this.catalogVersion = catalogVersion;
        this.catalogHash = catalogHash;

        List<RewardVersion> source = new ArrayList<>();
        if (versions != null) {
            for (RewardVersion version : versions) {
                source.add(Objects.requireNonNull(version, "version"));
            }
        }
        source.sort((left, right) -> left.referenceCode().compareTo(right.referenceCode()));

        Map<String, RewardVersion> index = new LinkedHashMap<>();
        Map<String, RewardVersion> identities = new LinkedHashMap<>();
        for (RewardVersion version : source) {
            if (index.putIfAbsent(version.referenceCode(), version) != null) {
                throw CatalogSafety.invalid("rewardCatalogDuplicateReference");
            }
            String identity = version.logicalId() + '|' + version.versionId();
            RewardVersion existing = identities.putIfAbsent(identity, version);
            if (existing != null) {
                if (!Objects.equals(existing.contentHash(), version.contentHash())) {
                    throw CatalogSafety.invalid("rewardCatalogHashConflict");
                }
                throw CatalogSafety.invalid("rewardCatalogDuplicateVersion");
            }
        }
        this.versions = List.copyOf(index.values());
        this.byReferenceCode = Map.copyOf(index);
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public ContentHash catalogHash() {
        return catalogHash;
    }

    /** Every frozen reward version, in deterministic reference-code order. */
    public List<RewardVersion> versions() {
        return versions;
    }

    /**
     * The one exact reward version a reference code names, or empty when the catalog does not hold it.
     *
     * <p>There is deliberately no "latest" or "current" lookup: an absent code is absent, never the newest
     * entry that happens to exist.
     */
    public Optional<RewardVersion> resolve(String referenceCode) {
        return Optional.ofNullable(referenceCode == null ? null : byReferenceCode.get(referenceCode));
    }

    /** The same catalog carrying the given hash pin. */
    public RewardCatalog withCatalogHash(ContentHash hash) {
        return new RewardCatalog(catalogVersion, hash, versions);
    }

    /** The canonical UTF-8 bytes of this catalog with the hash pin excluded. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(descriptor());
    }

    /** The canonical content hash of this catalog with the hash pin excluded. */
    public ContentHash recomputeCatalogHash() {
        return CANONICAL_JSON.hash(descriptor());
    }

    /** The canonical descriptor the catalog bytes and hash both cover. */
    private Map<String, Object> descriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("entityKind", ENTITY_KIND);
        descriptor.put("catalogVersion", catalogVersion);
        descriptor.put("versions", versions);
        return descriptor;
    }

    /** Refuses a catalog whose declared hash pin is absent or does not match its own content. */
    public void requireValidCatalogHash() {
        if (catalogHash == null || !catalogHash.equals(recomputeCatalogHash())) {
            throw CatalogSafety.invalid("rewardCatalogHash");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RewardCatalog catalog
                && Objects.equals(catalogVersion, catalog.catalogVersion)
                && Objects.equals(catalogHash, catalog.catalogHash)
                && Objects.equals(versions, catalog.versions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalogVersion, catalogHash, versions);
    }

    @Override
    public String toString() {
        return "RewardCatalog[version=" + catalogVersion
                + ", rewards=" + versions.size()
                + ", hash=" + catalogHash + ']';
    }
}