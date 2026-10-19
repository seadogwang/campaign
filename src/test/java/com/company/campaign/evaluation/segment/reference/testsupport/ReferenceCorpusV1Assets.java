package com.company.campaign.evaluation.segment.reference.testsupport;

import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusCase;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusExpected;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusManifest;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusVersions;
import com.company.campaign.evaluation.segment.reference.ReferenceCategory;
import com.company.campaign.evaluation.segment.reference.ReferenceFixtureCatalog;
import com.company.campaign.evaluation.segment.reference.ReferenceFixtureDescriptor;
import com.company.campaign.evaluation.segment.reference.ReferenceHumanReviewStatus;
import com.company.campaign.evaluation.segment.reference.ReferenceModelEvidenceStatus;
import com.company.campaign.evaluation.segment.reference.ReferenceReleaseStatus;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the complete committed reference corpus as canonical bytes.
 *
 * <p>Every hash in the tree is computed by the contract that owns it: the fixture payload hash by canonical
 * JSON over the pinned payload, the case, expectation and review-ledger file hashes by canonical JSON over
 * their own values, and the manifest hash by the manifest's own content rule. Nothing is written by hand, and
 * the same reviewed blueprint always produces the same bytes.
 *
 * <p>Nothing here claims anything a file cannot prove. The manifest is generated {@code DEVELOPMENT_ONLY}
 * with {@code PENDING} human review and no recorded model run, and every review ledger is generated with one
 * {@code PENDING} entry per owner role: this generator cannot manufacture an approval, and
 * {@code ReferenceCorpusV1Generator} refuses to overwrite a ledger that carries a real decision.
 */
final class ReferenceCorpusV1Assets {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final String ROOT = ReferenceCorpusVersions.CORPUS_ROOT;

    private ReferenceCorpusV1Assets() {
    }

    /** Every committed asset, keyed by repository-relative path. */
    static Map<String, byte[]> build() {
        Map<String, byte[]> assets = new LinkedHashMap<>();

        CatalogReference fixtureCatalogReference = writeFixtureCatalog(assets);

        List<CaseFileReference> references = new ArrayList<>();
        for (ReferenceCorpusBlueprints.CaseBlueprint blueprint : ReferenceCorpusBlueprints.cases()) {
            references.add(writeCase(assets, blueprint));
        }

        ReferenceCorpusManifest manifest = ReferenceCorpusManifest.of(
                EvaluationVersions.CURRENT,
                ReferenceCorpusVersions.CORPUS_VERSION,
                fixtureCatalogReference,
                ReferenceCorpusVersions.GATE_MINIMUM_CASES,
                ReferenceCorpusVersions.GATE_MINIMUM_CASES_PER_CATEGORY,
                ReferenceHumanReviewStatus.PENDING,
                ReferenceModelEvidenceStatus.NOT_RUN,
                null,
                ReferenceReleaseStatus.DEVELOPMENT_ONLY,
                ReferenceCategory.ordered(),
                references);
        assets.put(ROOT + "/manifest.json", CANONICAL_JSON.write(manifest));
        assets.put(ROOT + "/README.md", readme().getBytes(StandardCharsets.UTF_8));
        return assets;
    }

    /** The repository-relative path of one trusted fixture payload. */
    static String fixturePayloadPath(String fixtureId) {
        return ROOT + "/fixtures/payloads/" + fixtureId + ".json";
    }

    private static CatalogReference writeFixtureCatalog(Map<String, byte[]> assets) {
        List<ReferenceFixtureDescriptor> descriptors = new ArrayList<>();
        for (ReferenceCorpusBlueprints.FixtureSpec spec : ReferenceCorpusBlueprints.fixtures()) {
            String payloadPath = fixturePayloadPath(spec.fixtureId());
            byte[] payload = CANONICAL_JSON.write(spec.payload());
            assets.put(payloadPath, payload);
            descriptors.add(new ReferenceFixtureDescriptor(
                    spec.fixtureId(), spec.role(), spec.fixtureVersion(),
                    ContentHash.sha256(payload), payloadPath));
        }
        ReferenceFixtureCatalog catalog = new ReferenceFixtureCatalog(
                EvaluationVersions.CURRENT, ReferenceCorpusVersions.FIXTURE_CATALOG_VERSION, descriptors);
        byte[] catalogBytes = CANONICAL_JSON.write(catalog);
        String catalogPath = ROOT + "/fixtures/catalog.json";
        assets.put(catalogPath, catalogBytes);
        return new CatalogReference(
                ReferenceCorpusVersions.FIXTURE_CATALOG_VERSION, ContentHash.sha256(catalogBytes), catalogPath);
    }

