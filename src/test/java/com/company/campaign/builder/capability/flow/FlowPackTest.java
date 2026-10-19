package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The T19 section 4 Flow node capability packs: {@code flow.start}, {@code flow.message}, {@code flow.wait},
 * {@code flow.condition}, {@code flow.reward} and {@code flow.end}.
 *
 * <p>Every pack is proven to implement the shared {@code CapabilityPackContract} metadata with exact codes and
 * contract version {@code 1.0}, to normalize deterministically and idempotently, to declare an honest
 * preview-only runtime compatibility, to contribute exactly one generic safe preview card bound to the
 * component it resolved, and to pin a versioned in-code evaluation asset whose fixtures it actually accepts
 * and refuses. Unknown fields and wrong types fail closed everywhere.
 *
 * <p>The packs are proven to validate their own node configuration only: no pack reads a component set, a
 * manifest, a plan or a referenced catalog version, and the sources reach no graph, edge, schedule,
 * publication or runtime boundary.
 */
class FlowPackTest {

    private static final String LOGICAL_ID = "flow-node-01";

    private static final Path FLOW_ROOT = Path.of("src", "main", "java", "com", "company", "campaign",
            "builder", "capability", "flow");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.builder.capability.",
            "com.company.campaign.builder.compiler.",
            "com.company.campaign.contracts.",
            "com.company.campaign.shared.canonical.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.", "jakarta.persistence", "javax.persistence", "java.sql", "java.net",
            "javax.script", "com.company.campaign.segment", "com.company.campaign.query",
            "com.company.campaign.preview", "com.company.campaign.metadata", "com.company.campaign.evaluation",
            "com.company.campaign.runtime", "com.company.campaign.lifecycle", "com.company.campaign.security",
            "com.company.campaign.builder.authoring", "com.company.campaign.builder.proposal",
            "com.company.campaign.builder.agent", "com.company.campaign.builder.refine");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
            "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err",
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "Method.invoke",
            "ProcessBuilder", "Runtime.getRuntime", "Thread.sleep", "Files.write", "Files.read",
            "readValue", "treeToValue", "valueToTree", "javax.script", "ScriptEngine", "jdbc:", "DataSource");

    /**
     * The graph, component-set and plan types a node-local pack must never name. They are matched with word
     * boundaries and exact case, so prose that states what is deferred never satisfies the check and a real
     * dependency on a graph, cardinality, manifest or accepted Segment preview type does.
     */
    private static final List<String> FORBIDDEN_STRUCTURAL_TYPES = List.of(
            "\\bPlanPreviewCard\\b", "\\bResolvedComponent\\b", "\\bResolvedCatalogReference\\b",
            "\\bResolvedCampaignManifest\\b", "\\bCampaignPlan\\b", "\\bCampaignCompiler\\b",
            "\\bFrozenCapabilityCatalog\\b", "\\bCapabilityCompileContext\\b", "\\bManifest\\b",
            "\\bPredicate\\b", "\\bSegmentDsl\\b", "\\bEdge\\b", "\\bBranch\\b", "\\bSuccessor\\b",
            "\\bReachability\\b", "\\bCycle\\b", "\\bAdjacency\\b", "\\bCardinality\\b");

    // ------------------------------------------------------------------ fixtures and helpers

    /** The six accepted Flow node packs, as the shared implementation every test drives. */
    static Stream<FlowPackSupport> packs() {
        return Stream.of(new FlowStartPack(), new FlowMessagePack(), new FlowWaitPack(),
                new FlowConditionPack(), new FlowRewardPack(), new FlowEndPack());
    }

    /** The five packs whose closed payload contract declares at least one required field. */
    static Stream<FlowPackSupport> packsWithRequiredFields() {
        return packs().filter(pack -> !pack.payloadContract().requiredNames().isEmpty());
    }

    private static CapabilityResolution resolve(FlowPackSupport pack, Map<String, Object> payload) {
        return pack.resolve(new CapabilityRequest(
                LOGICAL_ID,
                CapabilityPayload.of(payload),
                BuilderFixtures.catalog(),
                BuilderFixtures.policy(),
                BuilderFixtures.context()));
    }

    private static CapabilityResolution.Resolved resolved(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Resolved.class);
        return (CapabilityResolution.Resolved) resolution;
    }

    private static void assertFailed(String code, CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<Issue> issues = ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(code);
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.ERROR);
    }

    private static FlowEvaluationFixture accepted(FlowPackSupport pack) {
        return pack.evaluationFixtures().stream().filter(FlowEvaluationFixture::isAccepted)
                .findFirst().orElseThrow();
    }

    private static CapabilityFieldConstraint constraintOf(FlowPackSupport pack, String fieldName) {
        return pack.payloadContract().fields().stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .findFirst().map(CapabilityFieldSchema::constraint).orElseThrow();
    }

    private static CapabilityPlanPreviewCard onlyCard(CapabilityResolution resolution) {
        List<CapabilityPlanPreviewCard> cards = resolved(resolution).capabilityPreviewCards();
        assertThat(cards).hasSize(1);
        return cards.get(0);
    }

    // ------------------------------------------------------------------ the shared pack contract

    @ParameterizedTest
    @MethodSource("packs")
    void everyPackDeclaresTheFrozenAcceptedContractFacts(FlowPackSupport pack) {
        CapabilityPackMetadata metadata = pack.packMetadata();

        assertThat(FlowPackVersions.CAPABILITY_CODES).contains(pack.capabilityCode());
        assertThat(pack.contractVersion()).isEqualTo("1.0");
        assertThat(metadata.capabilityCode()).isEqualTo(pack.capabilityCode());
        assertThat(metadata.contractVersion()).isEqualTo("1.0");
        assertThat(metadata.packVersion()).isEqualTo("t19-v1");
        assertThat(metadata.schemaVersion()).isEqualTo(SchemaVersion.parse("1.0"));

        assertThat(metadata.slot()).isEqualTo(new CompositionSlot("campaign.flow", true, false));
        assertThat(pack.slot().code()).isEqualTo("campaign.flow");
        assertThat(pack.slot().ordered()).isTrue();
        assertThat(pack.slot().singleton()).isFalse();

        assertThat(metadata.previewCardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(metadata.previewCardVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);

        assertThat(pack.runtimeCompatibility())
                .isEqualTo(new RuntimeCompatibility("campaign-runtime/1.0", true, false));
        assertThat(pack.runtimeCompatibility().previewSupported()).isTrue();
        assertThat(pack.runtimeCompatibility().publishable()).isFalse();
        assertThat(pack.enabled()).isTrue();

        CapabilityPayloadContract contract = metadata.payloadContract();
        assertThat(contract.fields()).doesNotHaveDuplicates();
        assertThat(contract.requiredNames()).doesNotHaveDuplicates();
        assertThat(contract.optionalNames()).doesNotHaveDuplicates();
        for (String name : contract.requiredNames()) {
            assertThat(contract.optionalNames()).doesNotContain(name);
        }

        CapabilityEvaluationAsset asset = metadata.evaluationAsset();
        assertThat(asset.assetVersion()).isEqualTo("1.0");
        assertThat(asset.assetId()).isEqualTo("t19." + pack.capabilityCode() + ".evaluation");
        assertThat(asset.positiveFixtures()).isNotEmpty();
        assertThat(asset.negativeFixtures()).isNotEmpty();
        assertThat(pinnedFixtures(pack)).allSatisfy(
                fixture -> assertThat(fixture.payloadHash()).isNotNull());
    }

    @ParameterizedTest
    @MethodSource("packs")
    void everyPackMetadataHashIsDeterministicAndInternallyConsistent(FlowPackSupport pack) {
        CapabilityPackMetadata metadata = pack.packMetadata();

        assertThatCode(metadata::requireValidPackHash).doesNotThrowAnyException();
        assertThat(metadata.recomputePackHash()).isEqualTo(metadata.packHash());
        assertThat(new FlowStartPack().packMetadata().packHash())
                .isEqualTo(packs().findFirst().orElseThrow().packMetadata().packHash());
    }

    private static List<CapabilityEvaluationFixture> pinnedFixtures(FlowPackSupport pack) {
        CapabilityEvaluationAsset asset = pack.packMetadata().evaluationAsset();
        return Stream.concat(asset.positiveFixtures().stream(), asset.negativeFixtures().stream()).toList();
    }

    @ParameterizedTest
    @MethodSource("packs")
    void everyAcceptedFixtureNormalizesDeterministicallyAndIdempotently(FlowPackSupport pack) {
        for (FlowEvaluationFixture fixture : pack.evaluationFixtures()) {
            if (!fixture.isAccepted()) {
                continue;
            }
            CapabilityResolution.Resolved first = resolved(resolve(pack, fixture.payload()));
            CapabilityResolution.Resolved second = resolved(resolve(pack, fixture.payload()));

            assertThat(first.value().canonicalBytes()).isEqualTo(second.value().canonicalBytes());
            assertThat(first.value().contentHash()).isEqualTo(second.value().contentHash());

            // Normalization is a fixed point: re-resolving the normalized payload changes nothing.
            CapabilityResolution.Resolved again = resolved(resolve(pack, first.value().fields()));
            assertThat(again.value()).isEqualTo(first.value());

            // A Flow node resolves no catalog reference and contributes no accepted Segment plan card.
            assertThat(first.references()).isEmpty();
            assertThat(first.cards()).isEmpty();
            assertThat(onlyCard(first).normalizedPayloadHash()).isEqualTo(first.value().contentHash());
        }
    }

    @ParameterizedTest
    @MethodSource("packs")
    void everyRejectedFixtureFailsClosedWithOneBoundedIssue(FlowPackSupport pack) {
        for (FlowEvaluationFixture fixture : pack.evaluationFixtures()) {
            if (fixture.isAccepted()) {
                continue;
            }
            assertFailedWithOneIssue(resolve(pack, fixture.payload()));
        }
    }

    @ParameterizedTest
    @MethodSource("packs")
    void thePinnedEvaluationAssetMatchesTheInCodeCorpus(FlowPackSupport pack) {
        for (FlowEvaluationFixture fixture : pack.evaluationFixtures()) {
            CapabilityEvaluationFixture pinned = pinnedFixtures(pack).stream()
                    .filter(candidate -> candidate.fixtureId().equals(fixture.fixtureId()))
                    .findFirst().orElseThrow();
            assertThat(pinned.expectation()).isEqualTo(fixture.expectation());
            assertThat(pinned.payloadHash()).isEqualTo(new CanonicalJson().hash(fixture.payload()));
        }
        assertThat(pinnedFixtures(pack)).hasSameSizeAs(pack.evaluationFixtures());
    }

    @ParameterizedTest
    @MethodSource("packs")
    void theContributedCardIsASafeProjectionBoundToTheResolvedComponent(FlowPackSupport pack) {
        CapabilityResolution.Resolved resolution = resolved(resolve(pack, accepted(pack).payload()));
        CapabilityPlanPreviewCard card = onlyCard(resolution);

        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(card.cardContractVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThat(card.componentLogicalId()).isEqualTo(LOGICAL_ID);
        assertThat(card.capabilityCode()).isEqualTo(pack.capabilityCode());
        assertThat(card.contractVersion()).isEqualTo(pack.contractVersion());
        assertThat(card.normalizedPayloadHash()).isEqualTo(resolution.value().contentHash());
        assertThat(card.hasResolvedCatalogReference()).isFalse();
        assertThat(card.resolvedCatalogReferenceHash()).isNull();
        assertThat(card.runtimeCompatibility()).isEqualTo(pack.runtimeCompatibility());
        assertThat(card.evaluationAssetHash()).isEqualTo(pack.packMetadata().evaluationAsset().contentHash());

        assertThat(card.summaryCodes()).isNotEmpty()
                .allSatisfy(code -> assertThat(MACHINE_CODE.matcher(code).matches()).isTrue());
        assertThat(card.riskCodes()).isNotEmpty()
                .allSatisfy(code -> assertThat(MACHINE_CODE.matcher(code).matches()).isTrue());
        assertThat(card.riskCodes()).contains("RUNTIME_PREVIEW_ONLY");

        // The projection carries no payload value, so no declared value of an accepted fixture is observable
        // on the card's own diagnostics.
        String diagnostics = card.toString();
        for (Object value : accepted(pack).payload().values()) {
            if (value instanceof String text && text.length() >= 4) {
                assertThat(diagnostics).as("card diagnostics must not echo %s", text).doesNotContain(text);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("packsWithRequiredFields")
    void aMissingDeclaredFieldFailsClosed(FlowPackSupport pack) {
        assertFailed(FlowPackErrorCodes.MISSING_FIELD, resolve(pack, Map.of()));
    }

    @ParameterizedTest
    @MethodSource("packsWithRequiredFields")
    void aNonTextDeclaredFieldFailsClosed(FlowPackSupport pack) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String name : pack.payloadContract().requiredNames()) {
            payload.put(name, 7);
        }
        assertFailed(FlowPackErrorCodes.TYPE_INVALID, resolve(pack, payload));
    }

    @ParameterizedTest
    @MethodSource("packs")
    void anUnknownFieldAlwaysFailsClosed(FlowPackSupport pack) {
        Map<String, Object> payload = new LinkedHashMap<>(accepted(pack).payload());
        payload.put("unexpectedExtra", "value");

        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack, payload));
    }

    // ------------------------------------------------------------------ the accepted pack set

    @Test
    void thePackSetIsExactlyTheSixAcceptedFlowNodes() {
        List<CapabilityPackContract> packs = FlowCapabilityPacks.all();

        assertThat(packs).hasSize(6);
        assertThat(packs.stream().map(CapabilityPackContract::capabilityCode).toList())
                .containsExactlyElementsOf(FlowPackVersions.CAPABILITY_CODES);
        assertThat(packs.stream().map(CapabilityPackContract::contractVersion).distinct().toList())
                .containsExactly("1.0");
        assertThat(packs.stream().map(pack -> pack.packMetadata().packHash()).distinct().toList())
                .as("every pack has its own frozen metadata hash")
                .hasSize(6);
        assertThat(packs.stream().map(pack -> pack.packMetadata().evaluationAsset().assetId()).toList())
                .doesNotHaveDuplicates();
        assertThat(packs.stream().map(Object::toString).toList())
                .as("no pack prints a declared field, fixture or payload value")
                .doesNotContain("referenceCode", "fixture", "PT15M");

        // A freshly constructed pack set is identical to the shipped one, facts and hash included.
        for (CapabilityPackContract fresh : packs().toList()) {
            CapabilityPackContract shipped = packs.stream()
                    .filter(candidate -> candidate.capabilityCode().equals(fresh.capabilityCode()))
                    .findFirst().orElseThrow();
            assertThat(fresh.packMetadata()).isEqualTo(shipped.packMetadata());
            assertThat(fresh.packMetadata().packHash()).isEqualTo(shipped.packMetadata().packHash());
        }
    }

    // ------------------------------------------------------------------ flow.start

    @Test
    void flowStartAcceptsAnOptionalRegisteredTriggerEvent() {
        FlowStartPack pack = new FlowStartPack();

        assertThat(pack.payloadContract().requiredNames()).isEmpty();
        assertThat(pack.payloadContract().optionalNames())
                .containsExactly(FlowStartPack.TRIGGER_EVENT_FIELD);

        CapabilityResolution.Resolved resolution = resolved(resolve(pack, Map.of()));
        assertThat(resolution.value().fields()).isEmpty();
        assertThat(resolution.value()).isEqualTo(CapabilityPayload.empty());
        assertThat(resolved(resolve(pack, Map.of(
                FlowStartPack.TRIGGER_EVENT_FIELD, "PURCHASE_COMPLETED"))).value().fields())
                .containsEntry(FlowStartPack.TRIGGER_EVENT_FIELD, "PURCHASE_COMPLETED");

        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack, Map.of("terminal", "END")));
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack, Map.of("duration", "PT5M")));
    }

    // ------------------------------------------------------------------ flow.message

    @Test
    void flowMessageAcceptsExactlyOneBoundedReference() {
        FlowMessagePack pack = new FlowMessagePack();
        Map<String, Object> valid = Map.of(FlowMessagePack.REFERENCE_FIELD, "message-welcome-01");

        assertThat(pack.payloadContract().requiredNames())
                .containsExactly(FlowMessagePack.REFERENCE_FIELD);
        assertThat(pack.payloadContract().optionalNames()).isEmpty();
        assertThat(constraintOf(pack, FlowMessagePack.REFERENCE_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.ReferenceTarget(FlowMessagePack.REFERENCE_KIND));
        assertThat(FlowMessagePack.REFERENCE_KIND).isEqualTo("MESSAGE_TEMPLATE");

        assertThat(resolved(resolve(pack, valid)).value().fields()).containsExactlyEntriesOf(valid);

        assertFailed(FlowPackErrorCodes.MISSING_FIELD, resolve(pack, Map.of()));
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack,
                Map.of(FlowMessagePack.REFERENCE_FIELD, "message-welcome-01", "template", "welcome")));
        assertFailed(FlowPackErrorCodes.REFERENCE_INVALID, resolve(pack,
                Map.of(FlowMessagePack.REFERENCE_FIELD, "message welcome 01")));
        assertFailed(FlowPackErrorCodes.REFERENCE_INVALID, resolve(pack,
                Map.of(FlowMessagePack.REFERENCE_FIELD, "")));
        assertFailed(FlowPackErrorCodes.TYPE_INVALID, resolve(pack,
                Map.of(FlowMessagePack.REFERENCE_FIELD, 7)));
    }

    // ------------------------------------------------------------------ flow.wait

    @Test
    void flowWaitAcceptsOnlyPositiveBoundedDurationsAndClosedAnchors() {
        FlowWaitPack pack = new FlowWaitPack();

        assertThat(pack.payloadContract().requiredNames())
                .containsExactlyInAnyOrder(FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.DURATION_FIELD);
        assertThat(pack.payloadContract().optionalNames()).isEmpty();
        assertThat(constraintOf(pack, FlowWaitPack.DURATION_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.DurationBounds(1, FlowWaitPack.MAX_WAIT_SECONDS));
        assertThat(constraintOf(pack, FlowWaitPack.ANCHOR_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.EnumCodes(FlowWaitPack.ANCHORS));
        assertThat(FlowWaitPack.ANCHORS)
                .containsExactly("ENTRY", "PREVIOUS_ACCEPTED_ACTION");

        // Equivalent spellings of one length normalize to one canonical payload.
        CapabilityPayload minutes = resolved(resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT60M", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY)))
                .value();
        CapabilityPayload hours = resolved(resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT1H", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY)))
                .value();
        assertThat(minutes).isEqualTo(hours);
        assertThat(minutes.fields()).containsEntry(FlowWaitPack.DURATION_FIELD, "PT1H");

        assertThat(resolved(resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "P1D", FlowWaitPack.ANCHOR_FIELD,
                FlowWaitPack.ANCHOR_PREVIOUS_ACCEPTED_ACTION))).value().fields())
                .containsEntry(FlowWaitPack.DURATION_FIELD, "PT24H");
        assertThat(resolved(resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT720H", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY)))
                .value().fields())
                .as("the fixed contract maximum itself is accepted")
                .containsEntry(FlowWaitPack.DURATION_FIELD, "PT720H");

        assertFailed(FlowPackErrorCodes.WAIT_DURATION_INVALID, resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT0S", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY)));
        assertFailed(FlowPackErrorCodes.WAIT_DURATION_INVALID, resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "-PT1H", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY)));
        assertFailed(FlowPackErrorCodes.WAIT_DURATION_INVALID, resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT720H1S", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY)));
        assertFailed(FlowPackErrorCodes.WAIT_DURATION_INVALID, resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "15 minutes", FlowWaitPack.ANCHOR_FIELD,
                FlowWaitPack.ANCHOR_ENTRY)));
        assertFailed(FlowPackErrorCodes.WAIT_ANCHOR_INVALID, resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT5M", FlowWaitPack.ANCHOR_FIELD, "AFTER_ACTION")));
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack, Map.of(
                FlowWaitPack.DURATION_FIELD, "PT5M", FlowWaitPack.ANCHOR_FIELD, FlowWaitPack.ANCHOR_ENTRY,
                "resumeAt", "2026-01-01T00:00:00Z")));
    }

    // ------------------------------------------------------------------ flow.condition

    @Test
    void flowConditionAcceptsOneCoherentTypedFactPredicate() {
        FlowConditionPack pack = new FlowConditionPack();

        assertThat(pack.payloadContract().requiredNames()).containsExactlyInAnyOrder(
                FlowConditionPack.FACT_CODE_FIELD, FlowConditionPack.ON_UNKNOWN_FIELD,
                FlowConditionPack.OPERATOR_FIELD, FlowConditionPack.VALUE_FIELD,
                FlowConditionPack.VALUE_TYPE_FIELD);
        assertThat(pack.payloadContract().optionalNames()).isEmpty();
        assertThat(constraintOf(pack, FlowConditionPack.FACT_CODE_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.Text(FlowConditionPack.MAX_FACT_CODE_LENGTH));
        assertThat(constraintOf(pack, FlowConditionPack.VALUE_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.Text(FlowConditionPack.MAX_VALUE_LENGTH));
        assertThat(constraintOf(pack, FlowConditionPack.OPERATOR_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.EnumCodes(FlowConditionPack.OPERATORS));
        assertThat(constraintOf(pack, FlowConditionPack.VALUE_TYPE_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.EnumCodes(FlowConditionPack.VALUE_TYPES));
        assertThat(constraintOf(pack, FlowConditionPack.ON_UNKNOWN_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.EnumCodes(FlowConditionPack.ON_UNKNOWN_POLICIES));
        assertThat(FlowConditionPack.ON_UNKNOWN_POLICIES).containsExactly("UNKNOWN", "ERROR");
        assertThat(FlowConditionPack.OPERATORS)
                .containsExactly("EQ", "NEQ", "LT", "LTE", "GT", "GTE");

        // Every typed scalar has exactly one canonical text form, and re-normalizing it is a fixed point.
        assertCanonical(pack, FlowConditionPack.TYPE_STRING, "GOLD", "GOLD");
        assertCanonical(pack, FlowConditionPack.TYPE_INTEGER, "007", "7");
        assertCanonical(pack, FlowConditionPack.TYPE_DECIMAL, "1.50", "1.5");
        assertCanonical(pack, FlowConditionPack.TYPE_DECIMAL, "-0.500", "-0.5");
        assertCanonical(pack, FlowConditionPack.TYPE_BOOLEAN, "true", "true");
        assertCanonical(pack, FlowConditionPack.TYPE_DATE, "2026-03-01", "2026-03-01");
        assertCanonical(pack, FlowConditionPack.TYPE_DATETIME,
                "2026-03-01T10:00:00+08:00", "2026-03-01T02:00:00Z");

        // A value that is not the declared type, or not representable in it, fails closed.
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, predicate(FlowConditionPack.TYPE_INTEGER, "many")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, predicate(FlowConditionPack.TYPE_DECIMAL, "1E+3")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, predicate(FlowConditionPack.TYPE_DECIMAL, "0.0000001")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, predicate(FlowConditionPack.TYPE_BOOLEAN, "True")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, predicate(FlowConditionPack.TYPE_DATE, "2026-02-30")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, predicate(FlowConditionPack.TYPE_DATETIME, "2026-03-01T10:00:00")));

        // The fact code is a closed dotted identifier, so no statement-, quote- or parenthesis-bearing
        // construct and no reserved segment is representable.
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.FACT_CODE_FIELD, "select.tier")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.FACT_CODE_FIELD, "Member.Tier")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.FACT_CODE_FIELD, "a.b.c.d")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.FACT_CODE_FIELD, "member tier")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.FACT_CODE_FIELD, "member.tier.value()")));

        // The operator, the type and the unknown-fact policy are closed, and an ordered comparison on a
        // boolean is refused because a boolean has no meaningful order here.
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.OPERATOR_FIELD, "LIKE")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.VALUE_TYPE_FIELD, "MONEY")));
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID,
                resolve(pack, with(FlowConditionPack.ON_UNKNOWN_FIELD, "SKIP")));
        Map<String, Object> orderedBoolean = predicate(FlowConditionPack.TYPE_BOOLEAN, "true");
        orderedBoolean.put(FlowConditionPack.OPERATOR_FIELD, FlowConditionPack.OPERATOR_LESS_THAN);
        assertFailed(FlowPackErrorCodes.CONDITION_INVALID, resolve(pack, orderedBoolean));

        // The predicate declares no action and no such field is representable.
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD,
                resolve(pack, with("action", "send")));
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD,
                resolve(pack, with("expression", "x")));

        // Both closed unknown-fact policies are accepted as declared.
        assertThat(resolved(resolve(pack, predicate(FlowConditionPack.TYPE_STRING, "GOLD"))).value().fields())
                .containsEntry(FlowConditionPack.ON_UNKNOWN_FIELD, FlowConditionPack.ON_UNKNOWN);
        Map<String, Object> errorPolicy = predicate();
        errorPolicy.put(FlowConditionPack.ON_UNKNOWN_FIELD, FlowConditionPack.ON_UNKNOWN_ERROR);
        assertThat(resolved(resolve(pack, errorPolicy)).value().fields())
                .containsEntry(FlowConditionPack.ON_UNKNOWN_FIELD, "ERROR");
    }

    private static void assertCanonical(
            FlowConditionPack pack, String valueType, String raw, String canonical) {
        CapabilityPayload normalized = resolved(resolve(pack, predicate(valueType, raw))).value();

        assertThat(normalized.fields()).containsEntry(FlowConditionPack.VALUE_FIELD, canonical);
        assertThat(normalized.fields()).containsEntry(FlowConditionPack.VALUE_TYPE_FIELD, valueType);
        assertThat(resolved(resolve(pack, normalized.fields())).value()).isEqualTo(normalized);
    }

    private static Map<String, Object> predicate() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FlowConditionPack.FACT_CODE_FIELD, "member.tier");
        payload.put(FlowConditionPack.OPERATOR_FIELD, FlowConditionPack.OPERATOR_EQUALS);
        payload.put(FlowConditionPack.VALUE_TYPE_FIELD, FlowConditionPack.TYPE_STRING);
        payload.put(FlowConditionPack.VALUE_FIELD, "GOLD");
        payload.put(FlowConditionPack.ON_UNKNOWN_FIELD, FlowConditionPack.ON_UNKNOWN);
        return payload;
    }

    private static Map<String, Object> predicate(String valueType, String value) {
        Map<String, Object> payload = predicate();
        payload.put(FlowConditionPack.VALUE_TYPE_FIELD, valueType);
        payload.put(FlowConditionPack.VALUE_FIELD, value);
        return payload;
    }

    private static Map<String, Object> with(String fieldName, Object value) {
        Map<String, Object> payload = predicate();
        payload.put(fieldName, value);
        return payload;
    }

    // ------------------------------------------------------------------ flow.reward

    @Test
    void flowRewardAcceptsExactlyOneBoundedReference() {
        FlowRewardPack pack = new FlowRewardPack();
        Map<String, Object> valid = Map.of(FlowRewardPack.REFERENCE_FIELD, "reward-welcome-01");

        assertThat(pack.capabilityCode()).isEqualTo("flow.reward");
        assertThat(pack.payloadContract().requiredNames())
                .containsExactly(FlowRewardPack.REFERENCE_FIELD);
        assertThat(pack.payloadContract().optionalNames()).isEmpty();
        assertThat(constraintOf(pack, FlowRewardPack.REFERENCE_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.ReferenceTarget(FlowRewardPack.REFERENCE_KIND));
        assertThat(FlowRewardPack.REFERENCE_KIND).isEqualTo("PROMOTION_REWARD");

        assertThat(resolved(resolve(pack, valid)).value().fields()).containsExactlyEntriesOf(valid);

        // The card names the exposure and governance coverage this node deliberately does not settle.
        assertThat(onlyCard(resolve(pack, valid)).riskCodes())
                .contains("REWARD_EXPOSURE_UNRESERVED", "GOVERNANCE_COVERAGE_REQUIRED");

        assertFailed(FlowPackErrorCodes.MISSING_FIELD, resolve(pack, Map.of()));
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack,
                Map.of(FlowRewardPack.REFERENCE_FIELD, "reward-welcome-01", "quantity", "2")));
        assertFailed(FlowPackErrorCodes.REFERENCE_INVALID, resolve(pack,
                Map.of(FlowRewardPack.REFERENCE_FIELD, "reward welcome 01")));
        assertFailed(FlowPackErrorCodes.TYPE_INVALID, resolve(pack,
                Map.of(FlowRewardPack.REFERENCE_FIELD, 7)));
    }

    // ------------------------------------------------------------------ flow.end

    @Test
    void flowEndAcceptsOnlyTheClosedTerminalCodes() {
        FlowEndPack pack = new FlowEndPack();

        assertThat(pack.payloadContract().requiredNames()).containsExactly(FlowEndPack.TERMINAL_FIELD);
        assertThat(pack.payloadContract().optionalNames()).isEmpty();
        assertThat(constraintOf(pack, FlowEndPack.TERMINAL_FIELD))
                .isEqualTo(new CapabilityFieldConstraint.EnumCodes(FlowEndPack.TERMINALS));
        assertThat(FlowEndPack.TERMINALS).containsExactly("END", "EXIT");

        assertThat(resolved(resolve(pack, Map.of(FlowEndPack.TERMINAL_FIELD, "END"))).value().fields())
                .containsEntry(FlowEndPack.TERMINAL_FIELD, "END");
        assertThat(resolved(resolve(pack, Map.of(FlowEndPack.TERMINAL_FIELD, "EXIT"))).value().fields())
                .containsEntry(FlowEndPack.TERMINAL_FIELD, "EXIT");

        assertFailed(FlowPackErrorCodes.MISSING_FIELD, resolve(pack, Map.of()));
        assertFailed(FlowPackErrorCodes.END_TERMINAL_INVALID, resolve(pack,
                Map.of(FlowEndPack.TERMINAL_FIELD, "STOP")));
        assertFailed(FlowPackErrorCodes.TYPE_INVALID, resolve(pack,
                Map.of(FlowEndPack.TERMINAL_FIELD, 7)));
        // A terminal node carries no outgoing reference of any kind.
        assertFailed(FlowPackErrorCodes.UNKNOWN_FIELD, resolve(pack,
                Map.of(FlowEndPack.TERMINAL_FIELD, "END", "target", "flow-end-other")));
    }

    // ------------------------------------------------------------------ the node-local boundary

    @Test
    void theFlowPackSourcesStayInsideTheDeterministicNodeLocalBoundary() throws Exception {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(FLOW_ROOT)) {
            sources = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertThat(sources).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder(
                        "FlowCapabilityPacks.java", "FlowConditionPack.java", "FlowEndPack.java",
                        "FlowEvaluationAssets.java", "FlowEvaluationFixture.java", "FlowMessagePack.java",
                        "FlowPackErrorCodes.java", "FlowPackException.java", "FlowPackSupport.java",
                        "FlowPackVersions.java", "FlowRewardPack.java", "FlowStartPack.java",
                        "FlowWaitPack.java");

        List<String> violations = new java.util.ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            String name = source.getFileName().toString();

            Matcher imports = IMPORT.matcher(text);
            while (imports.find()) {
                String imported = imports.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(name + " -> " + imported + " is outside the node-local boundary");
                }
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(name + " -> " + imported + " is forbidden");
                    }
                }
            }
            for (String forbidden : FORBIDDEN_TEXT) {
                if (text.contains(forbidden)) {
                    violations.add(name + " -> " + forbidden);
                }
            }
            for (String typeName : FORBIDDEN_STRUCTURAL_TYPES) {
                if (Pattern.compile(typeName).matcher(text).find()) {
                    violations.add(name + " -> " + typeName + " is not a node-local type");
                }
            }
        }
        assertThat(violations).as("the Flow node packs must stay node-local and deterministic").isEmpty();

        // The closed version set has exactly one source of truth.
        for (String closedToken : List.of(FlowPackVersions.START, FlowPackVersions.MESSAGE,
                FlowPackVersions.WAIT, FlowPackVersions.CONDITION, FlowPackVersions.REWARD,
                FlowPackVersions.END, FlowPackVersions.SLOT_CODE, FlowPackVersions.RUNTIME_SCHEMA_VERSION)) {
            List<String> declaring = sources.stream()
                    .filter(source -> {
                        try {
                            return Files.readString(source, StandardCharsets.UTF_8).contains(closedToken);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(source -> source.getFileName().toString())
                    .toList();
            assertThat(declaring).as("%s must be declared once", closedToken)
                    .containsExactly("FlowPackVersions.java");
        }
    }

    private static void assertFailedWithOneIssue(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<Issue> issues = ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.ERROR);
    }
}
