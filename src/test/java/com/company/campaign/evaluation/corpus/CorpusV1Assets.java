package com.company.campaign.evaluation.corpus;

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
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataCatalog;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the complete candidate v1 corpus as canonical bytes.
 *
 * <p>Every hash in the tree is computed by the contract that owns it: the Metadata snapshot hash by T04,
 * the editable DSL hash by T05, and every file hash by canonical JSON. Nothing is written by hand, and the
 * same blueprint always produces the same bytes.
 */
final class CorpusV1Assets {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslCodec CODEC = new SegmentDslCodec();

    private static final String[] CATEGORY_CODES = {
            "attr", "bool", "neg", "bound", "time", "agg", "curr", "ex1", "ex2",
            "evt", "uns", "cand", "ref", "lang", "sec"};

    private CorpusV1Assets() {
    }

    /** Every candidate asset, keyed by repository-relative path. */
    static Map<String, byte[]> build() {
        Map<String, byte[]> assets = new LinkedHashMap<>();

        MetadataSnapshot snapshot = CorpusV1Support.metadataSnapshot();
        byte[] metadataPayload = CANONICAL_JSON.write(snapshot);
        String metadataPayloadPath = CorpusV1Support.CORPUS_ROOT
                + "/metadata/payloads/" + CorpusV1Support.SNAPSHOT_ID + ".json";
        assets.put(metadataPayloadPath, metadataPayload);
        ContentHash metadataPayloadHash = ContentHash.sha256(metadataPayload);

        MetadataSnapshotDescriptor snapshotDescriptor = new MetadataSnapshotDescriptor(
                CorpusV1Support.SNAPSHOT_ID, CorpusV1Support.SNAPSHOT_VERSION, metadataPayloadHash,
                metadataPayloadPath);
        CatalogReference metadataCatalogReference = writeCatalog(
                assets, CorpusV1Support.CORPUS_ROOT + "/metadata/catalog.json",
                new MetadataCatalog(EvaluationVersions.CURRENT, "1", List.of(snapshotDescriptor)));

        List<FixtureDescriptor> fixtureDescriptors = CorpusV1Support.fixtureDescriptors();
        writeFixturePayloads(assets, fixtureDescriptors);
        CatalogReference fixtureCatalogReference = writeCatalog(
                assets, CorpusV1Support.CORPUS_ROOT + "/fixtures/catalog.json",
                new FixtureCatalog(EvaluationVersions.CURRENT, "1", fixtureDescriptors));

        List<CorpusV1Blueprints.CaseSpec> blueprints = CorpusV1Blueprints.all();
        // Blueprints are declared and concatenated in category order, fifteen per category, so the index
        // maps to a category exactly. Fail loudly before indexing if that invariant is ever broken.
        if (blueprints.size() != 15 * SegmentCategory.values().length) {
            throw new IllegalStateException(
                    "Candidate corpus has " + blueprints.size() + " blueprints, expected exactly "
                            + (15 * SegmentCategory.values().length));
        }
        List<CaseFileReference> references = new ArrayList<>(blueprints.size());
        Map<SegmentCategory, Integer> perCategory = new LinkedHashMap<>();
        for (int index = 0; index < blueprints.size(); index++) {
            CorpusV1Blueprints.CaseSpec blueprint = blueprints.get(index);
            SegmentCategory category = categoryAt(index);
            perCategory.merge(category, 1, Integer::sum);
            references.add(writeCase(assets, blueprint, category, index, metadataPayloadHash));
        }
        requireFifteenPerCategory(perCategory);

        CorpusManifest manifest = CorpusManifest.of(
                EvaluationVersions.CURRENT, "v1", metadataCatalogReference, fixtureCatalogReference,
                CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY,
                CorpusManifest.GATE_REQUIRED_HIGH_RISK_REPEATS, references);
        assets.put(CorpusV1Support.CORPUS_ROOT + "/manifest.json", CANONICAL_JSON.write(manifest));
        assets.put(CorpusV1Support.CORPUS_ROOT + "/README.md",
                readme().getBytes(StandardCharsets.UTF_8));
        return assets;
    }

