package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The membership observation of one exact pin in a reference-corpus membership case.
 *
 * <p>One entry answers one lookup: the exact {@link ReferencePin} the case looked up, whether the fixture
 * membership had anything for it, and — only when it did — the hash of the exact member set it answered with.
 * A case declares one entry per declared pin, so an observation can never be silently reused for a different
 * version, and the per-pin outcome is stated here rather than collapsed into the case's single terminal
 * outcome, because one case may look up several pins and reach a different answer for each.
 *
 * <p>The member identifiers are deliberately absent. Only the hash is stated, exactly as the reference core's
 * own membership value carries it, so a corpus asset never records who is in an audience.
 *
 * @param pin            the exact version the lookup named
 * @param outcome        {@link ReferenceOutcome#PRESENT} or {@link ReferenceOutcome#ABSENT}
 * @param memberSetHash  the hash of the exact member set, or {@code null} when the lookup was absent
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceExpectedMembership(
        ReferencePin pin,
        ReferenceOutcome outcome,
        ContentHash memberSetHash
) {

    public ReferenceExpectedMembership {
        if (pin == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.PIN_INVALID,
                    "A membership observation requires the exact pin it looked up");
        }
        if (outcome != ReferenceOutcome.PRESENT && outcome != ReferenceOutcome.ABSENT) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A membership observation must state PRESENT or ABSENT for the exact pin it looked up");
        }
        boolean present = outcome == ReferenceOutcome.PRESENT;
        if (present && memberSetHash == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A present membership observation requires the hash of the exact member set it answered with",
                    Map.of("version", pin.identityKey()));
        }
        if (!present && memberSetHash != null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                    "An absent membership observation must not carry a member-set hash, because absent is not an"
                            + " empty member set",
                    Map.of("version", pin.identityKey()));
        }
    }

    /**
     * True when the exact pin was present in the fixture membership.
     *
     * <p>Derived, never stored: it is excluded from the wire shape exactly as the base review ledger's
     * {@code isFullyApproved()} is, so the canonical bytes carry only the pinned facts and a corpus asset
     * stays its own canonical serialization.
     */
    @JsonIgnore
    public boolean isPresent() {
        return outcome == ReferenceOutcome.PRESENT;
    }

    /** A bounded diagnostic naming the pin identity and presence, never a member identifier. */
    @Override
    public String toString() {
        return "ReferenceExpectedMembership[pin=" + pin.identityKey()
                + ", outcome=" + outcome
                + ", memberSetHash=" + (memberSetHash == null ? "none" : memberSetHash)
                + ']';
    }
}