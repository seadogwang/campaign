package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 2: unknown, forbidden, unavailable, wrong-Skill, version-mismatched, duplicate, out-of-order and
 * oversized calls all fail before a handler is entered, and every attempt leaves one audit-safe trace entry.
 */
class CampaignToolInvokerTest {

    private static final ContentHash HASH = new CanonicalJson().hash(Map.of("evidence", "invoker"));

    private static CampaignToolCatalog catalog() {
        return CampaignToolCatalog.standard();
    }

    private static CampaignToolRequest.MessageDiscover discover() {
        return CampaignToolRequest.MessageDiscover.all();
    }

    private static CampaignToolRequest.MessageResolve resolve() {
        return new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE);
    }

    private static CampaignToolRequest.MessagePreviewSafe preview() {
        return new CampaignToolRequest.MessagePreviewSafe(
                CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                CampaignAssemblyFixtures.variables("firstName", "Ada"));
    }

    /** A registry that records which Tools were entered, without touching any real evidence. */
    private static final class EnteringHandlers {

        private final Map<String, CampaignToolHandler> handlers = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> entries = new LinkedHashMap<>();

        private EnteringHandlers() {
            for (String toolId : CampaignToolIds.implemented()) {
                AtomicInteger counter = new AtomicInteger();
                entries.put(toolId, counter);
                handlers.put(toolId, (run, request) -> {
                    counter.incrementAndGet();
                    return CampaignToolExecution.resolved(new CampaignToolOutcome(
                            toolId, "TEST_OUTCOME", 1, HASH, java.util.List.of()));
                });
            }
        }

        private Map<String, CampaignToolHandler> handlers() {
            return handlers;
        }

        private boolean entered(String toolId) {
            return entries.get(toolId).get() > 0;
        }
    }

    private static CampaignToolInvoker invoker(EnteringHandlers handlers) {
        return new CampaignToolInvoker(catalog(), CampaignSkillIds.MESSAGE_TEMPLATE, handlers.handlers(),
                CampaignAssemblyFixtures.runContext());
    }

    @Test
    void theExactSkillAllowlistExecutesInDeclaredOrder() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        assertThat(invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover())).resolved()).isTrue();
        assertThat(invoker.invoke(CampaignAssemblyFixtures.call("call-2", resolve())).resolved()).isTrue();
        assertThat(invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CampaignAssemblyFixtures.variables("firstName", "Ada")))).resolved()).isTrue();
        assertThat(invoker.invoke(CampaignAssemblyFixtures.call("call-4", preview())).resolved()).isTrue();
        assertThat(invoker.callCount()).isEqualTo(4);
        assertThat(invoker.trace()).hasSize(4);
    }

    @Test
    void anUnknownToolFailsBeforeAnyHandlerAndStillLeavesATraceEntry() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", "message.template.unknown", "1.0", discover()));

        // The identifier is outside the closed vocabulary, so the refusal comes from the vocabulary itself.
        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_UNKNOWN);
        assertThat(invoker.callCount()).isZero();
        assertThat(invoker.trace()).singleElement()
                .satisfies(entry -> assertThat(entry.code())
                        .isEqualTo(CampaignAssemblyErrorCodes.TOOL_UNKNOWN));
        handlers.entries.forEach((toolId, counter) -> assertThat(counter.get()).as(toolId).isZero());
    }

    @Test
    void forbiddenHostOnlyCapabilitiesFailClosedBeforeAnyHandler() {
        for (String forbidden : new String[]{
                "save.draft", "apply.plan", "publish.campaign", "pause.campaign", "resume.campaign",
                "retry.run", "archive.campaign", "runtime.activate", "provider.call", "credential.read",
                "storage.lookup", "sql.query", "jdbc.execute", "repository.read", "inventory.reserve"}) {
            EnteringHandlers handlers = new EnteringHandlers();
            CampaignToolInvoker invoker = invoker(handlers);
            CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                    "call-1", forbidden, "1.0", discover()));
            assertThat(admission.disposition()).as(forbidden).isEqualTo(Disposition.DENIED);
            assertThat(admission.failure().code()).as(forbidden)
                    .isEqualTo(CampaignAssemblyErrorCodes.TOOL_FORBIDDEN_CAPABILITY);
            assertThat(invoker.callCount()).as(forbidden).isZero();
            assertThat(invoker.trace()).as(forbidden).hasSize(1);
        }
    }

    @Test
    void aPlannedUnavailableToolIsRefusedBeforeAnyHandler() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                catalog(), CampaignSkillIds.SCHEDULE_BASIC, Map.of(), CampaignAssemblyFixtures.runContext());
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", CampaignToolIds.SCHEDULE_OCCURRENCES_PREVIEW, "1.0", discover()));

        assertThat(admission.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_UNAVAILABLE);
        assertThat(invoker.callCount()).isZero();
    }

    @Test
    void aHostOnlyBridgeIsNeverModelCallable() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                catalog(), CampaignSkillIds.SEGMENT_RULE_BASED, Map.of(),
                CampaignAssemblyFixtures.runContext());
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", CampaignToolIds.SEGMENT_ARTIFACT_REFERENCE, "1.0", discover()));

        assertThat(admission.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_NOT_MODEL_CALLABLE);
    }

    @Test
    void aToolOwnedByAnotherSkillIsRefusedAsWrongSkill() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", CampaignToolIds.REWARD_DISCOVER, "1.0", discover()));

        assertThat(admission.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_WRONG_SKILL);
        assertThat(handlers.entered(CampaignToolIds.REWARD_DISCOVER)).isFalse();
    }

    @Test
    void aVersionMismatchedToolIsRefused() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, "2.0", discover()));

        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_VERSION_MISMATCH);
        assertThat(handlers.entered(CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER)).isFalse();
    }

    @Test
    void aStaleSchemaVersionIsRefused() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        CampaignToolCall stale = CampaignAssemblyFixtures.call(
                new SchemaVersion(1, 2), "call-1", CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, "1.0", discover());

        CampaignToolAdmission admission = invoker.invoke(stale);
        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_SCHEMA_STALE);
    }

    @Test
    void duplicateCallsFailDeterministically() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        assertThat(invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover())).resolved()).isTrue();
        CampaignToolAdmission duplicateCall =
                invoker.invoke(CampaignAssemblyFixtures.call("call-1", resolve()));
        CampaignToolAdmission duplicateTool =
                invoker.invoke(CampaignAssemblyFixtures.call("call-2", discover()));

        assertThat(duplicateCall.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_DUPLICATE_CALL);
        assertThat(duplicateTool.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_DUPLICATE_CALL);
        assertThat(invoker.callCount()).isEqualTo(1);
        assertThat(invoker.trace()).hasSize(3);
    }

    @Test
    void anOutOfOrderCallIsRefused() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-1", resolve()));

        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_OUT_OF_ORDER);
        assertThat(invoker.callCount()).isZero();
    }

    @Test
    void anOutOfOrderReversalIsRefused() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover()));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2", resolve()));
        invoker.invoke(CampaignAssemblyFixtures.call("call-3", preview()));
        CampaignToolAdmission reversal = invoker.invoke(CampaignAssemblyFixtures.call("call-4",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CampaignAssemblyFixtures.variables("firstName", "Ada"))));

        assertThat(reversal.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_OUT_OF_ORDER);
        assertThat(invoker.callCount()).isEqualTo(3);
    }

    @Test
    void thePerRunCallCountLimitFailsBeforeAnyHandler() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                catalog(), CampaignSkillIds.MESSAGE_TEMPLATE, handlers.handlers(),
                CampaignAssemblyFixtures.runContext(), 2);
        invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover()));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2", resolve()));
        CampaignToolAdmission overLimit = invoker.invoke(CampaignAssemblyFixtures.call("call-3", preview()));

        assertThat(overLimit.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(overLimit.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_CALL_LIMIT_EXCEEDED);
        assertThat(invoker.callCount()).isEqualTo(2);
    }

    @Test
    void anOversizedInputFailsBeforeAnyHandler() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover()));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2", resolve()));
        // The declared variables object is far over the descriptor's own bounded input size.
        CapabilityPayload huge = CapabilityPayload.of(Map.of("firstName", "x".repeat(20_000)));
        CampaignToolAdmission oversized = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE, huge)));

        assertThat(oversized.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(oversized.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_INPUT_LIMIT_EXCEEDED);
        assertThat(handlers.entered(CampaignToolIds.MESSAGE_VARIABLES_VALIDATE)).isFalse();
    }

    @Test
    void aMissingHandlerFailsClosed() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                catalog(), CampaignSkillIds.MESSAGE_TEMPLATE, Map.of(), CampaignAssemblyFixtures.runContext());
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover()));
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_NOT_IMPLEMENTED);
    }

    @Test
    void aThrowingHandlerFailsClosedWithoutLeakingItsMessage() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                catalog(), CampaignSkillIds.MESSAGE_TEMPLATE,
                Map.of(CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, (run, request) -> {
                    throw new IllegalStateException("secret-like implementation detail");
                }), CampaignAssemblyFixtures.runContext());
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover()));

        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_EXECUTION_FAILED);
        assertThat(admission.failure().message()).doesNotContain("secret-like");
    }

    @Test
    void aPostTerminalCallFailsClosed() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        invoker.invoke(CampaignAssemblyFixtures.call("call-1", discover()));
        invoker.markTerminal();
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-2", resolve()));

        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_POST_TERMINAL);
        assertThat(invoker.callCount()).isEqualTo(1);
    }

    @Test
    void aCallWhoseEnvelopeNamesAnotherToolIsIncoherent() {
        EnteringHandlers handlers = new EnteringHandlers();
        CampaignToolInvoker invoker = invoker(handlers);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE, "1.0", discover()));

        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        assertThat(invoker.callCount()).isZero();
        assertThat(handlers.entries.values()).allSatisfy(counter -> assertThat(counter.get()).isZero());
    }

}
