package com.company.campaign.evaluation.segment.loader;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureCatalog;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MetadataCatalog;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic loader for the Segment evaluation skeleton.
 *
 * <p>The loader answers exactly one question: <em>are these files what the manifest says they are?</em> It
 * verifies that every reference resolves beneath the evaluation root, that the file it names really lives
 * beneath that root once symlinks and junctions are resolved, that every referenced file exists, and that
 * every file's bytes match the SHA-256 hash declared for it. It then parses each asset strictly,
 * so unknown fields and duplicate JSON keys fail through the canonical JSON rules rather than being
 * absorbed.
 *
 * <p>It answers that question and no other. Category coverage, counterexample requirements and every other
 * semantic expectation are left to the validators: a small reviewed example must be loadable without being
 * mistaken for a Gate corpus, and a corpus must be able to fail validation without the loader refusing to
 * read it.
 *
 * <p>Nothing here reads a clock, generates an identifier, touches the network or depends on the default
 * locale: the same files always produce the same bundle.
 */
public final class SegmentEvaluationLoader {

    /** File name of the corpus manifest directly inside the evaluation root. */
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final EvaluationRoot root;

    public SegmentEvaluationLoader(Path repositoryRoot) {
        this(EvaluationRoot.of(repositoryRoot));
    }

