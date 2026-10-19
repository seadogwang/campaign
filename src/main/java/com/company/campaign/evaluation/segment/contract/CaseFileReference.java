package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One ordered manifest entry pairing a case input file with its expected result file.
 *
 * <p>Both halves are hashed here so the manifest alone can prove the reviewed pair. The expected half is
 * optional in the wire shape on purpose: a corpus that lost one half must be detectable as a missing
 * counterpart with a specific code, not silently read as a smaller corpus. The loader turns that absence
 * into {@link EvaluationErrorCodes#CASE_EXPECTED_MISSING}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseFileReference(
        String caseId,
        String casePath,
        ContentHash caseHash,
        String expectedPath,
        ContentHash expectedHash,
        String reviewPath,
        ContentHash reviewHash
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern RELATIVE_JSON = Pattern.compile(EvaluationAssetPatterns.REPOSITORY_RELATIVE_JSON);

    /**
     * A reference without review evidence, for a corpus that is deliberately non-Gate.
     *
     * <p>The checked-in example predates the review ledger and stays loadable; a Gate-ready corpus carries
     * the review half, and the Gate readiness validator reports its absence.
     */
    public CaseFileReference(
            String caseId,
            String casePath,
            ContentHash caseHash,
            String expectedPath,
            ContentHash expectedHash) {
        this(caseId, casePath, caseHash, expectedPath, expectedHash, null, null);
    }

    public CaseFileReference {
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Case reference caseId '" + caseId + "' is not a valid reference identifier");
        }
        if (casePath == null || !RELATIVE_JSON.matcher(casePath).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Case path '" + casePath + "' is not a repository-relative JSON asset path",
                    Map.of("path", String.valueOf(casePath)));
        }
        if (caseHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Case reference for '" + caseId + "' must declare the case file content hash");
        }
        // The expected half is allowed to be absent so the loader can report a missing counterpart; when a
        // path is present a hash is mandatory, because an unhashed reference would be unverifiable.
        if (expectedPath != null && !RELATIVE_JSON.matcher(expectedPath).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Expected path '" + expectedPath + "' is not a repository-relative JSON asset path",
                    Map.of("path", expectedPath));
        }
        if (expectedPath != null && expectedHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Expected reference for case '" + caseId + "' must declare the expected file content hash");
        }
        if (expectedPath == null && expectedHash != null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Expected reference for case '" + caseId + "' declares a hash without a path");
        }
        // The review half is optional so the pre-review example still loads. When present, both halves are
        // mandatory: an unhashed review reference would be unverifiable, and a hash without a path cannot be
        // read. The Gate readiness validator reports a case that has no review reference at all.
        if (reviewPath != null && !RELATIVE_JSON.matcher(reviewPath).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Review path '" + reviewPath + "' is not a repository-relative JSON asset path",
                    Map.of("path", reviewPath));
        }
        if (reviewPath != null && reviewHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Review reference for case '" + caseId + "' must declare the review file content hash");
        }
        if (reviewPath == null && reviewHash != null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Review reference for case '" + caseId + "' declares a hash without a path");
        }
    }

    /** True when both halves of the pair are declared. */
    public boolean hasExpectedCounterpart() {
        return expectedPath != null && expectedHash != null;
    }

    /** True when the manifest binds a review-evidence file for this case. */
    public boolean hasReviewEvidence() {
        return reviewPath != null && reviewHash != null;
    }
}