package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.loader.EvaluationRoot;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic loader for the T18 Existing Audience Reference corpus.
 *
 * <p>It answers exactly one question: <em>are these files what the manifest says they are?</em> Every
 * reference is resolved beneath the evaluation root and re-checked against the real filesystem through
 * {@link SegmentEvaluationLoader}, so containment, symlink retargeting and hash verification are proved by
 * the one accepted implementation rather than a second one that could disagree with it. Each asset is then
 * parsed strictly and required to be its own canonical serialization, so a hand-edited file whose bytes no
 * longer match the reviewed value cannot pass as the reviewed value.
 *
 * <p>It answers that question and no other. Coverage, expectation completeness and review status are the
 * validator's business: a corpus must be able to fail validation without the loader refusing to read it, and
 * a small development corpus must be loadable without pretending to be Gate-ready.
 *
 * <p>Nothing here reads a clock, generates an identifier, touches the network or depends on the default
 * locale: the same files always produce the same bundle.
 */
public final class ReferenceCorpusLoader {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final EvaluationRoot root;
    private final SegmentEvaluationLoader delegate;

    public ReferenceCorpusLoader(Path repositoryRoot) {
        this(EvaluationRoot.of(repositoryRoot));
    }

    public ReferenceCorpusLoader(EvaluationRoot root) {
        this.root = Objects.requireNonNull(root, "root");
        this.delegate = new SegmentEvaluationLoader(root);
    }

    /** The configured evaluation root. */
    public EvaluationRoot root() {
        return root;
    }

    /** The committed reference-corpus manifest reference inside the configured evaluation root. */
    public String defaultManifestReference() {
        return ReferenceCorpusVersions.MANIFEST_REFERENCE;
    }

    /** Loads and verifies the reference-corpus manifest. */
    public ReferenceCorpusManifest loadManifest(String manifestReference) {
        ReferenceCorpusManifest manifest = readAsset(
                manifestReference, null, ReferenceCorpusManifest.class);
        manifest.requireValidContentHash();
        EvaluationVersions.requireSupported(manifest.schemaVersion());
        return manifest;
    }

    /** Loads and verifies the corpus using the committed manifest reference. */
    public ReferenceCorpusBundle loadCorpus() {
        return loadCorpus(defaultManifestReference());
    }

