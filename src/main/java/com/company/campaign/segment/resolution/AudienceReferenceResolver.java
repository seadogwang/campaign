package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.AudienceReferenceWording;
import com.company.campaign.segment.reference.AudienceSummary;
import com.company.campaign.segment.reference.AudienceVersionChoice;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.ReferenceDiscoveryLimits;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The deterministic, evidence-based resolver of one Existing Audience Reference.
 *
 * <p>It receives only explicit immutable inputs: the host-owned discovery directory with its exact revision
 * pins, the untrusted request wording the Intent carried, and the host-applied clarification selections. It
 * reads no repository, no Metadata, no DSL and no model payload beyond that wording, and it never constructs a
 * Segment DSL: it answers exactly one question — which immutable published version this request may reference,
 * or why it cannot yet be answered.
 *
 * <p><strong>Semantics.</strong>
 *
 * <ul>
 *   <li><em>zero candidates</em> — no audience is visible for the request, which is also what an audience the
 *       caller may not see looks like. It is one bounded {@code UNSUPPORTED} "unavailable" outcome and never a
 *       list, so existence cannot be probed;</li>
 *   <li><em>denied candidate</em> — an audience that discovery already surfaced may no longer be referenced.
 *       The outcome is {@code DENIED} and carries no candidate list at all;</li>
 *   <li><em>several audiences</em> or <em>several eligible versions</em> — one structured clarification whose
 *       choices are already-safe summaries or exact immutable pins, in canonical order;</li>
 *   <li><em>exactly one eligible immutable published version</em> — its exact version identity and content hash
 *       are pinned and the resolution evidence is recorded.</li>
 * </ul>
 *
 * <p><strong>Dynamic wording.</strong> A request such as "最新人群" is detected from the request text itself,
 * never from a model flag, and can never remain dynamic: it either asks for version semantics or pins exactly
 * one immutable version and records that the pin had to be disclosed explicitly. The resolver never selects a
 * "newest" version — with several eligible versions it asks, with one it pins that one.
 *
 * <p><strong>Host checks only.</strong> Every accepted pin comes from the directory, is de-duplicated by its
 * full exact pin, and is rejected when the same version identity appears twice with different bytes. Owner,
 * permission, DataScope, dependency and Gate facts are not inputs here and cannot be supplied by a caller: the
 * resolver has no parameter for any of them.
 *
 * <p>It is stateless and thread-safe, and every failure is one stable machine code that echoes neither the
 * request text nor a candidate label.
 */
public final class AudienceReferenceResolver {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The suffix that turns one reference identity into its version-selection target. */
    private static final String VERSION_TARGET_SUFFIX = "/version";

    private final AudienceReferenceDirectoryContext directoryContext;

