package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SourceSpan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One expected Intent slot of a case.
 *
 * <p>A slot records what the resolver must extract, not the text it came from: {@code concept} and
 * {@code operatorIntent} are stable machine codes, and {@code value} is a canonical JSON tree placeholder
 * because the typed value union belongs to T05. {@code value} may be {@code null} — operators such as
 * {@code IS_NULL} genuinely carry no value — and the field is written explicitly so "no value" cannot be
 * confused with "field forgotten".
 *
 * <p>{@code grounded} states whether the slot must be traceable to the user's own words. When it is true
 * the slot has to carry a source span that points at real utterance text, checked against the case
 * utterance rather than trusted.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ExpectedIntentSlot(
        String slotId,
        String concept,
        String operatorIntent,
        boolean grounded,
        SourceSpan sourceSpan,
        JsonNode value
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public ExpectedIntentSlot {
        if (slotId == null || !REFERENCE_ID.matcher(slotId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Intent slot id '" + slotId + "' is not a valid reference identifier");
        }
        if (concept == null || !MACHINE_CODE.matcher(concept).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Intent slot '" + slotId + "' concept '" + concept + "' is not a valid machine code",
                    Map.of("slotId", slotId));
        }
        if (operatorIntent == null || !MACHINE_CODE.matcher(operatorIntent).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Intent slot '" + slotId + "' operatorIntent '" + operatorIntent
                            + "' is not a valid machine code",
                    Map.of("slotId", slotId));
        }
    }

    /** True when this slot must be traceable to user text, which the validator then has to prove. */
    public boolean requiresSourceEvidence() {
        return grounded;
    }
}