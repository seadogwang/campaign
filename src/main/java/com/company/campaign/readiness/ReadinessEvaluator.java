package com.company.campaign.readiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic readiness evaluator.
 *
 * <p>It expands the requested level through {@link ReadinessCatalog}, takes the single evidence record that
 * exists for each required requirement in the requested scope, and derives the level's status and ordered
 * blockers from it. Nothing is defaulted: an absent requirement is a blocker, not a pass.
 *
 * <p>Everything a caller could try to smuggle past the catalog is refused here with a bounded blocker:
 * <ul>
 *   <li>evidence recorded in another environment, or claiming a production origin outside a production
 *       environment, makes the level {@link ReadinessStatus#INVALID};</li>
 *   <li>two distinct records for one requirement make the level {@code INVALID} rather than letting one of
 *       them silently win;</li>
 *   <li>expired, forward-dated, out-of-origin and non-production evidence never satisfies a requirement;</li>
 *   <li>a production-only requirement additionally demands evidence that is explicitly stamped production
 *       Gate evidence, so {@code productionGateEvidence=false}, fixture, simulated, synthetic and
 *       development-only evidence are all refused.</li>
 * </ul>
 *
 * <p>The evaluator reads no ambient clock, generates no identifier and consults no store: the instant, the
 * assessment id and the evidence list are all inputs, so the same inputs always produce the same assessment
 * bytes.
 */
public final class ReadinessEvaluator {

    /** Evaluates one level against one scope's evidence. */
    public ReadinessAssessment evaluate(ReadinessEvaluationRequest request) {
        ReadinessEvaluationRequest evaluation = ReadinessSupport.requireNonNull(request, "request");
        ReadinessLevel level = evaluation.level();
        ReadinessEnvironment environment = evaluation.environment();
        Instant evaluatedAt = evaluation.evaluatedAt();

        Map<ReadinessRequirement, List<RequirementEvidence>> byRequirement = new LinkedHashMap<>();
        for (RequirementEvidence evidence : evaluation.evidence()) {
            if (evidence.requirement().requiredAt(level)) {
                byRequirement.computeIfAbsent(evidence.requirement(), key -> new ArrayList<>()).add(evidence);
            }
        }

        List<EvidenceReference> accepted = new ArrayList<>();
        List<ReadinessBlocker> blockers = new ArrayList<>();
        boolean invalid = false;

        for (ReadinessRequirement requirement : ReadinessCatalog.requirementsFor(level)) {
            List<RequirementEvidence> records = byRequirement.getOrDefault(requirement, List.of());
            if (records.isEmpty()) {
                blockers.add(blocker(ReadinessBlockerCodes.REQUIREMENT_MISSING, requirement));
                continue;
            }
            if (records.size() > 1) {
                // Two distinct records for one requirement is ambiguity, not a choice: refuse to assess.
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_DUPLICATE, requirement));
                invalid = true;
                continue;
            }
            RequirementEvidence evidence = records.get(0);
            if (evidence.environment() != environment) {
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_WRONG_ENVIRONMENT, requirement));
                invalid = true;
                continue;
            }
            if (evidence.status() == EvidenceStatus.INVALID) {
                blockers.add(blocker(evidence.reasonCode(), requirement));
                invalid = true;
                continue;
            }
            if (evidence.status() == EvidenceStatus.BLOCKED) {
                blockers.add(blocker(evidence.reasonCode(), requirement));
                continue;
            }
            if (evidence.observedAfter(evaluatedAt)) {
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_FUTURE_OBSERVED, requirement));
                invalid = true;
                continue;
            }
            if (evidence.expiredAt(evaluatedAt)) {
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_EXPIRED, requirement));
                continue;
            }
            if (!requirement.admits(evidence.origin())) {
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_WRONG_ORIGIN, requirement));
                continue;
            }
            if (evidence.origin().productionCapable() && !environment.production()) {
                // A production-capable origin outside the production environment is a false claim.
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_ORIGIN_MISMATCH, requirement));
                invalid = true;
                continue;
            }
            if (requirement.productionOnly() && !evidence.productionGateEvidence()) {
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_NOT_PRODUCTION, requirement));
                continue;
            }
            if (evidence.productionGateEvidence() && !evidence.origin().productionCapable()) {
                blockers.add(blocker(ReadinessBlockerCodes.EVIDENCE_ORIGIN_MISMATCH, requirement));
                invalid = true;
                continue;
            }
            accepted.add(EvidenceReference.of(evidence));
        }

        ReadinessStatus status = invalid
                ? ReadinessStatus.INVALID
                : (blockers.isEmpty() ? ReadinessStatus.READY : ReadinessStatus.BLOCKED);
        return ReadinessAssessment.of(
                evaluation.assessmentId(), level, status, environment, evaluatedAt, accepted, blockers);
    }

    private static ReadinessBlocker blocker(String code, ReadinessRequirement requirement) {
        return new ReadinessBlocker(0, code, requirement, requirement.level());
    }
}