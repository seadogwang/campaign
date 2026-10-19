package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.evaluation.segment.validation.GateReadinessValidator;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The deterministic production boundary that classifies an exact loaded corpus for use.
 *
 * <p>It answers one question and no other: <em>may this exact revision be used, and for what?</em> The
 * answer is derived entirely from the accepted validators:
 *
 * <ul>
 *   <li>{@link CorpusUsageClassification#INVALID} when candidate completeness fails — a structurally
 *       unusable corpus, which nothing may consume;</li>
 *   <li>{@link CorpusUsageClassification#DEVELOPMENT_ONLY} when candidate completeness passes but human
 *       review readiness does not — the machine-complete, not-yet-approved state that later development may
 *       use under development-only labelling;</li>
 *   <li>{@link CorpusUsageClassification#GATE_ELIGIBLE} only when both passes succeed.</li>
 * </ul>
 *
 * <p>Pending, rejected, missing or stale reviews are never silently ignored: the readiness issues are
 * carried as bounded stable codes on the result, and a non-eligible result cannot be turned into a
 * Gate-eligible one by assertion. The result also pins the exact corpus, manifest and review-set hashes it
 * was computed over, so the verdict cannot be relabeled onto another revision.
 *
 * <p>Nothing here reads a clock, a random source, the default locale, thread-local or global state, a model
 * or a database. The same bundle always yields the same verdict and the same hashes.
 */
public final class SegmentCorpusReviewBoundary {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final GateReadinessValidator validator = new GateReadinessValidator();

    /** Classifies one exact loaded corpus, carrying the hashes and issue codes that explain the verdict. */
    public ReviewedCorpusClassification classify(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        ContentHash manifestHash = requireManifestHash(bundle);
        ContentHash reviewSetHash = reviewSetHash(bundle);
        ContentHash corpusHash = corpusHash(bundle, manifestHash, reviewSetHash);

        EvaluationValidationReport candidate = validator.validateCandidate(bundle);
        if (!candidate.isCandidateComplete()) {
            return ReviewedCorpusClassification.invalid(
                    candidate.issueCodes(), corpusHash, manifestHash, reviewSetHash);
        }

        EvaluationValidationReport gate = validator.validate(bundle);
        if (gate.isGateReady()) {
            return ReviewedCorpusClassification.gateEligible(corpusHash, manifestHash, reviewSetHash);
        }
        // The candidate pass is clean, so the only remaining findings are the review-readiness ones. They
        // are carried verbatim: a pending, rejected, missing or stale review is never swallowed.
        return ReviewedCorpusClassification.developmentOnly(
                gate.issueCodes(), corpusHash, manifestHash, reviewSetHash);
    }

    private static ContentHash requireManifestHash(CorpusBundle bundle) {
        ContentHash manifestHash = bundle.manifest().manifestHash();
        if (manifestHash == null) {
            // A bundle that was not loaded through the manifest verifier has no exact corpus identity.
            throw new EvaluationException(
                    EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "A corpus usage verdict requires a manifest that declares its exact content hash");
        }
        return manifestHash;
    }

    /**
     * The exact ordered review-set pin: each case's current ledger content hash, in manifest order.
     *
     * <p>A case that declares no review evidence contributes an explicit null rather than being skipped, so a
     * missing review changes the pin instead of leaving it looking identical to a corpus that never had one.
     * When a ledger is loaded its own content hash is pinned; a corpus whose reviews were not loaded falls
     * back to the manifest's declared review-file hash, so a declared review can never vanish from the pin.
     */
    private static ContentHash reviewSetHash(CorpusBundle bundle) {
        List<CaseFileReference> references = bundle.manifest().cases();
        List<CaseReviewEvidence> reviews = bundle.reviews();
        List<ContentHash> hashes = new ArrayList<>(references.size());
        for (int index = 0; index < references.size(); index++) {
            CaseReviewEvidence review = index < reviews.size() ? reviews.get(index) : null;
            hashes.add(review != null ? review.reviewHash() : references.get(index).reviewHash());
        }
        return CANONICAL_JSON.hash(hashes);
    }

    private static ContentHash corpusHash(
            CorpusBundle bundle, ContentHash manifestHash, ContentHash reviewSetHash) {
        return CANONICAL_JSON.hash(Map.of(
                "corpusVersion", bundle.manifest().corpusVersion(),
                "manifestHash", manifestHash.value(),
                "reviewSetHash", reviewSetHash.value()));
    }
}