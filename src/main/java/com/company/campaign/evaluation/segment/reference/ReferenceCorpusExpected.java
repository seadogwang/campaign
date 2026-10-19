package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The reviewed expectation of one reference-corpus case: exactly what the reference core must observe.
 *
 * <p>It states the terminal outcome and every artifact that outcome requires — the ordered reference findings,
 * the canonical AST hash of the validated parent document, the frozen dependency graph, the accepted exact pin
 * with its resolution evidence hash, the member-set hashes and the retention or re-check observation — and it
 * states nothing the outcome forbids. That symmetry is what makes an under-specified or over-specified
 * expectation a corpus defect rather than an unchecked claim: an expectation that omits the graph of a clean
 * resolution, or that carries a graph for a rejection, is refused when the corpus loads.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceCorpusExpected(
        SchemaVersion schemaVersion,
        String caseId,
        ReferenceOutcome expectedOutcome,
        List<String> expectedIssueCodes,
        ContentHash editableDslHash,
        ContentHash canonicalAstHash,
        ReferenceExpectedGraph graph,
        ReferenceExpectedResolution resolution,
        List<ReferenceExpectedMembership> membership,
        ReferenceExpectedRetention retention,
        ReferenceExpectedRecheck recheck
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferenceCorpusExpected {
        EvaluationVersions.requireSupported(schemaVersion);
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Reference corpus expectation caseId '" + caseId
                            + "' is not a valid reference identifier");
        }
        if (expectedOutcome == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference corpus expectation for '" + caseId + "' must declare its outcome");
        }
        expectedIssueCodes = ImmutableValues.freezeList(
                expectedIssueCodes == null ? List.of() : expectedIssueCodes);
        membership = ImmutableValues.freezeList(membership == null ? List.of() : membership);

        boolean rejectedOutcome = expectedOutcome == ReferenceOutcome.REJECTED
                || expectedOutcome == ReferenceOutcome.INVALID;
        if (rejectedOutcome != !expectedIssueCodes.isEmpty()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference corpus expectation for '" + caseId
                            + "' must state its findings exactly when its outcome is a refusal");
        }
        if ((editableDslHash == null) != (canonicalAstHash == null)) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference corpus expectation for '" + caseId
                            + "' must state both document hashes or neither");
        }
        boolean cleanDocument = expectedOutcome == ReferenceOutcome.CLEAN;
        if (cleanDocument != (canonicalAstHash != null)) {
            throw new ReferenceCorpusException(
                    cleanDocument
                            ? ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE
                            : ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                    "Reference corpus expectation for '" + caseId
                            + "' states the parent document hashes exactly for a clean document");
        }
        if ((graph != null) != cleanDocument) {
            throw new ReferenceCorpusException(
                    cleanDocument
                            ? ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE
                            : ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                    "Reference corpus expectation for '" + caseId
                            + "' freezes its dependency graph exactly for a clean document");
        }
        if (recheck != null && expectedOutcome != ReferenceOutcome.CLEAN) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                    "Reference corpus expectation for '" + caseId
                            + "' must state a re-check only for a clean document");
        }
        if ((resolution != null) != expectedOutcome.appliesTo(ReferenceScenario.REFERENCE_RESOLUTION)) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference corpus expectation for '" + caseId
                            + "' must state its resolution evidence exactly for a resolution outcome");
        }
        if (!membership.isEmpty() != expectedOutcome.appliesTo(ReferenceScenario.REFERENCE_MEMBERSHIP)) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference corpus expectation for '" + caseId
                            + "' must state its membership observations exactly for a membership outcome");
        }
        if ((retention != null) != expectedOutcome.appliesTo(ReferenceScenario.REFERENCE_RETENTION)) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference corpus expectation for '" + caseId
                            + "' must state its retention observation exactly for a retention outcome");
        }
    }

    /** True when this expectation freezes a reference graph. */
    public boolean freezesGraph() {
        return graph != null;
    }

    /** The frozen exact pin of an accepted resolution, or {@code null} for every other outcome. */
    public ReferencePin resolvedPin() {
        return resolution == null ? null : resolution.pin();
    }

    /** The exact number of reference sites the expectation accounts for, when it freezes a graph. */
    public int distinctVersions() {
        return graph == null ? 0 : graph.distinctVersions();
    }
}