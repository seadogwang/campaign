package com.company.campaign.evaluation.segment.runner.testsupport;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.CurrentSegmentRef;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureCatalog;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataCatalog;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;

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
import java.util.Locale;
import java.util.Map;

/**
 * Writes a real, small synthetic T13 evaluation corpus beneath a temporary repository root.
 *
 * <p>It is deliberately not the shared T03 example fixture: that one writes placeholder fixture payloads, and
 * the Evaluation Runner loads every pinned fixture through the genuine T09 dataset loader, so a placeholder
 * would refuse the run at freeze time. Here every payload is a real payload the genuine loader accepts, the
 * Metadata is a real T04 snapshot, and the case/expected/review triples are the accepted contract objects
 * written as canonical bytes with their real hashes.
 */
public final class T13TestCorpus {

    /** The evaluation root every reference is relative to. */
    public static final String EVALUATION_ROOT = "evaluation/segment/t13";

    /** The repository-relative manifest reference a run is started with. */
    public static final String MANIFEST_REFERENCE = EVALUATION_ROOT + "/manifest.json";

    public static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    public static final Instant DATA_WATERMARK = Instant.parse("2026-10-01T01:55:00Z");
    public static final ZoneId TIMEZONE = ZoneId.of("Asia/Shanghai");

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    private final Path repositoryRoot;
    private final Map<String, byte[]> metadataPayloads = new LinkedHashMap<>();
    private final Map<String, FixtureHolder> fixtures = new LinkedHashMap<>();
    private final List<CaseHolder> cases = new ArrayList<>();
    private int minimumCasesPerCategory = 1;
    private int requiredHighRiskRepeats = 5;
    private com.company.campaign.evaluation.segment.contract.ReviewStatus reviewStatus =
            com.company.campaign.evaluation.segment.contract.ReviewStatus.PENDING;

