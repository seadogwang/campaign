package com.company.campaign.evaluation.segment.review;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Pattern;

/**
 * The opaque authenticated reviewer reference supplied by an already-authenticated host.
 *
 * <p>This type exists to make reviewer identity and payload unrepresentable. It stores one bounded reference
 * — never an email address, a name, a credential, a tenant/user/DataScope or a request payload — because
 * the shared reference grammar admits neither {@code @} nor whitespace. The host is responsible for
 * authenticating the human and handing over only this reference; a model, generator or evaluation runner
 * cannot construct a review actor from its own output because the value never appears in model-visible
 * payloads.
 *
 * <p>The reference is a pointer to a revision-controlled identity, not the identity itself, so a ledger
 * record of it discloses nothing sensitive.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewActor(String reviewerRef) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReviewActor {
        if (reviewerRef == null || !REFERENCE_ID.matcher(reviewerRef).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_REFERENCE_INVALID,
                    "A review actor must carry one bounded opaque reviewer reference");
        }
    }
}