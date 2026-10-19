package com.company.campaign.builder.tool;

import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillLoader;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.ToolEnvelope;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.contracts.ToolInputRejectedException;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirements 2, 3 and 8: only the exact Skill allowlist can execute, unknown/mutation/SQL/repository calls fail
 * before a Tool implementation runs, model payloads cannot inject trusted context, and the call count, payload,
 * output, duplicate, out-of-order and post-terminal rules fail deterministically. Every attempt — admitted or
 * refused — leaves exactly one audit-safe trace entry that carries the redacted payload fingerprint.
 */
class SegmentToolInvokerTest {

    private static final ContentHash HASH = new CanonicalJson().hash(Map.of("evidence", "test"));
    private static final ContentHash EMPTY_INPUT_HASH = new CanonicalJson().hash(Map.of());

    private static SegmentSkillDefinition skill() {
        return new SegmentSkillLoader().loadDefault();
    }

    /** A Skill with a reduced allowlist that still passes every semantic rule. */
    private static SegmentSkillDefinition skillWithTools(List<SegmentSkillDefinition.ToolPin> tools) {
        SegmentSkillDefinition base = skill();
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), tools, base.inputDispositions(),
                base.outputDispositions(), base.terminalStates(), base.limits(), base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior());
    }

    /** A Skill that permits exactly one Tool as the first permitted Tool. */
    private static SegmentSkillDefinition singleToolSkill(String toolId) {
        return skillWithTools(List.of(new SegmentSkillDefinition.ToolPin(toolId, "1.0", 0, 4096)));
    }

    private static SegmentSkillDefinition skillWithBounds(int maxToolCalls, int maxToolPayloadBytes) {
        SegmentSkillDefinition base = skill();
        SegmentSkillDefinition.Bounds bounds = new SegmentSkillDefinition.Bounds(
                base.limits().maxSteps(), maxToolCalls, maxToolPayloadBytes, base.limits().maxModelOutputBytes(),
                base.limits().maxUtteranceLength(), base.limits().maxConversationTurns());
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), base.tools(), base.inputDispositions(),
                base.outputDispositions(), base.terminalStates(), bounds, base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior());
    }

    private static SegmentToolRunContext emptyRun() {
        return new SegmentToolRunContext() {
            @Override
            public TrustedExecutionContext trustedContext() {
                return null;
            }

            @Override
            public MetadataSnapshot metadataSnapshot() {
                return null;
            }

            @Override
            public MetadataQueryService metadataQueryService() {
                return null;
            }

            @Override
            public SegmentIntent intent() {
                return null;
            }

            @Override
            public ResolutionResult resolution() {
                return null;
            }

            @Override
            public SegmentDsl dslDocument() {
                return null;
            }

            @Override
            public SegmentValidationResult validation() {
                return null;
            }

            @Override
            public SemanticQueryPlan plan() {
                return null;
            }

            @Override
            public PreviewResponse previewResponse() {
                return null;
            }
        };
    }

    private static SegmentToolInvoker invoker(SegmentSkillDefinition skill, Map<String, SegmentToolHandler> handlers) {
        return new SegmentToolInvoker(SegmentToolCatalog.fromSkill(skill), handlers, emptyRun());
    }

    private static Map<String, SegmentToolHandler> resolvedHandlers(SegmentSkillDefinition skill) {
        Map<String, SegmentToolHandler> handlers = new LinkedHashMap<>();
        for (String toolId : skill.allowedToolIds()) {
            handlers.put(toolId, (run, input) -> SegmentToolExecution.resolved(
                    new SegmentToolOutcome(toolId, "TEST_OUTCOME", 1, HASH, List.of())));
        }
        return handlers;
    }

    private static ToolEnvelope envelope(String callId, String toolId) {
        return new ToolEnvelope(ContractVersions.CURRENT, callId, toolId, false, ToolInput.empty());
    }

    private static ToolEnvelope envelope(String callId, String toolId, Map<String, Object> arguments) {
        return new ToolEnvelope(ContractVersions.CURRENT, callId, toolId, false, ToolInput.of(arguments));
    }

    @Test
    void onlyTheExactAllowlistExecutesAndItExecutesInOrder() {
        SegmentSkillDefinition skill = skill();
        SegmentToolInvoker invoker = invoker(skill, resolvedHandlers(skill));

        int sequence = 0;
        for (String toolId : skill.allowedToolIds()) {
            SegmentToolAdmission result = invoker.invoke(envelope("call-" + (++sequence), toolId));
            assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
            assertThat(result.result().toolId()).isEqualTo(toolId);
            assertThat(result.inputHash()).isEqualTo(EMPTY_INPUT_HASH);
        }
        assertThat(invoker.callCount()).isEqualTo(skill.allowedToolIds().size());
        assertThat(invoker.trace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactlyElementsOf(skill.allowedToolIds());
    }

    @Test
    void unknownToolFailsBeforeAnyImplementationRunsAndStillLeavesATraceEntry() {
        AtomicBoolean invoked = new AtomicBoolean();
        SegmentToolInvoker invoker = invoker(skill(), Map.of(
                "metadata.discover", (run, input) -> {
                    invoked.set(true);
                    return SegmentToolExecution.failed(Disposition.INVALID, "SHOULD_NOT_RUN");
                }));

        SegmentToolAdmission result = invoker.invoke(envelope("call-1", "unknown.tool"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_UNKNOWN);
        assertThat(invoked).isFalse();
        assertThat(invoker.callCount()).isZero();
        assertThat(invoker.trace()).singleElement().satisfies(record -> {
            assertThat(record.toolId()).isEqualTo("unknown.tool");
            assertThat(record.disposition()).isEqualTo(Disposition.INVALID);
            assertThat(record.code()).isEqualTo(SegmentToolErrorCodes.TOOL_UNKNOWN);
            assertThat(record.inputFingerprint()).isEqualTo(EMPTY_INPUT_HASH);
            assertThat(record.rank()).isEqualTo(SegmentToolCallRecord.UNKNOWN_RANK);
        });
    }

    @Test
    void aServerOwnedButDisallowedToolFailsBeforeAnyImplementationRuns() {
        SegmentSkillDefinition reduced = skillWithTools(List.of(
                new SegmentSkillDefinition.ToolPin(SegmentToolIds.METADATA_DISCOVER, "1.0", 0, 4096)));
        AtomicBoolean invoked = new AtomicBoolean();
        SegmentToolInvoker invoker = invoker(reduced, Map.of(
                SegmentToolIds.DSL_VALIDATE, (run, input) -> {
                    invoked.set(true);
                    return SegmentToolExecution.resolved(new SegmentToolOutcome(
                            SegmentToolIds.DSL_VALIDATE, "TEST_OUTCOME", 0, HASH, List.of()));
                }));

        SegmentToolAdmission result = invoker.invoke(envelope("call-1", SegmentToolIds.DSL_VALIDATE));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(invoked).isFalse();
        assertThat(invoker.trace()).hasSize(1);
    }

    @Test
    void mutationSqlRepositoryAndRuntimeCapabilitiesFailClosedBeforeImplementation() {
        for (String forbidden : List.of(
                "save.draft", "publish.campaign", "apply.plan", "pause.campaign", "retry.run",
                "sql.query", "jdbc.execute", "repository.read", "runtime.activate")) {
            SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
            SegmentToolAdmission result = invoker.invoke(envelope("call-1", forbidden));
            assertThat(result.disposition()).as(forbidden).isEqualTo(Disposition.DENIED);
            assertThat(result.failure().code()).as(forbidden)
                    .isEqualTo(SegmentToolErrorCodes.TOOL_FORBIDDEN_CAPABILITY);
            assertThat(invoker.callCount()).as(forbidden).isZero();
            assertThat(invoker.trace()).as(forbidden).hasSize(1);
        }
    }

    @Test
    void aModelPayloadCannotInjectTrustedContext() {
        assertThatThrownBy(() -> envelope("call-1", SegmentToolIds.METADATA_DETAIL, Map.of("tenantId", "other")))
                .isInstanceOf(ToolInputRejectedException.class);
        assertThatThrownBy(() -> envelope("call-1", SegmentToolIds.PREVIEW_SAFE, Map.of("dataScope", "scope-1")))
                .isInstanceOf(ToolInputRejectedException.class);
        assertThatThrownBy(() -> envelope("call-1", SegmentToolIds.PREVIEW_SAFE, Map.of("asOf", "2026-01-01T00:00:00Z")))
                .isInstanceOf(ToolInputRejectedException.class);
        assertThatThrownBy(() -> envelope("call-1", SegmentToolIds.PREVIEW_SAFE, Map.of("metadataSnapshot", "x")))
                .isInstanceOf(ToolInputRejectedException.class);
        assertThatThrownBy(() -> envelope("call-1", SegmentToolIds.PLAN_BUILD, Map.of("publish", true)))
                .isInstanceOf(ToolInputRejectedException.class);
        assertThatThrownBy(() -> envelope("call-1", SegmentToolIds.METADATA_DETAIL,
                        Map.of("arguments", List.of(Map.of("user_id", "other")))))
                .isInstanceOf(ToolInputRejectedException.class);
    }

    @Test
    void duplicateCallIdentifiersAndDuplicateToolsFailDeterministically() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        assertThat(invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER)).disposition())
                .isEqualTo(Disposition.RESOLVED);

        SegmentToolAdmission duplicateCall =
                invoker.invoke(envelope("call-1", SegmentToolIds.INTENT_RESOLVE));
        assertThat(duplicateCall.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(duplicateCall.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_DUPLICATE_CALL);

        SegmentToolAdmission duplicateTool =
                invoker.invoke(envelope("call-2", SegmentToolIds.METADATA_DISCOVER));
        assertThat(duplicateTool.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(duplicateTool.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_DUPLICATE_CALL);
        assertThat(invoker.callCount()).isEqualTo(1);
        assertThat(invoker.trace()).hasSize(3);
    }

    @Test
    void aFirstCallThatSkipsTheFirstPermittedToolFailsDeterministically() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        assertThat(invoker.invoke(envelope("call-1", SegmentToolIds.PLAN_BUILD)).disposition())
                .isEqualTo(Disposition.INVALID);
        assertThat(invoker.callCount()).isZero();
    }

    @Test
    void aLaterCallThatReversesTheDeclaredOrderFailsDeterministically() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));
        invoker.invoke(envelope("call-2", SegmentToolIds.INTENT_RESOLVE));
        invoker.invoke(envelope("call-3", SegmentToolIds.PLAN_BUILD));
        // The planner phase was reached out of order: the validation phase has the lower rank and is refused.
        SegmentToolAdmission outOfOrder = invoker.invoke(envelope("call-4", SegmentToolIds.DSL_VALIDATE));
        assertThat(outOfOrder.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(outOfOrder.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_OUT_OF_ORDER);
        assertThat(invoker.callCount()).isEqualTo(3);
        assertThat(invoker.trace()).hasSize(4);
    }

    @Test
    void aDeclaredPhaseMayBeSkippedBecauseOnlyTheHostOrchestratorChoosesThePath() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        assertThat(invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER)).disposition())
                .isEqualTo(Disposition.RESOLVED);
        // Rank 1 and rank 2 ("intent.resolve", "dsl.validate") are deliberately not issued by this caller: the
        // invoker only requires the rank to advance, so a skipped phase is a host choice, never a model one.
        SegmentToolAdmission jumped = invoker.invoke(envelope("call-2", SegmentToolIds.PLAN_BUILD));
        assertThat(jumped.disposition()).isEqualTo(Disposition.RESOLVED);
    }

    @Test
    void aPostTerminalToolCallFailsDeterministically() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        assertThat(invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER)).disposition())
                .isEqualTo(Disposition.RESOLVED);
        invoker.markTerminal();

        SegmentToolAdmission result = invoker.invoke(envelope("call-2", SegmentToolIds.INTENT_RESOLVE));
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_POST_TERMINAL);
        assertThat(invoker.callCount()).isEqualTo(1);
        assertThat(invoker.trace()).hasSize(2);
    }

    @Test
    void thePerRunCallCountLimitFailsBeforeImplementation() {
        SegmentSkillDefinition bounded = skillWithBounds(1, 262_144);
        AtomicBoolean invoked = new AtomicBoolean();
        Map<String, SegmentToolHandler> handlers = new LinkedHashMap<>();
        handlers.put(SegmentToolIds.METADATA_DISCOVER, (run, input) -> {
            invoked.set(true);
            return SegmentToolExecution.resolved(new SegmentToolOutcome(
                    SegmentToolIds.METADATA_DISCOVER, "TEST_OUTCOME", 1, HASH, List.of()));
        });
        handlers.put(SegmentToolIds.INTENT_RESOLVE, (run, input) -> SegmentToolExecution.resolved(
                new SegmentToolOutcome(SegmentToolIds.INTENT_RESOLVE, "TEST_OUTCOME", 1, HASH, List.of())));

        SegmentToolInvoker invoker = invoker(bounded, handlers);
        assertThat(invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER)).disposition())
                .isEqualTo(Disposition.RESOLVED);
        SegmentToolAdmission result = invoker.invoke(envelope("call-2", SegmentToolIds.INTENT_RESOLVE));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_CALL_LIMIT_EXCEEDED);
        assertThat(invoker.callCount()).isEqualTo(1);
        assertThat(invoker.trace()).hasSize(2);
    }

    @Test
    void anOversizedCanonicalPayloadFailsBeforeImplementation() {
        SegmentSkillDefinition base = singleToolSkill(SegmentToolIds.METADATA_DISCOVER);
        SegmentSkillDefinition bounded = SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), base.tools(), base.inputDispositions(),
                base.outputDispositions(), base.terminalStates(),
                new SegmentSkillDefinition.Bounds(
                        base.limits().maxSteps(), base.limits().maxToolCalls(), 8,
                        base.limits().maxModelOutputBytes(), base.limits().maxUtteranceLength(),
                        base.limits().maxConversationTurns()),
                base.dataClassification(), base.sampleOutputPermitted(), base.ambiguityStopsAt(),
                base.failureBehavior());
        AtomicBoolean invoked = new AtomicBoolean();
        Map<String, SegmentToolHandler> handlers = new LinkedHashMap<>();
        handlers.put(SegmentToolIds.METADATA_DISCOVER, (run, input) -> {
            invoked.set(true);
            return SegmentToolExecution.resolved(new SegmentToolOutcome(
                    SegmentToolIds.METADATA_DISCOVER, "TEST_OUTCOME", 1, HASH, List.of()));
        });

        SegmentToolInvoker invoker = invoker(bounded, handlers);
        SegmentToolAdmission result = invoker.invoke(
                envelope("call-1", SegmentToolIds.METADATA_DISCOVER, Map.of("a", "a-very-long-identifier")));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_PAYLOAD_LIMIT_EXCEEDED);
        assertThat(invoked).isFalse();
        assertThat(invoker.callCount()).isZero();
        assertThat(invoker.trace()).singleElement()
                .satisfies(record -> assertThat(record.inputFingerprint()).isNotEqualTo(EMPTY_INPUT_HASH));
    }

    @Test
    void anOversizedToolOutcomeIsRefused() {
        SegmentToolInvoker invoker = invoker(skill(), Map.of(
                SegmentToolIds.METADATA_DISCOVER, (run, input) -> SegmentToolExecution.resolved(
                        new SegmentToolOutcome(
                                SegmentToolIds.METADATA_DISCOVER, "TEST_OUTCOME",
                                SegmentToolDescriptor.MAX_RESULT_ITEMS + 1, HASH, List.of()))));

        SegmentToolAdmission result =
                invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_OUTPUT_LIMIT_EXCEEDED);
        assertThat(invoker.callCount()).isEqualTo(1);
    }

    @Test
    void anArtifactThatDoesNotHashToThePublishedOutcomeIsRefused() {
        SegmentToolInvoker invoker = invoker(skill(), Map.of(
                SegmentToolIds.METADATA_DISCOVER, (run, input) -> SegmentToolExecution.resolved(
                        new SegmentToolOutcome(SegmentToolIds.METADATA_DISCOVER, "TEST_OUTCOME", 0, HASH, List.of()),
                        new SegmentToolArtifact.MetadataDiscovery(List.of("member.tier"),
                                new CanonicalJson().hash(Map.of("other", "snapshot"))))));

        SegmentToolAdmission result = invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_ARTIFACT_INCOHERENT);
        assertThat(result.hasArtifact()).isFalse();
    }

    @Test
    void anArtifactOfAnotherToolIsRefused() {
        SegmentSkillDefinition single = singleToolSkill(SegmentToolIds.DSL_VALIDATE);
        SegmentToolInvoker invoker = invoker(single, Map.of(
                SegmentToolIds.DSL_VALIDATE, (run, input) -> SegmentToolExecution.resolved(
                        new SegmentToolOutcome(SegmentToolIds.DSL_VALIDATE, "TEST_OUTCOME", 0, HASH, List.of()),
                        new SegmentToolArtifact.MetadataDiscovery(List.of(), HASH))));

        SegmentToolAdmission result = invoker.invoke(envelope("call-1", SegmentToolIds.DSL_VALIDATE));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_ARTIFACT_INCOHERENT);
    }

    @Test
    void aCoherentArtifactIsBoundToItsAdmittedCallToolSkillAndOutcome() {
        SegmentToolInvoker invoker = invoker(skill(), Map.of(
                SegmentToolIds.METADATA_DISCOVER, (run, input) -> SegmentToolExecution.resolved(
                        new SegmentToolOutcome(SegmentToolIds.METADATA_DISCOVER, "TEST_OUTCOME", 0, HASH, List.of()),
                        new SegmentToolArtifact.MetadataDiscovery(List.of("member.tier"), HASH))));

        SegmentToolAdmission result = invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.claim("call-1", SegmentToolIds.METADATA_DISCOVER, skill().canonicalHash()))
                .isInstanceOf(SegmentToolArtifact.MetadataDiscovery.class);
        assertThatThrownBy(() -> result.claim("call-2", SegmentToolIds.METADATA_DISCOVER, skill().canonicalHash()))
                .isInstanceOf(SegmentToolException.class)
                .extracting(failure -> ((SegmentToolException) failure).code())
                .isEqualTo(SegmentToolErrorCodes.TOOL_ARTIFACT_MISMATCH);
        assertThatThrownBy(() -> result.claim("call-1", SegmentToolIds.PREVIEW_SAFE, skill().canonicalHash()))
                .isInstanceOf(SegmentToolException.class)
                .extracting(failure -> ((SegmentToolException) failure).code())
                .isEqualTo(SegmentToolErrorCodes.TOOL_ARTIFACT_MISMATCH);
        assertThatThrownBy(() -> result.claim("call-1", SegmentToolIds.METADATA_DISCOVER,
                        new ContentHash("sha256:" + "0".repeat(64))))
                .isInstanceOf(SegmentToolException.class)
                .extracting(failure -> ((SegmentToolException) failure).code())
                .isEqualTo(SegmentToolErrorCodes.TOOL_ARTIFACT_MISMATCH);
    }

    @Test
    void aCallWithoutAnArtifactCannotBeClaimed() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        SegmentToolAdmission result = invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.hasArtifact()).isFalse();
        assertThatThrownBy(() -> result.claim("call-1", SegmentToolIds.METADATA_DISCOVER, skill().canonicalHash()))
                .isInstanceOf(SegmentToolException.class)
                .extracting(failure -> ((SegmentToolException) failure).code())
                .isEqualTo(SegmentToolErrorCodes.TOOL_ARTIFACT_MISSING);
    }

    @Test
    void aStaleSchemaVersionFailsClosed() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        ToolEnvelope stale = new ToolEnvelope(
                new SchemaVersion(1, 2), "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty());

        SegmentToolAdmission result = invoker.invoke(stale);

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_SCHEMA_STALE);
        assertThat(invoker.callCount()).isZero();
        assertThat(invoker.trace()).hasSize(1);
    }

    @Test
    void aMissingHandlerFailsClosed() {
        SegmentToolInvoker invoker = invoker(singleToolSkill(SegmentToolIds.METADATA_DISCOVER), Map.of());
        SegmentToolAdmission result =
                invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_NOT_IMPLEMENTED);
    }

    @Test
    void aThrowingHandlerFailsClosedWithoutLeakingItsMessage() {
        SegmentToolInvoker invoker = invoker(singleToolSkill(SegmentToolIds.PREVIEW_SAFE), Map.of(
                SegmentToolIds.PREVIEW_SAFE, (run, input) -> {
                    throw new IllegalStateException("secret-like implementation detail");
                }));

        SegmentToolAdmission result = invoker.invoke(envelope("call-1", SegmentToolIds.PREVIEW_SAFE));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_EXECUTION_FAILED);
        assertThat(result.failure().message()).doesNotContain("secret-like");
        assertThat(invoker.trace()).singleElement()
                .satisfies(record -> assertThat(record.code())
                        .isEqualTo(SegmentToolErrorCodes.TOOL_EXECUTION_FAILED));
    }

    @Test
    void aHandlerNullExecutionFailsClosed() {
        SegmentToolInvoker invoker = invoker(singleToolSkill(SegmentToolIds.METADATA_DISCOVER),
                Map.of(SegmentToolIds.METADATA_DISCOVER, (run, input) -> null));

        SegmentToolAdmission result =
                invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT);
        assertThat(invoker.trace()).hasSize(1);
    }

    @Test
    void aResultEnvelopeIsImmutableAndBounded() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        SegmentToolAdmission result =
                invoker.invoke(envelope("call-1", SegmentToolIds.METADATA_DISCOVER));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.result().itemCount()).isEqualTo(1);
        assertThat(result.issues()).isEmpty();
        assertThat(result.inputHash()).isNotNull();
        assertThat(invoker.trace()).hasSize(1);
        assertThatThrownBy(() -> invoker.trace().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullEnvelopeIsRefusedWithAStableCode() {
        SegmentToolInvoker invoker = invoker(skill(), resolvedHandlers(skill()));
        assertThatThrownBy(() -> invoker.invoke(null))
                .isInstanceOf(SegmentToolException.class)
                .extracting(failure -> ((SegmentToolException) failure).code())
                .isEqualTo(SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT);
    }
}