    public SegmentEvaluationLoader(EvaluationRoot root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public EvaluationRoot root() {
        return root;
    }

    /** Repository-relative reference of the manifest inside the configured evaluation root. */
    public String defaultManifestReference() {
        return root.referenceFor(root.evaluationRoot().resolve(MANIFEST_FILE_NAME));
    }

    /** Loads and verifies the corpus manifest. */
    public CorpusManifest loadManifest(String manifestReference) {
        CorpusManifest manifest = readAsset(manifestReference, null, CorpusManifest.class);
        manifest.requireValidContentHash();
        requireSupported(manifest.schemaVersion());
        return manifest;
    }

    /** Loads and verifies a case input file. */
    public CaseInput loadCase(String caseReference) {
        CaseInput caseInput = readAsset(caseReference, null, CaseInput.class);
        requireSupported(caseInput.schemaVersion());
        return caseInput;
    }

    /** Loads and verifies an expected-result file. */
    public ExpectedResult loadExpected(String expectedReference) {
        ExpectedResult expected = readAsset(expectedReference, null, ExpectedResult.class);
        requireSupported(expected.schemaVersion());
        return expected;
    }

    /** Loads and verifies one case review-evidence file against its declared hash, when known. */
    public CaseReviewEvidence loadReview(String reviewReference, ContentHash expectedHash) {
        CaseReviewEvidence review = readAsset(reviewReference, expectedHash, CaseReviewEvidence.class);
        requireSupported(review.schemaVersion());
        review.requireValidContentHash();
        return review;
    }

    /**
     * Loads a whole corpus from its manifest, verifying every reference, hash and identity pairing.
     *
     * <p>The returned bundle is intact but not necessarily Gate-ready; call a validator for that.
     */
    public CorpusBundle loadCorpus(String manifestReference) {
        CorpusManifest manifest = loadManifest(manifestReference);

        MetadataCatalog metadataCatalog = readAsset(
                manifest.metadataCatalog().path(), manifest.metadataCatalog().contentHash(), MetadataCatalog.class);
        requireSupported(metadataCatalog.schemaVersion());
        requireMatchingCatalogVersion(
                "Metadata", manifest.metadataCatalog(), metadataCatalog.catalogVersion());
        Map<String, byte[]> metadataPayloads = verifyMetadataPayloads(metadataCatalog);

        FixtureCatalog fixtureCatalog = readAsset(
                manifest.fixtureCatalog().path(), manifest.fixtureCatalog().contentHash(), FixtureCatalog.class);
        requireSupported(fixtureCatalog.schemaVersion());
        requireMatchingCatalogVersion(
                "fixture", manifest.fixtureCatalog(), fixtureCatalog.catalogVersion());
        Map<String, TrustedExecutionContextRef> executionContexts = verifyFixturePayloads(fixtureCatalog);

        Set<String> referencedPaths = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        List<CaseInput> cases = new ArrayList<>(manifest.cases().size());
        List<ExpectedResult> expectedResults = new ArrayList<>(manifest.cases().size());
        List<CaseReviewEvidence> reviews = new ArrayList<>(manifest.cases().size());

        for (CaseFileReference reference : manifest.cases()) {
            if (!caseIds.add(reference.caseId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_ID,
                        "Case id '" + reference.caseId() + "' is declared more than once in the manifest",
                        Map.of("caseId", reference.caseId()));
            }
            requireUniqueReference(referencedPaths, reference.casePath());
            if (!reference.hasExpectedCounterpart()) {
                throw new EvaluationException(
                        EvaluationErrorCodes.CASE_EXPECTED_MISSING,
                        "Case '" + reference.caseId() + "' has no expected-result reference",
                        Map.of("caseId", reference.caseId()));
            }
            requireUniqueReference(referencedPaths, reference.expectedPath());

            CaseInput caseInput = readAsset(reference.casePath(), reference.caseHash(), CaseInput.class);
            requireSupported(caseInput.schemaVersion());
            if (!reference.caseId().equals(caseInput.caseId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.CASE_EXPECTED_MISMATCH,
                        "Manifest reference '" + reference.caseId() + "' does not match case file id '"
                                + caseInput.caseId() + "'",
                        Map.of("declared", reference.caseId(), "actual", caseInput.caseId()));
            }

            ExpectedResult expected = readAsset(
                    reference.expectedPath(), reference.expectedHash(), ExpectedResult.class);
            requireSupported(expected.schemaVersion());
            if (!caseInput.caseId().equals(expected.caseId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.CASE_EXPECTED_MISMATCH,
                        "Expected result id '" + expected.caseId() + "' does not match case id '"
                                + caseInput.caseId() + "'",
                        Map.of("caseId", caseInput.caseId(), "expectedCaseId", expected.caseId()));
            }

            cases.add(caseInput);
            expectedResults.add(expected);

            if (reference.hasReviewEvidence()) {
                requireUniqueReference(referencedPaths, reference.reviewPath());
                CaseReviewEvidence review = loadReview(reference.reviewPath(), reference.reviewHash());
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

        return new CorpusBundle(
                manifest, metadataCatalog, fixtureCatalog, cases, expectedResults, reviews, metadataPayloads,
                executionContexts);
    }

    /** Loads the corpus using the manifest at the default location inside the evaluation root. */
    public CorpusBundle loadCorpus() {
        return loadCorpus(defaultManifestReference());
    }

    /**
     * Reads a file's bytes and requires them to match the declared hash.
     *
     * <p>Containment is enforced twice on purpose. {@link EvaluationRoot#resolve(String)} rejects absolute,
     * traversing and backslash references deterministically from the reference text alone. The real path is
     * then resolved with {@link RealPathResolver} and required to be beneath the real evaluation root, so a
     * symlink or junction inside the root cannot redirect the read to a file outside it. The bytes are read
     * from the resolved real path rather than the original alias, so retargeting the link after validation
     * cannot cause a second traversal.
     */
    public byte[] readVerifiedBytes(String reference, ContentHash expectedHash) {
        Path candidate = root.resolve(reference);
        Path file = new RealPathResolver(root.evaluationRoot()).resolveExistingFile(candidate, reference);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_UNREADABLE,
                    "Asset '" + reference + "' cannot be read",
                    Map.of("reference", reference), exception);
        }
        if (expectedHash != null && !expectedHash.matches(bytes)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.HASH_MISMATCH,
                    "Asset '" + reference + "' does not match its declared content hash",
                    Map.of("reference", reference,
                            "declared", expectedHash.value(),
                            "actual", ContentHash.sha256(bytes).value()));
        }
        return bytes;
    }

    /**
     * Verifies each Metadata payload's bytes and returns them keyed by snapshot id.
     *
     * <p>The loader stays deliberately opaque here, exactly as T03 designed it: it proves the bytes are
     * what the catalog pins and hands them on, without interpreting attributes, metrics or relations.
     * T06's validation layer parses them as a T04 snapshot and reports a payload that is not one.
     */
    private Map<String, byte[]> verifyMetadataPayloads(MetadataCatalog catalog) {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        for (MetadataSnapshotDescriptor descriptor : catalog.snapshots()) {
            payloads.put(descriptor.snapshotId(),
                    readVerifiedBytes(descriptor.payloadPath(), descriptor.contentHash()));
        }
        return payloads;
    }

