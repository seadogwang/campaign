package com.company.campaign.evaluation.segment.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The retention observation one reference-corpus retention case must produce.
 *
 * <p>It states exactly the two facts the host retention store reveals about the exact version: the terminal
 * outcome of the program the case ran — deleted, refused by an active hold, kept alive by the host policy, or
 * naming a version the store never held — and how many retention holds are still active on that version when
 * the program finishes. Keeping both apart is what stops "the deletion succeeded" from being confused with
 * "the deletion was refused and nothing changed", and what lets a reader check the hold accounting without
 * re-running the program.
 *
 * <p>The outcome is restricted to the retention scenarios of the closed {@link ReferenceOutcome} set, so an
 * expectation that pairs the retention block with a document or resolution outcome is a corpus defect rather
 * than an unchecked claim.
 *
 * @param outcome         one {@link ReferenceOutcome} valid for {@link ReferenceScenario#REFERENCE_RETENTION}
 * @param activeHoldCount how many holds the store still holds active on the exact version, never negative
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceExpectedRetention(
        ReferenceOutcome outcome,
        int activeHoldCount
) {

    public ReferenceExpectedRetention {
        if (outcome == null || !outcome.appliesTo(ReferenceScenario.REFERENCE_RETENTION)) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A retention observation must state the terminal outcome the retention program produces");
        }
        if (activeHoldCount < 0) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A retention observation must state a non-negative count of holds left active",
                    Map.of("activeHoldCount", activeHoldCount));
        }
    }
}