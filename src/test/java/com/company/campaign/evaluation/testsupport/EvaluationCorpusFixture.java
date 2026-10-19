package com.company.campaign.evaluation.testsupport;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureCatalog;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataCatalog;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a complete evaluation corpus into a JUnit temporary directory.
 *
 * <p>Assets are written from the real contract objects and then hashed from the bytes that were actually
 * written, so the happy path is valid by construction and a test can corrupt exactly one thing without
 * having to reason about the rest. Nothing is ever written into the source tree.
 */
public final class EvaluationCorpusFixture {

    public static final String EVALUATION_ROOT = "evaluation/segment";
    public static final String CONTEXT_FIXTURE_ID = "fixture-context-001";
    public static final String METADATA_SNAPSHOT_ID = "metadata-001";
    public static final String PRIMARY_FIXTURE_ID = "fixture-primary-001";
    public static final String COUNTEREXAMPLE_FIXTURE_ID = "fixture-counterexample-001";

    public static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    public static final ZoneId TIMEZONE = ZoneId.of("Asia/Shanghai");
    public static final Instant DATA_WATERMARK = Instant.parse("2026-10-01T01:55:00Z");

    /** A fixed decision instant, so a fixture never reads a clock. */
    public static final Instant REVIEW_DECISION_TIME = Instant.parse("2026-10-01T03:00:00Z");

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final Path repositoryRoot;
    private final List<FixtureHolder> fixtures = new ArrayList<>();
    private final List<CaseSpec> cases = new ArrayList<>();
    private final Map<String, byte[]> metadataPayloads = new LinkedHashMap<>();

    private TrustedExecutionContextRef contextPayload = new TrustedExecutionContextRef(
            "tenant-001", "program-001", "user-001", "scope-008", METADATA_SNAPSHOT_ID,
            new LocaleTag("zh-CN"), TIMEZONE, "CNY", AS_OF, DATA_WATERMARK);
    private byte[] contextPayloadOverride;
    private Instant contextDescriptorAsOf = AS_OF;
    private ZoneId contextDescriptorTimezone = TIMEZONE;
    private Instant contextDescriptorWatermark = DATA_WATERMARK;
    private Instant dataFixtureDescriptorAsOf = AS_OF;
    private ZoneId dataFixtureDescriptorTimezone = TIMEZONE;
    private Instant dataFixtureDescriptorWatermark = DATA_WATERMARK;

    private int minimumCasesPerCategory = 1;
    private int requiredHighRiskRepeats = 1;
    private String declaredCatalogVersion = "1";
    private ReviewStatus reviewStatus = ReviewStatus.APPROVED;
    private boolean omitReviews;

