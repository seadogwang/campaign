package com.company.campaign.contracts;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Agent, Skill and Tool envelopes.
 *
 * <p>Covers requirement 12 (a Tool payload cannot carry trusted context or host-only command fields) and
 * requirement 14 (result and failure envelopes serialize deterministically).
 */
class ToolContractTest {

    private static final String DENIED_MESSAGE = "Not permitted for this DataScope";
    private static final String DENIED_ISSUE =
            "{\"code\":\"DENIED\",\"message\":\"" + DENIED_MESSAGE + "\",\"severity\":\"ERROR\"}";

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    // The Tool request envelope must not be able to substitute a permissive payload type. If a type
    // parameter is ever reintroduced, ToolEnvelope<Map<String, Object>> would again bypass ToolInput.
    @Test
    void toolEnvelopeFixesItsPayloadTypeSoNoPermissiveSubstituteIsExpressible() {
        Map<String, Class<?>> componentTypes = new LinkedHashMap<>();
        for (RecordComponent component : ToolEnvelope.class.getRecordComponents()) {
            componentTypes.put(component.getName(), component.getType());
        }

        assertThat(ToolEnvelope.class.getTypeParameters())
                .as("ToolEnvelope type parameters")
                .isEmpty();
        assertThat(componentTypes)
                .containsEntry("payload", ToolInput.class)
                .containsEntry("schemaVersion", SchemaVersion.class);
    }

