package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compiler-owned {@code SEGMENT_PREVIEW} plan card is a closed, immutable, bounded projection of one
 * resolved Segment's already-approved Preview evidence: every accepted fact is carried, every hostile,
 * unbounded, foreign or incoherent value fails closed with one stable code, no trusted identifier reaches
 * diagnostics, the card material is deterministic, and the card reaches no Segment, Query, Preview or
 * Evaluation package.
 */
class PlanPreviewCardTest {

    private static final String SEGMENT_VERSION = "segment-rule-based-1.0";
    private static final String SAMPLE_TOKEN = "sha256:" + "0123456789abcdef".repeat(4);

    private static final ContentHash CATALOG_HASH = hash("catalog");
    private static final ContentHash POLICY_HASH = hash("policy");
    private static final ContentHash PROPOSAL_HASH = hash("proposal");

    private static final ResolvedComponent COMPONENT = BuilderFixtures.component(
            "component-a", BuilderFixtures.SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"),
            Map.of("spec", "a"));
    private static final List<CapabilityPin> PINS =
            List.of(new CapabilityPin(BuilderFixtures.SEGMENT, SEGMENT_VERSION));

    private static final Path COMPILER_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "compiler");

    /**
     * The accepted canonical bytes of one reference-free card, recorded before the optional reference
     * evidence slot existed.
     *
     * <p>It is the byte-compatibility pin of the T18 extension: an accepted card that carries no reference
     * evidence must serialize to exactly these bytes — the optional slot must be omitted, not serialized as a
     * null — and therefore keep its accepted content hash.
     */
    private static final String ACCEPTED_REFERENCE_FREE_CARD_JSON =
            "{\"approvedDecisionHash\":null,\"asOf\":\"2026-10-03T00:00:00Z\","
                    + "\"canonicalAstHash\":\"sha256:"
                    + "c05ece66a64e1d658d44418a8e297046b70f42671d712676fc3fc9481d9df3db\","
                    + "\"cardKind\":\"SEGMENT_PREVIEW\",\"componentLogicalId\":\"component-a\","
                    + "\"conditionStatistics\":[{\"falseCount\":0,\"path\":\"r\",\"trueCount\":10,"
                    + "\"unknownCount\":5},{\"falseCount\":1,\"path\":\"r.1.exists\",\"trueCount\":4,"
                    + "\"unknownCount\":0}],\"dataWatermark\":\"2026-10-02T00:00:00Z\",\"dslHash\":\"sha256:"
                    + "e07a412e2418e2c9d9bc559c6c95683cd12d951fe9ce91cf61796b938cb4857e\","
                    + "\"gateInputsHash\":\"sha256:"
                    + "a65c7cd0821fd1c847de8deb7a2b91ab9895b1eaca14bbe4d4c95512408e597b\","
                    + "\"matchedCount\":1234,\"metadataHash\":\"sha256:"
                    + "45447b7afbd5e544f7d0f1df0fccd26014d9850130abd3f020b89ff96b82079f\","
                    + "\"metadataSnapshotId\":\"snapshot-7\",\"previewHash\":\"sha256:"
                    + "5975cf1bba432391c94667f5886225f69377c0aa8b9fa21fddfb21c89bcf9092\","
                    + "\"readinessState\":\"EXPERIMENTAL\",\"sampleTokens\":null,\"semanticPlanHash\":\"sha256:"
                    + "38c9296202c026a076ed4f5c9ffc34e5d060b061bf7e03e454db938bcd29859c\","
                    + "\"skillHash\":\"sha256:"
                    + "9c53c074d7ac6a2728b638ac1f376c5fa9eb8f71603017c3ea638c2fd40548df\","
                    + "\"timezone\":\"Asia/Shanghai\"}";

    /** The accepted content hash of {@link #ACCEPTED_REFERENCE_FREE_CARD_JSON}. */
    private static final String ACCEPTED_REFERENCE_FREE_CARD_HASH =
            "sha256:fb3e8963f44228a13bff3a483d69cfd335638d8b13eca0469048709c84f8ac4b";

    // ------------------------------------------------------------------ the accepted projection

    @Test
    void aResolvedSegmentProjectsOneBoundedPreviewCard() {
        PlanPreviewCard card = new Draft().build();

        assertThat(card.cardKind()).isEqualTo("SEGMENT_PREVIEW");
        assertThat(PlanPreviewCard.SEGMENT_PREVIEW).isEqualTo("SEGMENT_PREVIEW");
        assertThat(card.componentLogicalId()).isEqualTo("component-a");
        assertThat(card.matchedCount()).isEqualTo(1_234);
        assertThat(card.conditionStatistics())
                .extracting(PlanPreviewStatistic::path)
                .containsExactly("r", "r.1.exists");
        assertThat(card.sampleTokens()).isNull();
        assertThat(card.hasSample()).isFalse();
        assertThat(card.activeReadiness()).isFalse();
        assertThat(card.hasApprovedDecision()).isFalse();
        assertThat(card.cardIdentity()).isEqualTo("component-a|SEGMENT_PREVIEW");
        assertThat(card.readinessState()).isEqualTo(CapabilityReadiness.EXPERIMENTAL);
    }

    @Test
    void anAuthorizedSampleIsCarriedAsOpaqueHashedTokens() {
        PlanPreviewCard card = new Draft().sampleTokens(List.of(SAMPLE_TOKEN)).build();

        assertThat(card.hasSample()).isTrue();
        assertThat(card.sampleTokens()).containsExactly(SAMPLE_TOKEN);
    }

    @Test
    void anActiveCardBindsTheApprovedDecisionAndGateInputsItRestsOn() {
        PlanPreviewCard card = new Draft().active().build();

        assertThat(card.activeReadiness()).isTrue();
        assertThat(card.hasApprovedDecision()).isTrue();
        assertThat(card.gateInputsHash()).isEqualTo(hash("gate-inputs"));
        assertThat(card.approvedDecisionHash()).isEqualTo(hash("decision"));
    }

    @Test
    void aNonActiveCardMayReportOrOmitTheApprovedDecisionItWasKeptOff() {
        assertThat(new Draft().readinessState(CapabilityReadiness.EXPERIMENTAL).build().hasApprovedDecision())
                .isFalse();
        assertThat(new Draft().readinessState(CapabilityReadiness.EXPERIMENTAL)
                .approvedDecisionHash(hash("not-passed")).build().hasApprovedDecision()).isTrue();
        assertThat(new Draft().readinessState(CapabilityReadiness.EVALUATION_REQUIRED)
                .approvedDecisionHash(hash("stale")).build().hasApprovedDecision()).isTrue();
    }

    // ------------------------------------------------------------------ immutability

    @Test
    void theCardFreezesEveryCollectionItCarries() {
        List<PlanPreviewStatistic> statistics =
                new ArrayList<>(List.of(new PlanPreviewStatistic("r", 3, 1, 0)));
        List<String> tokens = new ArrayList<>(List.of(SAMPLE_TOKEN));
        PlanPreviewCard card = new Draft().conditionStatistics(statistics).sampleTokens(tokens).build();

        statistics.clear();
        tokens.clear();

        assertThat(card.conditionStatistics()).hasSize(1);
        assertThat(card.sampleTokens()).containsExactly(SAMPLE_TOKEN);
        assertThatThrownBy(() -> card.conditionStatistics().add(new PlanPreviewStatistic("r", 1, 0, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> card.sampleTokens().add(SAMPLE_TOKEN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theCardIsClosedAndExposesNoConstructionOrExecutionEscapeHatch() {
        assertThat(PlanPreviewCard.class.isRecord()).isTrue();
        assertThat(Modifier.isFinal(PlanPreviewCard.class.getModifiers())).isTrue();
        // Exactly two constructors: the canonical one, which carries the optional reference evidence, and the
        // accepted backward-compatible overload, whose parameter list is the canonical one minus that slot.
        // There is no third escape hatch.
        List<Class<?>> canonicalSignature = Arrays.stream(PlanPreviewCard.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
        assertThat(Arrays.stream(PlanPreviewCard.class.getDeclaredConstructors())
                .map(constructor -> Arrays.asList(constructor.getParameterTypes())))
                .containsExactlyInAnyOrder(
                        canonicalSignature, canonicalSignature.subList(0, canonicalSignature.size() - 1));
        assertThat(Arrays.stream(PlanPreviewCard.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())))
                .as("a card must expose no static factory")
                .isEmpty();
        assertThat(Arrays.stream(PlanPreviewCard.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        com.fasterxml.jackson.annotation.JsonCreator.class)))
                .as("a card must expose no deserializer")
                .isEmpty();
        assertThat(Arrays.stream(PlanPreviewCard.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("cardKind", "componentLogicalId", "matchedCount",
                        "conditionStatistics", "sampleTokens", "metadataSnapshotId", "asOf", "timezone",
                        "dataWatermark", "dslHash", "canonicalAstHash", "semanticPlanHash", "previewHash",
                        "metadataHash", "skillHash", "readinessState", "gateInputsHash",
                        "approvedDecisionHash", "referenceEvidence", "activeReadiness", "hasSample",
                        "hasApprovedDecision", "cardIdentity", "equals", "hashCode", "toString");
    }

    @Test
    void equalityCoversEveryCardFactIncludingTheApprovedDecisionHash() {
        PlanPreviewCard card = new Draft().build();

        assertThat(new Draft().build()).isEqualTo(card).hasSameHashCodeAs(card);
        assertThat(new Draft().approvedDecisionHash(hash("decision")).build()).isNotEqualTo(card);
        assertThat(new Draft().gateInputsHash(hash("other-inputs")).build()).isNotEqualTo(card);
        assertThat(new Draft().sampleTokens(List.of(SAMPLE_TOKEN)).build()).isNotEqualTo(card);
        assertThat(new Draft().matchedCount(1_235).build()).isNotEqualTo(card);
        assertThat(new Draft().readinessState(CapabilityReadiness.EVALUATION_REQUIRED).build())
                .isNotEqualTo(card);
    }

    // ------------------------------------------------------------------ closed card kind and safe facts

    @Test
    void anUnknownOrInventedCardKindFailsClosed() {
        for (String kind : List.of("AUDIENCE_PREVIEW", "segment_preview", "SEGMENT_PREVIEW ", "")) {
            assertInconsistent(() -> new Draft().cardKind(kind).build().cardKind(), "planPreviewCardKind");
        }
        assertInconsistent(() -> new Draft().cardKind(null).build().cardKind(), "planPreviewCardKind");
    }

    @Test
    void aMalformedComponentIdentityFailsClosedWithoutEchoingIt() {
        String hostile = "component-a'; DROP TABLE members--";
        for (String logicalId : List.of(hostile, "", " ", "component a", "component/a", "a".repeat(129))) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().componentLogicalId(logicalId).build().componentLogicalId(),
                    "planPreviewComponent", logicalId);
        }
        assertInconsistent(() -> new Draft().componentLogicalId(null).build().cardIdentity(),
                "planPreviewComponent");
    }

    @Test
    void aMalformedMetadataSnapshotIdentityFailsClosed() {
        assertInconsistent(() -> new Draft().metadataSnapshotId(null).build().metadataSnapshotId(),
                "planPreviewMetadataSnapshot");
        assertInconsistent(() -> new Draft().metadataSnapshotId("snapshot 7; select 1").build()
                .metadataSnapshotId(), "planPreviewMetadataSnapshot");
    }

    @Test
    void aNegativeOrUnboundedMatchedCountFailsClosed() {
        assertInconsistent(() -> new Draft().matchedCount(-1).build().matchedCount(),
                "planPreviewMatchedCount");
        assertInconsistent(
                () -> new Draft().matchedCount(PlanPreviewCard.MAX_MATCHED_COUNT + 1).build().matchedCount(),
                "planPreviewMatchedCount");
        assertThat(new Draft().matchedCount(PlanPreviewCard.MAX_MATCHED_COUNT).build().matchedCount())
                .isEqualTo(PlanPreviewCard.MAX_MATCHED_COUNT);
    }

    @Test
    void theMatchedCountAndStatisticBudgetsAreTheAcceptedPreviewBudgets() {
        // The mirror of the accepted absolute preview bounds: the card must be able to carry exactly what the
        // accepted T09 Preview boundary approved, and nothing the boundary itself would refuse.
        assertThat(PreviewBounds.MAX_MATCHED_COUNT).isEqualTo(PlanPreviewCard.MAX_MATCHED_COUNT);
        assertThat(PreviewBounds.MAX_CONDITION_STATS).isEqualTo(PlanPreviewCard.MAX_CONDITION_STATISTICS);
        assertThat(PreviewBounds.MAX_SAMPLE_SIZE).isEqualTo(PlanPreviewCard.MAX_SAMPLE_TOKENS);
    }

    @Test
    void aNullStatisticListIsCarriedAsGenuinelyAbsent() {
        PlanPreviewCard card = new Draft().conditionStatistics(null).build();

        assertThat(card.conditionStatistics()).isNull();
    }

    @Test
    void anOverBoundOrForeignStatisticListFailsClosed() {
        List<PlanPreviewStatistic> overBound = new ArrayList<>();
        for (int index = 0; index <= PlanPreviewCard.MAX_CONDITION_STATISTICS; index++) {
            overBound.add(new PlanPreviewStatistic("r", 0, 0, 0));
        }
        assertInconsistent(() -> new Draft().conditionStatistics(overBound).build().conditionStatistics(),
                "planPreviewConditionStatistics");

        List<PlanPreviewStatistic> withNull = new ArrayList<>();
        withNull.add(new PlanPreviewStatistic("r", 0, 0, 0));
        withNull.add(null);
        assertInconsistent(() -> new Draft().conditionStatistics(withNull).build().conditionStatistics(),
                "planPreviewConditionStatistics");

        assertThat(new Draft().conditionStatistics(List.of()).build().conditionStatistics()).isEmpty();
    }

    @Test
    void aStatisticThatIsNotAnAcceptedStructuralPathFailsClosed() {
        assertThatThrownBy(() -> new PlanPreviewStatistic("r.01", 0, 0, 0))
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> assertThat(((CompilerException) throwable).details())
                        .containsEntry("invariant", "planPreviewStatisticPath"));
        assertThatThrownBy(() -> new PlanPreviewStatistic("r.0", -1, 0, 0))
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> assertThat(((CompilerException) throwable).details())
                        .containsEntry("invariant", "planPreviewStatistic"));
    }

    @Test
    void theStatisticPathDomainIsExactlyTheAcceptedStructuralDomain() {
        for (String path : List.of("r", "r.0", "r.9", "r.9999", "r.1.exists", "r.0.not", "r.1.not.exists",
                deepestAcceptedPath())) {
            assertThat(new PlanPreviewStatistic(path, 0, 0, 0).path()).isEqualTo(path);
        }
        for (String path : List.of("", " ", "R", "r.", ".0", "r..0", "r.01", "r.10000", "r.11111", "r.-1",
                "r.+1", oneStepTooDeepPath(), "r.0.EXISTS", "r.0.not ", "r/a", "r[0]",
                "r.0'; DROP TABLE members--")) {
            assertThatThrownBy(() -> new PlanPreviewStatistic(path, 0, 0, 0))
                    .as("path %s", path)
                    .isInstanceOf(CompilerException.class);
        }
    }

    @Test
    void theStatisticPathBudgetsAreDerivedFromTheAcceptedDslDepth() {
        // The compiler package reaches no Segment package, so it re-states the accepted Preview path domain:
        // the deepest accepted path is the root plus MAX_PREDICATE_DEPTH - 1 child steps, and its exact text
        // stays inside the accepted length budget.
        assertThat(PlanPreviewStatistic.MAX_PATH_STEPS).isEqualTo(SegmentDslLimits.MAX_PREDICATE_DEPTH - 1);
        assertThat(PlanPreviewStatistic.MAX_PATH_LENGTH).isEqualTo(PreviewBounds.MAX_CONDITION_PATH_LENGTH);
        assertThat(PlanPreviewStatistic.MAX_PATH_INDEX).isEqualTo(PreviewBounds.MAX_CONDITION_PATH_INDEX);
        assertThat(deepestAcceptedPath().length()).isLessThanOrEqualTo(PlanPreviewStatistic.MAX_PATH_LENGTH);
    }

    // ------------------------------------------------------------------ sample tokens

    @Test
    void aSampleTokenThatIsNotAnOpaqueHashFailsClosedWithoutEchoingIt() {
        String rawMemberId = "member-42";
        for (String token : List.of(rawMemberId, "sha256:" + "A".repeat(64), "sha256:" + "a".repeat(63),
                "md5:" + "a".repeat(32), SAMPLE_TOKEN + " ", "")) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().sampleTokens(List.of(token)).build().sampleTokens(),
                    "planPreviewSample", token);
        }
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertInconsistent(() -> new Draft().sampleTokens(withNull).build().sampleTokens(),
                "planPreviewSample");
    }

    @Test
    void anOverBoundSampleFailsClosed() {
        List<String> overBound = new ArrayList<>();
        for (int index = 0; index <= PlanPreviewCard.MAX_SAMPLE_TOKENS; index++) {
            overBound.add(SAMPLE_TOKEN);
        }
        assertInconsistent(() -> new Draft().sampleTokens(overBound).build().sampleTokens(),
                "planPreviewSample");
    }

    // ------------------------------------------------------------------ hashes, time pins and readiness

    @Test
    void aMissingDslCanonicalPlanPreviewMetadataOrSkillHashFailsClosed() {
        assertInconsistent(() -> new Draft().dslHash(null).build().dslHash(), "planPreviewPins");
        assertInconsistent(() -> new Draft().canonicalAstHash(null).build().canonicalAstHash(),
                "planPreviewPins");
        assertInconsistent(() -> new Draft().semanticPlanHash(null).build().semanticPlanHash(),
                "planPreviewPins");
        assertInconsistent(() -> new Draft().previewHash(null).build().previewHash(), "planPreviewPins");
        assertInconsistent(() -> new Draft().metadataHash(null).build().metadataHash(), "planPreviewPins");
        assertInconsistent(() -> new Draft().skillHash(null).build().skillHash(), "planPreviewPins");
    }

    @Test
    void aMissingOrIncoherentTrustedTimePinFailsClosed() {
        assertInconsistent(() -> new Draft().asOf(null).build().asOf(), "planPreviewTimePins");
        assertInconsistent(() -> new Draft().dataWatermark(null).build().dataWatermark(),
                "planPreviewTimePins");
        assertInconsistent(
                () -> new Draft().dataWatermark(Instant.parse("2026-10-04T00:00:00Z")).build().dataWatermark(),
                "planPreviewTimePins");
        assertThat(new Draft().dataWatermark(new Draft().asOf).build().dataWatermark())
                .isEqualTo(new Draft().asOf);
    }

    @Test
    void aTimezoneThatIsNotABoundedCanonicalZoneIdFailsClosed() {
        for (String timezone : List.of("", " ", "utc", "UTC+08:00 ", "GMT+8", "8", "Europe/Kyiv ",
                "z".repeat(PlanPreviewCard.MAX_TIMEZONE_LENGTH + 1),
                "Asia/Shanghai'; DROP TABLE members--", "Europe/Berlin\n", "../UTC")) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().timezone(timezone).build().timezone(), "planPreviewTimezone", timezone);
        }
        assertInconsistent(() -> new Draft().timezone(null).build().timezone(), "planPreviewTimezone");

        for (String timezone : List.of("UTC", "Z", "Asia/Shanghai", "+08:00", "America/Argentina/Buenos_Aires",
                "Etc/GMT+4")) {
            assertThat(new Draft().timezone(timezone).build().timezone()).isEqualTo(timezone);
        }
    }

    @Test
    void anUnknownReadinessCodeFailsClosed() {
        for (String state : List.of("active", "DISABLED", "DEPRECATED", "PASSED", "")) {
            assertInconsistent(() -> new Draft().readinessState(state).build().readinessState(),
                    "planPreviewReadiness");
        }
        assertInconsistent(() -> new Draft().readinessState(null).build().readinessState(),
                "planPreviewReadiness");
    }

    @Test
    void anActiveCardWithoutItsCurrentGateInputsOrItsApprovedDecisionFailsClosed() {
        assertInconsistent(() -> new Draft().active().gateInputsHash(null).build().activeReadiness(),
                "planPreviewGateInputs");
        assertInconsistent(() -> new Draft().active().approvedDecisionHash(null).build().activeReadiness(),
                "planPreviewApprovedDecision");
    }

    @Test
    void everyCardBindsTheGateInputsItsCompileUsed() {
        assertInconsistent(() -> new Draft().gateInputsHash(null).build().gateInputsHash(),
                "planPreviewGateInputs");
        assertThat(new Draft().gateInputsHash(hash("inputs")).build().gateInputsHash())
                .isEqualTo(hash("inputs"));
    }

    // ------------------------------------------------------------------ diagnostics

    @Test
    void theDiagnosticsAreRedacted() {
        PlanPreviewCard card = new Draft().sampleTokens(List.of(SAMPLE_TOKEN)).timezone("Asia/Shanghai")
                .metadataSnapshotId("snapshot-7").build();

        assertThat(card.toString())
                .contains("SEGMENT_PREVIEW", "1234", CapabilityReadiness.EXPERIMENTAL)
                .doesNotContain("component-a", "snapshot-7", "Asia/Shanghai", SAMPLE_TOKEN,
                        BuilderFixtures.TENANT, BuilderFixtures.USER, BuilderFixtures.CAMPAIGN);
        assertThat(new PlanPreviewStatistic("r.1.exists", 1, 2, 3).toString())
                .contains("r.1.exists")
                .doesNotContain("exists=1", "1, 2, 3");
    }

    // ------------------------------------------------------------------ optional reference evidence

    @Test
    void theAcceptedConstructorSuppliesNoReferenceEvidenceAndKeepsTheAcceptedBytes() {
        CanonicalJson canonicalJson = new CanonicalJson();
        PlanPreviewCard accepted = new Draft().build();
        // The canonical constructor with the evidence slot absent is the same card.
        PlanPreviewCard canonical = new Draft().canonical();

        assertThat(accepted.referenceEvidence()).isNull();
        assertThat(canonical.referenceEvidence()).isNull();
        assertThat(accepted).isEqualTo(canonical).hasSameHashCodeAs(canonical);
        assertThat(canonicalJson.writeString(accepted)).isEqualTo(ACCEPTED_REFERENCE_FREE_CARD_JSON);
        assertThat(canonicalJson.writeString(canonical)).isEqualTo(ACCEPTED_REFERENCE_FREE_CARD_JSON);
        assertThat(canonicalJson.hash(accepted).value()).isEqualTo(ACCEPTED_REFERENCE_FREE_CARD_HASH);
    }

    @Test
    void aCardThatCarriesReferenceEvidenceIsADifferentCardAndADifferentPlanMaterial() {
        PlanPreviewCard bare = new Draft().build();
        PlanPreviewCard withEvidence = new Draft().referenceEvidence(evidence("dependencies-1")).build();
        CampaignPlan barePlan = plan(bare);
        CampaignPlan evidencePlan = plan(withEvidence);

        assertThat(withEvidence.referenceEvidence()).isNotNull();
        assertThat(withEvidence).isNotEqualTo(bare);
        assertThat(evidencePlan.planHash()).isNotEqualTo(barePlan.planHash());
        assertThat(evidencePlan.planId()).isNotEqualTo(barePlan.planId());
        // Every other card fact is untouched: only the optional evidence slot moved.
        assertThat(withEvidence.previewHash()).isEqualTo(bare.previewHash());
        assertThat(withEvidence.matchedCount()).isEqualTo(bare.matchedCount());
    }

    @Test
    void referenceEvidenceIsClosedToTheDedicatedIdentityAndTheDevelopmentStates() {
        ContentHash dependencies = hash("dependencies");
        ContentHash inputs = hash("reference-inputs");

        // The identity is one closed value; a base capability approval is not an approval of the extension.
        for (String capabilityId : List.of(
                "segment.rule-based", "SEGMENT_PREVIEW", "existing-audience-reference", "", " ")) {
            assertInconsistent(
                    () -> new ReferencePlanEvidence(capabilityId, dependencies, inputs,
                            ReferencePlanEvidence.DEVELOPMENT_ONLY).capabilityId(),
                    "planPreviewReferenceCapability");
        }
        assertInconsistent(
                () -> new ReferencePlanEvidence(null, dependencies, inputs,
                        ReferencePlanEvidence.DEVELOPMENT_ONLY).capabilityId(),
                "planPreviewReferenceCapability");

        // No production-ready member exists: ACTIVE is not representable on a card.
        for (String state : List.of("ACTIVE", "PRODUCTION", "PASSED", "EVALUATION_REQUIRED", "active", "")) {
            assertInconsistent(
                    () -> new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, dependencies, inputs,
                            state).readinessState(),
                    "planPreviewReferenceReadiness");
        }
        assertInconsistent(
                () -> new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, dependencies, inputs, null)
                        .readinessState(),
                "planPreviewReferenceReadiness");

        // Both host hashes are required: evidence without them is not evidence.
        assertInconsistent(
                () -> new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, null, inputs,
                        ReferencePlanEvidence.DEVELOPMENT_ONLY).referenceDependenciesHash(),
                "planPreviewReferencePins");
        assertInconsistent(
                () -> new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, dependencies, null,
                        ReferencePlanEvidence.DEVELOPMENT_ONLY).referenceInputsHash(),
                "planPreviewReferencePins");

        assertThat(new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, dependencies, inputs,
                ReferencePlanEvidence.DEVELOPMENT_ONLY).developmentOnly()).isTrue();
        assertThat(new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, dependencies, inputs,
                ReferencePlanEvidence.EXPERIMENTAL).developmentOnly()).isFalse();
    }

    @Test
    void equalityCoversTheReferenceEvidenceIncludingBothHostHashes() {
        PlanPreviewCard card = new Draft().referenceEvidence(evidence("dependencies-1")).build();

        assertThat(new Draft().referenceEvidence(evidence("dependencies-1")).build())
                .isEqualTo(card).hasSameHashCodeAs(card);
        assertThat(new Draft().referenceEvidence(evidence("dependencies-2")).build()).isNotEqualTo(card);
        assertThat(new Draft().referenceEvidence(
                evidence("dependencies-1", hash("other-inputs"))).build()).isNotEqualTo(card);
        assertThat(new Draft().referenceEvidence(
                new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, hash("dependencies-1"),
                        hash("reference-inputs"), ReferencePlanEvidence.EXPERIMENTAL)).build())
                .isNotEqualTo(card);
        assertThat(new Draft().build()).isNotEqualTo(card);
    }

    @Test
    void theDiagnosticsShowOnlyReferencePresenceAndState() {
        ReferencePlanEvidence evidence = evidence("dependencies-1");

        assertThat(new Draft().build().toString()).contains("reference=none");
        assertThat(new Draft().referenceEvidence(evidence).build().toString())
                .contains("reference=" + ReferencePlanEvidence.DEVELOPMENT_ONLY)
                .doesNotContain(ReferencePlanEvidence.CAPABILITY_ID,
                        evidence.referenceDependenciesHash().value(),
                        evidence.referenceInputsHash().value());
        // The evidence diagnostic is itself bounded: the state and presence, never an identity or a hash.
        assertThat(evidence.toString())
                .contains(ReferencePlanEvidence.DEVELOPMENT_ONLY)
                .doesNotContain(ReferencePlanEvidence.CAPABILITY_ID,
                        evidence.referenceDependenciesHash().value(),
                        evidence.referenceInputsHash().value());
    }

    // ------------------------------------------------------------------ deterministic plan material

    @Test
    void theCanonicalCardMaterialIsDeterministicAndCoversEveryCarriedFact() {
        CanonicalJson canonicalJson = new CanonicalJson();

        assertThat(canonicalJson.write(new Draft().build())).isEqualTo(canonicalJson.write(new Draft().build()));
        assertThat(canonicalJson.write(new Draft().active().build()))
                .isNotEqualTo(canonicalJson.write(new Draft().build()));
        assertThat(canonicalJson.write(new Draft().approvedDecisionHash(hash("decision")).build()))
                .isNotEqualTo(canonicalJson.write(new Draft().build()));
        assertThat(canonicalJson.write(new Draft().sampleTokens(List.of(SAMPLE_TOKEN)).build()))
                .isNotEqualTo(canonicalJson.write(new Draft().build()));
        assertThat(canonicalJson.write(new Draft().timezone("Europe/Berlin").build()))
                .isNotEqualTo(canonicalJson.write(new Draft().build()));
    }

    @Test
    void thePlanHashCoversEveryCardSemanticAndRecompilationIsByteIdentical() {
        CampaignPlan bare = plan();
        CampaignPlan withCard = plan(new Draft().build());
        CampaignPlan identical = plan(new Draft().build());

        assertThat(withCard.planHash()).isNotEqualTo(bare.planHash());
        assertThat(withCard.planId()).isNotEqualTo(bare.planId());
        assertThat(withCard.previewCards()).containsExactly(new Draft().build());

        assertThat(identical.planHash()).isEqualTo(withCard.planHash());
        assertThat(identical.canonicalMaterialBytes()).isEqualTo(withCard.canonicalMaterialBytes());

        assertThat(plan(new Draft().active().build()).planHash()).isNotEqualTo(withCard.planHash());
        assertThat(plan(new Draft().approvedDecisionHash(hash("decision")).build()).planHash())
                .isNotEqualTo(withCard.planHash());
        assertThat(plan(new Draft().sampleTokens(List.of(SAMPLE_TOKEN)).build()).planHash())
                .isNotEqualTo(withCard.planHash());
        assertThat(plan(new Draft().conditionStatistics(null).build()).planHash())
                .isNotEqualTo(withCard.planHash());
        assertThat(plan(new Draft().matchedCount(1).build()).planHash()).isNotEqualTo(withCard.planHash());
    }

    @Test
    void aStaleOrMixedCardFailsClosedAgainstThePlanItIsProjectedInto() {
        // A card for a component the plan's manifest no longer holds.
        assertThatThrownBy(() -> plan(new Draft().componentLogicalId("component-b").build()))
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> assertThat(((CompilerException) throwable).details())
                        .containsEntry("invariant", "previewCards"));

        // One component must never carry two cards: a stale card never merges with a refreshed one.
        assertThatThrownBy(() -> plan(new Draft().build(), new Draft().build()))
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> assertThat(((CompilerException) throwable).details())
                        .containsEntry("invariant", "previewCards"));
    }

    // ------------------------------------------------------------------ dependency boundary

    @Test
    void theCardAndItsStatisticReachNoSegmentQueryPreviewOrEvaluationPackage() throws Exception {
        List<String> forbidden = List.of("com.company.campaign.segment", "com.company.campaign.query",
                "com.company.campaign.preview", "com.company.campaign.evaluation",
                "com.company.campaign.runtime", "com.company.campaign.builder.agent");
        List<String> forbiddenText = List.of("Instant.now", "LocalDate.now", "LocalDateTime.now",
                "ZonedDateTime.now", "OffsetDateTime.now", "Clock.system", "ZoneId.systemDefault",
                "TimeZone.getDefault", "Locale.getDefault", "System.currentTimeMillis", "System.nanoTime",
                "Math.random", "new Random", "UUID.randomUUID", "System.out", "System.err", "org.slf4j");

        for (String source : List.of("PlanPreviewCard.java", "PlanPreviewStatistic.java")) {
            String text = Files.readString(COMPILER_ROOT.resolve(source), StandardCharsets.UTF_8);

            for (String prefix : forbidden) {
                assertThat(text).as("%s must not reach %s", source, prefix).doesNotContain(prefix);
            }
            for (String value : forbiddenText) {
                assertThat(text).as("%s must not use %s", source, value).doesNotContain(value);
            }
            Matcher imports = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE).matcher(text);
            while (imports.find()) {
                String imported = imports.group(1);
                // The card reaches no Segment, Query, Preview, Evaluation, Runtime, Agent or capability package. The
                // one permitted non-JDK import is the Jackson inclusion annotation, which exists solely so an
                // absent optional reference-evidence slot is omitted from the material instead of serialized
                // as a null; it carries no semantic and reaches nothing.
                assertThat(imported).as("%s imports %s", source, imported)
                        .matches("java\\..*|com\\.company\\.campaign\\.contracts\\..*"
                                + "|com\\.company\\.campaign\\.shared\\.canonical\\..*"
                                + "|com\\.fasterxml\\.jackson\\.annotation\\.JsonInclude");
            }
        }
    }

    @Test
    void everyComponentOfTheCardIsItselfClosedAndSafe() {
        Set<String> allowed = Set.of("java.lang.String", "int", "java.time.Instant", "java.util.List",
                "com.company.campaign.shared.canonical.ContentHash",
                "com.company.campaign.builder.compiler.PlanPreviewStatistic",
                "com.company.campaign.builder.compiler.ReferencePlanEvidence");

        for (RecordComponent component : PlanPreviewCard.class.getRecordComponents()) {
            assertThat(allowed).as("component %s", component.getName()).contains(component.getType().getName());
            if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
                for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                    assertThat(allowed).as("component %s", component.getName())
                            .contains(((Class<?>) argument).getName());
                }
            }
        }
        assertThat(PlanPreviewStatistic.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(String.class, int.class, int.class, int.class);
    }

    // ------------------------------------------------------------------ fixtures

    /** A test-only draft of one card, so each test can vary exactly one fact. */
    private static final class Draft {

        private String cardKind = PlanPreviewCard.SEGMENT_PREVIEW;
        private String componentLogicalId = "component-a";
        private int matchedCount = 1_234;
        private List<PlanPreviewStatistic> conditionStatistics = new ArrayList<>(List.of(
                new PlanPreviewStatistic("r", 10, 0, 5),
                new PlanPreviewStatistic("r.1.exists", 4, 1, 0)));
        private List<String> sampleTokens = null;
        private String metadataSnapshotId = "snapshot-7";
        private Instant asOf = Instant.parse("2026-10-03T00:00:00Z");
        private String timezone = "Asia/Shanghai";
        private Instant dataWatermark = Instant.parse("2026-10-02T00:00:00Z");
        private ContentHash dslHash = hash("dsl");
        private ContentHash canonicalAstHash = hash("canonical-ast");
        private ContentHash semanticPlanHash = hash("semantic-plan");
        private ContentHash previewHash = hash("preview");
        private ContentHash metadataHash = hash("metadata");
        private ContentHash skillHash = hash("skill");
        private String readinessState = CapabilityReadiness.EXPERIMENTAL;
        private ContentHash gateInputsHash = hash("gate-inputs");
        private ContentHash approvedDecisionHash = null;
        private ReferencePlanEvidence referenceEvidence = null;

        Draft cardKind(String value) {
            this.cardKind = value;
            return this;
        }

        Draft componentLogicalId(String value) {
            this.componentLogicalId = value;
            return this;
        }

        Draft matchedCount(int value) {
            this.matchedCount = value;
            return this;
        }

        Draft conditionStatistics(List<PlanPreviewStatistic> value) {
            this.conditionStatistics = value;
            return this;
        }

        Draft sampleTokens(List<String> value) {
            this.sampleTokens = value;
            return this;
        }

        Draft metadataSnapshotId(String value) {
            this.metadataSnapshotId = value;
            return this;
        }

        Draft asOf(Instant value) {
            this.asOf = value;
            return this;
        }

        Draft timezone(String value) {
            this.timezone = value;
            return this;
        }

        Draft dataWatermark(Instant value) {
            this.dataWatermark = value;
            return this;
        }

        Draft dslHash(ContentHash value) {
            this.dslHash = value;
            return this;
        }

        Draft canonicalAstHash(ContentHash value) {
            this.canonicalAstHash = value;
            return this;
        }

        Draft semanticPlanHash(ContentHash value) {
            this.semanticPlanHash = value;
            return this;
        }

        Draft previewHash(ContentHash value) {
            this.previewHash = value;
            return this;
        }

        Draft metadataHash(ContentHash value) {
            this.metadataHash = value;
            return this;
        }

        Draft skillHash(ContentHash value) {
            this.skillHash = value;
            return this;
        }

        Draft readinessState(String value) {
            this.readinessState = value;
            if (!CapabilityReadiness.ACTIVE.equals(value)) {
                this.approvedDecisionHash = null;
            }
            return this;
        }

        Draft gateInputsHash(ContentHash value) {
            this.gateInputsHash = value;
            return this;
        }

        Draft approvedDecisionHash(ContentHash value) {
            this.approvedDecisionHash = value;
            return this;
        }

        Draft active() {
            this.readinessState = CapabilityReadiness.ACTIVE;
            this.approvedDecisionHash = hash("decision");
            return this;
        }

        Draft referenceEvidence(ReferencePlanEvidence value) {
            this.referenceEvidence = value;
            return this;
        }

        PlanPreviewCard build() {
            if (referenceEvidence != null) {
                return canonical();
            }
            // The accepted backward-compatible constructor, exercised by every reference-free test.
            return new PlanPreviewCard(cardKind, componentLogicalId, matchedCount, conditionStatistics,
                    sampleTokens, metadataSnapshotId, asOf, timezone, dataWatermark, dslHash, canonicalAstHash,
                    semanticPlanHash, previewHash, metadataHash, skillHash, readinessState, gateInputsHash,
                    approvedDecisionHash);
        }

        /** The same card through the canonical constructor, with the optional evidence slot explicit. */
        PlanPreviewCard canonical() {
            return new PlanPreviewCard(cardKind, componentLogicalId, matchedCount, conditionStatistics,
                    sampleTokens, metadataSnapshotId, asOf, timezone, dataWatermark, dslHash, canonicalAstHash,
                    semanticPlanHash, previewHash, metadataHash, skillHash, readinessState, gateInputsHash,
                    approvedDecisionHash, referenceEvidence);
        }
    }

    private static CampaignPlan plan(PlanPreviewCard... cards) {
        ResolvedCampaignManifest manifest = ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION, ProposalMode.EDIT,
                BuilderFixtures.CAMPAIGN, BuilderFixtures.CAMPAIGN_REVISION, BuilderFixtures.DRAFT,
                BuilderFixtures.DRAFT_REVISION, null, List.of(COMPONENT), PINS,
                BuilderFixtures.CATALOG_VERSION, CATALOG_HASH, BuilderFixtures.POLICY_VERSION, POLICY_HASH);
        return CampaignPlan.create(
                CompilerVersions.PLAN_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION,
                BuilderFixtures.COMPILED_AT, BuilderFixtures.EXPIRES_AT,
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER,
                manifest.campaignId(), manifest.baseCampaignRevision(), manifest.baseDraftId(),
                manifest.baseDraftRevision(), manifest.forkBase(),
                PROPOSAL_HASH, manifest.manifestHash(), manifest.capabilityPins(), manifest.catalogVersion(),
                manifest.catalogHash(), manifest.policyVersion(), manifest.policyHash(), manifest, List.of(),
                new SemanticDiff(List.of()), new ChangeSet(CompilerVersions.CHANGESET_FORMAT_VERSION, List.of()),
                List.of(), List.of(cards), RiskSummary.empty());
    }

    /** The absolute preview bounds the card re-states, read from the accepted Preview boundary. */
    private static final class PreviewBounds {

        private static final int MAX_MATCHED_COUNT = com.company.campaign.preview.PreviewLimits
                .MAX_MATCHED_COUNT;
        private static final int MAX_CONDITION_STATS = com.company.campaign.preview.PreviewLimits
                .MAX_CONDITION_STATS;
        private static final int MAX_SAMPLE_SIZE = com.company.campaign.preview.PreviewLimits.MAX_SAMPLE_SIZE;
        private static final int MAX_CONDITION_PATH_LENGTH = com.company.campaign.preview.PreviewLimits
                .MAX_CONDITION_PATH_LENGTH;
        private static final int MAX_CONDITION_PATH_INDEX = com.company.campaign.preview.PreviewLimits
                .MAX_CONDITION_PATH_INDEX;

        private PreviewBounds() {
        }
    }

    /**
     * The deepest path an accepted predicate tree can produce: the root plus the accepted child steps, taken
     * from the accepted DSL depth rather than from the card's own re-stated constant.
     */
    private static String deepestAcceptedPath() {
        int steps = SegmentDslLimits.MAX_PREDICATE_DEPTH - 1;
        StringBuilder path = new StringBuilder("r");
        for (int step = 0; step < steps; step++) {
            path.append(step == steps - 1 ? ".exists" : ".9999");
        }
        return path.toString();
    }

    /** One step deeper than any accepted predicate tree can nest, so no accepted traversal can emit it. */
    private static String oneStepTooDeepPath() {
        StringBuilder path = new StringBuilder("r");
        for (int step = 0; step <= SegmentDslLimits.MAX_PREDICATE_DEPTH - 1; step++) {
            path.append(".1");
        }
        return path.toString();
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** One development-only reference evidence whose dependency hash varies with its seed. */
    private static ReferencePlanEvidence evidence(String dependenciesSeed) {
        return evidence(dependenciesSeed, hash("reference-inputs"));
    }

    private static ReferencePlanEvidence evidence(String dependenciesSeed, ContentHash inputs) {
        return new ReferencePlanEvidence(ReferencePlanEvidence.CAPABILITY_ID, hash(dependenciesSeed), inputs,
                ReferencePlanEvidence.DEVELOPMENT_ONLY);
    }

    private static void assertInconsistent(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage())
                            .doesNotContain(BuilderFixtures.TENANT, BuilderFixtures.USER, "component-a");
                });
    }

    private static void assertInconsistentWithoutDisclosure(
            Runnable construction, String invariant, String rejectedValue) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    if (rejectedValue == null || rejectedValue.isBlank() || rejectedValue.length() < 4) {
                        // Nothing distinctive could leak from a blank or trivially short value.
                        return;
                    }
                    assertThat(failure.getMessage()).doesNotContain(rejectedValue);
                    assertThat(failure.details().values()).allSatisfy(detail ->
                            assertThat(String.valueOf(detail)).doesNotContain(rejectedValue));
                });
    }
}