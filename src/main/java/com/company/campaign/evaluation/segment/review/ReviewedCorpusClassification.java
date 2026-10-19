package com.company.campaign.evaluation.segment.review;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The deterministic usage verdict for one exact loaded corpus, with the evidence that prevents relabeling.
 *
 * <p>The verdict is only ever produced by {@link SegmentCorpusReviewBoundary}. It carries the bounded stable
 * issue codes that explain a non-eligible verdict and the exact corpus, manifest and review-set hashes the
 * verdict was computed over. Those hashes are the anti-relabeling guard: a {@link
 * CorpusUsageClassification#DEVELOPMENT_ONLY} result names the precise manifest and review set it describes,
 * so moving the label onto a different revision is detectable rather than silent.
 *
 * <p>The type is a class rather than a record on purpose. A record's canonical constructor is public, which
 * would let any caller fabricate a {@link CorpusUsageClassification#GATE_ELIGIBLE} verdict; here the
 * constructor is private and the only factories are package-private and called by the classifier. Downstream
 * code can therefore obtain Gate eligibility by reclassifying an exact corpus, never by asserting it — which
 * is exactly the "cannot be converted without reloading and revalidating exact review evidence" rule.
 */
public final class ReviewedCorpusClassification {

    private final CorpusUsageClassification classification;
    private final List<String> issueCodes;
    private final ContentHash corpusHash;
    private final ContentHash manifestHash;
    private final ContentHash reviewSetHash;

    private ReviewedCorpusClassification(
            CorpusUsageClassification classification,
            List<String> issueCodes,
            ContentHash corpusHash,
            ContentHash manifestHash,
            ContentHash reviewSetHash) {
        this.classification = Objects.requireNonNull(classification, "classification");
        this.corpusHash = Objects.requireNonNull(corpusHash, "corpusHash");
        this.manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        this.reviewSetHash = Objects.requireNonNull(reviewSetHash, "reviewSetHash");

        List<String> bounded = boundedCodes(issueCodes);
        if (classification == CorpusUsageClassification.GATE_ELIGIBLE && !bounded.isEmpty()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_NOT_GATE_ELIGIBLE,
                    "A Gate-eligible corpus cannot carry blocking review issue codes");
        }
        if (classification != CorpusUsageClassification.GATE_ELIGIBLE && bounded.isEmpty()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A non-eligible corpus must carry the stable issue codes that explain its verdict");
        }
        this.issueCodes = ImmutableValues.freezeList(bounded);
    }

    static ReviewedCorpusClassification invalid(
            List<String> issueCodes, ContentHash corpusHash, ContentHash manifestHash,
            ContentHash reviewSetHash) {
        return new ReviewedCorpusClassification(
                CorpusUsageClassification.INVALID, issueCodes, corpusHash, manifestHash, reviewSetHash);
    }

    static ReviewedCorpusClassification developmentOnly(
            List<String> issueCodes, ContentHash corpusHash, ContentHash manifestHash,
            ContentHash reviewSetHash) {
        return new ReviewedCorpusClassification(
                CorpusUsageClassification.DEVELOPMENT_ONLY, issueCodes, corpusHash, manifestHash, reviewSetHash);
    }

    static ReviewedCorpusClassification gateEligible(
            ContentHash corpusHash, ContentHash manifestHash, ContentHash reviewSetHash) {
        return new ReviewedCorpusClassification(
                CorpusUsageClassification.GATE_ELIGIBLE, List.of(), corpusHash, manifestHash, reviewSetHash);
    }

    public CorpusUsageClassification classification() {
        return classification;
    }

    /** The distinct stable issue codes that explain the verdict, in deterministic evaluation order. */
    public List<String> issueCodes() {
        return issueCodes;
    }

    /** Hash binding the corpus version, manifest hash and review-set hash this verdict was computed over. */
    public ContentHash corpusHash() {
        return corpusHash;
    }

    /** The exact manifest content hash the verdict was computed over. */
    public ContentHash manifestHash() {
        return manifestHash;
    }

    /** The exact ordered review-ledger hash set the verdict was computed over. */
    public ContentHash reviewSetHash() {
        return reviewSetHash;
    }

    @JsonIgnore
    public boolean isGateEligible() {
        return classification.isGateEligible();
    }

    @JsonIgnore
    public boolean isUsableForDevelopment() {
        return classification.isUsableForDevelopment();
    }

    /** Requires Gate eligibility, refusing to let a development-only verdict act as an official one. */
    public void requireGateEligible() {
        if (!isGateEligible()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_NOT_GATE_ELIGIBLE,
                    "Corpus usage classification " + classification
                            + " is not Gate-eligible; reload and revalidate exact review evidence");
        }
    }

    /** Requires a usable corpus, refusing to hand an invalid corpus to later development code. */
    public void requireUsableForDevelopment() {
        if (!isUsableForDevelopment()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "An invalid corpus cannot be used for development or Gate evaluation");
        }
    }

    private static List<String> boundedCodes(List<String> issueCodes) {
        if (issueCodes == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A corpus usage verdict must carry its issue-code list");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String code : issueCodes) {
            if (code == null || !code.matches(ContractPatterns.MACHINE_CODE)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                        "A corpus usage verdict may only carry bounded machine issue codes");
            }
            distinct.add(code);
        }
        return new ArrayList<>(distinct);
    }

    /** The verdict in one line, for deterministic reporting. */
    @Override
    public String toString() {
        return "ReviewedCorpusClassification{" + classification
                + ", issueCodes=" + issueCodes
                + ", corpusHash=" + corpusHash
                + ", manifestHash=" + manifestHash
                + ", reviewSetHash=" + reviewSetHash + '}';
    }
}