    private static CaseFileReference writeCase(
            Map<String, byte[]> assets, ReferenceCorpusBlueprints.CaseBlueprint blueprint) {

        ReferenceCorpusCase caseInput = blueprint.caseInput();
        String caseId = caseInput.caseId();

        byte[] caseBytes = CANONICAL_JSON.write(caseInput);
        String casePath = ReferenceCorpusBlueprints.casePath(caseId);
        assets.put(casePath, caseBytes);

        ReferenceCorpusExpected expected = blueprint.expected();
        byte[] expectedBytes = CANONICAL_JSON.write(expected);
        String expectedPath = ReferenceCorpusBlueprints.expectedPath(caseId);
        assets.put(expectedPath, expectedBytes);

        ContentHash caseHash = ContentHash.sha256(caseBytes);
        ContentHash expectedHash = ContentHash.sha256(expectedBytes);
        CaseReviewEvidence review = CaseReviewEvidence.of(
                EvaluationVersions.CURRENT, caseId, caseHash, expectedHash,
                ReferenceCorpusBlueprints.pendingReviews());
        byte[] reviewBytes = CANONICAL_JSON.write(review);
        String reviewPath = ReferenceCorpusBlueprints.reviewPath(caseId);
        assets.put(reviewPath, reviewBytes);

        return new CaseFileReference(
                caseId, casePath, caseHash, expectedPath, expectedHash, reviewPath,
                ContentHash.sha256(reviewBytes));
    }

    private static String readme() {
        return """
                # Existing Audience Reference Corpus v1

                This directory is the committed T18 Existing Audience Reference corpus: trusted fixture
                payloads and their catalog, one case/expected/review triple per reference case, and the
                manifest that pins them all. It is generated deterministically by
                `ReferenceCorpusV1Generator` from the reviewed
                `ReferenceCorpusBlueprints`, and it is **not** approved: every review ledger is `PENDING`,
                no configured-model run is recorded, and the manifest is `DEVELOPMENT_ONLY`.

                ## Layout

                | Path | Contents |
                |---|---|
                | `manifest.json` | corpus identity, fixture-catalog pin and the ordered case/expected/review triples |
                | `fixtures/catalog.json` | the trusted fixture catalog: identity, role, revision and payload hash |
                | `fixtures/payloads/` | the trusted fixture payloads the catalog pins |
                | `cases/` | case inputs — the request a reference scenario makes, never its answer |
                | `expected/` | expected observations, stored apart from their cases |
                | `reviews/` | one review ledger per case, bound to the exact case and expected hashes |
                | `README.md` | this file |

                ## Trusted inputs

                A case never declares an owner, a permission, an effective DataScope, a dependency or a
                readiness state: it names fixtures, and every trusted value is read from the host-owned
                fixture ports at evaluation time. The fixture catalog pins the exact payload hash of each
                fixture, so editing a payload without re-reviewing the catalog is a hash mismatch rather
                than a silently different evaluation.

                Membership is deliberately recorded as a count and a member-set hash only. No asset in this
                directory carries a raw member identifier, so a corpus file can never become a second,
                unreviewed copy of the member universe.

                ## Review

                Every review ledger is generated `PENDING`, and the manifest states `PENDING` review and
                `NOT_RUN` model evidence. Those claims are checked against the ledgers: a manifest that
                claims review complete while any required owner role is still pending is refused, and a
                ledger that records a decision while the manifest claims pending is refused as fabricated.
                `ReferenceReleaseStatus` has no `PASSED` or `ACTIVE` constant at all, so a production
                activation claim is unrepresentable rather than merely forbidden.

                ## Regeneration

                Committed assets are source evidence; they are never written at test time. To regenerate
                after a deliberate blueprint change, run (with JDK 21):

                ```text
                .\\mvnw.cmd -q test -Dtest=ReferenceCorpusV1GeneratorTest -Dcampaign.reference-corpus.regenerate=true
                ```

                Without the property, `ReferenceCorpusV1GeneratorTest` only verifies that the committed
                bytes are byte-identical to the generated bytes, without rewriting them. The generator
                refuses to run at all once any committed review ledger carries a real decision.
                """;
    }
}