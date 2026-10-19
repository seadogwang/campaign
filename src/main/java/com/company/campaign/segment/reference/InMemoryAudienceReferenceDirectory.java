package com.company.campaign.segment.reference;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A synchronized, in-memory {@link AudienceReferenceDirectory} for tests and explicitly local use only.
 *
 * <p>It is not a production adapter: there is no persistence, no authorization service and no replication
 * here. What it does model honestly are the three facts the resolver's semantics depend on:
 *
 * <ul>
 *   <li><strong>visibility is not referenceability.</strong> An audience can be listable and yet not
 *       referenceable, which is how a denied candidate that discovery already surfaced produces a denial
 *       instead of a fabricated "no such audience";</li>
 *   <li><strong>only published immutable versions are offered.</strong> A version choice is registered with its
 *       exact pin and publication instant, and there is no method that answers "the latest one" — the choices
 *       are returned in exact pin order, never in publication order;</li>
 *   <li><strong>the directory has one exact revision.</strong> {@link #contentHash()} covers every registered
 *       fact, so a clarification produced against one state is comparable with the state a later answer meets.
 * </ul>
 *
 * <p>Registration is deliberately explicit and total: there is no wildcard audience, no default and no
 * fallback, so a test can never accidentally read a candidate it did not declare.
 */
public final class InMemoryAudienceReferenceDirectory implements AudienceReferenceDirectory {

    /** The version identity of this local adapter state, so a resolution can pin what it read. */
    public static final String DIRECTORY_VERSION = "audience-directory/in-memory/1";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private record Audience(
            String audienceId,
            String displayLabel,
            List<String> matchTexts,
            boolean referenceable,
            List<AudienceVersionChoice> versions) {

        Audience withReferenceable(boolean value) {
            return new Audience(audienceId, displayLabel, matchTexts, value, versions);
        }

        Audience withVersions(List<AudienceVersionChoice> value) {
            return new Audience(audienceId, displayLabel, matchTexts, referenceable, value);
        }
    }

    private final Map<String, Audience> audiences = new LinkedHashMap<>();

    /** Registers one audience that this caller may list and reference, matching the supplied texts. */
    public synchronized InMemoryAudienceReferenceDirectory registerAudience(
            String audienceId, String displayLabel, List<String> matchTexts) {
        return registerAudience(audienceId, displayLabel, matchTexts, true);
    }

    /** Registers one audience with an explicit referenceability, so a denied candidate can be exercised. */
    public synchronized InMemoryAudienceReferenceDirectory registerAudience(
            String audienceId, String displayLabel, List<String> matchTexts, boolean referenceable) {
        Audience audience = new Audience(
                new AudienceSummary(audienceId, displayLabel).audienceId(),
                displayLabel,
                matchTexts == null ? List.of() : List.copyOf(matchTexts),
                referenceable,
                List.of());
        audiences.put(audience.audienceId(), audience);
        return this;
    }

    /** Publishes one immutable version choice for one registered audience. */
    public synchronized InMemoryAudienceReferenceDirectory publishVersion(AudienceVersionChoice choice) {
        if (choice == null) {
            throw ReferenceException.invalid("A directory publish requires a version choice");
        }
        Audience audience = audiences.get(choice.reference().audienceId());
        if (audience == null) {
            throw ReferenceException.invalid("A version choice requires an already-registered audience");
        }
        List<AudienceVersionChoice> versions = new ArrayList<>(audience.versions());
        for (AudienceVersionChoice existing : versions) {
            if (existing.reference().equals(choice.reference())) {
                return this;
            }
        }
        versions.add(choice);
        versions.sort(java.util.Comparator.comparing(
                AudienceVersionChoice::reference, AudienceVersionRef.ORDER));
        audiences.put(audience.audienceId(), audience.withVersions(List.copyOf(versions)));
        return this;
    }

    /** Publishes a version choice from its raw pin parts. */
    public synchronized InMemoryAudienceReferenceDirectory publishVersion(
            AudienceVersionRef reference, Instant publishedAt, String displayLabel) {
        return publishVersion(new AudienceVersionChoice(reference, publishedAt, displayLabel));
    }

    /** Makes one registered audience discoverable but no longer referenceable, which is a denial. */
    public synchronized InMemoryAudienceReferenceDirectory denyReference(String audienceId) {
        Audience audience = audiences.get(audienceId);
        if (audience == null) {
            throw ReferenceException.invalid("A denial target must be a registered audience");
        }
        audiences.put(audienceId, audience.withReferenceable(false));
        return this;
    }

    @Override
    public synchronized List<AudienceSummary> discover(String candidateText) {
        if (candidateText == null) {
            throw ReferenceException.invalid("Discovery requires a bounded candidate text");
        }
        String normalized = candidateText.strip().toLowerCase(Locale.ROOT);
        Set<String> matched = new LinkedHashSet<>();
        for (Audience audience : audiences.values()) {
            if (matches(audience, candidateText, normalized)) {
                matched.add(audience.audienceId());
            }
        }
        List<String> ordered = new ArrayList<>(matched);
        java.util.Collections.sort(ordered);
        List<AudienceSummary> summaries = new ArrayList<>(ordered.size());
        for (String audienceId : ordered) {
            Audience audience = audiences.get(audienceId);
            summaries.add(new AudienceSummary(audience.audienceId(), audience.displayLabel()));
        }
        return List.copyOf(summaries);
    }

    @Override
    public synchronized boolean accessible(String audienceId) {
        Audience audience = audiences.get(audienceId);
        return audience != null && audience.referenceable();
    }

    @Override
    public synchronized List<AudienceVersionChoice> publishedVersions(String audienceId) {
        Audience audience = audiences.get(audienceId);
        return audience == null ? List.of() : audience.versions();
    }

    /** The canonical content hash of every registered fact of this exact directory state. */
    public synchronized ContentHash contentHash() {
        List<Object> entries = new ArrayList<>(audiences.size());
        for (Audience audience : audiences.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("audienceId", audience.audienceId());
            entry.put("displayLabel", audience.displayLabel());
            entry.put("matchTexts", audience.matchTexts());
            entry.put("referenceable", audience.referenceable());
            List<Object> versions = new ArrayList<>(audience.versions().size());
            for (AudienceVersionChoice version : audience.versions()) {
                Map<String, Object> pin = new LinkedHashMap<>();
                pin.put("audienceId", version.reference().audienceId());
                pin.put("versionId", version.reference().versionId());
                pin.put("versionHash", version.reference().versionHash().value());
                pin.put("publishedAt", version.publishedAt().toString());
                pin.put("displayLabel", version.displayLabel());
                versions.add(pin);
            }
            entry.put("versions", versions);
            entries.add(entry);
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("directoryVersion", DIRECTORY_VERSION);
        material.put("audiences", entries);
        return CANONICAL_JSON.hash(material);
    }

    /** The exact host-owned context a resolver is handed for this directory state. */
    public synchronized AudienceReferenceDirectoryContext context() {
        return new AudienceReferenceDirectoryContext(this, DIRECTORY_VERSION, contentHash());
    }

    private static boolean matches(Audience audience, String candidateText, String normalized) {
        if (audience.audienceId().equals(candidateText)) {
            return true;
        }
        if (audience.displayLabel().toLowerCase(Locale.ROOT).equals(normalized)) {
            return true;
        }
        for (String text : audience.matchTexts()) {
            if (text.toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}