    // Requirement 12: every trusted context field name is refused as Tool input.
    @Test
    void everyTrustedContextFieldNameIsReservedInToolInput() {
        RecordComponent[] components = TrustedExecutionContextRef.class.getRecordComponents();
        assertThat(components).isNotEmpty();

        for (RecordComponent component : components) {
            String fieldName = component.getName();
            ContractAssertions.assertFailsWithCode(
                    () -> new ToolInput(Map.of(fieldName, "forged")),
                    ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
        }
    }

    // Requirement 12: case and separator variants cannot smuggle a reserved name through.
    @ParameterizedTest
    @ValueSource(strings = {
            "tenantId", "tenant_id", "TENANT-ID", "tenant.id", "TenantID",
            "programId", "program_id", "userId", "user_id", "userName",
            "dataScopeId", "data_scope_id", "DataScope", "scope",
            "roles", "role", "permissions", "authorities", "principal",
            "securityContext", "trustedContext",
            "locale", "timezone", "reportingCurrency", "metadataSnapshotId", "asOf", "dataWatermark",
            "apply", "save", "publish", "pause", "resume", "retry", "archive",
    })
    void reservedFieldNameAliasesAreRejected(String reservedName) {
        ContractAssertions.assertFailsWithCode(
                () -> new ToolInput(Map.of(reservedName, "value")),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
    }

    // Requirement 12: the refusal applies at any nesting depth, not only at the top level.
    @Test
    void reservedFieldNamesAreRejectedAtAnyDepth() {
        ContractAssertions.assertFailsWithCode(
                () -> new ToolInput(Map.of("filter", Map.of("userId", "forged"))),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> new ToolInput(Map.of("items", List.of(Map.of("programId", "forged")))),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> new ToolInput(Map.of("a", Map.of("b", List.of(Map.of("data_scope", "forged"))))),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
    }

    @Test
    void reservedFieldRefusalNamesTheFieldAndItsPath() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> new ToolInput(Map.of("filter", Map.of("tenantId", "forged"))));

        assertThat(thrown).isInstanceOf(ToolInputRejectedException.class);
        assertThat(((ToolInputRejectedException) thrown).details())
                .containsEntry("field", "tenantId")
                .containsEntry("path", "$.filter");
    }

    // Requirement 12: the refusal also applies when the payload arrives as JSON.
    @Test
    void reservedFieldNamesAreRejectedDuringDeserialization() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("{\"tenantId\":\"forged\"}", ToolInput.class),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"selector\":{\"dataScopeId\":\"forged\"}}", ToolInput.class),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"dryRun\":true,\"payload\":{\"publish\":true}}",
                        ToolEnvelope.class),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
    }

    // Regression for the generic-payload bypass: a trusted field nested inside an envelope payload must
    // still be refused, which only holds while the envelope binds its payload to ToolInput.
    @Test
    void envelopePayloadCarryingANestedTrustedFieldIsRejectedDuringDeserialization() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"dryRun\":false,\"payload\":{\"selector\":{\"tenantId\":\"forged\"}}}",
                        ToolEnvelope.class),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);

        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"dryRun\":false,\"payload\":{\"items\":[{\"userId\":\"forged\"}]}}",
                        ToolEnvelope.class),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);

        // A trusted field directly under the payload is refused too.
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"dryRun\":false,\"payload\":{\"dataScopeId\":\"forged\"}}",
                        ToolEnvelope.class),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
    }

    @Test
    void envelopePayloadAcceptsLegitimateArgumentsAndRejectsAnyOtherPayloadShape() {
        ToolEnvelope envelope = canonicalJson.read(
                "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                        + "\"dryRun\":false,\"payload\":{\"value\":\"tier\"}}",
                ToolEnvelope.class);

        assertThat(envelope.payload().arguments()).containsEntry("value", "tier");

        // A scalar payload is not a ToolInput argument object.
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"t\","
                                + "\"dryRun\":false,\"payload\":\"not-an-object\"}",
                        ToolEnvelope.class),
                ContractErrorCodes.CONTRACT_INVALID_VALUE);
    }

    @Test
    void legitimateToolInputIsAcceptedAndCanonicalized() {
        ToolInput input = canonicalJson.read("{\"value\":\"tier\",\"limit\":5,\"queryKind\":\"ATTRIBUTE\"}", ToolInput.class);

        assertThat(input.arguments())
                .containsEntry("value", "tier")
                .containsEntry("limit", 5);
        assertThat(canonicalJson.writeString(input))
                .isEqualTo("{\"limit\":5,\"queryKind\":\"ATTRIBUTE\",\"value\":\"tier\"}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolInputArgumentsAreDeeplyImmutable() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("value", "tier");
        List<String> items = new ArrayList<>(List.of("a"));
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("selector", nested);
        arguments.put("items", items);

        ToolInput input = new ToolInput(arguments);

        arguments.put("injected", "after");
        nested.put("injected", "after");
        items.add("b");

        Map<String, Object> frozenSelector = (Map<String, Object>) input.arguments().get("selector");
        List<String> frozenItems = (List<String>) input.arguments().get("items");

        assertThat(input.arguments()).doesNotContainKey("injected");
        assertThat(frozenSelector).doesNotContainKey("injected").containsEntry("value", "tier");
        assertThat(frozenItems).containsExactly("a");
        assertThatThrownBy(() -> input.arguments().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozenSelector.put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozenItems.add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Envelopes are model-facing and must never carry trusted identity.
    @Test
    void envelopesCarryNoTrustedContextFields() {
        for (Class<?> envelope : List.of(AgentEnvelope.class, SkillEnvelope.class, ToolEnvelope.class)) {
            assertThat(envelope.getRecordComponents())
                    .as("%s components", envelope.getSimpleName())
                    .noneMatch(component -> List.of(
                                    "tenantId", "programId", "userId", "dataScopeId",
                                    "metadataSnapshotId", "asOf", "dataWatermark")
                            .contains(component.getName()));
        }
    }

    @Test
    void toolEnvelopeRoundTripsWithATypedInput() {
        String json = "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                + "\"dryRun\":true,\"payload\":{\"value\":\"tier\"}}";

        ToolEnvelope envelope = canonicalJson.read(json, ToolEnvelope.class);

        assertThat(envelope.schemaVersion()).isEqualTo(ContractVersions.V1_3);
        assertThat(envelope.dryRun()).isTrue();
        assertThat(envelope.payload().arguments()).containsEntry("value", "tier");
        assertThat(canonicalJson.writeString(envelope)).isEqualTo(
                "{\"dryRun\":true,\"payload\":{\"value\":\"tier\"},\"schemaVersion\":\"1.3\","
                        + "\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\"}");
    }

    @Test
    void envelopesRejectUnknownFields() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"t\","
                                + "\"dryRun\":false,\"payload\":{},\"tenantId\":\"forged\"}",
                        ToolEnvelope.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    @Test
    void envelopesAreValidated() {
        assertThat(validator.validate(new ToolEnvelope(
                ContractVersions.V1_3, "call-1", "list_attributes", true, ToolInput.empty()))).isEmpty();
        assertThat(validator.validate(new ToolEnvelope(
                ContractVersions.V1_3, " ", "list_attributes", true, ToolInput.empty()))).isNotEmpty();
        assertThat(validator.validate(new AgentEnvelope<>(
                ContractVersions.V1_3, "run-1", "conversation-1", ToolInput.empty()))).isEmpty();
        assertThat(validator.validate(new AgentEnvelope<>(
                ContractVersions.V1_3, "", "conversation-1", ToolInput.empty()))).isNotEmpty();
        assertThat(validator.validate(new SkillEnvelope<>(
                ContractVersions.V1_3, "invocation-1", "segment.authoring", "1.0.0", ToolInput.empty()))).isEmpty();
        assertThat(validator.validate(new SkillEnvelope<>(
                ContractVersions.V1_3, "invocation-1", "segment.authoring", " ", ToolInput.empty()))).isNotEmpty();
    }

    @Test
    void envelopesRejectUnsupportedSchemaVersionsAtTheCallSite() {
        ToolEnvelope envelope = new ToolEnvelope(
                SchemaVersion.parse("1.2"), "call-1", "list_attributes", true, ToolInput.empty());

        ContractAssertions.assertFailsWithCode(
                () -> SchemaCompatibility.requireCurrent(envelope.schemaVersion()),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
    }

    // Requirement 14: a resolved result envelope is byte-for-byte reproducible.
    @Test
    void resolvedResultEnvelopeSerializesDeterministically() {
        ContentHash inputHash = contentHash("{\"value\":\"tier\"}");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", "tier");

        ToolResultEnvelope<Map<String, Object>> envelope =
                ToolResultEnvelope.resolved("tool-call-1", "list_attributes", result, inputHash, List.of());

        String expected = "{\"disposition\":\"RESOLVED\","
                + "\"inputHash\":\"" + inputHash.value() + "\","
                + "\"issues\":[],"
                + "\"result\":{\"query\":\"tier\"},"
                + "\"schemaVersion\":\"1.3\","
                + "\"toolCallId\":\"tool-call-1\","
                + "\"toolId\":\"list_attributes\"}";

        assertThat(canonicalJson.writeString(envelope)).isEqualTo(expected);
        assertThat(canonicalJson.writeString(envelope)).isEqualTo(expected);
        assertThat(canonicalJson.hash(envelope)).isEqualTo(canonicalJson.hash(
                ToolResultEnvelope.resolved("tool-call-1", "list_attributes",
                        Map.of("query", "tier"), inputHash, List.of())));
    }

    // Requirement 14: a failure envelope is byte-for-byte reproducible and omits no reason.
    @Test
    void failureEnvelopeSerializesDeterministically() {
        ContentHash inputHash = contentHash("{\"publish\":true}");
        ToolFailure failure = new ToolFailure(
                "DENIED", DENIED_MESSAGE, false, List.of(Issue.error("DENIED", DENIED_MESSAGE)));

        ToolResultEnvelope<Map<String, Object>> envelope = ToolResultEnvelope.unresolved(
                "tool-call-2", "list_attributes", Disposition.DENIED, failure, inputHash);

        String expected = "{\"disposition\":\"DENIED\","
                + "\"failure\":{\"code\":\"DENIED\",\"issues\":[" + DENIED_ISSUE + "],"
                + "\"message\":\"" + DENIED_MESSAGE + "\",\"retryable\":false},"
                + "\"inputHash\":\"" + inputHash.value() + "\","
                + "\"issues\":[" + DENIED_ISSUE + "],"
                + "\"schemaVersion\":\"1.3\","
                + "\"toolCallId\":\"tool-call-2\","
                + "\"toolId\":\"list_attributes\"}";

        assertThat(canonicalJson.writeString(envelope)).isEqualTo(expected);
        assertThat(canonicalJson.writeString(envelope)).isEqualTo(expected);
    }

    @Test
    void everyNonResolvedDispositionCarriesAFailureAndNoResult() {
        ContentHash inputHash = contentHash("{}");
        ToolFailure failure = ToolFailure.of("CODE", "Reason");

        for (Disposition disposition : Disposition.values()) {
            if (disposition.isResolved()) {
                continue;
            }
            ToolResultEnvelope<Map<String, Object>> envelope =
                    ToolResultEnvelope.unresolved("call", "tool", disposition, failure, inputHash);

            assertThat(envelope.result()).isNull();
            assertThat(envelope.failure()).isNotNull();
            assertThat(envelope.disposition()).isEqualTo(disposition);
            assertThat(canonicalJson.writeString(envelope)).contains("\"disposition\":\"" + disposition.name() + "\"");
        }
    }

    // Requirement 14 again, from the other side: a contradictory envelope must not be constructible.
    @Test
    void resultEnvelopeInvariantIsEnforced() {
        ContentHash inputHash = contentHash("{}");
        ToolFailure failure = ToolFailure.of("CODE", "Reason");

        ContractAssertions.assertFailsWithCode(
                () -> new ToolResultEnvelope<>(
                        ContractVersions.V1_3, "call", "tool", Disposition.RESOLVED,
                        null, null, inputHash, List.of()),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);

        ContractAssertions.assertFailsWithCode(
                () -> new ToolResultEnvelope<>(
                        ContractVersions.V1_3, "call", "tool", Disposition.RESOLVED,
                        Map.of("partial", true), failure, inputHash, List.of()),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);

        ContractAssertions.assertFailsWithCode(
                () -> new ToolResultEnvelope<>(
                        ContractVersions.V1_3, "call", "tool", Disposition.DENIED,
                        Map.of("partial", true), failure, inputHash, List.of()),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);

        ContractAssertions.assertFailsWithCode(
                () -> new ToolResultEnvelope<>(
                        ContractVersions.V1_3, "call", "tool", Disposition.INVALID,
                        null, null, inputHash, List.of()),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);
    }

    // The documented rule that DENIED, UNSUPPORTED and INVALID failures are never retryable is enforced.
    @Test
    void retryableFailuresAreRejectedUnderDeterministicDispositions() {
        ContentHash inputHash = contentHash("{}");
        List<Issue> issues = List.of(Issue.error("CODE", "Reason"));
        ToolFailure retryable = new ToolFailure("CODE", "Reason", true, issues);

        for (Disposition disposition : List.of(Disposition.DENIED, Disposition.UNSUPPORTED, Disposition.INVALID)) {
            Throwable thrown = catchThrowable(
                    () -> ToolResultEnvelope.unresolved("call", "tool", disposition, retryable, inputHash));

            assertThat(thrown)
                    .as("retryable failure under %s", disposition)
                    .isInstanceOf(ToolResultInvariantException.class);
            assertThat(((ToolResultInvariantException) thrown).code())
                    .isEqualTo(ContractErrorCodes.TOOL_RESULT_INVARIANT);
            assertThat(((ToolResultInvariantException) thrown).details())
                    .containsEntry("invariant",
                            ToolResultInvariantException.RETRYABLE_FORBIDDEN_FOR_DISPOSITION)
                    .containsEntry("disposition", disposition.name());
        }

        // The same combination is refused when it arrives as JSON rather than as a constructor call.
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"disposition\":\"UNSUPPORTED\","
                                + "\"failure\":{\"code\":\"UNSUPPORTED\",\"message\":\"Reason\",\"retryable\":true,"
                                + "\"issues\":[]},"
                                + "\"inputHash\":\"" + contentHash("{}").value() + "\",\"issues\":[]}",
                        ToolResultEnvelope.class),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);
    }

    @Test
    void onlyNeedsInputAllowsARetryableFailure() {
        assertThat(Disposition.NEEDS_INPUT.allowsRetryableFailure()).isTrue();
        assertThat(Disposition.RESOLVED.allowsRetryableFailure()).isFalse();
        assertThat(Disposition.UNSUPPORTED.allowsRetryableFailure()).isFalse();
        assertThat(Disposition.DENIED.allowsRetryableFailure()).isFalse();
        assertThat(Disposition.INVALID.allowsRetryableFailure()).isFalse();

        ToolFailure awaitingInput = new ToolFailure("NEEDS_INPUT", "Choose a tier", true, List.of());
        ToolResultEnvelope<Map<String, Object>> envelope = ToolResultEnvelope.unresolved(
                "call", "tool", Disposition.NEEDS_INPUT, awaitingInput, contentHash("{}"));

        assertThat(envelope.failure().retryable()).isTrue();
        assertThat(envelope.result()).isNull();
    }

    // The failure is the single authoritative issue source for a non-resolved result.
    @Test
    void nonResolvedEnvelopeIssuesMustEqualTheFailureIssues() {
        ContentHash inputHash = contentHash("{}");
        ToolFailure failure = new ToolFailure(
                "DENIED", DENIED_MESSAGE, false, List.of(Issue.error("DENIED", DENIED_MESSAGE)));

        ToolResultEnvelope<Map<String, Object>> agreeing = new ToolResultEnvelope<>(
                ContractVersions.V1_3, "call", "tool", Disposition.DENIED,
                null, failure, inputHash, failure.issues());
        assertThat(agreeing.issues()).isEqualTo(failure.issues()).hasSize(1);

        // An empty list and a different list are both contradictions, even when the failure has no issues.
        Throwable emptied = catchThrowable(() -> new ToolResultEnvelope<>(
                ContractVersions.V1_3, "call", "tool", Disposition.DENIED,
                null, failure, inputHash, List.of()));
        assertThat(emptied).isInstanceOf(ToolResultInvariantException.class);
        assertThat(((ToolResultInvariantException) emptied).details())
                .containsEntry("invariant", ToolResultInvariantException.ISSUES_DISAGREE_WITH_FAILURE);

        ContractAssertions.assertFailsWithCode(
                () -> new ToolResultEnvelope<>(
                        ContractVersions.V1_3, "call", "tool", Disposition.DENIED,
                        null, failure, inputHash, List.of(Issue.error("OTHER", "Different"))),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);

        // A failure with no issues must be paired with an empty envelope list, which is what it derives.
        ToolFailure silent = new ToolFailure("DENIED", DENIED_MESSAGE, false, List.of());
        assertThat(new ToolResultEnvelope<>(
                ContractVersions.V1_3, "call", "tool", Disposition.DENIED,
                null, silent, inputHash, List.of()).issues()).isEmpty();
    }

    @Test
    void unresolvedFactoryDerivesTopLevelIssuesFromTheFailure() {
        ToolFailure failure = new ToolFailure(
                "DENIED", DENIED_MESSAGE, false, List.of(Issue.error("DENIED", DENIED_MESSAGE)));

        ToolResultEnvelope<Map<String, Object>> envelope = ToolResultEnvelope.unresolved(
                "call", "tool", Disposition.DENIED, failure, contentHash("{}"));

        assertThat(envelope.issues()).isEqualTo(failure.issues()).hasSize(1);
        assertThat(canonicalJson.writeString(envelope)).contains(
                "\"issues\":[{\"code\":\"DENIED\",\"message\":\"" + DENIED_MESSAGE + "\",\"severity\":\"ERROR\"}]");
    }

    // The result envelope stays generic because a Tool's result shape is tool-specific. Reading it through a
    // parameterized type must keep that shape rather than erasing the payload to an untyped map.
    @Test
    void genericResultEnvelopeIsReadThroughItsTypeReference() {
        String inputHash = contentHash("{}").value();

        ToolResultEnvelope<Map<String, Object>> envelope = canonicalJson.read(
                "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                        + "\"disposition\":\"RESOLVED\",\"result\":{\"query\":\"tier\",\"limit\":5},"
                        + "\"inputHash\":\"" + inputHash + "\",\"issues\":[]}",
                new TypeReference<ToolResultEnvelope<Map<String, Object>>>() {
                });

        assertThat(envelope.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(envelope.result()).containsEntry("query", "tier").containsEntry("limit", 5);
        assertThat(envelope.failure()).isNull();

        // Deserialization goes through the same constructor, so the invariants hold for JSON too.
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"disposition\":\"RESOLVED\",\"result\":{\"query\":\"tier\"},"
                                + "\"failure\":{\"code\":\"X\",\"message\":\"Y\",\"retryable\":false,\"issues\":[]},"
                                + "\"inputHash\":\"" + inputHash + "\",\"issues\":[]}",
                        new TypeReference<ToolResultEnvelope<Map<String, Object>>>() {
                        }),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);
    }

    @Test
    void contradictoryResultEnvelopeIsRejectedDuringDeserialization() {
        String inputHash = contentHash("{}").value();

        // Retryable under a deterministic disposition, and the envelope list disagreeing with the failure.
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"disposition\":\"DENIED\","
                                + "\"failure\":{\"code\":\"DENIED\",\"message\":\"Reason\",\"retryable\":true,"
                                + "\"issues\":[]},"
                                + "\"inputHash\":\"" + inputHash + "\",\"issues\":[]}",
                        ToolResultEnvelope.class),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);

        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(
                        "{\"schemaVersion\":\"1.3\",\"toolCallId\":\"call-1\",\"toolId\":\"list_attributes\","
                                + "\"disposition\":\"DENIED\","
                                + "\"failure\":{\"code\":\"DENIED\",\"message\":\"Reason\",\"retryable\":false,"
                                + "\"issues\":[{\"code\":\"DENIED\",\"message\":\"Reason\",\"severity\":\"ERROR\"}]},"
                                + "\"inputHash\":\"" + inputHash + "\",\"issues\":[]}",
                        ToolResultEnvelope.class),
                ContractErrorCodes.TOOL_RESULT_INVARIANT);
    }

    @Test
    void toolFailureIssuesAreImmutableCopies() {
        List<Issue> issues = new ArrayList<>(List.of(Issue.error("CODE", "Reason")));
        ToolFailure failure = new ToolFailure("CODE", "Reason", false, issues);

        issues.clear();

        assertThat(failure.issues()).hasSize(1);
        assertThatThrownBy(() -> failure.issues().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolResultRequiresValidationOfAllIdentityFields() {
        ContentHash inputHash = contentHash("{}");

        assertThat(validator.validate(ToolResultEnvelope.resolved(
                "call-1", "list_attributes", Map.of("query", "tier"), inputHash, List.of()))).isEmpty();
        assertThat(validator.validate(new ToolResultEnvelope<>(
                ContractVersions.V1_3, " ", "list_attributes", Disposition.RESOLVED,
                Map.of(), null, inputHash, List.of()))).isNotEmpty();
        assertThat(validator.validate(new ToolResultEnvelope<>(
                ContractVersions.V1_3, "call-1", "list_attributes", Disposition.RESOLVED,
                Map.of(), null, null, List.of()))).isNotEmpty();
    }

    private static ContentHash contentHash(String canonicalJson) {
        return ContentHash.sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
    }
}