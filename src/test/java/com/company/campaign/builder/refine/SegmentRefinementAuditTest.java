package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 2.4 of the task: every admitted and every rejected outcome is audit-covered with one immutable,
 * redacted projection, and a present sink that refuses fails the run closed instead of dropping evidence.
 */
class SegmentRefinementAuditTest {

    private static SegmentDsl base() {
        return RefinementTestFixtures.baseDocument();
    }

    private static SegmentRefinementPatch thirtyDayPatch() {
        return RefinementTestFixtures.patch(base(), RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30));
    }

    private static SegmentRefinementEnvironment environment(RefinementTestFixtures.RecordingAuditSink sink) {
        return RefinementTestFixtures.environment(
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()),
                sink,
                runId -> 4_200L);
    }

    @Test
    void aSuccessfulRunPublishesExactlyOneCompleteAuditRecord() {
        RefinementTestFixtures.RecordingAuditSink sink = new RefinementTestFixtures.RecordingAuditSink();
        SegmentRefinementPatch patch = thirtyDayPatch();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                environment(sink), base(), patch);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(sink.records()).hasSize(1);
        SegmentRefinementAuditRecord record = sink.records().get(0);
        assertThat(record.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(record.terminalState()).isEqualTo("READY_FOR_PREVIEW");
        assertThat(record.failureCode()).isNull();
        assertThat(record.refinementRunId()).isEqualTo(RefinementTestFixtures.RUN_ID);
        assertThat(record.conversationId()).isEqualTo(RefinementTestFixtures.CONVERSATION_ID);
        assertThat(record.skillId()).isEqualTo("segment.rule-based");
        assertThat(record.skillHash()).isEqualTo(result.skillHash());
        assertThat(record.patchFingerprint()).isEqualTo(patch.patchFingerprint());
        assertThat(record.baseDslHash()).isEqualTo(base().editableDslHash());
        assertThat(record.refinedDslHash()).isEqualTo(result.refinedDslHash());
        assertThat(record.canonicalAstHash()).isEqualTo(result.canonicalAstHash());
        assertThat(record.planHash()).isEqualTo(result.planHash());
        assertThat(record.previewHash()).isEqualTo(result.previewHash());
        assertThat(record.metadataSnapshotHash()).isEqualTo(result.metadataSnapshotHash());
        assertThat(record.resultHash()).isEqualTo(result.resultHash());
        assertThat(record.durationNanos()).isEqualTo(4_200L);
        assertThat(record.stateCodes()).isNotEmpty().allSatisfy(code ->
                assertThat(code).matches("^[A-Z][A-Z0-9_]{0,63}$"));
        assertThat(record.toolCodes()).extracting(
                        com.company.campaign.builder.agent.SegmentAuditToolCode::toolId)
                .containsExactly(
                        SegmentToolIds.METADATA_DISCOVER,
                        SegmentToolIds.DSL_VALIDATE,
                        SegmentToolIds.PLAN_BUILD,
                        SegmentToolIds.PREVIEW_SAFE);
        assertThat(record.toolCodes()).allSatisfy(code -> {
            assertThat(code.code()).isNull();
            assertThat(code.inputFingerprint()).isNotNull();
        });
    }

    @Test
    void aDeniedRunAndEveryInvalidRunAreAlsoAuditCovered() {
        RefinementTestFixtures.RecordingAuditSink deniedSink = new RefinementTestFixtures.RecordingAuditSink();
        SegmentRefinementResult denied = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(
                        SegmentValidationFixtures.denyAll(),
                        AgentTestFixtures.previewPort(
                                RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                                SegmentValidationFixtures.denyAll()),
                        deniedSink, null),
                base(), thirtyDayPatch());

        assertThat(denied.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(deniedSink.records()).hasSize(1);
        assertThat(deniedSink.records().get(0).disposition()).isEqualTo(Disposition.DENIED);
        assertThat(deniedSink.records().get(0).terminalState()).isEqualTo("DENIED");
        assertThat(deniedSink.records().get(0).failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("metadataDiscoveryFailure"));
        assertThat(deniedSink.records().get(0).refinedDslHash()).isNull();

        RefinementTestFixtures.RecordingAuditSink invalidSink = new RefinementTestFixtures.RecordingAuditSink();
        SegmentRefinementResult invalid = RefinementTestFixtures.run(
                environment(invalidSink), base(),
                RefinementTestFixtures.patch(base(),
                        new SegmentRefinementOperation.MoveNode("op-1", "n-tier", "n-root", "n-missing")));

        assertThat(invalid.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(invalidSink.records()).hasSize(1);
        assertThat(invalidSink.records().get(0).disposition()).isEqualTo(Disposition.INVALID);
        assertThat(invalidSink.records().get(0).failureCode())
                .isEqualTo(SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND);
        assertThat(invalidSink.records().get(0).baseDslHash()).isEqualTo(base().editableDslHash());
        assertThat(invalidSink.records().get(0).refinedDslHash()).isNull();
    }

    @Test
    void aRefusedToolAttemptIsAuditCoveredWithItsPayloadFingerprint() {
        RefinementTestFixtures.RecordingAuditSink sink = new RefinementTestFixtures.RecordingAuditSink();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(
                        SegmentValidationFixtures.denyAll(),
                        AgentTestFixtures.previewPort(
                                RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                                SegmentValidationFixtures.denyAll()),
                        sink, null),
                base(), thirtyDayPatch());

        assertThat(result.toolTrace()).hasSize(1);
        assertThat(sink.records()).hasSize(1);
        SegmentRefinementAuditRecord record = sink.records().get(0);
        assertThat(record.toolCodes()).hasSize(1);
        assertThat(record.toolCodes().get(0).toolId()).isEqualTo(SegmentToolIds.METADATA_DISCOVER);
        assertThat(record.toolCodes().get(0).code()).isNotBlank();
        assertThat(record.toolCodes().get(0).inputFingerprint())
                .isEqualTo(result.toolTrace().get(0).inputFingerprint());
    }

    @Test
    void anAbsentSinkLeavesTheRunUnaffected() {
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base(), thirtyDayPatch());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.resultHash()).isNotNull();
    }

    @Test
    void aRefusingSinkFailsTheRunClosedWithNoArtifactAndNoRecursiveRecord() {
        RefinementTestFixtures.RecordingAuditSink sink =
                new RefinementTestFixtures.RecordingAuditSink(true);

        SegmentRefinementResult result = RefinementTestFixtures.run(environment(sink), base(), thirtyDayPatch());

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("auditFailure"));
        assertThat(result.refinedDsl()).isNull();
        assertThat(result.refinedDslHash()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.previewHash()).isNull();
        assertThat(result.previewCard()).isNull();
        assertThat(result.hasGroundedExplanation()).isFalse();
        assertThat(result.stateTrace()).extracting(SegmentRefinementTransition::toState)
                .endsWith(SegmentRefinementState.INVALID);
        assertThat(result.stateTrace().get(result.stateTrace().size() - 1).evidenceCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("auditFailure"));
        // Exactly one attempt: a broken sink can never trigger a recursive record.
        assertThat(sink.records()).hasSize(1);
        assertThat(result.patchFingerprint()).isEqualTo(thirtyDayPatch().patchFingerprint());
    }

    @Test
    void noAuditRecordExposesThePatchTheDslSqlPiiOrATrustedIdentifier() {
        RefinementTestFixtures.RecordingAuditSink sink = new RefinementTestFixtures.RecordingAuditSink();
        SegmentRefinementResult result = RefinementTestFixtures.run(environment(sink), base(), thirtyDayPatch());

        SegmentRefinementAuditRecord record = sink.records().get(0);
        assertThat(new String(result.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("SELECT")
                .doesNotContain(AgentTestFixtures.MEMBERS.get(0));
        assertThat(List.of(record.stateCodes(), List.of(record.terminalState())))
                .allSatisfy(codes -> assertThat(codes).allSatisfy(code ->
                        assertThat(code).doesNotContain(" ").doesNotContain("/")));
        assertThat(record.patchFingerprint().value()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(record.resultHash().value()).matches("^sha256:[0-9a-f]{64}$");
    }

    @Test
    void theAuditRecordCarriesOnlyHashesAndNeverTheDslOrThePatch() {
        assertThat(SegmentRefinementAuditRecord.class.getRecordComponents()).allSatisfy(component ->
                assertThat(component.getType())
                        .isNotIn(SegmentDsl.class, SegmentRefinementPatch.class,
                                SegmentRefinementOperation.class));
    }
}