package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.tool.SegmentToolErrorCodes;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationChoiceService;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 4 of the corrective task: every admitted and every rejected outcome publishes exactly one audit
 * record when a sink is configured, every Tool attempt leaves a trace entry with its redacted payload
 * fingerprint, and a refusing sink fails the run closed without a second audit attempt.
 */
class SegmentAgentAuditCoverageTest {

    private static final String MONEY_UTTERANCE = "过去90天消费超过1000元";

    /** A recording sink that counts every record it was handed. */
    private static final class RecordingSink implements SegmentAuditSink {

        private final List<SegmentAuditRecord> records = new ArrayList<>();

        @Override
        public void record(SegmentAuditRecord record) {
            records.add(record);
        }
    }

    /** A sink that always refuses with a secret-like message, and counts its attempts. */
    private static final class RefusingSink implements SegmentAuditSink {

        private int attempts;

        @Override
        public void record(SegmentAuditRecord record) {
            attempts++;
            throw new IllegalStateException("sink unavailable with secret-like text");
        }
    }

    private static SegmentAgentEnvironment environment(
            com.company.campaign.builder.segment.SegmentIntentModelPort port,
            com.company.campaign.builder.tool.SegmentPreviewPort previewPort,
            com.company.campaign.metadata.domain.MetadataSnapshot snapshot,
            com.company.campaign.security.authorization.AuthorizationService authorization,
            SegmentAuditSink sink) {
        return AgentTestFixtures.environment(
                AgentTestFixtures.skill(), port, snapshot, AgentTestFixtures.context(), authorization, previewPort,
                sink, null);
    }