    private static CaseFileReference writeCase(
            Map<String, byte[]> assets,
            CorpusV1Blueprints.CaseSpec blueprint,
            SegmentCategory category,
            int index,
            ContentHash metadataPayloadHash) {

        String caseId = caseId(category, index, blueprint.slug());
        CaseInput caseInput = new CaseInput(
                EvaluationVersions.CURRENT, caseId, category, blueprint.risk(), blueprint.locales(),
                blueprint.utterance(), currentSegment(blueprint, index, metadataPayloadHash),
                blueprint.executionContextFixture(),
                CorpusV1Support.SNAPSHOT_ID, blueprint.fixtures(), blueprint.complex(),
                List.of(blueprint.subcase(), category.name().toLowerCase(java.util.Locale.ROOT)),
                "Candidate case generated from the reviewed corpus blueprint " + blueprint.slug() + ".");
        byte[] caseBytes = CANONICAL_JSON.write(caseInput);
        String casePath = CorpusV1Support.CORPUS_ROOT + "/cases/" + caseId + ".json";
        assets.put(casePath, caseBytes);

        ExpectedResult expected = new ExpectedResult(
                EvaluationVersions.CURRENT, caseId, blueprint.disposition(),
                intentSlots(blueprint), blueprint.selectedCandidates(), blueprint.rejectedCandidates(),
                canonicalAst(blueprint.predicate(), metadataPayloadHash),
                blueprint.validationCodes(), blueprint.questionCodes(), memberSets(blueprint),
                blueprint.claimIds(), blueprint.refinement(), blueprint.forbiddenAssumptions());
        byte[] expectedBytes = CANONICAL_JSON.write(expected);
        String expectedPath = CorpusV1Support.CORPUS_ROOT + "/expected/" + caseId + ".json";
        assets.put(expectedPath, expectedBytes);

        ContentHash caseHash = ContentHash.sha256(caseBytes);
        ContentHash expectedHash = ContentHash.sha256(expectedBytes);
        CaseReviewEvidence review = CaseReviewEvidence.of(
                EvaluationVersions.CURRENT, caseId, caseHash, expectedHash, pendingReviews());
        byte[] reviewBytes = CANONICAL_JSON.write(review);
        String reviewPath = CorpusV1Support.CORPUS_ROOT + "/reviews/" + caseId + ".json";
        assets.put(reviewPath, reviewBytes);

        return new CaseFileReference(caseId, casePath, caseHash, expectedPath, expectedHash,
                reviewPath, ContentHash.sha256(reviewBytes));
    }

    private static List<CaseReviewEntry> pendingReviews() {
        List<CaseReviewEntry> entries = new ArrayList<>(ReviewRole.ordered().size());
        for (ReviewRole role : ReviewRole.ordered()) {
            entries.add(CaseReviewEntry.pending(role,
                    "Pending " + role + " owner review of this exact case and expected revision."));
        }
        return entries;
    }

    private static List<ExpectedIntentSlot> intentSlots(CorpusV1Blueprints.CaseSpec blueprint) {
        List<ExpectedIntentSlot> slots = new ArrayList<>();
        for (CorpusV1Blueprints.Slot slot : blueprint.slots()) {
            if (slot.grounded()) {
                slots.add(CorpusV1Support.slot(
                        slot.id(), slot.concept(), slot.operator(), blueprint.utterance(),
                        slot.slotText(), slot.value()));
            } else {
                slots.add(new ExpectedIntentSlot(slot.id(), slot.concept(), slot.operator(), false,
                        new SourceSpan("", 0, 0), slot.value()));
            }
        }
        return slots;
    }

    private static Map<String, MemberIdSet> memberSets(CorpusV1Blueprints.CaseSpec blueprint) {
        Map<String, MemberIdSet> sets = new LinkedHashMap<>();
        sets.put(blueprint.primaryFixture(), new MemberIdSet(blueprint.primaryMembers()));
        if (blueprint.counterFixture() != null) {
            sets.put(blueprint.counterFixture(), new MemberIdSet(
                    blueprint.counterMembers() == null ? List.of() : blueprint.counterMembers()));
        }
        return sets;
    }

    private static JsonNode canonicalAst(Predicate predicate, ContentHash metadataPayloadHash) {
        if (predicate == null) {
            return null;
        }
        SegmentDsl document = CorpusV1Support.publish(
                CorpusV1Support.binding(metadataPayloadHash), predicate);
        return CANONICAL_JSON.readTree(CODEC.writeString(document));
    }

    private static CurrentSegmentRef currentSegment(
            CorpusV1Blueprints.CaseSpec blueprint, int index, ContentHash metadataPayloadHash) {
        if (blueprint.refinement() == null) {
            return null;
        }
        // A refinement case starts from a base segment; the base is an input, not an expected answer.
        SegmentDsl base = CorpusV1Support.publish(
                CorpusV1Support.binding(metadataPayloadHash),
                CorpusV1Support.all("n-root",
                        CorpusV1Support.attr("n-tier", "member.tier",
                                com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                                CorpusV1Support.enumValue("GOLD")),
                        CorpusV1Support.attr("n-city", "member.city",
                                com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                                CorpusV1Support.str("上海"))));
        return new CurrentSegmentRef("segment-base-" + (index + 1), CANONICAL_JSON.readTree(CODEC.writeString(base)));
    }

    private static CatalogReference writeCatalog(Map<String, byte[]> assets, String path, Object catalog) {
        byte[] bytes = CANONICAL_JSON.write(catalog);
        assets.put(path, bytes);
        String catalogVersion = catalog instanceof MetadataCatalog metadata
                ? metadata.catalogVersion() : ((FixtureCatalog) catalog).catalogVersion();
        return new CatalogReference(catalogVersion, ContentHash.sha256(bytes), path);
    }

