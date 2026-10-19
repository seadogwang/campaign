package com.company.campaign.builder.agent;

import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One typed, fully grounded explanation claim.
 *
 * <p>A claim references only accepted clause ids, accepted predicate node ids, accepted Metadata definition
 * ids and/or a safe Preview statistic path, plus bounded semantic operator/value/window summaries. It cannot
 * contain free model prose, a physical schema fact, SQL, hidden Metadata, a member identifier or a value not
 * supported by the resolved DSL, plan or Preview. Every text field is bounded and machine-checkable, and the
 * record is immutable and deterministic.
 */
public record ExplanationClaim(
        String claimId,
        ExplanationClaimKind kind,
        List<String> clauseIds,
        List<String> nodeIds,
        List<String> definitionIds,
        String operator,
        String valueSummary,
        String windowSummary,
        String previewStatisticPath,
        String previewStatisticSummary
) {

    private static final Pattern CLAIM_ID = Pattern.compile("^claim-[1-9][0-9]{0,5}$");
    private static final Pattern REFERENCE = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final int MAX_SUMMARY = 256;
    private static final int MAX_REFERENCES = 64;

    public ExplanationClaim {
        if (claimId == null || !CLAIM_ID.matcher(claimId).matches()) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "An explanation claim requires a deterministic claim id");
        }
        if (kind == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "An explanation claim requires its kind");
        }
        clauseIds = references(clauseIds, "clauseIds");
        nodeIds = references(nodeIds, "nodeIds");
        definitionIds = references(definitionIds, "definitionIds");
        operator = bounded(operator, "operator");
        valueSummary = bounded(valueSummary, "valueSummary");
        windowSummary = bounded(windowSummary, "windowSummary");
        previewStatisticPath = bounded(previewStatisticPath, "previewStatisticPath");
        previewStatisticSummary = bounded(previewStatisticSummary, "previewStatisticSummary");
        if (clauseIds.isEmpty() && nodeIds.isEmpty() && definitionIds.isEmpty() && previewStatisticPath == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "An explanation claim must reference accepted evidence");
        }
    }

    private static List<String> references(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_REFERENCES) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "An explanation claim exceeds its bound: " + field);
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || !REFERENCE.matcher(value).matches()) {
                throw SegmentAgentException.invalid(
                        SegmentAgentErrorCodes.EXPLANATION_FAILED,
                        "An explanation claim reference must be a bounded stable identifier: " + field);
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static String bounded(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > MAX_SUMMARY) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "An explanation claim summary is invalid: " + field);
        }
        return value;
    }
}