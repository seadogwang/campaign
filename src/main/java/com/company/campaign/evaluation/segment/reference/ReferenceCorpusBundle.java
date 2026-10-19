package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.ImmutableValues;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a loaded reference corpus consists of, in manifest order.
 *
 * <p>A bundle exists only after the loader has verified every file reference, every content hash, every
 * identity pairing and the manifest's own hash. It is therefore the point at which "the files are intact" is
 * established — and deliberately not the point at which readiness is established, because a corpus can be
 * intact and still fail every quality and evidence rule the validator applies.
 *
 * <p>Cases, expectations and the manifest's references are positionally aligned, so a validator and an
 * evaluator can walk the corpus deterministically without re-sorting. The fixture payload bytes are carried
 * opaquely, exactly as the base corpus carries Metadata payload bytes: interpreting them is the fixture
 * loader's job, and one that is not interpretable must be reportable rather than unloadable.
 */
public record ReferenceCorpusBundle(
        ReferenceCorpusManifest manifest,
        ReferenceFixtureCatalog fixtureCatalog,
        Map<String, byte[]> fixturePayloads,
        List<ReferenceCorpusCase> cases,
        List<ReferenceCorpusExpected> expectedResults,
        List<CaseReviewEvidence> reviews
) {

    public ReferenceCorpusBundle {
        if (manifest == null || fixtureCatalog == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference corpus bundle requires its manifest and fixture catalog");
        }
        if (cases == null || expectedResults == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference corpus bundle requires its cases and expectations");
        }
        if (cases.size() != expectedResults.size()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_EXPECTED_MISSING,
                    "The reference corpus has " + cases.size() + " cases but " + expectedResults.size()
                            + " expectations");
        }
        List<CaseReviewEvidence> alignedReviews = reviews == null ? List.of() : reviews;
        if (!alignedReviews.isEmpty() && alignedReviews.size() != cases.size()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.REVIEW_EVIDENCE_MISSING,
                    "The reference corpus has " + cases.size() + " cases but " + alignedReviews.size()
                            + " review ledgers");
        }
        cases = ImmutableValues.freezeList(cases);
        expectedResults = ImmutableValues.freezeList(expectedResults);
        reviews = ImmutableValues.freezeList(alignedReviews);
        fixturePayloads = ImmutableValues.freezeMap(
                fixturePayloads == null ? Map.of() : new LinkedHashMap<>(fixturePayloads));
    }

    /** The raw bytes of one fixture payload, or {@code null} when the catalog does not declare it. */
    public byte[] fixturePayload(String fixtureId) {
        byte[] payload = fixturePayloads.get(fixtureId);
        return payload == null ? null : payload.clone();
    }

    /** The expectation paired with the given case, or {@code null} when the bundle has none. */
    public ReferenceCorpusExpected expectedFor(String caseId) {
        for (ReferenceCorpusExpected expected : expectedResults) {
            if (expected.caseId().equals(caseId)) {
                return expected;
            }
        }
        return null;
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
}