    public AudienceReferenceResolver(AudienceReferenceDirectoryContext directoryContext) {
        if (directoryContext == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.REFERENCE_DIRECTORY_MISSING,
                    "An audience reference resolver requires the host-owned directory context");
        }
        this.directoryContext = directoryContext;
    }

    /** The clarification target a version-selection answer addresses. */
    public static String versionSelectionTarget(String referenceId) {
        return referenceId + VERSION_TARGET_SUFFIX;
    }

    /** Resolves one reference with no clarification answer applied. */
    public AudienceReferenceResolution resolve(
            String referenceId, List<String> candidateTexts, String requestText) {
        return resolve(referenceId, candidateTexts, requestText, null, null);
    }

    /**
     * Resolves one reference with the host-applied clarification answers, deterministically.
     *
     * @param audienceSelection the selected audience identity, or null when it was never asked or not answered
     * @param versionSelection  the selected exact pin key, or null when it was never asked or not answered
     */
    public AudienceReferenceResolution resolve(
            String referenceId,
            List<String> candidateTexts,
            String requestText,
            String audienceSelection,
            String versionSelection) {
        requireIdentity(referenceId);
        List<String> candidates = requireCandidateTexts(candidateTexts);
        boolean dynamicWording = AudienceReferenceWording.isDynamic(requestText);
        ContentHash requestHash = requestHash(referenceId, candidates, requestText);

        List<AudienceSummary> discovered = discover(candidates);
        if (audienceSelection != null) {
            AudienceSummary selected = null;
            for (AudienceSummary candidate : discovered) {
                if (candidate.audienceId().equals(audienceSelection)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw incompatible("AUDIENCE");
            }
            discovered = List.of(selected);
        }
        if (discovered.isEmpty()) {
            return unavailable(referenceId, dynamicWording);
        }
        if (discovered.size() > 1) {
            return audienceClarification(referenceId, discovered, dynamicWording);
        }

        AudienceSummary audience = discovered.get(0);
        if (!accessible(audience.audienceId())) {
            // A visible candidate that may no longer be referenced is a denial, and a denial names nothing: the
            // caller has learned only what discovery had already shown it.
            return new AudienceReferenceResolution(
                    referenceId, Disposition.DENIED, null, null, null, dynamicWording, false,
                    List.of(), List.of(), discovered.size(), null);
        }

        List<AudienceVersionChoice> versions = publishedVersions(audience.audienceId());
        if (versionSelection != null) {
            AudienceVersionRef pin = parsePin(versionSelection);
            for (AudienceVersionChoice choice : versions) {
                if (choice.reference().equals(pin)) {
                    return accepted(
                            referenceId, audience, choice, dynamicWording, requestHash, discovered);
                }
            }
            throw incompatible("VERSION");
        }
        if (versions.isEmpty()) {
            return unavailable(referenceId, dynamicWording);
        }
        if (versions.size() > 1) {
            return versionClarification(referenceId, versions, dynamicWording);
        }
        return accepted(referenceId, audience, versions.get(0), dynamicWording, requestHash, discovered);
    }

    // ------------------------------------------------------------------ outcomes

    private static AudienceReferenceResolution unavailable(String referenceId, boolean dynamicWording) {
        return new AudienceReferenceResolution(
                referenceId, Disposition.UNSUPPORTED, null, null, null, dynamicWording, false,
                List.of(), List.of(QuestionCode.AUDIENCE_REFERENCE_UNAVAILABLE), 0, null);
    }

    private AudienceReferenceResolution audienceClarification(
            String referenceId, List<AudienceSummary> discovered, boolean dynamicWording) {
        List<AudienceReferenceChoice> choices = new ArrayList<>(discovered.size());
        for (AudienceSummary summary : discovered) {
            choices.add(new AudienceReferenceChoice(summary.audienceId(), summary.displayLabel()));
        }
        return new AudienceReferenceResolution(
                referenceId, Disposition.NEEDS_INPUT, null, null, null, dynamicWording, false,
                choices, List.of(QuestionCode.SELECT_AUDIENCE_REFERENCE), discovered.size(), null);
    }

    private AudienceReferenceResolution versionClarification(
            String referenceId, List<AudienceVersionChoice> versions, boolean dynamicWording) {
        List<AudienceReferenceChoice> choices = new ArrayList<>(versions.size());
        for (AudienceVersionChoice version : versions) {
            choices.add(new AudienceReferenceChoice(version.reference().pinKey(), version.displayLabel()));
        }
        return new AudienceReferenceResolution(
                referenceId, Disposition.NEEDS_INPUT, null, null, null, dynamicWording, false,
                choices, List.of(QuestionCode.SELECT_AUDIENCE_VERSION), versions.size(), null);
    }

    private AudienceReferenceResolution accepted(
            String referenceId,
            AudienceSummary audience,
            AudienceVersionChoice version,
            boolean dynamicWording,
            ContentHash requestHash,
            List<AudienceSummary> discovered) {
        List<String> candidateIds = new ArrayList<>(discovered.size());
        for (AudienceSummary summary : discovered) {
            candidateIds.add(summary.audienceId());
        }
        AudienceReferenceEvidence evidence = AudienceReferenceEvidence.of(
                referenceId, requestHash, candidateIds, audience.audienceId(), version.reference(),
                version.publishedAt(), dynamicWording, dynamicWording,
                directoryContext.directoryVersion(), directoryContext.directoryHash());
        return new AudienceReferenceResolution(
                referenceId, Disposition.RESOLVED, version.reference(), version.displayLabel(),
                version.publishedAt(), dynamicWording, dynamicWording, List.of(), List.of(),
                discovered.size(), evidence);
    }

    // ------------------------------------------------------------------ host directory boundary

    private List<AudienceSummary> discover(List<String> candidateTexts) {
        Map<String, AudienceSummary> byId = new TreeMap<>();
        for (String text : candidateTexts) {
            List<AudienceSummary> found;
            try {
                found = directoryContext.directory().discover(text);
            } catch (RuntimeException failure) {
                // The trusted port failed: no candidate is admitted and the reason is never echoed.
                throw invalid("discover");
            }
            if (found == null || found.size() > ReferenceDiscoveryLimits.MAX_CANDIDATES) {
                throw invalid("discover");
            }
            for (AudienceSummary summary : found) {
                if (summary == null) {
                    throw invalid("discover");
                }
                byId.putIfAbsent(summary.audienceId(), summary);
            }
        }
        return List.copyOf(byId.values());
    }

    private boolean accessible(String audienceId) {
        try {
            return directoryContext.directory().accessible(audienceId);
        } catch (RuntimeException failure) {
            throw invalid("accessible");
        }
    }

    /**
     * The eligible published versions of one audience, in exact canonical pin order.
     *
     * <p>Two entries that name the same version identity with different content hashes are a conflict, not two
     * choices: accepting both would let one mutable version identity masquerade as two immutable ones, which is
     * exactly the pin-collapse the reference contract forbids.
     */
    private List<AudienceVersionChoice> publishedVersions(String audienceId) {
        List<AudienceVersionChoice> raw;
        try {
            raw = directoryContext.directory().publishedVersions(audienceId);
        } catch (RuntimeException failure) {
            throw invalid("publishedVersions");
        }
        if (raw == null || raw.size() > ReferenceDiscoveryLimits.MAX_PUBLISHED_VERSIONS) {
            throw invalid("publishedVersions");
        }
        Map<String, AudienceVersionChoice> byPin = new LinkedHashMap<>();
        for (AudienceVersionChoice choice : raw) {
            if (choice == null || !choice.reference().audienceId().equals(audienceId)) {
                throw invalid("publishedVersions");
            }
            if (byPin.putIfAbsent(choice.reference().pinKey(), choice) != null) {
                continue;
            }
        }
        Map<String, AudienceVersionChoice> byIdentity = new LinkedHashMap<>();
        for (AudienceVersionChoice choice : byPin.values()) {
            String identity = choice.reference().identityKey();
            AudienceVersionChoice existing = byIdentity.putIfAbsent(identity, choice);
            if (existing != null && !existing.reference().equals(choice.reference())) {
                throw invalid("publishedVersions");
            }
        }
        List<AudienceVersionChoice> ordered = new ArrayList<>(byPin.values());
        ordered.sort(java.util.Comparator.comparing(AudienceVersionChoice::reference, AudienceVersionRef.ORDER));
        return List.copyOf(ordered);
    }

    // ------------------------------------------------------------------ text and pins

    private static void requireIdentity(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INPUT_MISSING,
                    "An audience reference resolution requires a bounded reference identity");
        }
    }

    private static List<String> requireCandidateTexts(List<String> candidateTexts) {
        if (candidateTexts == null || candidateTexts.isEmpty()
                || candidateTexts.size() > ReferenceDiscoveryLimits.MAX_CANDIDATE_TEXTS) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INPUT_MISSING,
                    "An audience reference resolution requires bounded candidate texts");
        }
        for (String text : candidateTexts) {
            if (text == null || text.isBlank()
                    || text.length() > ReferenceDiscoveryLimits.MAX_CANDIDATE_TEXT_LENGTH) {
                throw ResolutionException.invalid(
                        ResolutionErrorCodes.INPUT_MISSING, "An audience reference candidate text is unusable");
            }
        }
        return List.copyOf(candidateTexts);
    }

    private static ContentHash requestHash(String referenceId, List<String> candidateTexts, String requestText) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("referenceId", referenceId);
        material.put("candidateTexts", candidateTexts);
        material.put("requestText", requestText);
        return CANONICAL_JSON.hash(material);
    }

    /**
     * Parses one exact pin key back out of a clarification choice.
     *
     * <p>The key is {@code audienceId/versionId@sha256:...}. Neither an audience nor a version identity can
     * contain {@code /} or {@code @}, so the split is unambiguous, and the pin is validated by the same record
     * a catalog would answer with — a malformed key is refused rather than partially interpreted.
     */
    private static AudienceVersionRef parsePin(String pinKey) {
        if (pinKey == null) {
            return null;
        }
        int at = pinKey.indexOf('@');
        if (at <= 0) {
            return null;
        }
        String identity = pinKey.substring(0, at);
        String hash = pinKey.substring(at + 1);
        int slash = identity.indexOf('/');
        if (slash <= 0 || slash == identity.length() - 1) {
            return null;
        }
        try {
            return new AudienceVersionRef(
                    identity.substring(0, slash), identity.substring(slash + 1),
                    new com.company.campaign.shared.canonical.ContentHash(hash));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static ResolutionException incompatible(String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        return new ResolutionException(
                ResolutionErrorCodes.OPTION_INCOMPATIBLE,
                "The selected audience reference option is no longer compatible",
                details);
    }

    private static ResolutionException invalid(String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("boundary", reason);
        return new ResolutionException(
                ResolutionErrorCodes.REFERENCE_DIRECTORY_INVALID,
                "The audience reference directory did not answer with acceptable material",
                details);
    }

    /** A bounded diagnostic: the pinned directory revision only, never a candidate or a label. */
    @Override
    public String toString() {
        return "AudienceReferenceResolver[directoryVersion=" + directoryContext.directoryVersion() + ']';
    }
}