package com.company.campaign.readiness;

import java.time.Instant;
import java.util.List;

/**
 * The host-only input to one readiness evaluation.
 *
 * <p>The evidence list is what the host read for one scope, the environment is that scope's environment, and
 * {@code evaluatedAt} is the trusted instant expiry and freshness are judged against — never a value the
 * evaluator reads from an ambient clock.
 */
public record ReadinessEvaluationRequest(
        String assessmentId,
        ReadinessLevel level,
        ReadinessEnvironment environment,
        Instant evaluatedAt,
        List<RequirementEvidence> evidence) {

    public ReadinessEvaluationRequest {
        ReadinessSupport.requireId(assessmentId, "assessmentId");
        ReadinessSupport.requireNonNull(level, "level");
        ReadinessSupport.requireNonNull(environment, "environment");
        ReadinessSupport.requireInstant(evaluatedAt, "evaluatedAt");
        if (evidence == null || evidence.size() > ReadinessLimits.MAX_EVIDENCE_PER_READ) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "A readiness evaluation request must carry a bounded evidence list.");
        }
        for (RequirementEvidence record : evidence) {
            ReadinessSupport.requireNonNull(record, "evidence");
        }
        evidence = List.copyOf(evidence);
    }
}