    private static SegmentAgentEnvironment happy(SegmentAuditSink sink) {
        return environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                AgentTestFixtures.snapshot(),
                SegmentValidationFixtures.allowAll(),
                sink);
    }

    private static Clarification genuineClarification() {
        IntentResolver resolver = new IntentResolver(
                AgentTestFixtures.ambiguousSnapshot(), SliceTestFixtures.policy(), SliceTestFixtures.registry());
        return resolver.resolve(new com.company.campaign.segment.intent.SegmentIntentCodec().parse(
                SliceTestFixtures.ambiguousSpendJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                MONEY_UTTERANCE, SliceTestFixtures.registry())).clarification();
    }

    private static SegmentAgentEnvironment ambiguous(SegmentAuditSink sink) {
        return environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.ambiguousSpendJson()),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.ambiguousSnapshot(), AgentTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()),
                AgentTestFixtures.ambiguousSnapshot(),
                SegmentValidationFixtures.allowAll(),
                sink);
    }

    private static SegmentAgentEnvironment unsupported(SegmentAuditSink sink) {
        String json = SliceTestFixtures.enumTierJson()
                .replace("\"unsupportedRequirements\":[]", "\"unsupportedRequirements\":[\"RAW_SQL\"]");
        return environment(
                AgentTestFixtures.portReturning(json),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                AgentTestFixtures.snapshot(),
                SegmentValidationFixtures.allowAll(),
                sink);
    }

    private static SegmentAgentEnvironment denied(SegmentAuditSink sink) {
        return environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                AgentTestFixtures.snapshot(),
                SegmentValidationFixtures.denyAll(),
                sink);
    }

    private static SegmentAgentEnvironment invalid(SegmentAuditSink sink) {
        return environment(
                AgentTestFixtures.portReturning("this is prose, not one raw JSON object"),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                AgentTestFixtures.snapshot(),
                SegmentValidationFixtures.allowAll(),
                sink);
    }

    @Test
    void everyTerminalOutcomePublishesExactlyOneAuditRecord() {
        RecordingSink sink = new RecordingSink();
        List<SegmentAgentResult> results = List.of(
                AgentTestFixtures.run(happy(sink), new SegmentAgentRequest("Gold会员")),
                AgentTestFixtures.run(ambiguous(sink), new SegmentAgentRequest(MONEY_UTTERANCE)),
                AgentTestFixtures.run(unsupported(sink), new SegmentAgentRequest("Gold会员")),
                AgentTestFixtures.run(denied(sink), new SegmentAgentRequest("Gold会员")),
                AgentTestFixtures.run(invalid(sink), new SegmentAgentRequest("Gold会员")));

        assertThat(results).extracting(SegmentAgentResult::disposition).containsExactly(
                Disposition.RESOLVED, Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED,
                Disposition.INVALID);
        // Five terminal outcomes, five audit records — no more and no fewer.
        assertThat(sink.records).hasSize(5);
        assertThat(sink.records).extracting(SegmentAuditRecord::disposition)
                .containsExactlyElementsOf(results.stream().map(SegmentAgentResult::disposition).toList());
        assertThat(sink.records).extracting(SegmentAuditRecord::terminalState)
                .containsExactly("READY_FOR_PREVIEW", "NEEDS_INPUT", "UNSUPPORTED", "DENIED", "INVALID");
        assertThat(sink.records).extracting(SegmentAuditRecord::resultHash)
                .containsExactlyElementsOf(results.stream().map(SegmentAgentResult::resultHash).toList());
        assertThat(sink.records).allSatisfy(record -> assertThat(record.utteranceFingerprint()).isNotNull());

        assertThat(sink.records.get(0).failureCode()).isNull();
        assertThat(sink.records).filteredOn(record -> !record.disposition().isResolved())
                .allSatisfy(record -> assertThat(record.failureCode()).isNotNull());

        assertThat(results.get(0).clarification()).isNull();
        assertThat(results.get(1).clarification()).isNotNull();
        assertThat(results.get(1).clarification().questionCode()).isEqualTo(genuineClarification().questionCode());
        assertThat(results.get(2).clarification()).isNull();
    }

    @Test
    void anEarlyTrustedContextOrMetadataFailureStillPublishesOneAuditRecord() {
        RecordingSink sink = new RecordingSink();
        SegmentAgentEnvironment unavailable = SegmentAgentEnvironment.of(
                AgentTestFixtures.skill(),
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.failing(),
                context -> AgentTestFixtures.snapshot(),
                new com.company.campaign.metadata.service.MetadataQueryService(
                        SegmentValidationFixtures.allowAll(),
                        AgentTestFixtures.repository(AgentTestFixtures.snapshot())),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                SliceTestFixtures.policy(),
                SliceTestFixtures.registry(),
                SliceTestFixtures.validationPolicy(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                sink,
                null);

        SegmentAgentResult denied = AgentTestFixtures.run(
                unavailable, new SegmentAgentRequest("Gold会员"));

        assertThat(denied.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(denied.failureCode()).isEqualTo(SegmentAgentErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE);
        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).stateCodes()).isEmpty();
        assertThat(sink.records.get(0).toolCodes()).isEmpty();
        assertThat(sink.records.get(0).resultHash()).isEqualTo(denied.resultHash());
    }

    @Test
    void aRejectedToolCallLeavesAnAuditTraceEntryWithItsRedactedPayloadFingerprint() {
        RecordingSink sink = new RecordingSink();
        SegmentAgentResult result = AgentTestFixtures.run(denied(sink), new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.toolTrace()).singleElement().satisfies(call -> {
            assertThat(call.toolId()).isEqualTo(SegmentToolIds.METADATA_DISCOVER);
            assertThat(call.disposition()).isEqualTo(Disposition.DENIED);
            assertThat(call.code()).isEqualTo(SegmentToolErrorCodes.TOOL_DENIED);
            assertThat(call.inputFingerprint()).isNotNull();
        });
        assertThat(sink.records).singleElement().satisfies(record -> {
            assertThat(record.toolCodes()).singleElement().satisfies(code -> {
                assertThat(code.toolId()).isEqualTo(SegmentToolIds.METADATA_DISCOVER);
                assertThat(code.code()).isEqualTo(SegmentToolErrorCodes.TOOL_DENIED);
                assertThat(code.inputFingerprint()).isNotNull();
            });
            assertThat(record.failureCode())
                    .isEqualTo(AgentTestFixtures.skill().failureCode("metadataDiscoveryFailure"));
        });
    }

    @Test
    void everySuccessfulToolAttemptCarriesItsRedactedPayloadFingerprintIntoTheAuditRecord() {
        RecordingSink sink = new RecordingSink();
        SegmentAgentResult result = AgentTestFixtures.run(happy(sink), new SegmentAgentRequest("Gold会员"));

        assertThat(result.toolTrace()).hasSize(6);
        assertThat(result.toolTrace()).allSatisfy(
                call -> assertThat(call.inputFingerprint()).isNotNull());
        SegmentAuditRecord record = sink.records.get(0);
        assertThat(record.toolCodes()).hasSize(6);
        assertThat(record.toolCodes()).allSatisfy(code -> assertThat(code.inputFingerprint()).isNotNull());
        assertThat(record.stateCodes()).isNotEmpty();
    }

    @Test
    void aContinuationResolvedRunStillPublishesExactlyOneAuditRecord() {
        RecordingSink sink = new RecordingSink();
        IntentResolver resolver = new IntentResolver(
                AgentTestFixtures.ambiguousSnapshot(), SliceTestFixtures.policy(), SliceTestFixtures.registry());
        var intent = new com.company.campaign.segment.intent.SegmentIntentCodec().parse(
                SliceTestFixtures.ambiguousSpendJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                MONEY_UTTERANCE, SliceTestFixtures.registry());
        List<ClarificationSelectionReceipt> receipts = new ClarificationChoiceService(resolver)
                .continueResolution(intent, clarification -> clarification.optionIds().get(0))
                .selectionReceipts();

        SegmentAgentResult result = AgentTestFixtures.run(
                ambiguous(sink), new SegmentAgentRequest(MONEY_UTTERANCE, receipts));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).resultHash()).isEqualTo(result.resultHash());
    }

    @Test
    void aRefusingSinkFailsEveryTerminalOutcomeClosedWithOneRecordAttempt() {
        for (int scenario = 0; scenario < 4; scenario++) {
            RefusingSink sink = new RefusingSink();
            SegmentAgentResult result = switch (scenario) {
                case 0 -> AgentTestFixtures.run(happy(sink), new SegmentAgentRequest("Gold会员"));
                case 1 -> AgentTestFixtures.run(ambiguous(sink), new SegmentAgentRequest(MONEY_UTTERANCE));
                case 2 -> AgentTestFixtures.run(unsupported(sink), new SegmentAgentRequest("Gold会员"));
                default -> AgentTestFixtures.run(denied(sink), new SegmentAgentRequest("Gold会员"));
            };

            assertThat(result.disposition()).as("scenario " + scenario).isEqualTo(Disposition.INVALID);
            assertThat(result.terminalState()).isEqualTo(SegmentAgentState.INVALID);
            assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("auditFailure"));
            assertThat(result.previewCard()).isNull();
            assertThat(result.explanation()).isNull();
            assertThat(result.dslEditableHash()).isNull();
            assertThat(result.stateTrace()).extracting(AgentTransition::toState)
                    .doesNotContain(SegmentAgentState.READY_FOR_PREVIEW, SegmentAgentState.NEEDS_INPUT,
                            SegmentAgentState.UNSUPPORTED, SegmentAgentState.DENIED)
                    .endsWith(SegmentAgentState.INVALID);
            assertThat(sink.attempts).as("one non-recursive audit attempt").isEqualTo(1);
            assertThat(result.toString()).doesNotContain("secret-like");
        }
    }

    @Test
    void noAuditRecordCarriesRawUtterancePayloadProviderOutputOrExceptionMessages() {
        RecordingSink sink = new RecordingSink();
        AgentTestFixtures.run(happy(sink), new SegmentAgentRequest("Gold会员"));
        AgentTestFixtures.run(invalid(sink), new SegmentAgentRequest("Gold会员"));

        for (SegmentAuditRecord record : sink.records) {
            String rendered = record.toString();
            assertThat(rendered)
                    .doesNotContain("Gold")
                    .doesNotContain("member-1")
                    .doesNotContain("tenant-001")
                    .doesNotContain("program-001")
                    .doesNotContain("scope-008")
                    .doesNotContain("user-001")
                    .doesNotContain("SELECT")
                    .doesNotContain("this is prose")
                    .doesNotContain("segmentDsl")
                    .doesNotContain("unsupportedRequirements");
        }
        assertThat(sink.records.get(1).intentHash()).isNull();
        assertThat(sink.records.get(1).utteranceFingerprint()).isNotNull();
    }
}