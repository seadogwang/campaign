package com.company.campaign.builder.capability;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generic T19 capability preview card binds exactly the safe facts T19 section 2 names and nothing else:
 * a closed card kind and card contract version, the resolved component's stable identity and exact capability
 * contract version, the normalized payload hash, the optional resolved-catalog-reference hash, the frozen
 * runtime compatibility, two canonical bounded machine-code lists and the evaluation asset hash.
 *
 * <p>Every hostile, blank, duplicated, unbounded or foreign value fails closed with one stable invariant, no
 * rejected value reaches diagnostics, both code lists are canonicalized to one deterministic order, every
 * carried fact is immutable, and the type is capability-agnostic: it holds no business-specific constant, no
 * name, body, credential, raw variable, member identifier, SQL or trusted security context.
 */
class CapabilityPlanPreviewCardTest {

    private static final Path SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "capability");
    private static final String SOURCE_FILE = "CapabilityPlanPreviewCard.java";

    // ------------------------------------------------------------------ the bound facts

    @Test
    void aCardBindsEverySafeFactT19SectionTwoNames() {
        CapabilityPlanPreviewCard card = new Draft().build();

        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW).isEqualTo("CAPABILITY_PREVIEW");
        assertThat(card.cardContractVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThat(card.cardContractVersion()).isEqualTo("1.0");
        assertThat(card.componentLogicalId()).isEqualTo("component-a");
        assertThat(card.capabilityCode()).isEqualTo("sample.capability");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(hash("payload"));
        assertThat(card.resolvedCatalogReferenceHash()).isEqualTo(hash("reference"));
        assertThat(card.hasResolvedCatalogReference()).isTrue();
        assertThat(card.runtimeCompatibility())
                .isEqualTo(new RuntimeCompatibility("runtime-schema/1.0", true, false));
        assertThat(card.summaryCodes()).containsExactly("NORMALIZED", "READY");
        assertThat(card.riskCodes()).containsExactly("LOW");
        assertThat(card.evaluationAssetHash()).isEqualTo(hash("evaluation"));
    }

    @Test
    void aCardThatResolvesNoCatalogReferenceCarriesAGenuinelyAbsentSlot() {
        CapabilityPlanPreviewCard card = new Draft().resolvedCatalogReferenceHash(null).build();

        assertThat(card.resolvedCatalogReferenceHash()).isNull();
        assertThat(card.hasResolvedCatalogReference()).isFalse();
        assertThat(card).isNotEqualTo(new Draft().build());
    }

    // ------------------------------------------------------------------ closed card kind and version

    @Test
    void anUnknownOrInventedCardKindFailsClosed() {
        for (String kind : List.of("MESSAGE_PREVIEW", "capability_preview", "CAPABILITY_PREVIEW ", "")) {
            assertInconsistent(() -> new Draft().cardKind(kind).build().cardKind(),
                    "capabilityPreviewCardKind");
        }
        assertInconsistent(() -> new Draft().cardKind(null).build().cardKind(), "capabilityPreviewCardKind");
    }

    @Test
    void anUnknownOrBlankCardContractVersionFailsClosed() {
        for (String version : List.of("1.1", "2.0", "v1.0", "1.0 ", "")) {
            assertInconsistent(() -> new Draft().cardContractVersion(version).build().cardContractVersion(),
                    "capabilityPreviewCardContractVersion");
        }
        assertInconsistent(() -> new Draft().cardContractVersion(null).build().cardContractVersion(),
                "capabilityPreviewCardContractVersion");
    }

    // ------------------------------------------------------------------ identity, capability code, contract version

    @Test
    void aMalformedComponentIdentityFailsClosedWithoutEchoingIt() {
        String hostile = "component-a'; DROP TABLE members--";
        for (String logicalId : List.of(hostile, "", " ", "component a", "component/a", "a".repeat(129))) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().componentLogicalId(logicalId).build().componentLogicalId(),
                    "capabilityPreviewComponent", logicalId);
        }
        assertInconsistent(() -> new Draft().componentLogicalId(null).build().componentLogicalId(),
                "capabilityPreviewComponent");
    }

    @Test
    void aMalformedCapabilityCodeFailsClosedWithoutEchoingIt() {
        String hostile = "message.template'; DROP TABLE members--";
        for (String code : List.of(hostile, "", " ", "Message.Template", "message template", "1message",
                "-message", "message_template", "a".repeat(65))) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().capabilityCode(code).build().capabilityCode(),
                    "capabilityPreviewCapabilityCode", code);
        }
        assertInconsistent(() -> new Draft().capabilityCode(null).build().capabilityCode(),
                "capabilityPreviewCapabilityCode");
    }

    @Test
    void aMalformedContractVersionFailsClosedWithoutEchoingIt() {
        for (String version : List.of("", " ", "1.0 ", "-1", "1.0'; DROP TABLE members--")) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().contractVersion(version).build().contractVersion(),
                    "capabilityPreviewContractVersion", version);
        }
        assertInconsistent(() -> new Draft().contractVersion(null).build().contractVersion(),
                "capabilityPreviewContractVersion");
    }

    // ------------------------------------------------------------------ required pins

    @Test
    void aMissingPayloadRuntimeOrEvaluationAssetHashFailsClosed() {
        assertInconsistent(() -> new Draft().normalizedPayloadHash(null).build().normalizedPayloadHash(),
                "capabilityPreviewPayloadHash");
        assertInconsistent(() -> new Draft().runtimeCompatibility(null).build().runtimeCompatibility(),
                "capabilityPreviewRuntime");
        assertInconsistent(() -> new Draft().evaluationAssetHash(null).build().evaluationAssetHash(),
                "capabilityPreviewEvaluationAsset");
    }

    // ------------------------------------------------------------------ canonical bounded code lists

    @Test
    void bothCodeListsAreCanonicalizedToOneDeterministicOrder() {
        CapabilityPlanPreviewCard card = new Draft()
                .summaryCodes(List.of("RISK_NOTE", "NORMALIZED", "READY"))
                .riskCodes(List.of("MEDIUM", "LOW"))
                .build();

        assertThat(card.summaryCodes()).containsExactly("NORMALIZED", "READY", "RISK_NOTE");
        assertThat(card.riskCodes()).containsExactly("LOW", "MEDIUM");
        assertThat(card).isEqualTo(new Draft()
                .summaryCodes(List.of("NORMALIZED", "READY", "RISK_NOTE"))
                .riskCodes(List.of("LOW", "MEDIUM"))
                .build())
                .hasSameHashCodeAs(card);
    }

    @Test
    void anEmptyCodeListIsCarriedAsGenuinelyEmpty() {
        CapabilityPlanPreviewCard card = new Draft().summaryCodes(List.of()).riskCodes(List.of()).build();

        assertThat(card.summaryCodes()).isEmpty();
        assertThat(card.riskCodes()).isEmpty();
    }

    @Test
    void aNullCodeListFailsClosed() {
        assertInconsistent(() -> new Draft().summaryCodes(null).build().summaryCodes(),
                "capabilityPreviewSummaryCodes");
        assertInconsistent(() -> new Draft().riskCodes(null).build().riskCodes(),
                "capabilityPreviewRiskCodes");
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertInconsistent(() -> new Draft().summaryCodes(withNull).build().summaryCodes(),
                "capabilityPreviewSummaryCodes");
    }

    @Test
    void aBlankOrForeignCodeFailsClosedWithoutEchoingIt() {
        for (String code : List.of("", " ", "ready", "READY ", "READY;DROP", "sha256:" + "a".repeat(64),
                "A".repeat(65))) {
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().summaryCodes(List.of(code)).build().summaryCodes(),
                    "capabilityPreviewSummaryCodes", code);
            assertInconsistentWithoutDisclosure(
                    () -> new Draft().riskCodes(List.of(code)).build().riskCodes(),
                    "capabilityPreviewRiskCodes", code);
        }
    }

    @Test
    void aDuplicatedCodeFailsClosedRatherThanBeingSilentlyDeduplicated() {
        assertInconsistent(() -> new Draft().summaryCodes(List.of("READY", "READY")).build().summaryCodes(),
                "capabilityPreviewSummaryCodes");
        assertInconsistent(() -> new Draft().riskCodes(List.of("LOW", "HIGH", "LOW")).build().riskCodes(),
                "capabilityPreviewRiskCodes");
    }

    @Test
    void anOverBoundCodeListFailsClosedAtExactlyTheBound() {
        assertThat(new Draft().summaryCodes(codes(CapabilityPlanPreviewCard.MAX_SUMMARY_CODES)).build()
                .summaryCodes()).hasSize(CapabilityPlanPreviewCard.MAX_SUMMARY_CODES);
        assertThat(new Draft().riskCodes(codes(CapabilityPlanPreviewCard.MAX_RISK_CODES)).build()
                .riskCodes()).hasSize(CapabilityPlanPreviewCard.MAX_RISK_CODES);

        assertInconsistent(
                () -> new Draft().summaryCodes(codes(CapabilityPlanPreviewCard.MAX_SUMMARY_CODES + 1)).build()
                        .summaryCodes(),
                "capabilityPreviewSummaryCodes");
        assertInconsistent(
                () -> new Draft().riskCodes(codes(CapabilityPlanPreviewCard.MAX_RISK_CODES + 1)).build()
                        .riskCodes(),
                "capabilityPreviewRiskCodes");
    }

    // ------------------------------------------------------------------ immutability and closure

    @Test
    void theCardFreezesEveryCodeListItCarries() {
        List<String> summary = new ArrayList<>(List.of("READY"));
        List<String> risk = new ArrayList<>(List.of("LOW"));
        CapabilityPlanPreviewCard card = new Draft().summaryCodes(summary).riskCodes(risk).build();

        summary.clear();
        risk.clear();

        assertThat(card.summaryCodes()).containsExactly("READY");
        assertThat(card.riskCodes()).containsExactly("LOW");
        assertThatThrownBy(() -> card.summaryCodes().add("NORMALIZED"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> card.riskCodes().add("HIGH"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theCardIsClosedAndExposesNoConstructionOrExecutionEscapeHatch() {
        assertThat(CapabilityPlanPreviewCard.class.isRecord()).isTrue();
        assertThat(Modifier.isFinal(CapabilityPlanPreviewCard.class.getModifiers())).isTrue();
        List<Class<?>> canonicalSignature = Arrays.stream(CapabilityPlanPreviewCard.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
        assertThat(Arrays.stream(CapabilityPlanPreviewCard.class.getDeclaredConstructors())
                .map(constructor -> Arrays.asList(constructor.getParameterTypes())))
                .containsExactlyInAnyOrder(canonicalSignature);
        assertThat(Arrays.stream(CapabilityPlanPreviewCard.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())))
                .as("a card must expose no static factory")
                .isEmpty();
        assertThat(Arrays.stream(CapabilityPlanPreviewCard.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        com.fasterxml.jackson.annotation.JsonCreator.class)))
                .as("a card must expose no deserializer")
                .isEmpty();
        assertThat(Arrays.stream(CapabilityPlanPreviewCard.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("cardKind", "cardContractVersion", "componentLogicalId",
                        "capabilityCode", "contractVersion", "normalizedPayloadHash",
                        "resolvedCatalogReferenceHash", "runtimeCompatibility", "summaryCodes", "riskCodes",
                        "evaluationAssetHash", "hasResolvedCatalogReference", "equals", "hashCode", "toString");
    }

    @Test
    void equalityCoversEveryCarriedFact() {
        CapabilityPlanPreviewCard card = new Draft().build();

        assertThat(new Draft().build()).isEqualTo(card).hasSameHashCodeAs(card);
        assertThat(new Draft().cardKind(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW).build()).isEqualTo(card);
        assertThat(new Draft().componentLogicalId("component-b").build()).isNotEqualTo(card);
        assertThat(new Draft().capabilityCode("sample.other").build()).isNotEqualTo(card);
        assertThat(new Draft().contractVersion("1.1").build()).isNotEqualTo(card);
        assertThat(new Draft().normalizedPayloadHash(hash("other")).build()).isNotEqualTo(card);
        assertThat(new Draft().resolvedCatalogReferenceHash(hash("other")).build()).isNotEqualTo(card);
        assertThat(new Draft().runtimeCompatibility(new RuntimeCompatibility("runtime-schema/2.0", true, false))
                .build()).isNotEqualTo(card);
        assertThat(new Draft().summaryCodes(List.of("READY")).build()).isNotEqualTo(card);
        assertThat(new Draft().riskCodes(List.of()).build()).isNotEqualTo(card);
        assertThat(new Draft().evaluationAssetHash(hash("other")).build()).isNotEqualTo(card);
    }

    // ------------------------------------------------------------------ diagnostics

    @Test
    void theDiagnosticsAreRedacted() {
        CapabilityPlanPreviewCard card = new Draft().build();

        assertThat(card.toString())
                .contains("CAPABILITY_PREVIEW", "sample.capability", "1.0")
                // The component logical identity is the one trusted value the card carries; a diagnostic is a
                // bounded machine fact, so it reports presence and counts, never the identity.
                .doesNotContain("component-a");
    }

    // ------------------------------------------------------------------ capability-agnostic boundary

    @Test
    void theCardHoldsNoBusinessSpecificConstantAndReachesNoForeignPackage() throws Exception {
        String text = Files.readString(SOURCE_ROOT.resolve(SOURCE_FILE), StandardCharsets.UTF_8);

        // No concrete T19 capability code, slot, currency or capability-specific bound is baked into the
        // generic card: the type carries only closed machine codes and hashes.
        for (String businessConstant : List.of("message.template", "promotion.reference", "flow.", "schedule.",
                "governance.", "ISO-4217", "maxRecipients", "rewardFaceValue", "currency")) {
            assertThat(text).as("a generic card must not hard-code %s", businessConstant)
                    .doesNotContain(businessConstant);
        }
        for (String forbidden : List.of("com.company.campaign.builder.authoring", "com.company.campaign.segment",
                "com.company.campaign.preview", "com.company.campaign.evaluation", "com.company.campaign.runtime",
                "com.company.campaign.builder.agent", "com.company.campaign.security", "com.company.campaign.metadata",
                "com.company.campaign.query")) {
            assertThat(text).as("the card must not reach %s", forbidden).doesNotContain(forbidden);
        }
        for (String forbiddenText : List.of("Instant.now", "LocalDate.now", "LocalDateTime.now", "Clock.system",
                "ZoneId.systemDefault", "Locale.getDefault", "System.currentTimeMillis", "Math.random",
                "new Random", "UUID.randomUUID", "System.out", "System.err", "org.slf4j")) {
            assertThat(text).as("the card must not use %s", forbiddenText).doesNotContain(forbiddenText);
        }

        Matcher imports = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE).matcher(text);
        while (imports.find()) {
            String imported = imports.group(1);
            assertThat(imported).as("the card imports %s", imported)
                    .matches("java\\..*|com\\.company\\.campaign\\.contracts\\..*"
                            + "|com\\.company\\.campaign\\.shared\\.canonical\\..*"
                            + "|com\\.company\\.campaign\\.builder\\.compiler\\.(RuntimeCompatibility"
                            + "|CompilerException|CompilerErrorCodes)"
                            + "|com\\.fasterxml\\.jackson\\.annotation\\.JsonInclude");
        }
    }

    @Test
    void everyComponentOfTheCardIsItselfClosedAndSafe() {
        assertThat(CapabilityPlanPreviewCard.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("cardKind", "cardContractVersion", "componentLogicalId", "capabilityCode",
                        "contractVersion", "normalizedPayloadHash", "resolvedCatalogReferenceHash",
                        "runtimeCompatibility", "summaryCodes", "riskCodes", "evaluationAssetHash");

        Set<String> allowed = Set.of("java.lang.String", "java.util.List",
                "com.company.campaign.shared.canonical.ContentHash",
                "com.company.campaign.builder.compiler.RuntimeCompatibility");
        for (RecordComponent component : CapabilityPlanPreviewCard.class.getRecordComponents()) {
            assertThat(allowed).as("component %s", component.getName()).contains(component.getType().getName());
            Type generic = component.getGenericType();
            if (generic instanceof ParameterizedType parameterized) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    assertThat(allowed).as("component %s", component.getName())
                            .contains(((Class<?>) argument).getName());
                }
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A test-only draft of one card, so each test can vary exactly one fact. */
    private static final class Draft {

        private String cardKind = CapabilityPlanPreviewCard.CAPABILITY_PREVIEW;
        private String cardContractVersion = CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION;
        private String componentLogicalId = "component-a";
        private String capabilityCode = "sample.capability";
        private String contractVersion = "1.0";
        private ContentHash normalizedPayloadHash = hash("payload");
        private ContentHash resolvedCatalogReferenceHash = hash("reference");
        private RuntimeCompatibility runtimeCompatibility = new RuntimeCompatibility("runtime-schema/1.0", true, false);
        private List<String> summaryCodes = new ArrayList<>(List.of("READY", "NORMALIZED"));
        private List<String> riskCodes = new ArrayList<>(List.of("LOW"));
        private ContentHash evaluationAssetHash = hash("evaluation");

        Draft cardKind(String value) {
            this.cardKind = value;
            return this;
        }

        Draft cardContractVersion(String value) {
            this.cardContractVersion = value;
            return this;
        }

        Draft componentLogicalId(String value) {
            this.componentLogicalId = value;
            return this;
        }

        Draft capabilityCode(String value) {
            this.capabilityCode = value;
            return this;
        }

        Draft contractVersion(String value) {
            this.contractVersion = value;
            return this;
        }

        Draft normalizedPayloadHash(ContentHash value) {
            this.normalizedPayloadHash = value;
            return this;
        }

        Draft resolvedCatalogReferenceHash(ContentHash value) {
            this.resolvedCatalogReferenceHash = value;
            return this;
        }

        Draft runtimeCompatibility(RuntimeCompatibility value) {
            this.runtimeCompatibility = value;
            return this;
        }

        Draft summaryCodes(List<String> value) {
            this.summaryCodes = value;
            return this;
        }

        Draft riskCodes(List<String> value) {
            this.riskCodes = value;
            return this;
        }

        Draft evaluationAssetHash(ContentHash value) {
            this.evaluationAssetHash = value;
            return this;
        }

        CapabilityPlanPreviewCard build() {
            return new CapabilityPlanPreviewCard(cardKind, cardContractVersion, componentLogicalId,
                    capabilityCode, contractVersion, normalizedPayloadHash, resolvedCatalogReferenceHash,
                    runtimeCompatibility, summaryCodes, riskCodes, evaluationAssetHash);
        }
    }

    /** One bounded list of distinct uppercase machine codes, exactly {@code size} long. */
    private static List<String> codes(int size) {
        List<String> codes = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            codes.add("CODE_" + index);
        }
        return codes;
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInconsistent(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    assertThat(failure.getCause()).isNull();
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
                        return;
                    }
                    assertThat(failure.getMessage()).doesNotContain(rejectedValue);
                    assertThat(failure.details().values()).allSatisfy(detail ->
                            assertThat(String.valueOf(detail)).doesNotContain(rejectedValue));
                });
    }
}