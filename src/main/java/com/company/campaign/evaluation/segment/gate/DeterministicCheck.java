package com.company.campaign.evaluation.segment.gate;

/**
 * The exact pass tally of one deterministic-layer check.
 *
 * <p>Only an explicit passed and total count are stored. A boolean would hide the difference between "100 of
 * 100" and "0 of 0", and the Gate must treat a check that ran nothing as not proven rather than as vacuously
 * true. A check passes only when it ran at least one input and every one of them passed.
 */
public record DeterministicCheck(DeterministicCheckId checkId, int passed, int total) {

    public DeterministicCheck {
        if (checkId == null) {
            throw GateException.of(
                    GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE, "A deterministic check requires its id");
        }
        if (total < 1) {
            throw GateException.of(
                    GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                    "A deterministic check must run at least one input: " + checkId);
        }
        if (passed < 0 || passed > total) {
            throw GateException.of(
                    GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                    "A deterministic check must report a passed count within its total: " + checkId);
        }
    }

    /** A check that ran {@code total} inputs and passed all of them. */
    public static DeterministicCheck passing(DeterministicCheckId checkId, int total) {
        return new DeterministicCheck(checkId, total, total);
    }

    /** True only when every input this check ran passed. */
    public boolean isComplete() {
        return passed == total;
    }
}