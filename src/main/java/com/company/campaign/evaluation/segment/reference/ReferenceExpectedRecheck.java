package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The observation one reference-corpus document case must produce after applying its declared drift.
 *
 * <p>A re-check is a genuine second observation rather than a replayed decision, so its expectation is stated
 * apart from the first clean resolution: it names the outcome a later lookup must reach — still clean, refused
 * with findings, or still resolving while the retention hold that protected the bytes is gone — and, exactly
 * when it was refused, the ordered, de-duplicated findings it must report. Stating findings for a clean or
 * hold-released re-check, or omitting them for a refusal, is a corpus defect rather than an unchecked claim.
 *
 * <p>The block is present only for a {@link ReferenceOutcome#CLEAN} document that also declares the drift it
 * applies, which {@link ReferenceCorpusExpected} and {@link ReferenceCorpusValidator} enforce; nothing here can
 * manufacture the drift it claims to observe.
 *
 * @param outcome    the drift outcome the re-check observed
 * @param issueCodes the ordered, de-duplicated findings a refusal reports, empty for every other outcome
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceExpectedRecheck(
        ReferenceRecheckOutcome outcome,
        List<String> issueCodes
) {

    public ReferenceExpectedRecheck {
        if (outcome == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A re-check expectation must state the drift outcome it observed");
        }
        issueCodes = ImmutableValues.freezeList(issueCodes == null ? List.of() : issueCodes);
        Set<String> seen = new LinkedHashSet<>();
        for (String code : issueCodes) {
            if (code == null || code.isBlank()) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                        "A re-check finding requires a bounded stable code");
            }
            if (!seen.add(code)) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                        "A re-check expectation must state each finding code exactly once",
                        Map.of("code", code));
            }
        }
        boolean refused = outcome == ReferenceRecheckOutcome.REJECTED;
        if (refused != !issueCodes.isEmpty()) {
            throw new ReferenceCorpusException(
                    refused
                            ? ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE
                            : ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                    "A re-check expectation states its findings exactly when it refused the reference");
        }
    }
}