package com.company.campaign.segment.resolution;

import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact resolution evidence of one resolved Existing Audience Reference.
 *
 * <p>It exists only after the host checks have run: it records the request wording the resolution answered,
 * every visible candidate audience that was considered, the one audience and the one immutable version that
 * were accepted, that version's publication instant, whether the request used dynamic wording, whether the pin
 * therefore had to be disclosed explicitly, and the exact directory revision the answer was read from.
 *
 * <p>It carries no member, no member count, no owner, no DataScope and no permission: a candidate list holds
 * only identities the caller could already see through discovery, and everything else is a hash. The
 * {@code evidenceHash} is re-derived from the material at construction and a contradiction is refused, so the
 * hash is a proof of the evidence rather than a label attached to it: any change to the pin, the candidates,
 * the dynamic reading or the directory revision moves it, and the same exact resolution always reproduces it.
 */
public record AudienceReferenceEvidence(
        String evidenceVersion,
        String referenceId,
        ContentHash requestHash,
        List<String> candidateAudienceIds,
        String audienceId,
        AudienceVersionRef resolved,
        Instant publishedAt,
        boolean dynamicWording,
        boolean pinnedFromDynamicWording,
        String directoryVersion,
        ContentHash directoryHash,
        ContentHash evidenceHash
) {

    /** The canonical evidence document format version. */
    public static final String EVIDENCE_VERSION = "segment-audience-reference-evidence/1.0";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public AudienceReferenceEvidence {
        if (!EVIDENCE_VERSION.equals(evidenceVersion)) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Audience reference evidence must carry the one supported format version");
        }
        if (referenceId == null || requestHash == null || directoryVersion == null || directoryHash == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Audience reference evidence requires its request, directory and identity pins");
        }
        if (audienceId == null || resolved == null || publishedAt == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Audience reference evidence is only produced for an accepted immutable version");
        }
        if (!audienceId.equals(resolved.audienceId())) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Audience reference evidence must accept the version of the audience it names");
        }
        candidateAudienceIds = candidateAudienceIds == null
                ? List.of()
                : List.copyOf(new ArrayList<>(candidateAudienceIds));
        if (evidenceHash == null || !evidenceHash.equals(hash(
                referenceId, requestHash, candidateAudienceIds, audienceId, resolved, publishedAt,
                dynamicWording, pinnedFromDynamicWording, directoryVersion, directoryHash))) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "The declared audience reference evidence hash is not the material's hash");
        }
    }

    /** Builds the one evidence of an accepted resolution, deriving the hash from its exact material. */
    static AudienceReferenceEvidence of(
            String referenceId,
            ContentHash requestHash,
            List<String> candidateAudienceIds,
            String audienceId,
            AudienceVersionRef resolved,
            Instant publishedAt,
            boolean dynamicWording,
            boolean pinnedFromDynamicWording,
            String directoryVersion,
            ContentHash directoryHash) {
        return new AudienceReferenceEvidence(
                EVIDENCE_VERSION,
                referenceId,
                requestHash,
                candidateAudienceIds,
                audienceId,
                resolved,
                publishedAt,
                dynamicWording,
                pinnedFromDynamicWording,
                directoryVersion,
                directoryHash,
                hash(referenceId, requestHash, candidateAudienceIds, audienceId, resolved, publishedAt,
                        dynamicWording, pinnedFromDynamicWording, directoryVersion, directoryHash));
    }

    /** The exact canonical material the evidence hash covers. */
    private static ContentHash hash(
            String referenceId,
            ContentHash requestHash,
            List<String> candidateAudienceIds,
            String audienceId,
            AudienceVersionRef resolved,
            Instant publishedAt,
            boolean dynamicWording,
            boolean pinnedFromDynamicWording,
            String directoryVersion,
            ContentHash directoryHash) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("evidenceVersion", EVIDENCE_VERSION);
        material.put("referenceId", referenceId);
        material.put("requestHash", requestHash.value());
        material.put("candidateAudienceIds", candidateAudienceIds == null ? List.of() : candidateAudienceIds);
        material.put("audienceId", audienceId);
        material.put("versionId", resolved.versionId());
        material.put("versionHash", resolved.versionHash().value());
        material.put("publishedAt", publishedAt.toString());
        material.put("dynamicWording", dynamicWording);
        material.put("pinnedFromDynamicWording", pinnedFromDynamicWording);
        material.put("directoryVersion", directoryVersion);
        material.put("directoryHash", directoryHash.value());
        return CANONICAL_JSON.hash(material);
    }

    /** A bounded diagnostic: identities, counts and the hash, never a label or a member. */
    @Override
    public String toString() {
        return "AudienceReferenceEvidence[referenceId=" + referenceId
                + ", candidates=" + candidateAudienceIds.size()
                + ", version=" + resolved.identityKey()
                + ", dynamicWording=" + dynamicWording
                + ", evidenceHash=" + evidenceHash
                + ']';
    }
}