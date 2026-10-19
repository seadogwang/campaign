package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.builder.capability.composition.T19CompositionErrorCodes;
import com.company.campaign.builder.capability.composition.T19CompositionValidator;
import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.AuthoringValidationRequest;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T19 section 7: the hash-pinned capability evaluation corpus and its loader.
 *
 * <p>It loads and verifies the committed corpus under {@code evaluation/capabilities/t19-v1}, proves every
 * T19 pack is covered with both accepted and rejected fixtures and that the scenario categories the section
 * requires are present, and drives the one complete composition fixture through the real composition
 * validator. It then proves the loader fails closed on a tampered manifest hash, a wrong asset hash, an
 * unknown field and a non-canonical asset.
 */
class T19EvaluationLoaderTest {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final String RUNTIME = "campaign-runtime/1.0";

    private static T19EvaluationLoader committedLoader() {
        return new T19EvaluationLoader(Path.of("").toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------ the committed corpus

    @Test
    void theCommittedManifestIsHashPinnedAndNamesOneRuntimeSchema() {
        T19EvaluationManifest manifest = committedLoader().loadManifest();
        assertThat(manifest.assetId()).isEqualTo(T19EvaluationVersions.ASSET_ID);
        assertThat(manifest.assetVersion()).isEqualTo(T19EvaluationVersions.ASSET_VERSION);
        assertThat(manifest.runtimeSchemaVersion()).isEqualTo(RUNTIME);
        assertThat(manifest.schemaVersion()).isEqualTo("1.0");
        assertThat(manifest.packs()).hasSize(T19EvaluationVersions.CAPABILITY_CODES.size());
        assertThat(manifest.compositions()).isNotNull();
    }

    @Test
    void theCommittedCorpusCoversEveryPackWithBothPolarities() {
        T19EvaluationCorpus corpus = committedLoader().loadCorpus();
        assertThat(corpus.documents()).hasSize(T19EvaluationVersions.CAPABILITY_CODES.size());
        for (String capability : T19EvaluationVersions.CAPABILITY_CODES) {
            T19EvaluationFixtureDocument document = corpus.documentFor(capability);
            assertThat(document).as("corpus covers " + capability).isNotNull();
            assertThat(document.acceptedFixtures()).as(capability + " accepted").isNotEmpty();
            assertThat(document.rejectedFixtures()).as(capability + " rejected").isNotEmpty();
            assertThat(document.contractVersion()).isEqualTo("1.0");
        }
    }

    @Test
    void theCorpusDeclaresTheRequiredScenarioCategories() {
        T19EvaluationCorpus corpus = committedLoader().loadCorpus();
        assertThat(corpus.documentFor("message.template").declares("message-template-disabled-version")).isTrue();
        assertThat(corpus.documentFor("message.template").declares("message-template-unavailable-version")).isTrue();
        assertThat(corpus.documentFor("message.template").declares("message-template-unknown-field")).isTrue();
        assertThat(corpus.documentFor("promotion.reference").declares("promotion-reference-amount-overflow"))
                .isTrue();
        assertThat(corpus.documentFor("flow.message").declares("flow-message-missing-reference")).isTrue();
        assertThat(corpus.documentFor("flow.reward").declares("flow-reward-missing-reference")).isTrue();
        assertThat(corpus.documentFor("governance.limit").declares("governance-limit-bound-exceeded")).isTrue();
        assertThat(corpus.documentFor("schedule.basic").declares("schedule-window-too-long")).isTrue();
    }

    // ------------------------------------------------------------------ the complete composition

    @Test
    void theCompleteCompositionFixtureDrivesTheRealValidator() {
        T19EvaluationCorpus corpus = committedLoader().loadCorpus();
        T19CompositionValidator validator = new T19CompositionValidator(index());

        for (T19CompositionFixture fixture : corpus.compositions().compositions()) {
            List<Issue> issues = validator.validate(request(fixture));
            boolean blocking = issues.stream().anyMatch(issue -> issue.severity() == IssueSeverity.ERROR);
            if (fixture.accepted()) {
                assertThat(issues).as(fixture.compositionId() + " must be accepted").isEmpty();
            } else {
                assertThat(blocking).as(fixture.compositionId() + " must be rejected").isTrue();
            }
        }
    }

    @Test
    void theRejectedCompositionsReportTheirExpectedStableCodes() {
        T19EvaluationCorpus corpus = committedLoader().loadCorpus();
        T19CompositionValidator validator = new T19CompositionValidator(index());
        Map<String, String> expected = Map.of(
                "composition-reward-without-governance", T19CompositionErrorCodes.GOVERNANCE_REQUIRED,
                "composition-segment-reference", T19CompositionErrorCodes.SEGMENT_REFERENCE,
                "composition-reward-exposure-exceeded", T19CompositionErrorCodes.REWARD_GRANTS_EXCEEDED);
        for (T19CompositionFixture fixture : corpus.compositions().compositions()) {
            String expectedCode = expected.get(fixture.compositionId());
            if (expectedCode == null) {
                continue;
            }
            assertThat(codes(validator.validate(request(fixture)))).contains(expectedCode);
        }
    }

    // ------------------------------------------------------------------ fail-closed loader behaviour

    @Test
    void aTamperedManifestHashIsRefused(@TempDir Path root) throws IOException {
        writeCorpus(root, true, false, false, false);
        T19EvaluationException failure =
                catchThrowableOfType(() -> new T19EvaluationLoader(root).loadCorpus(), T19EvaluationException.class);
        assertThat(failure.code()).isEqualTo(T19EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    @Test
    void aWrongAssetHashIsRefused(@TempDir Path root) throws IOException {
        writeCorpus(root, false, true, false, false);
        T19EvaluationException failure =
                catchThrowableOfType(() -> new T19EvaluationLoader(root).loadCorpus(), T19EvaluationException.class);
        assertThat(failure.code()).isEqualTo(T19EvaluationErrorCodes.ASSET_HASH_MISMATCH);
    }

    @Test
    void anUnknownFieldIsRefused(@TempDir Path root) throws IOException {
        writeCorpus(root, false, false, true, false);
        T19EvaluationException failure =
                catchThrowableOfType(() -> new T19EvaluationLoader(root).loadCorpus(), T19EvaluationException.class);
        assertThat(failure.code()).isEqualTo(T19EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void aNonCanonicalAssetIsRefused(@TempDir Path root) throws IOException {
        writeCorpus(root, false, false, false, true);
        T19EvaluationException failure =
                catchThrowableOfType(() -> new T19EvaluationLoader(root).loadCorpus(), T19EvaluationException.class);
        assertThat(failure.code()).isEqualTo(T19EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> codes(List<Issue> issues) {
        List<String> codes = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            codes.add(issue.code());
        }
        return codes;
    }

    private static T19PackFacts fact(String capability, CompositionSlot slot) {
        return new T19PackFacts(capability, "1.0", slot, true, new RuntimeCompatibility(RUNTIME, true, false));
    }

    private static T19PackIndex index() {
        List<T19PackFacts> facts = new ArrayList<>();
        facts.add(fact("message.template", new CompositionSlot("campaign.message", false, false)));
        facts.add(fact("promotion.reference", new CompositionSlot("campaign.promotion", false, false)));
        for (String flow : List.of("flow.start", "flow.message", "flow.wait", "flow.condition",
                "flow.reward", "flow.end")) {
            facts.add(fact(flow, new CompositionSlot("campaign.flow", true, false)));
        }
        facts.add(fact("schedule.basic", new CompositionSlot("campaign.schedule", false, true)));
        facts.add(fact("governance.limit", new CompositionSlot("campaign.governance", false, true)));
        return T19PackIndex.of(RUNTIME, facts);
    }

    private static CompositionSlot slot(String code) {
        return switch (code) {
            case "campaign.flow" -> new CompositionSlot(code, true, false);
            case "campaign.schedule", "campaign.governance" -> new CompositionSlot(code, false, true);
            default -> new CompositionSlot(code, false, false);
        };
    }

    private static AuthoringValidationRequest request(T19CompositionFixture fixture) {
        List<ResolvedComponent> components = new ArrayList<>();
        for (T19CompositionComponent component : fixture.components()) {
            List<ResolvedCatalogReference> references = new ArrayList<>();
            for (T19CompositionReference reference : component.references()) {
                references.add(new ResolvedCatalogReference(reference.referenceCode(), reference.entityKind(),
                        reference.logicalId(), reference.versionId()));
            }
            components.add(new ResolvedComponent(component.logicalId(), component.capabilityCode(),
                    component.contractVersion(), slot(component.slot()),
                    CapabilityPayload.of(component.value()), references));
        }
        return new AuthoringValidationRequest(fixture.campaignId(), BuilderFixtures.TENANT,
                BuilderFixtures.PROGRAM, components, BuilderFixtures.context());
    }

    /**
     * Writes one minimal corpus into a temporary repository root, applying exactly one deliberate fault.
     */
    private static void writeCorpus(
            Path root,
            boolean tamperManifestHash,
            boolean wrongAssetHash,
            boolean unknownField,
            boolean nonCanonicalFixture) throws IOException {
        Path base = root.resolve(T19EvaluationVersions.EVALUATION_ROOT_REFERENCE);
        Path fixtures = base.resolve("fixtures");
        Files.createDirectories(fixtures);

        ContentHash acceptedHash = ContentHash.sha256("flow-start-empty-valid".getBytes(StandardCharsets.UTF_8));
        ContentHash rejectedHash = ContentHash.sha256("flow-start-unknown-field".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> fixtureDocument = new LinkedHashMap<>();
        fixtureDocument.put("schemaVersion", "1.0");
        fixtureDocument.put("assetId", "t19-flow-start-evaluation-v1");
        fixtureDocument.put("assetVersion", "1.0");
        fixtureDocument.put("capabilityCode", "flow.start");
        fixtureDocument.put("contractVersion", "1.0");
        fixtureDocument.put("fixtures", List.of(
                Map.of("expectation", "ACCEPTED", "fixtureId", "flow-start-empty-valid",
                        "payloadHash", acceptedHash.value()),
                Map.of("expectation", "REJECTED", "fixtureId", "flow-start-unknown-field",
                        "payloadHash", rejectedHash.value())));
        if (unknownField) {
            fixtureDocument.put("unexpected", "value");
        }
        byte[] fixtureBytes = CANONICAL_JSON.write(fixtureDocument);
        if (nonCanonicalFixture) {
            // A pretty-printed asset is well-formed but not its own canonical serialization.
            fixtureBytes = (new String(fixtureBytes, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        }
        ContentHash declaredFixtureHash = wrongAssetHash
                ? ContentHash.sha256("tampered".getBytes(StandardCharsets.UTF_8))
                : ContentHash.sha256(fixtureBytes);
        Files.write(fixtures.resolve("flow.start.json"), fixtureBytes);

        Map<String, Object> compositionDocument = new LinkedHashMap<>();
        compositionDocument.put("schemaVersion", "1.0");
        compositionDocument.put("assetId", "t19-composition-evaluation-v1");
        compositionDocument.put("assetVersion", "1.0");
        compositionDocument.put("compositions", List.of(
                Map.of("campaignId", "campaign-001", "components", List.of(),
                        "compositionId", "composition-complete", "expectation", "ACCEPTED"),
                Map.of("campaignId", "campaign-001", "components", List.of(),
                        "compositionId", "composition-rejected", "expectation", "REJECTED")));
        byte[] compositionBytes = CANONICAL_JSON.write(compositionDocument);
        Files.write(fixtures.resolve("composition.json"), compositionBytes);

        Map<String, Object> packReference = new LinkedHashMap<>();
        packReference.put("assetId", "t19-flow-start-evaluation-v1");
        packReference.put("assetVersion", "1.0");
        packReference.put("capabilityCode", "flow.start");
        packReference.put("contractVersion", "1.0");
        packReference.put("fixturesHash", declaredFixtureHash.value());
        packReference.put("fixturesPath", T19EvaluationVersions.EVALUATION_ROOT_REFERENCE
                + "/fixtures/flow.start.json");
        packReference.put("slot", "campaign.flow");

        Map<String, Object> compositionReference = new LinkedHashMap<>();
        compositionReference.put("assetId", "t19-composition-evaluation-v1");
        compositionReference.put("assetVersion", "1.0");
        compositionReference.put("contentHash", ContentHash.sha256(compositionBytes).value());
        compositionReference.put("path", T19EvaluationVersions.EVALUATION_ROOT_REFERENCE + "/fixtures/composition.json");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "1.0");
        manifest.put("assetId", T19EvaluationVersions.ASSET_ID);
        manifest.put("assetVersion", T19EvaluationVersions.ASSET_VERSION);
        manifest.put("runtimeSchemaVersion", RUNTIME);
        manifest.put("packs", List.of(packReference));
        manifest.put("compositions", compositionReference);
        ContentHash manifestHash = CANONICAL_JSON.hash(manifest);
        manifest.put("manifestHash", tamperManifestHash
                ? ContentHash.sha256("tampered".getBytes(StandardCharsets.UTF_8)).value()
                : manifestHash.value());
        Files.write(base.resolve("manifest.json"), CANONICAL_JSON.write(manifest));
    }
}