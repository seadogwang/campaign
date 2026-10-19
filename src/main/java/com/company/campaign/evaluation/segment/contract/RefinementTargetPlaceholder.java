package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Pattern;

/**
 * The refinement target and scope a case exercises, in a form T12 can replace without rewrites.
 *
 * <p>Identity is expressed as references — a target and an optional enclosing scope — rather than as an
 * array position, because Segment specification section 16 makes array-index patching from model output
 * illegal. T03 reserves the shape only; applying operations, checking the base hash and re-validating are
 * T12's contract.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefinementTargetPlaceholder(
        RefinementOperation operation,
        String targetRef,
        String scopeRef
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public RefinementTargetPlaceholder {
        if (operation == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Refinement target must declare its operation");
        }
        if (targetRef == null || !REFERENCE_ID.matcher(targetRef).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Refinement target '" + targetRef + "' is not a valid reference identifier");
        }
        if (scopeRef != null && !REFERENCE_ID.matcher(scopeRef).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Refinement scope '" + scopeRef + "' is not a valid reference identifier");
        }
    }
}