    /**
     * Loads a whole corpus from its manifest, verifying every reference, hash and identity pairing.
     *
     * <p>The returned bundle is intact but not necessarily complete; call {@link ReferenceCorpusValidator}
     * for that.
     */
    public ReferenceCorpusBundle loadCorpus(String manifestReference) {
        ReferenceCorpusManifest manifest = loadManifest(manifestReference);

        ReferenceFixtureCatalog fixtureCatalog = readAsset(
                manifest.fixtureCatalog().path(), manifest.fixtureCatalog().contentHash(),
                ReferenceFixtureCatalog.class);
        EvaluationVersions.requireSupported(fixtureCatalog.schemaVersion());
        requireMatchingCatalogVersion(manifest.fixtureCatalog(), fixtureCatalog.catalogVersion());
        Map<String, byte[]> fixturePayloads = verifyFixturePayloads(fixtureCatalog);

        Set<String> referencedPaths = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        List<ReferenceCorpusCase> cases = new ArrayList<>(manifest.cases().size());
        List<ReferenceCorpusExpected> expectedResults = new ArrayList<>(manifest.cases().size());
        List<CaseReviewEvidence> reviews = new ArrayList<>(manifest.cases().size());

        for (CaseFileReference reference : manifest.cases()) {
            if (!caseIds.add(reference.caseId())) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.DUPLICATE_CASE,
                        "Reference corpus case id '" + reference.caseId()
                                + "' is declared more than once in the manifest",
                        Map.of("caseId", reference.caseId()));
            }
            requireUniqueReference(referencedPaths, reference.casePath());
            if (!reference.hasExpectedCounterpart()) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.CASE_EXPECTED_MISSING,
                        "Reference corpus case '" + reference.caseId() + "' has no expected-result reference",
                        Map.of("caseId", reference.caseId()));
            }
            requireUniqueReference(referencedPaths, reference.expectedPath());

            ReferenceCorpusCase caseInput = readAsset(
                    reference.casePath(), reference.caseHash(), ReferenceCorpusCase.class);
            EvaluationVersions.requireSupported(caseInput.schemaVersion());
            if (!reference.caseId().equals(caseInput.caseId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.CASE_EXPECTED_MISMATCH,
                        "Manifest reference '" + reference.caseId() + "' does not match case file id '"
                                + caseInput.caseId() + "'",
                        Map.of("declared", reference.caseId(), "actual", caseInput.caseId()));
            }

            ReferenceCorpusExpected expected = readAsset(
                    reference.expectedPath(), reference.expectedHash(), ReferenceCorpusExpected.class);
            EvaluationVersions.requireSupported(expected.schemaVersion());
            if (!caseInput.caseId().equals(expected.caseId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.CASE_EXPECTED_MISMATCH,
                        "Expectation id '" + expected.caseId() + "' does not match case id '"
                                + caseInput.caseId() + "'",
                        Map.of("caseId", caseInput.caseId(), "expectedCaseId", expected.caseId()));
            }

            cases.add(caseInput);
            expectedResults.add(expected);

            if (reference.hasReviewEvidence()) {
                requireUniqueReference(referencedPaths, reference.reviewPath());
                CaseReviewEvidence review = delegate.loadReview(reference.reviewPath(), reference.reviewHash());
                if (!caseInput.caseId().equals(review.caseId())) {
                    throw new EvaluationException(
                            EvaluationErrorCodes.REVIEW_CASE_MISMATCH,
                            "Review evidence id '" + review.caseId() + "' does not match case id '"
                                    + caseInput.caseId() + "'",
                            Map.of("caseId", caseInput.caseId(), "reviewCaseId", review.caseId()));
                }
                reviews.add(review);
            } else {
                reviews.add(null);
            }
        }

        return new ReferenceCorpusBundle(
                manifest, fixtureCatalog, fixturePayloads, cases, expectedResults, reviews);
    }

    /**
     * Verifies each fixture payload's bytes and returns them keyed by fixture identity.
     *
     * <p>The loader stays deliberately opaque here, exactly as the base corpus loader does with Metadata
     * payloads: it proves the bytes are what the catalog pins and hands them on, without interpreting which
     * port they configure. The fixture loader reports a payload that is not the port it claims to be.
     */
    private Map<String, byte[]> verifyFixturePayloads(ReferenceFixtureCatalog catalog) {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        for (ReferenceFixtureDescriptor fixture : catalog.fixtures()) {
            payloads.put(fixture.fixtureId(),
                    delegate.readVerifiedBytes(fixture.payloadPath(), fixture.payloadHash()));
        }
        return payloads;
    }

    /**
     * Reads, verifies and parses a JSON asset, requiring its bytes to be the canonical serialization of the
     * value they contain.
     *
     * <p>Requiring canonical bytes is what ties the reviewed file to the reviewed value: without it, two files
     * that differ only in whitespace or key order would carry different hashes while loading to the same
     * asset, and a hand-edited corpus could silently change the bytes a hash was taken over.
     */
    private <T> T readAsset(String reference, ContentHash expectedHash, Class<T> type) {
        byte[] bytes = delegate.readVerifiedBytes(reference, expectedHash);
        T asset = CANONICAL_JSON.read(new String(bytes, StandardCharsets.UTF_8), type);
        byte[] canonical = CANONICAL_JSON.write(asset);
        if (!java.util.Arrays.equals(canonical, bytes)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_NOT_CANONICAL,
                    "Asset '" + reference + "' is valid JSON but not its canonical serialization",
                    Map.of("reference", reference,
                            "canonicalHash", ContentHash.sha256(canonical).value()));
        }
        return asset;
    }

    private static void requireMatchingCatalogVersion(CatalogReference declared, String actual) {
        if (!declared.catalogVersion().equals(actual)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Fixture catalog version '" + actual + "' does not match the manifest's declared '"
                            + declared.catalogVersion() + "'",
                    Map.of("declared", declared.catalogVersion(), "actual", actual));
        }
    }

    private static void requireUniqueReference(Set<String> seen, String reference) {
        if (!seen.add(reference)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.DUPLICATE_REFERENCE,
                    "The manifest references '" + reference + "' more than once",
                    Map.of("reference", reference));
        }
    }
}