package com.company.campaign.segment.intent;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only way a registered-default provenance or currency request can be confirmed.
 *
 * <p>The port is injected by the host and is the sole authority on which defaults exist. Intent parsing
 * never invents a default: a clause that cites one is accepted only when this source returns the exact
 * revision the clause named. An implementation must be immutable and must not read a clock, locale or
 * global state, so the same inputs always verify the same way.
 */
public interface RegisteredDefaultSource {

    /** Finds the exact registered revision for a default id, or empty when no such default exists. */
    Optional<RegisteredDefaultRevision> find(String defaultId);

    /** The exact revision label of the whole registry, pinned into every resolution that consults it. */
    String registryVersion();

    /** The canonical content hash of every registered revision, so a registry drift is detectable. */
    ContentHash registryHash();

    /** An immutable registered-default revision: its id, exact version, content hash and canonical value. */
    record RegisteredDefaultRevision(
            String defaultId,
            String version,
            ContentHash contentHash,
            String value
    ) {

        public RegisteredDefaultRevision {
            defaultId = IntentTexts.requireReferenceId(defaultId, "defaultId");
            version = IntentTexts.requireText(version, "version");
            if (version.isEmpty() || contentHash == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                        "A registered revision requires an exact version and content hash");
            }
            if (value == null || value.length() > IntentLimits.MAX_TEXT_LENGTH) {
                throw IntentException.invalid(
                        IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                        "A registered revision requires a bounded value");
            }
        }
    }

    /**
     * An immutable revision-id-keyed registry.
     *
     * <p>Constructed from explicit revisions only; there is no mutable registration path and no lookup that
     * falls back to a "current" value, so a caller can never be handed a drifted revision.
     */
    final class Registry implements RegisteredDefaultSource {

        private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

        private final String registryVersion;
        private final ContentHash registryHash;
        private final Map<String, RegisteredDefaultRevision> revisions;

        public Registry(String registryVersion, Iterable<RegisteredDefaultRevision> revisions) {
            this.registryVersion = IntentTexts.requireText(registryVersion, "registryVersion");
            if (this.registryVersion.isEmpty()) {
                throw IntentException.invalid(
                        IntentErrorCodes.DEFAULT_REFERENCE_INVALID, "A registry requires a revision label");
            }
            Map<String, RegisteredDefaultRevision> copy = new LinkedHashMap<>();
            if (revisions != null) {
                for (RegisteredDefaultRevision revision : revisions) {
                    if (revision == null) {
                        throw IntentException.invalid(
                                IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                                "A registered revision must not be null");
                    }
                    if (copy.putIfAbsent(revision.defaultId(), revision) != null) {
                        throw IntentException.invalid(
                                IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                                "A registered default id is declared more than once");
                    }
                }
            }
            this.revisions = Map.copyOf(copy);
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("registryVersion", this.registryVersion);
            material.put("revisions", hashMaterial(copy));
            this.registryHash = CANONICAL_JSON.hash(material);
        }

        @Override
        public Optional<RegisteredDefaultRevision> find(String defaultId) {
            return Optional.ofNullable(revisions.get(defaultId));
        }

        @Override
        public String registryVersion() {
            return registryVersion;
        }

        @Override
        public ContentHash registryHash() {
            return registryHash;
        }

        private static List<Map<String, Object>> hashMaterial(
                Map<String, RegisteredDefaultRevision> revisions) {
            List<RegisteredDefaultRevision> ordered = new ArrayList<>(revisions.values());
            ordered.sort(Comparator.comparing(RegisteredDefaultRevision::defaultId));
            List<Map<String, Object>> material = new ArrayList<>(ordered.size());
            for (RegisteredDefaultRevision revision : ordered) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("defaultId", revision.defaultId());
                entry.put("version", revision.version());
                entry.put("contentHash", revision.contentHash().value());
                entry.put("value", revision.value());
                material.add(entry);
            }
            return material;
        }
    }

    /** An empty immutable registry, for callers that resolve without consulting any default. */
    static RegisteredDefaultSource empty() {
        return new Registry("registered-defaults/empty", List.of());
    }
}