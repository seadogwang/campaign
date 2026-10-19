package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 6: the audit projection carries only correlation references, versions, hashes, dispositions,
 * codes, bounded host-supplied durations and redacted fingerprints, and a throwing sink fails the run closed
 * with no artifact and no silent drop of required security evidence.
 */
class SegmentAgentAuditTest {

    /** A recording audit sink; it holds only what the adapter hands it. */
    private static final class RecordingSink implements SegmentAuditSink {

        private final List<SegmentAuditRecord> records = new ArrayList<>();

        @Override
        public void record(SegmentAuditRecord record) {
            records.add(record);
        }
    }

    private static SegmentAgentEnvironment auditedEnvironment(SegmentAuditSink sink, SegmentRunTimings timings) {
        return AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.allowAll()),
                sink,
                timings);
    }

    @Test
    void aResolvedRunRecordsOneAuditProjectionBoundToItsResult() {
        RecordingSink sink = new RecordingSink();
        SegmentAgentEnvironment environment = auditedEnvironment(sink, runId -> 1_234_567L);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(sink.records).hasSize(1);
        SegmentAuditRecord record = sink.records.get(0);
        assertThat(record.agentRunId()).isEqualTo(AgentTestFixtures.AGENT_RUN_ID);
        assertThat(record.conversationId()).isEqualTo(AgentTestFixtures.CONVERSATION_ID);
        assertThat(record.skillId()).isEqualTo(result.skillId());
        assertThat(record.skillHash()).isEqualTo(result.skillHash());
        assertThat(record.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(record.terminalState()).isEqualTo(SegmentAgentState.READY_FOR_PREVIEW.name());
        assertThat(record.failureCode()).isNull();
        assertThat(record.resultHash()).isEqualTo(result.resultHash());
        assertThat(record.intentHash()).isEqualTo(result.intentHash());
        assertThat(record.previewHash()).isEqualTo(result.previewHash());
        assertThat(record.stateCodes()).isNotEmpty();
        assertThat(record.toolCodes()).hasSize(result.toolTrace().size());
        assertThat(record.durationNanos()).isEqualTo(1_234_567L);
    }

    @Test
    void theAuditProjectionNeverCarriesRawUtteranceMemberIdsOrTrustedIdentifiers() {
        RecordingSink sink = new RecordingSink();
        SegmentAgentResult result = AgentTestFixtures.run(
                auditedEnvironment(sink, null), new SegmentAgentRequest("Gold会员"));
        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);

        String rendered = sink.records.get(0).toString();
        assertThat(rendered)
                .doesNotContain("Gold")
                .doesNotContain("member-1")
                .doesNotContain("tenant-001")
                .doesNotContain("program-001")
                .doesNotContain("scope-008")
                .doesNotContain("user-001")
                .doesNotContain("membership-1")
                .doesNotContain("SELECT");
        assertThat(sink.records.get(0).durationNanos()).isNull();
    }

    @Test
    void aThrowingAuditSinkFailsTheRunClosedWithNoArtifact() {
        SegmentAuditSink throwing = record -> {
            throw new IllegalStateException("sink unavailable with secret-like text");
        };

        SegmentAgentResult result = AgentTestFixtures.run(
                auditedEnvironment(throwing, null), new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.terminalState()).isEqualTo(SegmentAgentState.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("auditFailure"));
        assertThat(result.previewCard()).isNull();
        assertThat(result.explanation()).isNull();
        assertThat(result.previewHash()).isNull();
        assertThat(result.stateTrace()).extracting(AgentTransition::toState)
                .containsExactly(
                        SegmentAgentState.UNDERSTANDING, SegmentAgentState.DISCOVERING, SegmentAgentState.RESOLVING,
                        SegmentAgentState.GENERATING_DSL, SegmentAgentState.VALIDATING, SegmentAgentState.PLANNING,
                        SegmentAgentState.PREVIEWING, SegmentAgentState.INVALID)
                .doesNotContain(SegmentAgentState.READY_FOR_PREVIEW);
    }

    @Test
    void aThrowingAuditSinkNeverLeaksItsMessage() {
        SegmentAuditSink throwing = record -> {
            throw new IllegalStateException("sink unavailable with secret-like text");
        };
        SegmentAgentResult result = AgentTestFixtures.run(
                auditedEnvironment(throwing, null), new SegmentAgentRequest("Gold会员"));

        assertThat(result.toString()).doesNotContain("secret-like");
        assertThat(result.canonicalBytes().length).isGreaterThan(0);
    }
}