    private T13TestCorpus(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public static T13TestCorpus in(Path repositoryRoot) {
        return new T13TestCorpus(repositoryRoot);
    }

    public T13TestCorpus minimumCasesPerCategory(int value) {
        this.minimumCasesPerCategory = value;
        return this;
    }

    public T13TestCorpus requiredHighRiskRepeats(int value) {
        this.requiredHighRiskRepeats = value;
        return this;
    }

    /** Writes every review ledger with the given status; {@code PENDING} is the default. */
    public T13TestCorpus reviewStatus(com.company.campaign.evaluation.segment.contract.ReviewStatus value) {
        this.reviewStatus = value;
        return this;
    }

    /** Registers a real T04 Metadata snapshot as a pinned payload. */
    public T13TestCorpus metadata(MetadataSnapshot snapshot) {
        metadataPayloads.put(snapshot.snapshotId(), CANONICAL_JSON.write(snapshot));
        return this;
    }

    /** Registers a Metadata payload that is deliberately not a valid T04 snapshot, under a chosen id. */
    public T13TestCorpus rawMetadata(String snapshotId, String payloadJson) {
        metadataPayloads.put(snapshotId, payloadJson.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Registers a real member-set fixture payload. */
    public T13TestCorpus fixture(String fixtureId, FixtureRole role, MemberIdSet universe, String payloadJson) {
        fixtures.put(fixtureId, new FixtureHolder(
                fixtureId, role, universe, payloadJson.getBytes(StandardCharsets.UTF_8), AS_OF, TIMEZONE,
                DATA_WATERMARK));
        return this;
    }

    /** Registers a member-set fixture payload with an explicit time context. */
    public T13TestCorpus fixture(
            String fixtureId, FixtureRole role, MemberIdSet universe, String payloadJson,
            Instant asOf, ZoneId timezone, Instant dataWatermark) {
        fixtures.put(fixtureId, new FixtureHolder(
                fixtureId, role, universe, payloadJson.getBytes(StandardCharsets.UTF_8), asOf, timezone,
                dataWatermark));
        return this;
    }

    /** Registers a trusted execution-context fixture from an accepted reference. */
    public T13TestCorpus context(String fixtureId, TrustedExecutionContextRef reference) {
        fixtures.put(fixtureId, new FixtureHolder(
                fixtureId, FixtureRole.EXECUTION_CONTEXT, new MemberIdSet(List.of()),
                CANONICAL_JSON.write(reference), reference.asOf(), reference.timezone(),
                reference.dataWatermark()));
        return this;
    }

    public T13TestCorpus caseSpec(CaseHolder holder) {
        cases.add(holder);
        return this;
    }

    /** Writes every asset and returns the created manifest reference. */
    public String write() {
        Path root = repositoryRoot.resolve(EVALUATION_ROOT);
        CatalogReference metadataCatalog = writeMetadataCatalog(root);
        CatalogReference fixtureCatalog = writeFixtureCatalog(root);

        List<CaseFileReference> references = new ArrayList<>();
        for (CaseHolder holder : cases) {
            references.add(writeCase(root, holder));
        }
        CorpusManifest manifest = CorpusManifest.of(
                EvaluationVersions.CURRENT, "t13", metadataCatalog, fixtureCatalog, minimumCasesPerCategory,
                requiredHighRiskRepeats, references);
        writeFile(root.resolve("manifest.json"), CANONICAL_JSON.write(manifest));
        return MANIFEST_REFERENCE;
    }

    private CatalogReference writeMetadataCatalog(Path root) {
        List<MetadataSnapshotDescriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : metadataPayloads.entrySet()) {
            String path = EVALUATION_ROOT + "/metadata/payloads/" + entry.getKey() + ".json";
            writeFile(repositoryRoot.resolve(path), entry.getValue());
            descriptors.add(new MetadataSnapshotDescriptor(
                    entry.getKey(), "1", ContentHash.sha256(entry.getValue()), path));
        }
        byte[] bytes = CANONICAL_JSON.write(
                new MetadataCatalog(EvaluationVersions.CURRENT, "1", descriptors));
        writeFile(root.resolve("metadata/catalog.json"), bytes);
        return new CatalogReference("1", ContentHash.sha256(bytes), EVALUATION_ROOT + "/metadata/catalog.json");
    }

    private CatalogReference writeFixtureCatalog(Path root) {
        List<FixtureDescriptor> descriptors = new ArrayList<>();
        for (FixtureHolder holder : fixtures.values()) {
            String path = EVALUATION_ROOT + "/fixtures/payloads/" + holder.fixtureId() + ".json";
            writeFile(repositoryRoot.resolve(path), holder.payload());
            descriptors.add(new FixtureDescriptor(
                    holder.fixtureId(), "1", holder.role(), path, ContentHash.sha256(holder.payload()),
                    holder.asOf(), holder.timezone(), holder.dataWatermark(), holder.universe()));
        }
        byte[] bytes = CANONICAL_JSON.write(new FixtureCatalog(EvaluationVersions.CURRENT, "1", descriptors));
        writeFile(root.resolve("fixtures/catalog.json"), bytes);
        return new CatalogReference("1", ContentHash.sha256(bytes), EVALUATION_ROOT + "/fixtures/catalog.json");
    }

    private CaseFileReference writeCase(Path root, CaseHolder holder) {
        byte[] caseBytes = CANONICAL_JSON.write(holder.caseInput());
        String casePath = EVALUATION_ROOT + "/cases/" + holder.caseId() + ".json";
        writeFile(repositoryRoot.resolve(casePath), caseBytes);

        byte[] expectedBytes = CANONICAL_JSON.write(holder.expectedResult(holder.caseId()));
        String expectedPath = EVALUATION_ROOT + "/expected/" + holder.caseId() + ".json";
        writeFile(repositoryRoot.resolve(expectedPath), expectedBytes);

        ContentHash caseHash = ContentHash.sha256(caseBytes);
        ContentHash expectedHash = ContentHash.sha256(expectedBytes);
        List<CaseReviewEntry> entries = new ArrayList<>();
        for (ReviewRole role : ReviewRole.ordered()) {
            entries.add(reviewStatus
                    == com.company.campaign.evaluation.segment.contract.ReviewStatus.PENDING
                            ? CaseReviewEntry.pending(role, "Pending " + role + " owner review; generated.")
                            : CaseReviewEntry.decided(
                                    role, reviewStatus,
                                    "reviewer-" + role.name().toLowerCase(Locale.ROOT) + "-01",
                                    "review-evidence-" + role.name().toLowerCase(Locale.ROOT) + "-001",
                                    Instant.parse("2026-10-01T03:00:00Z"),
                                    "Recorded " + role + " owner decision for this exact revision."));
        }
        byte[] reviewBytes = CANONICAL_JSON.write(
                CaseReviewEvidence.of(EvaluationVersions.CURRENT, holder.caseId(), caseHash, expectedHash, entries));
        String reviewPath = EVALUATION_ROOT + "/reviews/" + holder.caseId() + ".json";
        writeFile(repositoryRoot.resolve(reviewPath), reviewBytes);
        return new CaseFileReference(
                holder.caseId(), casePath, caseHash, expectedPath, expectedHash, reviewPath,
                ContentHash.sha256(reviewBytes));
    }

    private static void writeFile(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record FixtureHolder(
            String fixtureId,
            FixtureRole role,
            MemberIdSet universe,
            byte[] payload,
            Instant asOf,
            ZoneId timezone,
            Instant dataWatermark) {
    }

    /** One synthetic Case with its exact expected result, written as two separate assets. */
    public static final class CaseHolder {

        private final String caseId;
        private final SegmentCategory category;
        private final RiskLevel riskLevel;
        private final String utterance;
        private final String metadataSnapshotId;
        private final String contextFixtureId;
        private final List<String> fixtures = new ArrayList<>();
        private final List<ExpectedIntentSlot> slots = new ArrayList<>();
        private final List<String> selected = new ArrayList<>();
        private final List<String> rejected = new ArrayList<>();
        private final List<String> validationCodes = new ArrayList<>();
        private final List<String> questionCodes = new ArrayList<>();
        private final List<String> claims = new ArrayList<>();
        private final List<String> forbidden = new ArrayList<>();
        private final Map<String, MemberIdSet> memberIds = new LinkedHashMap<>();
        private Disposition disposition = Disposition.RESOLVED;
        private JsonNode canonicalAst;
        private RefinementTargetPlaceholder refinementTarget;
        private JsonNode currentSegmentContent;
        private boolean complexSemantics;
        private LocaleTag locale = LocaleTag.of(Locale.SIMPLIFIED_CHINESE);

        public CaseHolder(String caseId, SegmentCategory category, RiskLevel riskLevel, String utterance,
                String metadataSnapshotId, String contextFixtureId) {
            this.caseId = caseId;
            this.category = category;
            this.riskLevel = riskLevel;
            this.utterance = utterance;
            this.metadataSnapshotId = metadataSnapshotId;
            this.contextFixtureId = contextFixtureId;
        }

        public CaseHolder fixture(String fixtureId) {
            this.fixtures.add(fixtureId);
            return this;
        }

        public CaseHolder disposition(Disposition value) {
            this.disposition = value;
            return this;
        }

        public CaseHolder ast(SegmentDsl document) {
            this.canonicalAst = CANONICAL_JSON.readTree(DSL_CODEC.writeString(document));
            return this;
        }

        public CaseHolder rawAst(JsonNode node) {
            this.canonicalAst = node;
            return this;
        }

        public CaseHolder slot(String slotId, String concept, String operator, String groundedText,
                JsonNode value) {
            int start = utterance.indexOf(groundedText);
            SourceSpan span = groundedText.isEmpty()
                    ? new SourceSpan("", 0, 0)
                    : new SourceSpan(groundedText, start, start + groundedText.length());
            slots.add(new ExpectedIntentSlot(slotId, concept, operator, true, span, value));
            return this;
        }

        /** One expected slot with an explicitly authored source span, for offset-evidence tests. */
        public CaseHolder slotWithSpan(
                String slotId, String concept, String operator, boolean grounded, SourceSpan span,
                JsonNode value) {
            slots.add(new ExpectedIntentSlot(slotId, concept, operator, grounded, span, value));
            return this;
        }

        public CaseHolder selected(String... ids) {
            selected.addAll(List.of(ids));
            return this;
        }

        public CaseHolder rejected(String... ids) {
            rejected.addAll(List.of(ids));
            return this;
        }

        public CaseHolder validationCodes(String... codes) {
            validationCodes.addAll(List.of(codes));
            return this;
        }

        public CaseHolder questionCodes(String... codes) {
            questionCodes.addAll(List.of(codes));
            return this;
        }

        public CaseHolder claims(String... ids) {
            claims.addAll(List.of(ids));
            return this;
        }

        public CaseHolder forbidden(String... ids) {
            forbidden.addAll(List.of(ids));
            return this;
        }

        public CaseHolder memberIds(String fixtureId, String... members) {
            memberIds.put(fixtureId, MemberIdSet.of(members));
            return this;
        }

        public CaseHolder refinement(RefinementOperation operation, String targetRef, String scopeRef) {
            this.refinementTarget = new RefinementTargetPlaceholder(operation, targetRef, scopeRef);
            return this;
        }

        public CaseHolder currentSegment(SegmentDsl document) {
            this.currentSegmentContent = CANONICAL_JSON.readTree(DSL_CODEC.writeString(document));
            return this;
        }

        public CaseHolder complexSemantics() {
            this.complexSemantics = true;
            return this;
        }

        public String caseId() {
            return caseId;
        }

        public CaseInput caseInput() {
            return new CaseInput(
                    EvaluationVersions.CURRENT, caseId, category, riskLevel, List.of(locale), utterance,
                    currentSegmentContent == null ? null : new CurrentSegmentRef("segment-" + caseId, currentSegmentContent),
                    contextFixtureId, metadataSnapshotId, fixtures, complexSemantics, List.of("t13"), "Synthetic T13 case.");
        }

        public ExpectedResult expectedResult(String id) {
            return new ExpectedResult(
                    EvaluationVersions.CURRENT, id, disposition, slots, selected, rejected, canonicalAst,
                    validationCodes, questionCodes, memberIds, claims, refinementTarget, forbidden);
        }
    }
}