    private Map<String, TrustedExecutionContextRef> verifyFixturePayloads(FixtureCatalog catalog) {
        Map<String, TrustedExecutionContextRef> contexts = new LinkedHashMap<>();
        for (FixtureDescriptor fixture : catalog.fixtures()) {
            byte[] bytes = readVerifiedBytes(fixture.payloadPath(), fixture.payloadHash());
            if (fixture.role() == FixtureRole.EXECUTION_CONTEXT) {
                contexts.put(fixture.fixtureId(), parseExecutionContext(fixture, bytes));
            }
        }
        return contexts;
    }

    private static TrustedExecutionContextRef parseExecutionContext(FixtureDescriptor fixture, byte[] bytes) {
        TrustedExecutionContextRef context;
        try {
            context = CANONICAL_JSON.read(new String(bytes, StandardCharsets.UTF_8), TrustedExecutionContextRef.class);
        } catch (ContractException exception) {
            throw new EvaluationException(
                    EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID,
                    "Execution-context fixture '" + fixture.fixtureId() + "' payload is not a trusted context",
                    Map.of("fixtureId", fixture.fixtureId()), exception);
        }
        requireUsableContext(context, fixture.fixtureId());
        return context;
    }

    /**
     * Requires the trusted context payload to be complete.
     *
     * <p>{@code TrustedExecutionContextRef} carries the T01 id grammar as annotations, and T03 never runs a
     * Bean Validation provider, so the fields a fixture must actually supply are checked here instead of
     * being assumed present.
     */
    private static void requireUsableContext(TrustedExecutionContextRef context, String fixtureId) {
        requireReferenceId(fixtureId, "tenantId", context.tenantId());
        requireReferenceId(fixtureId, "programId", context.programId());
        requireReferenceId(fixtureId, "userId", context.userId());
        requireReferenceId(fixtureId, "dataScopeId", context.dataScopeId());
        requireReferenceId(fixtureId, "metadataSnapshotId", context.metadataSnapshotId());
        if (context.locale() == null || context.timezone() == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID,
                    "Execution-context fixture '" + fixtureId + "' must declare a locale and a timezone",
                    Map.of("fixtureId", fixtureId));
        }
        if (context.reportingCurrency() == null
                || !Pattern.compile(ContractPatterns.CURRENCY).matcher(context.reportingCurrency()).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID,
                    "Execution-context fixture '" + fixtureId + "' must declare an ISO-4217 reporting currency",
                    Map.of("fixtureId", fixtureId));
        }
        if (context.asOf() == null || context.dataWatermark() == null
                || context.dataWatermark().isAfter(context.asOf())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID,
                    "Execution-context fixture '" + fixtureId
                            + "' must declare asOf and a dataWatermark that is not after it",
                    Map.of("fixtureId", fixtureId));
        }
    }

    private static void requireReferenceId(String fixtureId, String field, String value) {
        if (value == null || !Pattern.compile(ContractPatterns.REFERENCE_ID).matcher(value).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID,
                    "Execution-context fixture '" + fixtureId + "' has no usable " + field,
                    Map.of("fixtureId", fixtureId, "field", field));
        }
    }

    /**
     * Reads, verifies and parses a JSON asset, requiring its bytes to be the canonical serialization of
     * the value they contain.
     *
     * <p>Requiring canonical bytes is what ties the reviewed file to the reviewed value: without it, two
     * files that differ only in whitespace, key order or number formatting would carry different hashes
     * while loading to the same asset, and a hand-edited corpus could silently change the bytes the hash
     * was taken over.
     */
    private <T> T readAsset(String reference, ContentHash expectedHash, Class<T> type) {
        byte[] bytes = readVerifiedBytes(reference, expectedHash);
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

    private static void requireSupported(SchemaVersion version) {
        EvaluationVersions.requireSupported(version);
    }

    private static void requireMatchingCatalogVersion(
            String label, CatalogReference declared, String actual) {
        if (!declared.catalogVersion().equals(actual)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    label + " catalog version '" + actual + "' does not match the manifest's declared '"
                            + declared.catalogVersion() + "'",
                    Map.of("declared", declared.catalogVersion(), "actual", actual));
        }
    }

    private static void requireUniqueReference(Set<String> seen, String reference) {
        if (!seen.add(reference)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.DUPLICATE_REFERENCE,
                    "Manifest references '" + reference + "' more than once",
                    Map.of("reference", reference));
        }
    }
}