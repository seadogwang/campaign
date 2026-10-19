package com.company.campaign.evaluation.segment.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One case's after-the-fact drift check: what is withdrawn, revoked or released between two steps.
 *
 * <p>It is deliberately a value rather than a script. The case says which single drift is applied, and the
 * runner applies exactly that through the same trusted ports the first resolution used, so the re-check is a
 * genuine second observation rather than a replayed decision.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceRecheck(ReferenceRecheckKind kind, String holdId) {

    public ReferenceRecheck {
        if (kind == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED, "A re-check requires the drift it applies");
        }
        if (kind.requiresHoldId() && (holdId == null || holdId.isBlank())) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A retention re-check requires the hold identifier it releases",
                    Map.of("kind", kind.name()));
        }
        if (!kind.requiresHoldId() && holdId != null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED,
                    "A re-check that releases no hold must not name a hold identifier",
                    Map.of("kind", kind.name()));
        }
    }
}