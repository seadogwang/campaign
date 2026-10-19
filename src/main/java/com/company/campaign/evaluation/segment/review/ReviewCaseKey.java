package com.company.campaign.evaluation.segment.review;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Pattern;

/**
 * The exact identity of one reviewable revision, as pinned by the corpus manifest.
 *
 * <p>A key names the corpus version, the case id and the two content hashes that were reviewed. It is the
 * optimistic-concurrency identity of a decision: the same tuple always names the same bytes, so a decision
 * recorded against one revision cannot be replayed against an edited one. The key deliberately carries no
 * expectation content, reviewer identity or role — those belong to the command or the ledger.
 *
 * <p>Validation is bounded and non-disclosing: a malformed or missing field fails with one stable review
 * code, and the rejected text is never echoed back.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewCaseKey(
        String corpusVersion,
        String caseId,
        ContentHash caseHash,
        ContentHash expectedHash
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReviewCaseKey {
        if (corpusVersion == null || !REFERENCE_ID.matcher(corpusVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review case key must declare a bounded corpus version");
        }
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review case key must declare a bounded case id");
        }
        if (caseHash == null || expectedHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review case key must bind both the case hash and the expected-result hash");
        }
    }
}