    private EvaluationCorpusFixture(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
        fixtures.add(new FixtureHolder(CONTEXT_FIXTURE_ID, FixtureRole.EXECUTION_CONTEXT, List.of()));
        fixtures.add(new FixtureHolder(PRIMARY_FIXTURE_ID, FixtureRole.PRIMARY, List.of("m-001", "m-002")));
        // A real T04 snapshot, so generated corpora exercise the same Metadata contract a Gate does. The
        // snapshot id/version match the trusted context and every case's metadataSnapshot reference.
        MetadataSnapshot snapshot = MetadataFixtures.snapshot(
                METADATA_SNAPSHOT_ID, "1", "tenant-001", "program-001",
                List.of(MetadataFixtures.tierAttribute()),
                List.of(MetadataFixtures.netPaidAmountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
        metadataPayloads.put(METADATA_SNAPSHOT_ID, CANONICAL_JSON.write(snapshot));
    }

    public static EvaluationCorpusFixture in(Path repositoryRoot) {
        return new EvaluationCorpusFixture(repositoryRoot);
    }

    // --- configuration ---------------------------------------------------------------------------

    public EvaluationCorpusFixture contextPayload(TrustedExecutionContextRef payload) {
        this.contextPayload = payload;
        return this;
    }

    /** Writes arbitrary bytes as the execution-context payload, so an unusable context can be tested. */
    public EvaluationCorpusFixture contextPayloadJson(String rawJson) {
        this.contextPayloadOverride = rawJson.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public EvaluationCorpusFixture contextDescriptorTimeContext(Instant asOf, ZoneId timezone, Instant watermark) {
        this.contextDescriptorAsOf = asOf;
        this.contextDescriptorTimezone = timezone;
        this.contextDescriptorWatermark = watermark;
        return this;
    }

    public EvaluationCorpusFixture dataFixtureDescriptorTimeContext(
            Instant asOf, ZoneId timezone, Instant watermark) {
        this.dataFixtureDescriptorAsOf = asOf;
        this.dataFixtureDescriptorTimezone = timezone;
        this.dataFixtureDescriptorWatermark = watermark;
        return this;
    }

    public EvaluationCorpusFixture minimumCasesPerCategory(int value) {
        this.minimumCasesPerCategory = value;
        return this;
    }

    public EvaluationCorpusFixture requiredHighRiskRepeats(int value) {
        this.requiredHighRiskRepeats = value;
        return this;
    }

    /** Version the manifest declares for the catalogs, which the catalog files still stamp as "1". */
    public EvaluationCorpusFixture declaredCatalogVersion(String value) {
        this.declaredCatalogVersion = value;
        return this;
    }

    /** The status written into every generated review entry. Defaults to approved for Gate-ready corpora. */
    public EvaluationCorpusFixture reviewStatus(ReviewStatus value) {
        this.reviewStatus = value;
        return this;
    }

    /** Writes every case without a review reference, so missing-review handling can be exercised. */
    public EvaluationCorpusFixture omitReviews() {
        this.omitReviews = true;
        return this;
    }

    public EvaluationCorpusFixture fixture(String fixtureId, FixtureRole role, List<String> universe) {
        fixtures.add(new FixtureHolder(fixtureId, role, universe));
        return this;
    }

    public EvaluationCorpusFixture addCase(CaseSpec spec) {
        cases.add(spec);
        return this;
    }

    // --- writing ---------------------------------------------------------------------------------

    /** Writes the whole corpus and returns the path of the manifest file. */
    public Path write() {
        Path evaluationRoot = repositoryRoot.resolve(EVALUATION_ROOT);

        CatalogReference metadataCatalog = writeMetadataCatalog(evaluationRoot);
        CatalogReference fixtureCatalog = writeFixtureCatalog(evaluationRoot);

        List<CaseFileReference> references = new ArrayList<>();
        for (CaseSpec spec : cases) {
            references.add(writeCase(evaluationRoot, spec));
        }

        CorpusManifest manifest = CorpusManifest.of(
                EvaluationVersions.CURRENT, "1", metadataCatalog, fixtureCatalog,
                minimumCasesPerCategory, requiredHighRiskRepeats, references);
        return writeFile(evaluationRoot.resolve("manifest.json"), CANONICAL_JSON.write(manifest));
    }

    private CatalogReference writeMetadataCatalog(Path evaluationRoot) {
        List<MetadataSnapshotDescriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : metadataPayloads.entrySet()) {
            String payloadPath = EVALUATION_ROOT + "/metadata/payloads/" + entry.getKey() + ".json";
            writeFile(repositoryRoot.resolve(payloadPath), entry.getValue());
            descriptors.add(new MetadataSnapshotDescriptor(
                    entry.getKey(), "1", ContentHash.sha256(entry.getValue()), payloadPath));
        }
        MetadataCatalog catalog = new MetadataCatalog(EvaluationVersions.CURRENT, "1", descriptors);
        return writeCatalog(evaluationRoot.resolve("metadata/catalog.json"), catalog, "metadata/catalog.json");
    }

    private CatalogReference writeFixtureCatalog(Path evaluationRoot) {
        List<FixtureDescriptor> descriptors = new ArrayList<>();
        for (FixtureHolder holder : fixtures) {
            byte[] payload;
            if (holder.role() == FixtureRole.EXECUTION_CONTEXT) {
                payload = contextPayloadOverride != null
                        ? contextPayloadOverride : CANONICAL_JSON.write(contextPayload);
            } else {
                payload = ("{\"fixtureId\":\"" + holder.fixtureId() + "\",\"placeholder\":true}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            String payloadPath = EVALUATION_ROOT + "/fixtures/payloads/" + holder.fixtureId() + ".json";
            writeFile(repositoryRoot.resolve(payloadPath), payload);

            boolean isContext = holder.role() == FixtureRole.EXECUTION_CONTEXT;
            descriptors.add(new FixtureDescriptor(
                    holder.fixtureId(), "1", holder.role(), payloadPath, ContentHash.sha256(payload),
                    isContext ? contextDescriptorAsOf : dataFixtureDescriptorAsOf,
                    isContext ? contextDescriptorTimezone : dataFixtureDescriptorTimezone,
                    isContext ? contextDescriptorWatermark : dataFixtureDescriptorWatermark,
                    new MemberIdSet(holder.universe())));
        }
        FixtureCatalog catalog = new FixtureCatalog(EvaluationVersions.CURRENT, "1", descriptors);
        return writeCatalog(evaluationRoot.resolve("fixtures/catalog.json"), catalog, "fixtures/catalog.json");
    }

    private CatalogReference writeCatalog(Path file, Object catalog, String relativeToEvaluationRoot) {
        byte[] bytes = CANONICAL_JSON.write(catalog);
        writeFile(file, bytes);
        return new CatalogReference(
                declaredCatalogVersion, ContentHash.sha256(bytes), reference(relativeToEvaluationRoot));
    }

    private CaseFileReference writeCase(Path evaluationRoot, CaseSpec spec) {
        CaseInput caseInput = spec.toCaseInput();
        byte[] caseBytes = CANONICAL_JSON.write(caseInput);
        // The file name is independent of the declared case id, so a corpus can deliberately point one
        // reference at another case's file without also colliding on ids.
        String slug = spec.fileSlug == null ? spec.caseId : spec.fileSlug;
        String caseFilePath = EVALUATION_ROOT + "/cases/" + slug + ".json";
        String casePath = spec.declaredCasePath == null ? caseFilePath : spec.declaredCasePath;
        writeFile(repositoryRoot.resolve(caseFilePath), caseBytes);

        if (spec.omitExpectedCounterpart) {
            return new CaseFileReference(spec.caseId, casePath, ContentHash.sha256(caseBytes), null, null);
        }

        ExpectedResult expected = spec.toExpectedResult();
        // A case id mismatch is written into the file itself, because the expected-result contract refuses
        // to pair a case id with a different case's expectation. The reference hash then covers the
        // rewritten bytes, so the mismatch is the only defect the loader can find.
        byte[] expectedBytes = spec.declaredExpectedCaseId == null
                ? CANONICAL_JSON.write(expected)
                : withReplacedCaseId(CANONICAL_JSON.write(expected), spec.caseId, spec.declaredExpectedCaseId);
        String expectedPath = EVALUATION_ROOT + "/expected/" + slug + ".json";
        writeFile(repositoryRoot.resolve(expectedPath), expectedBytes);
        ContentHash caseHash = ContentHash.sha256(caseBytes);
        ContentHash expectedHash = ContentHash.sha256(expectedBytes);

        if (omitReviews) {
            return new CaseFileReference(spec.caseId, casePath, caseHash, expectedPath, expectedHash);
        }

        String reviewPath = EVALUATION_ROOT + "/reviews/" + slug + ".json";
        byte[] reviewBytes = CANONICAL_JSON.write(review(spec.caseId, caseHash, expectedHash));
        writeFile(repositoryRoot.resolve(reviewPath), reviewBytes);

        return new CaseFileReference(
                spec.declaredCaseId == null ? spec.caseId : spec.declaredCaseId,
                casePath, caseHash,
                expectedPath, expectedHash,
                reviewPath, ContentHash.sha256(reviewBytes));
    }

    private CaseReviewEvidence review(String caseId, ContentHash caseHash, ContentHash expectedHash) {
        List<CaseReviewEntry> entries = new ArrayList<>();
        for (ReviewRole role : ReviewRole.ordered()) {
            entries.add(reviewEntry(role));
        }
        return CaseReviewEvidence.of(EvaluationVersions.CURRENT, caseId, caseHash, expectedHash, entries);
    }

    private CaseReviewEntry reviewEntry(ReviewRole role) {
        if (reviewStatus == ReviewStatus.PENDING) {
            return CaseReviewEntry.pending(role, "Pending " + role + " owner review; generated, not approved.");
        }
        return CaseReviewEntry.decided(role, reviewStatus,
                "reviewer-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-01",
                "review-evidence-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-001",
                REVIEW_DECISION_TIME,
                "Recorded " + role + " owner decision for this exact revision.");
    }

    private static byte[] withReplacedCaseId(byte[] canonicalBytes, String from, String to) {
        String text = new String(canonicalBytes, StandardCharsets.UTF_8);
        return text.replace("\"caseId\":\"" + from + "\"", "\"caseId\":\"" + to + "\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Path writeFile(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // --- case specification ----------------------------------------------------------------------

    public static CaseSpec caseSpec(String caseId, SegmentCategory category) {
        return new CaseSpec(caseId, category);
    }

    /** Mutable description of one case and its expectation, with reviewable defaults. */
    public static final class CaseSpec {

        private final String caseId;
        private final SegmentCategory category;
        private RiskLevel riskLevel = RiskLevel.MEDIUM;
        private List<String> locales = List.of("zh-CN");
        private String utterance = "Gold会员";
        private boolean complexSemantics;
        private List<String> fixtureIds = new ArrayList<>(List.of(PRIMARY_FIXTURE_ID));
        private String executionContextFixture = CONTEXT_FIXTURE_ID;
        private String metadataSnapshot = METADATA_SNAPSHOT_ID;
        private String declaredCaseId;
        private String declaredExpectedCaseId;
        private String declaredCasePath;
        private String fileSlug;
        private boolean omitExpectedCounterpart;

        private Disposition disposition = Disposition.RESOLVED;
        private List<ExpectedIntentSlot> slots = new ArrayList<>(List.of(
                new ExpectedIntentSlot("c1", "MEMBER_TIER", "EQUAL", true,
                        new SourceSpan("Gold", 0, 4), TextNode.valueOf("GOLD"))));
        private List<String> selectedCandidates = new ArrayList<>(List.of("member.tier"));
        private List<String> rejectedCandidates = new ArrayList<>();
        private JsonNode canonicalAst;
        private List<String> validationIssueCodes = new ArrayList<>();
        private List<String> clarificationCodes = new ArrayList<>();
        private Map<String, MemberIdSet> memberSets = new LinkedHashMap<>();
        private boolean defaultMemberSets = true;
        private List<String> claimIds = new ArrayList<>();
        private List<String> forbiddenAssumptions = new ArrayList<>();
        private RefinementTargetPlaceholder refinementTarget;
        private List<String> tags = new ArrayList<>(List.of("generated"));

        private CaseSpec(String caseId, SegmentCategory category) {
            this.caseId = caseId;
            this.category = category;
        }

        public CaseSpec risk(RiskLevel value) {
            this.riskLevel = value;
            return this;
        }

        public CaseSpec utterance(String value) {
            this.utterance = value;
            return this;
        }

        public CaseSpec locales(List<String> values) {
            this.locales = List.copyOf(values);
            return this;
        }

        public CaseSpec complexSemantics(boolean value) {
            this.complexSemantics = value;
            return this;
        }

        public CaseSpec fixture(String fixtureId) {
            fixtureIds = new ArrayList<>(fixtureIds);
            fixtureIds.add(fixtureId);
            return this;
        }

        public CaseSpec fixtures(List<String> values) {
            this.fixtureIds = new ArrayList<>(values);
            return this;
        }

        public CaseSpec executionContextFixture(String value) {
            this.executionContextFixture = value;
            return this;
        }

        public CaseSpec metadataSnapshot(String value) {
            this.metadataSnapshot = value;
            return this;
        }

        public CaseSpec tags(List<String> values) {
            this.tags = new ArrayList<>(values);
            return this;
        }

        public CaseSpec disposition(Disposition value) {
            this.disposition = value;
            return this;
        }

        public CaseSpec intentSlots(List<ExpectedIntentSlot> values) {
            this.slots = new ArrayList<>(values);
            return this;
        }

        public CaseSpec clarificationCodes(List<String> values) {
            this.clarificationCodes = new ArrayList<>(values);
            return this;
        }

        public CaseSpec canonicalAst(JsonNode value) {
            this.canonicalAst = value;
            return this;
        }

        public CaseSpec selectedCandidates(List<String> values) {
            this.selectedCandidates = new ArrayList<>(values);
            return this;
        }

        public CaseSpec rejectedCandidates(List<String> values) {
            this.rejectedCandidates = new ArrayList<>(values);
            return this;
        }

        public CaseSpec forbiddenAssumptions(List<String> values) {
            this.forbiddenAssumptions = new ArrayList<>(values);
            return this;
        }

        public CaseSpec refinementTarget(RefinementTargetPlaceholder value) {
            this.refinementTarget = value;
            return this;
        }

        public CaseSpec memberSet(String fixtureId, List<String> members) {
            this.defaultMemberSets = false;
            this.memberSets.put(fixtureId, new MemberIdSet(members));
            return this;
        }

        public CaseSpec validating(String... codes) {
            this.validationIssueCodes = new ArrayList<>(List.of(codes));
            return this;
        }

        public CaseSpec omitExpectedCounterpart() {
            this.omitExpectedCounterpart = true;
            return this;
        }

        public CaseSpec declaredCaseId(String value) {
            this.declaredCaseId = value;
            return this;
        }

        /** Points the manifest reference at an explicit path, so two references can collide on purpose. */
        public CaseSpec declaredCasePath(String value) {
            this.declaredCasePath = value;
            return this;
        }

        /** Name of the case and expected files, so two cases can share an id without sharing a file. */
        public CaseSpec fileSlug(String value) {
            this.fileSlug = value;
            return this;
        }

        public CaseSpec declaredExpectedCaseId(String value) {
            this.declaredExpectedCaseId = value;
            return this;
        }

        private CaseInput toCaseInput() {
            return new CaseInput(
                    EvaluationVersions.CURRENT, caseId, category, riskLevel,
                    locales.stream().map(LocaleTag::new).toList(),
                    utterance, null, executionContextFixture, metadataSnapshot,
                    fixtureIds, complexSemantics, tags, null);
        }

        private ExpectedResult toExpectedResult() {
            Map<String, MemberIdSet> resolvedSets = new LinkedHashMap<>(memberSets);
            if (defaultMemberSets) {
                for (String fixtureId : fixtureIds) {
                    resolvedSets.put(fixtureId, new MemberIdSet(List.of("m-001")));
                }
            }
            return new ExpectedResult(
                    EvaluationVersions.CURRENT, caseId, disposition, slots,
                    selectedCandidates, rejectedCandidates, canonicalAst,
                    validationIssueCodes, clarificationCodes, resolvedSets,
                    claimIds, refinementTarget, forbiddenAssumptions);
        }

        public String caseId() {
            return caseId;
        }

        public SegmentCategory category() {
            return category;
        }
    }

    private record FixtureHolder(String fixtureId, FixtureRole role, List<String> universe) {
    }

    /** The manifest path for a corpus written into the given repository root. */
    public static Path manifestPath(Path repositoryRoot) {
        return repositoryRoot.resolve(EVALUATION_ROOT).resolve("manifest.json");
    }

    /** Reference string of a file relative to the evaluation root, for example {@code cases/x.json}. */
    public static String reference(String relativeToEvaluationRoot) {
        return EVALUATION_ROOT + "/" + relativeToEvaluationRoot;
    }

    /** Schema version helper so tests do not repeat the constant. */
    public static SchemaVersion currentVersion() {
        return EvaluationVersions.CURRENT;
    }
}