    private static void writeFixturePayloads(Map<String, byte[]> assets, List<FixtureDescriptor> descriptors) {
        for (FixtureDescriptor descriptor : descriptors) {
            byte[] payload;
            if (descriptor.fixtureId().equals(CorpusV1Support.CONTEXT_PRIMARY)) {
                payload = CANONICAL_JSON.write(CorpusV1Support.CONTEXT_PAYLOAD_PRIMARY);
            } else if (descriptor.fixtureId().equals(CorpusV1Support.CONTEXT_LIMITED)) {
                payload = CANONICAL_JSON.write(CorpusV1Support.CONTEXT_PAYLOAD_LIMITED);
            } else {
                payload = CorpusV1Support.fixturePayload(
                        descriptor.fixtureId(), descriptor.role(),
                        CorpusV1Support.datasetName(descriptor.fixtureId()));
            }
            if (!descriptor.payloadHash().equals(ContentHash.sha256(payload))) {
                throw new IllegalStateException(
                        "Fixture payload bytes do not match the descriptor hash for " + descriptor.fixtureId());
            }
            assets.put(descriptor.payloadPath(), payload);
        }
    }

    static String caseId(SegmentCategory category, int indexWithinCorpus, String slug) {
        int categoryIndex = category.specNumber() - 1;
        int indexInCategory = indexWithinCorpus - categoryIndex * 15 + 1;
        return "case-" + CATEGORY_CODES[categoryIndex] + "-" + String.format(
                java.util.Locale.ROOT, "%02d", indexInCategory) + "-" + slug;
    }

    private static SegmentCategory categoryAt(int index) {
        return SegmentCategory.ordered().get(index / 15);
    }

    private static void requireFifteenPerCategory(Map<SegmentCategory, Integer> perCategory) {
        for (SegmentCategory category : SegmentCategory.ordered()) {
            int count = perCategory.getOrDefault(category, 0);
            if (count != 15) {
                throw new IllegalStateException(
                        "Category " + category.name() + " has " + count + " blueprints, expected 15");
            }
        }
    }

    private static String readme() {
        return """
                # Candidate Golden Corpus v1

                This directory is the **candidate** v1 corpus: 225 machine-generated case/expected/review
                triples, at least 15 in each of the fifteen Segment specification categories. It is
                `CANDIDATE_COMPLETE` when every machine rule passes, and it is **not** Gate-ready: every
                review ledger is `PENDING` and no human owner has approved anything.

                ## Layout

                | Path | Contents |
                |---|---|
                | `manifest.json` | corpus version, catalog pins and the ordered case/expected/review triples |
                | `cases/` | case inputs — utterances and references only, never expected answers |
                | `expected/` | expected results, stored apart from their cases |
                | `reviews/` | one review ledger per case, bound to the exact case and expected hashes |
                | `metadata/` | a real T04 Metadata snapshot payload and its catalog |
                | `fixtures/` | fixture catalog, member datasets and trusted execution-context fixtures |
                | `README.md` | this file |

                ## Metadata

                `metadata/payloads/metadata-corpus-v1.json` is a T04 `MetadataSnapshot` in canonical form.
                Its catalog descriptor pins the payload file's SHA-256; the payload's own `contentHash` is
                the snapshot material hash recomputed by T04 on parse. Every expected canonical AST pins the
                same snapshot id, version and content hash, so an AST cannot bind a snapshot the corpus does
                not carry.

                ## Fixture payload schema

                Fixture payloads are compact immutable JSON with a documented, bounded schema:

                ```json
                {
                  "asOf": "<instant>", "watermark": "<instant>",
                  "dataset": "<name>", "fixtureId": "<id>", "role": "<role>",
                  "members": [{"memberId": "m-001", "tier": "GOLD", "status": "ACTIVE",
                               "city": "City-1", "joinDate": "2026-01-15"}],
                  "orders":  [{"orderId": "o-001", "memberId": "m-001", "amount": "120.00",
                               "currency": "CNY", "refunded": "0.00", "status": "PAID",
                               "paidAt": "<instant>"}],
                  "events":  [{"eventId": "e-001", "memberId": "m-001", "type": "LOGIN",
                               "occurredAt": "<instant>"}],
                  "refunds": [{"orderId": "o-002", "amount": "20.00"}],
                  "currencies": ["CNY", "USD"],
                  "statuses": ["ACTIVE", "DORMANT", "CHURNED"]
                }
                ```

                All values are synthetic and bounded. No real personal data is present. The member universe
                `m-001`..`m-012` is declared in the fixture catalog, and every expected member set is an
                exact subset of it.

                ## Review

                Review ledgers are generated `PENDING` by `CorpusV1Generator`. They may only be moved to
                `APPROVED` or `REJECTED` by a named product, data or test owner, and the ledger binds the
                exact case and expected hashes so an approval cannot survive an expectation edit.

                ## Regeneration

                Committed assets are source evidence; they are never written at test time. To regenerate
                after a deliberate blueprint change, run (with JDK 21):

                ```text
                .\\mvnw.cmd -q test -Dtest=CorpusV1GeneratorTest -Dcampaign.corpus.regenerate=true
                ```

                Without the property, `CorpusV1GeneratorTest` only verifies that the committed bytes are
                byte-identical to the generated bytes, without rewriting them.
                """;
    }
}