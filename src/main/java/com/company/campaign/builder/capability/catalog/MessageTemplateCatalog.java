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
 * The explicit, immutable, hash-bound catalog of Message Template Versions a compile is pinned to.
 *
 * <p>Every template version is a frozen host-owned input. Resolution accepts only a bounded reference code
 * and yields the one exact version that code names; there is no {@code latest} lookup, no mutable current
 * pointer and no fallback to a neighbouring version, so the same catalog version always describes the same
 * template set and the same reference always pins the same {@code logicalId}/{@code versionId}/content hash.
 *
 * <p>Entries are stored in deterministic reference-code order. A repeated reference code, a repeated
 * {@code logicalId}/{@code versionId} identity and a hash conflict — the same identity pinned to two
 * different content hashes — all fail closed, so "which template applies" can never depend on list position
 * or on which duplicate happened to win.
 */
public final class MessageTemplateCatalog {

    /** The one stable entity kind every Message Template reference resolves under. */
    public static final String ENTITY_KIND = "MESSAGE_TEMPLATE";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern VERSION = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final List<MessageTemplateVersion> versions;
    private final Map<String, MessageTemplateVersion> byReferenceCode;

    /**
     * Builds one catalog and stamps it with its own canonical content hash.
     *
     * <p>The hash covers the catalog version and every version entry, so a change to any template version
     * changes the catalog hash and therefore every artifact that pins it.
     */
    public static MessageTemplateCatalog of(String catalogVersion, List<MessageTemplateVersion> versions) {
        MessageTemplateCatalog unhashed =
                new MessageTemplateCatalog(catalogVersion, null, versions);
        return unhashed.withCatalogHash(unhashed.recomputeCatalogHash());
    }

    public MessageTemplateCatalog(
            String catalogVersion,
            ContentHash catalogHash,
            List<MessageTemplateVersion> versions) {
        if (catalogVersion == null || !VERSION.matcher(catalogVersion).matches()) {
            throw CatalogSafety.invalid("messageTemplateCatalogVersion");
        }
        this.catalogVersion = catalogVersion;
        this.catalogHash = catalogHash;

        List<MessageTemplateVersion> source = new ArrayList<>();
        if (versions != null) {
            for (MessageTemplateVersion version : versions) {
                source.add(Objects.requireNonNull(version, "version"));
            }
        }
        source.sort((left, right) -> left.referenceCode().compareTo(right.referenceCode()));

        Map<String, MessageTemplateVersion> index = new LinkedHashMap<>();
        Map<String, MessageTemplateVersion> identities = new LinkedHashMap<>();
        for (MessageTemplateVersion version : source) {
            if (index.putIfAbsent(version.referenceCode(), version) != null) {
                throw CatalogSafety.invalid("messageTemplateCatalogDuplicateReference");
            }
            String identity = version.logicalId() + '|' + version.versionId();
            MessageTemplateVersion existing = identities.putIfAbsent(identity, version);
            if (existing != null) {
                if (!Objects.equals(existing.contentHash(), version.contentHash())) {
                    throw CatalogSafety.invalid("messageTemplateCatalogHashConflict");
                }
                throw CatalogSafety.invalid("messageTemplateCatalogDuplicateVersion");
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

    /** Every frozen template version, in deterministic reference-code order. */
    public List<MessageTemplateVersion> versions() {
        return versions;
    }

    /**
     * The one exact template version a reference code names, or empty when the catalog does not hold it.
     *
     * <p>There is deliberately no "latest" or "current" lookup: an absent code is absent, never the newest
     * entry that happens to exist.
     */
    public Optional<MessageTemplateVersion> resolve(String referenceCode) {
        return Optional.ofNullable(referenceCode == null ? null : byReferenceCode.get(referenceCode));
    }

    /** The same catalog carrying the given hash pin. */
    public MessageTemplateCatalog withCatalogHash(ContentHash hash) {
        return new MessageTemplateCatalog(catalogVersion, hash, versions);
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
            throw CatalogSafety.invalid("messageTemplateCatalogHash");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MessageTemplateCatalog catalog
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
        return "MessageTemplateCatalog[version=" + catalogVersion
                + ", templates=" + versions.size()
                + ", hash=" + catalogHash + ']';
    }
}