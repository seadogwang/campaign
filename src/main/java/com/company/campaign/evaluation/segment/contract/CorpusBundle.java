package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.shared.ImmutableValues;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a loaded corpus consists of, in manifest order.
 *
 * <p>A bundle exists only after the loader has verified every file reference, every content hash, every
 * identity pairing and the manifest's own hash. It is therefore the point at which "the files are intact"
 * is established — and the point at which Gate readiness is deliberately <em>not</em> established, because
 * a small reviewed example must be loadable without pretending to be a Gate corpus.
 *
 * <p>Cases, expected results and the manifest's references are positionally aligned: entry {@code i} of
 * each describes the same case, so a validator can walk the corpus deterministically without re-sorting.
 */
public record CorpusBundle(
        CorpusManifest manifest,
        MetadataCatalog metadataCatalog,
        FixtureCatalog fixtureCatalog,
        List<CaseInput> cases,
        List<ExpectedResult> expectedResults,
        List<CaseReviewEvidence> reviews,
        Map<String, byte[]> metadataPayloads,
        Map<String, TrustedExecutionContextRef> executionContexts
) {

    /** A bundle without review evidence or Metadata payload bytes, for corpora that predate them. */
    public CorpusBundle(
            CorpusManifest manifest,
            MetadataCatalog metadataCatalog,
            FixtureCatalog fixtureCatalog,
            List<CaseInput> cases,
            List<ExpectedResult> expectedResults,
            Map<String, TrustedExecutionContextRef> executionContexts) {
        this(manifest, metadataCatalog, fixtureCatalog, cases, expectedResults, List.of(), Map.of(),
                executionContexts);
    }

    /** A bundle without Metadata payload bytes, for corpora whose Metadata is still opaque. */
    public CorpusBundle(
            CorpusManifest manifest,
            MetadataCatalog metadataCatalog,
            FixtureCatalog fixtureCatalog,
            List<CaseInput> cases,
            List<ExpectedResult> expectedResults,
            List<CaseReviewEvidence> reviews,
            Map<String, TrustedExecutionContextRef> executionContexts) {
        this(manifest, metadataCatalog, fixtureCatalog, cases, expectedResults, reviews, Map.of(),
                executionContexts);
    }

    public CorpusBundle {
        if (manifest == null || metadataCatalog == null || fixtureCatalog == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "A corpus bundle requires manifest and catalogs");
        }
        if (cases == null || expectedResults == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "A corpus bundle requires cases and expected results");
        }
        if (cases.size() != expectedResults.size()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.CASE_EXPECTED_MISSING,
                    "Corpus has " + cases.size() + " cases but " + expectedResults.size() + " expected results");
        }
        // Review evidence is positionally aligned with the cases. An entry may be null when the manifest
        // declared no review path for that case; the Gate then reports a missing review rather than the
        // loader refusing to read a corpus that is deliberately non-Gate.
        List<CaseReviewEvidence> alignedReviews = reviews == null ? List.of() : reviews;
        if (!alignedReviews.isEmpty() && alignedReviews.size() != cases.size()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_CASE_MISMATCH,
                    "Corpus has " + cases.size() + " cases but " + alignedReviews.size() + " review ledgers");
        }
        cases = ImmutableValues.freezeList(cases);
        expectedResults = ImmutableValues.freezeList(expectedResults);
        reviews = ImmutableValues.freezeList(alignedReviews);
        metadataPayloads = ImmutableValues.freezeMap(
                metadataPayloads == null ? Map.of() : new LinkedHashMap<>(metadataPayloads));
        executionContexts = ImmutableValues.freezeMap(
                executionContexts == null ? Map.of() : new LinkedHashMap<>(executionContexts));
    }

    /**
     * The raw bytes of the Metadata payload for the given snapshot id, or {@code null} when unknown.
     *
     * <p>The contract deliberately carries bytes rather than a parsed Metadata snapshot: the evaluation
     * contract and loader stay independent of T04, and interpreting the payload as a typed snapshot is the
     * validation layer's job. A payload that is present but not a valid snapshot therefore still loads, and
     * the validator reports it rather than the loader refusing the corpus.
     */
    public byte[] metadataPayload(String snapshotId) {
        byte[] payload = metadataPayloads.get(snapshotId);
        return payload == null ? null : payload.clone();
    }

    /** The review ledger for the given case, or {@code null} when the manifest declared none. */
    public CaseReviewEvidence reviewFor(String caseId) {
        if (reviews.isEmpty()) {
            return null;
        }
        for (int index = 0; index < cases.size(); index++) {
            if (cases.get(index).caseId().equals(caseId)) {
                return index < reviews.size() ? reviews.get(index) : null;
            }
        }
        return null;
    }

    /** The expected result paired with the given case, or {@code null} when the bundle has none. */
    public ExpectedResult expectedFor(String caseId) {
        for (ExpectedResult expected : expectedResults) {
            if (expected.caseId().equals(caseId)) {
                return expected;
            }
        }
        return null;
    }

    /** The fixture with the given id, or {@code null} when the catalog does not contain it. */
    public FixtureDescriptor fixture(String fixtureId) {
        return fixtureCatalog.find(fixtureId);
    }

    /** The parsed trusted execution context of the given execution-context fixture, if any. */
    public TrustedExecutionContextRef executionContext(String fixtureId) {
        return executionContexts.get(fixtureId);
    }
}