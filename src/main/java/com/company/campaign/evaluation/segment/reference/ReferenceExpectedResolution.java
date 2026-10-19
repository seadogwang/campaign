package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolution evidence one reference-corpus resolution case must observe.
 *
 * <p>It states exactly the two shapes a resolution can produce and nothing else. A resolution that accepted
 * one eligible immutable version states the accepted {@link ReferencePin} together with the hash of the exact
 * resolution evidence it was read from; a resolution that asked a question, disclosed a denial or produced a
 * bounded reason code states that question code instead and accepts nothing. The two are mutually exclusive,
 * so a corpus can never claim an accepted pin without the evidence that proves it, nor offer a question an
 * accepted resolution never asks.
 *
 * <p>Question codes are held in a frozen, ordered, de-duplicated list for the same reason the case's findings
 * are: "we asked two questions" and "we asked one twice" must not be the same byte string.
 *
 * @param pin                  the accepted exact pin, or {@code null} when no version was accepted
 * @param resolutionEvidenceHash the hash of the exact resolution evidence, or {@code null} when none exists
 * @param questionCodes        the stable question or bounded reason codes the resolver owns, in order
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceExpectedResolution(
        ReferencePin pin,
        ContentHash resolutionEvidenceHash,
        List<String> questionCodes
) {

    public ReferenceExpectedResolution {
        if ((pin == null) != (resolutionEvidenceHash == null)) {
            throw new ReferenceCorpusException(
                    pin == null
                            ? ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED
                            : ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A resolution expectation states the accepted pin and its evidence hash together");
        }
        questionCodes = ImmutableValues.freezeList(questionCodes == null ? List.of() : questionCodes);
        Set<String> seen = new LinkedHashSet<>();
        for (String code : questionCodes) {
            if (code == null || code.isBlank()) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                        "A resolution expectation requires a bounded question code");
            }
            if (!seen.add(code)) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                        "A resolution expectation must state each question code exactly once",
                        Map.of("code", code));
            }
        }
        if (pin != null && !questionCodes.isEmpty()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                    "An accepted resolution expectation must not state a question it never asks");
        }
    }

    /**
     * True when this expectation states the accepted pin and the evidence that proves it.
     *
     * <p>Derived, never stored: it is excluded from the wire shape exactly as the base review ledger's
     * {@code isFullyApproved()} is, so the canonical bytes carry only the pinned facts and a corpus asset
     * stays its own canonical serialization.
     */
    @JsonIgnore
    public boolean isAccepted() {
        return pin != null;
    }

    /** A bounded diagnostic naming the accepted pin's identity, never a candidate or a member. */
    @Override
    public String toString() {
        return "ReferenceExpectedResolution[pin=" + (pin == null ? "none" : pin.identityKey())
                + ", questionCodes=" + questionCodes.size()
                + ']';
    }
}