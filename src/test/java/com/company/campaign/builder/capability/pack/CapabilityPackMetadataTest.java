package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The common capability pack metadata binds exactly the frozen, machine-readable facts T19 section 1 names:
 * the canonical capability code, the pack, contract and schema versions, the composition slot, the safe
 * preview-card kind and version, the runtime compatibility, the closed typed payload contract and the
 * versioned evaluation asset — with one deterministic canonical byte form and content hash.
 *
 * <p>The type is capability-agnostic: it hard-codes no concrete capability, slot, field, bound or currency,
 * and it reaches no foreign package. Unknown kinds, blank or unbounded declarations, inconsistent pins and
 * any executable, credential, provider or trusted-authority content fail closed.
 */
class CapabilityPackMetadataTest {

    private static final Path SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "capability", "pack");
    private static final String SOURCE_FILE = "CapabilityPackMetadata.java";

    // ------------------------------------------------------------------ the bound facts

    @Test
    void theMetadataBindsEveryMachineReadableFactT19SectionOneNames() {
        CapabilityPackMetadata metadata = metadata();

        assertThat(metadata.capabilityCode()).isEqualTo("sample.capability");
        assertThat(metadata.packVersion()).isEqualTo("t19-v1");
        assertThat(metadata.contractVersion()).isEqualTo("1.0");
        assertThat(metadata.schemaVersion()).isEqualTo(SchemaVersion.parse("1.0"));
        assertThat(metadata.previewCardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(metadata.previewCardVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThat(metadata.slot()).isEqualTo(new CompositionSlot("campaign.sample", false, false));
        assertThat(metadata.runtimeCompatibility())
                .isEqualTo(new RuntimeCompatibility("runtime-schema/1.0", true, false));
        assertThat(metadata.payloadContract().requiredNames()).containsExactly("referenceCode");
        assertThat(metadata.evaluationAsset().assetVersion()).isEqualTo("1.0");
        assertThat(metadata.evaluationAsset().contentHash()).isEqualTo(hash("asset"));
    }

    // ------------------------------------------------------------------ canonical bytes and hash

    @Test
    void theMetadataHasOneDeterministicCanonicalFormAndHash() {
        CapabilityPackMetadata first = metadata();
        CapabilityPackMetadata second = metadata();

        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
        assertThat(first.packHash()).isEqualTo(second.packHash());
        assertThat(first.packHash()).isEqualTo(first.recomputePackHash());
        assertThat(first.canonicalBytes()).isEqualTo(first.withPackHash(null).canonicalBytes());
        assertThatCode(first::requireValidPackHash).doesNotThrowAnyException();
    }

    @Test
    void theHashCoversEveryDeclaredFact() {
        ContentHash hash = metadata().recomputePackHash();

        assertThat(metadataWith().capabilityCode("sample.other").build().recomputePackHash()).isNotEqualTo(hash);
        assertThat(metadataWith().packVersion("t19-v2").build().recomputePackHash()).isNotEqualTo(hash);
        assertThat(metadataWith().contractVersion("1.1").build().recomputePackHash()).isNotEqualTo(hash);
        assertThat(metadataWith().schemaVersion(SchemaVersion.parse("2.0")).build().recomputePackHash())
                .isNotEqualTo(hash);
        assertThat(metadataWith().previewCardVersion("1.0").build().recomputePackHash()).isEqualTo(hash);
        assertThat(metadataWith().slot(new CompositionSlot("campaign.other", false, false)).build()
                .recomputePackHash()).isNotEqualTo(hash);
        assertThat(metadataWith().runtimeCompatibility(new RuntimeCompatibility("runtime-schema/2.0", true, false))
                .build().recomputePackHash()).isNotEqualTo(hash);
    }

    @Test
    void anAbsentOrInconsistentHashPinFailsClosed() {
        assertInvalid(() -> metadata().withPackHash(null).requireValidPackHash(), "capabilityPackHash");
        assertInvalid(() -> metadata().withPackHash(hash("tampered")).requireValidPackHash(), "capabilityPackHash");
    }

    // ------------------------------------------------------------------ closed card kind and version

    @Test
    void anUnknownOrInventedPreviewCardKindOrVersionFailsClosed() {
        assertInvalid(() -> metadataWith().previewCardKind("MESSAGE_PREVIEW").build(), "capabilityPackPreviewCardKind");
        assertInvalid(() -> metadataWith().previewCardKind(null).build(), "capabilityPackPreviewCardKind");
        assertInvalid(() -> metadataWith().previewCardVersion("1.1").build(), "capabilityPackPreviewCardVersion");
        assertInvalid(() -> metadataWith().previewCardVersion(null).build(), "capabilityPackPreviewCardVersion");
    }

    // ------------------------------------------------------------------ version and identity pins

    @Test
    void aMalformedCapabilityCodeFailsClosed() {
        for (String code : List.of("Message.Template", "message template", "1message", "-message",
                "message_template", "", "a".repeat(65))) {
            assertInvalid(() -> metadataWith().capabilityCode(code).build(), "capabilityPackCode");
        }
        assertInvalid(() -> metadataWith().capabilityCode(null).build(), "capabilityPackCode");
    }

    @Test
    void aMalformedPackOrContractVersionFailsClosed() {
        for (String version : List.of("", " ", "1.0 ", "-1")) {
            assertInvalid(() -> metadataWith().packVersion(version).build(), "capabilityPackVersion");
            assertInvalid(() -> metadataWith().contractVersion(version).build(), "capabilityPackContractVersion");
        }
        assertInvalid(() -> metadataWith().packVersion(null).build(), "capabilityPackVersion");
        assertInvalid(() -> metadataWith().contractVersion(null).build(), "capabilityPackContractVersion");
    }

    @Test
    void aMissingRequiredComponentFailsClosed() {
        assertInvalid(() -> metadataWith().schemaVersion(null).build(), "capabilityPackSchemaVersion");
        assertInvalid(() -> metadataWith().slot(null).build(), "capabilityPackSlot");
        assertInvalid(() -> metadataWith().runtimeCompatibility(null).build(), "capabilityPackRuntime");
        assertInvalid(() -> metadataWith().payloadContract(null).build(), "capabilityPackPayloadContract");
        assertInvalid(() -> metadataWith().evaluationAsset(null).build(), "capabilityPackEvaluationAsset");
    }

    // ------------------------------------------------------------------ the narrow CapabilityContract extension

    @Test
    void thePackContractIsANarrowExtensionOfTheAcceptedCapabilityContract() throws Exception {
        assertThat(CapabilityContract.class.isAssignableFrom(CapabilityPackContract.class)).isTrue();
        assertThat(CapabilityPackContract.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("packMetadata");
        Method packMetadata = CapabilityPackContract.class.getDeclaredMethod("packMetadata");
        assertThat(packMetadata.getReturnType()).isEqualTo(CapabilityPackMetadata.class);
        assertThat(Modifier.isAbstract(packMetadata.getModifiers())).isTrue();
    }

    // ------------------------------------------------------------------ closure and immutability

    @Test
    void theMetadataIsAClosedRecordOverSafeComponentTypes() {
        assertThat(CapabilityPackMetadata.class.isRecord()).isTrue();
        assertThat(Modifier.isFinal(CapabilityPackMetadata.class.getModifiers())).isTrue();
        assertThat(CapabilityPackMetadata.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("capabilityCode", "packVersion", "contractVersion", "schemaVersion",
                        "previewCardKind", "previewCardVersion", "slot", "runtimeCompatibility",
                        "payloadContract", "evaluationAsset", "packHash");

        Set<String> allowed = Set.of("java.lang.String", "java.util.List",
                "com.company.campaign.contracts.SchemaVersion",
                "com.company.campaign.builder.compiler.CompositionSlot",
                "com.company.campaign.builder.compiler.RuntimeCompatibility",
                "com.company.campaign.builder.capability.pack.CapabilityPayloadContract",
                "com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset",
                "com.company.campaign.shared.canonical.ContentHash");
        for (RecordComponent component : CapabilityPackMetadata.class.getRecordComponents()) {
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

    @Test
    void equalityCoversEveryDeclaredFact() {
        CapabilityPackMetadata metadata = metadata();

        assertThat(metadata).isEqualTo(metadata()).hasSameHashCodeAs(metadata);
        assertThat(metadataWith().capabilityCode("sample.other").build()).isNotEqualTo(metadata);
        assertThat(metadataWith().packVersion("t19-v2").build()).isNotEqualTo(metadata);
        assertThat(metadataWith().contractVersion("1.1").build()).isNotEqualTo(metadata);
        assertThat(metadataWith().schemaVersion(SchemaVersion.parse("2.0")).build()).isNotEqualTo(metadata);
        assertThat(metadataWith().slot(new CompositionSlot("campaign.other", false, false)).build())
                .isNotEqualTo(metadata);
        assertThat(metadataWith().runtimeCompatibility(new RuntimeCompatibility("runtime-schema/2.0", true, false))
                .build()).isNotEqualTo(metadata);
    }

    @Test
    void theDiagnosticsReportMachineFactsOnly() {
        CapabilityPackMetadata metadata = metadata();

        assertThat(metadata.toString())
                .contains("sample.capability", "t19-v1", "1.0")
                .doesNotContain("referenceCode", "fixture-message-valid");
    }

    // ------------------------------------------------------------------ capability-agnostic boundary

    @Test
    void theMetadataHoldsNoBusinessConstantAndReachesNoForeignPackage() throws Exception {
        String text = Files.readString(SOURCE_ROOT.resolve(SOURCE_FILE), StandardCharsets.UTF_8);

        for (String businessConstant : List.of("message.template", "promotion.reference", "flow.", "schedule.",
                "governance.", "ISO-4217", "maxRecipients", "rewardFaceValue", "currency", "campaign.flow")) {
            assertThat(text).as("generic metadata must not hard-code %s", businessConstant)
                    .doesNotContain(businessConstant);
        }
        for (String forbidden : List.of("com.company.campaign.builder.authoring", "com.company.campaign.segment",
                "com.company.campaign.preview", "com.company.campaign.evaluation", "com.company.campaign.runtime",
                "com.company.campaign.builder.agent", "com.company.campaign.security",
                "com.company.campaign.metadata", "com.company.campaign.query", "com.company.campaign.builder.proposal")) {
            assertThat(text).as("the metadata must not reach %s", forbidden).doesNotContain(forbidden);
        }
        for (String forbiddenText : List.of("Instant.now", "LocalDate.now", "LocalDateTime.now", "Clock.system",
                "ZoneId.systemDefault", "Locale.getDefault", "System.currentTimeMillis", "Math.random",
                "new Random", "UUID.randomUUID", "System.out", "System.err", "org.slf4j")) {
            assertThat(text).as("the metadata must not use %s", forbiddenText).doesNotContain(forbiddenText);
        }

        Matcher imports = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE).matcher(text);
        while (imports.find()) {
            String imported = imports.group(1);
            assertThat(imported).as("the metadata imports %s", imported)
                    .matches("java\\..*"
                            + "|com\\.company\\.campaign\\.builder\\.capability\\..*"
                            + "|com\\.company\\.campaign\\.builder\\.compiler\\.(CapabilityContract"
                            + "|CompositionSlot|RuntimeCompatibility)"
                            + "|com\\.company\\.campaign\\.contracts\\.SchemaVersion"
                            + "|com\\.company\\.campaign\\.shared\\.canonical\\..*");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static CapabilityPackMetadata metadata() {
        return metadataWith().build();
    }

    private static Draft metadataWith() {
        return new Draft();
    }

    /** A test-only draft of one metadata, so each test can vary exactly one fact. */
    private static final class Draft {

        private String capabilityCode = "sample.capability";
        private String packVersion = "t19-v1";
        private String contractVersion = "1.0";
        private SchemaVersion schemaVersion = SchemaVersion.parse("1.0");
        private String previewCardKind = CapabilityPlanPreviewCard.CAPABILITY_PREVIEW;
        private String previewCardVersion = CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION;
        private CompositionSlot slot = new CompositionSlot("campaign.sample", false, false);
        private RuntimeCompatibility runtimeCompatibility = new RuntimeCompatibility("runtime-schema/1.0", true, false);
        private CapabilityPayloadContract payloadContract = new CapabilityPayloadContract(
                List.of(new CapabilityFieldSchema("referenceCode", new CapabilityFieldConstraint.Text(128))),
                List.of());
        private CapabilityEvaluationAsset evaluationAsset = new CapabilityEvaluationAsset(
                "t19-evaluation-v1", "1.0", hash("asset"),
                List.of(new CapabilityEvaluationFixture("fixture-message-valid", Expectation.ACCEPTED,
                        hash("payload-valid"))),
                List.of(new CapabilityEvaluationFixture("fixture-message-unknown", Expectation.REJECTED,
                        hash("payload-unknown"))));

        Draft capabilityCode(String value) {
            this.capabilityCode = value;
            return this;
        }

        Draft packVersion(String value) {
            this.packVersion = value;
            return this;
        }

        Draft contractVersion(String value) {
            this.contractVersion = value;
            return this;
        }

        Draft schemaVersion(SchemaVersion value) {
            this.schemaVersion = value;
            return this;
        }

        Draft previewCardKind(String value) {
            this.previewCardKind = value;
            return this;
        }

        Draft previewCardVersion(String value) {
            this.previewCardVersion = value;
            return this;
        }

        Draft slot(CompositionSlot value) {
            this.slot = value;
            return this;
        }

        Draft runtimeCompatibility(RuntimeCompatibility value) {
            this.runtimeCompatibility = value;
            return this;
        }

        Draft payloadContract(CapabilityPayloadContract value) {
            this.payloadContract = value;
            return this;
        }

        Draft evaluationAsset(CapabilityEvaluationAsset value) {
            this.evaluationAsset = value;
            return this;
        }

        CapabilityPackMetadata build() {
            return CapabilityPackMetadata.of(capabilityCode, packVersion, contractVersion, schemaVersion,
                    previewCardKind, previewCardVersion, slot, runtimeCompatibility, payloadContract,
                    evaluationAsset);
        }
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    assertThat(failure.getCause()).isNull();
                });
    }
}