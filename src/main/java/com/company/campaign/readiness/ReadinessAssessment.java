package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One immutable, self-proving readiness assessment.
 *
 * <p>It states the level that was assessed, the deterministic status, the trusted evaluation instant, the
 * evidence it accepted, the ordered blockers and a canonical hash over all of it. The hash is recomputed at
 * construction, so an assessment whose bytes do not match its own hash is not representable and a persisted
 * status cannot be edited into a different one.
 *
 * <p>A {@link ReadinessStatus#READY} assessment carries no blocker; a {@link ReadinessStatus#BLOCKED} or
 * {@link ReadinessStatus#INVALID} assessment carries at least one. Both the accepted evidence and the
 * blockers are ordered by the requirement catalog and then by code, so the same inputs always produce the
 * same list — and the same hash.
 */
public record ReadinessAssessment(
        String formatVersion,
        String assessmentId,
        ReadinessLevel level,
        ReadinessStatus status,
        ReadinessEnvironment environment,
        Instant evaluatedAt,
        List<EvidenceReference> acceptedEvidence,
        List<ReadinessBlocker> blockers,
        ContentHash assessmentHash) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ReadinessAssessment {
        if (!ReadinessVersions.ASSESSMENT_FORMAT_VERSION.equals(formatVersion)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "The readiness assessment format version is not the accepted one.");
        }
        ReadinessSupport.requireId(assessmentId, "assessmentId");
        ReadinessSupport.requireNonNull(level, "level");
        ReadinessSupport.requireNonNull(status, "status");
        ReadinessSupport.requireNonNull(environment, "environment");
        ReadinessSupport.requireInstant(evaluatedAt, "evaluatedAt");
        ReadinessSupport.requireNonNull(assessmentHash, "assessmentHash");
        if (acceptedEvidence == null || acceptedEvidence.size() > ReadinessLimits.MAX_ACCEPTED_EVIDENCE) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID, "The accepted evidence list is unbounded.");
        }
        if (blockers == null || blockers.size() > ReadinessLimits.MAX_BLOCKERS) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID, "The blocker list is unbounded.");
        }
        List<EvidenceReference> accepted = List.copyOf(acceptedEvidence);
        List<ReadinessBlocker> ordered = ordered(blockers);
        if (!ordered.equals(blockers)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "Readiness blockers must be in deterministic catalog order.");
        }
        boolean carriesBlocker = !ordered.isEmpty();
        if (status.ready() == carriesBlocker) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "A READY assessment carries no blocker and a non-READY assessment carries at least one.");
        }
        Set<ReadinessRequirement> seen = new HashSet<>();
        for (EvidenceReference reference : accepted) {
            if (reference == null || !seen.add(reference.requirement())) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                        "An assessment may accept at most one evidence record per requirement.");
            }
        }
        acceptedEvidence = accepted;
        blockers = ordered;
        AssessmentMaterial canonicalMaterial = new AssessmentMaterial(
                formatVersion,
                assessmentId,
                level.name(),
                status.name(),
                environment.name(),
                evaluatedAt,
                accepted.stream().map(EvidenceReference::material).toList(),
                ordered.stream().map(ReadinessBlocker::material).toList());
        if (!assessmentHash.equals(CANONICAL_JSON.hash(canonicalMaterial))) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_HASH_MISMATCH,
                    "The readiness assessment hash does not match its own canonical bytes.");
        }
    }

    /** Stamps one assessment with its own canonical hash. */
    public static ReadinessAssessment of(
            String assessmentId,
            ReadinessLevel level,
            ReadinessStatus status,
            ReadinessEnvironment environment,
            Instant evaluatedAt,
            List<EvidenceReference> acceptedEvidence,
            List<ReadinessBlocker> blockers) {
        List<EvidenceReference> accepted = List.copyOf(acceptedEvidence);
        List<ReadinessBlocker> ordered = ordered(blockers);
        ContentHash hash = CANONICAL_JSON.hash(new AssessmentMaterial(
                ReadinessVersions.ASSESSMENT_FORMAT_VERSION,
                assessmentId,
                level.name(),
                status.name(),
                environment.name(),
                evaluatedAt,
                accepted.stream().map(EvidenceReference::material).toList(),
                ordered.stream().map(ReadinessBlocker::material).toList()));
        return new ReadinessAssessment(
                ReadinessVersions.ASSESSMENT_FORMAT_VERSION,
                assessmentId,
                level,
                status,
                environment,
                evaluatedAt,
                accepted,
                ordered,
                hash);
    }

    /** True only when this assessment is a readiness claim for its level. */
    public boolean ready() {
        return status.ready();
    }

    /** Every distinct blocker code, in deterministic order. */
    public List<String> blockerCodes() {
        List<String> codes = new ArrayList<>(blockers.size());
        for (ReadinessBlocker blocker : blockers) {
            if (!codes.contains(blocker.code())) {
                codes.add(blocker.code());
            }
        }
        return List.copyOf(codes);
    }

    /** Redacted diagnostics: no tenant, program, evidence payload or subject is printed. */
    @Override
    public String toString() {
        return "ReadinessAssessment[level=" + level + ", status=" + status
                + ", environment=" + environment + ", blockers=" + blockers.size()
                + ", assessmentHash=" + assessmentHash + ']';
    }

    private static List<ReadinessBlocker> ordered(List<ReadinessBlocker> blockers) {
        List<ReadinessBlocker> ordered = new ArrayList<>(blockers == null ? List.of() : blockers);
        ordered.sort(Comparator
                .comparingInt((ReadinessBlocker blocker) -> blocker.requirement().ordinal())
                .thenComparing(ReadinessBlocker::code));
        List<ReadinessBlocker> renumbered = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            ReadinessBlocker blocker = ordered.get(index);
            renumbered.add(new ReadinessBlocker(index, blocker.code(), blocker.requirement(), blocker.level()));
        }
        return List.copyOf(renumbered);
    }

    private AssessmentMaterial material() {
        return new AssessmentMaterial(
                formatVersion,
                assessmentId,
                level.name(),
                status.name(),
                environment.name(),
                evaluatedAt,
                acceptedEvidence.stream().map(EvidenceReference::material).toList(),
                blockers.stream().map(ReadinessBlocker::material).toList());
    }

    /** Package-private assessment hash material. */
    record AssessmentMaterial(
            String formatVersion,
            String assessmentId,
            String level,
            String status,
            String environment,
            Instant evaluatedAt,
            List<EvidenceReference.ReferenceMaterial> acceptedEvidence,
            List<ReadinessBlocker.BlockerMaterial> blockers) {
    }
}
