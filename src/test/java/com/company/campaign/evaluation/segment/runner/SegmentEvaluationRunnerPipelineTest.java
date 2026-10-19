package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.segment.intent.SegmentIntentHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 2: the runner executes the accepted pipeline rather than a parallel implementation, and every hash it
 * observes equals the hash the genuine stage published.
 *
 * <p>The observation boundary is the only channel used, it is injected by the runner, and the artifacts read
 * from it are the exact immutable objects the admitted T11 Tools and the genuine T09 executor produced.
 */
class SegmentEvaluationRunnerPipelineTest {

    @Test
    void everyObservedHashEqualsTheHashTheGenuineStagePublished(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-pipeline-01", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13TestHost host = new T13TestHost(T13Scenarios.tierPort());

        EvaluationReport report = T13Scenarios.run(root, "t13-pipeline-01", host);

        EvaluationObservation observation = host.lastObservation();
        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());

        // The observation boundary saw exactly the admitted pipeline, in order, and nothing else.
        assertThat(observation.stages()).containsExactly(
                EvaluationObservation.ObservedStage.INTENT,
                EvaluationObservation.ObservedStage.RESOLUTION,
                EvaluationObservation.ObservedStage.DSL,
                EvaluationObservation.ObservedStage.VALIDATION,
                EvaluationObservation.ObservedStage.PLAN,
                EvaluationObservation.ObservedStage.PREVIEW,
                EvaluationObservation.ObservedStage.EXPLANATION);

        // The T10 Intent hash the boundary captured is the hash of the exact Intent it captured.
        assertThat(observation.intentHash()).isEqualTo(SegmentIntentHasher.hash(observation.intent()));

        // The T09C Preview card is bound to the exact artifacts of the same run.
        assertThat(observation.previewCard().planHash()).isEqualTo(observation.plan().planHash());
        assertThat(observation.previewCard().dslHash()).isEqualTo(observation.document().editableDslHash());
        assertThat(observation.previewCard().canonicalAstHash())
                .isEqualTo(observation.validation().canonicalAstHash());
        assertThat(observation.previewCard().metadataHash())
                .isEqualTo(host.snapshot().contentHash());
        assertThat(observation.previewCard().skillHash()).isEqualTo(host.skill().canonicalHash());
        assertThat(observation.previewCard().previewHash()).isEqualTo(observation.preview().contentHash());

        // The T08 Query Plan canonicalizes the same AST the T07 validation accepted.
        assertThat(observation.plan().canonicalAstHash()).isEqualTo(observation.validation().canonicalAstHash());
        assertThat(observation.plan().metadataContentHash()).isEqualTo(host.snapshot().contentHash());

        // The reported evidence carries exactly those observed hashes.
        assertThat(report.cases().get(0).attempts().get(0).evidence().planHash())
                .isEqualTo(observation.plan().planHash());
        assertThat(report.cases().get(0).attempts().get(0).evidence().actualCanonicalAstHash())
                .isEqualTo(observation.validation().canonicalAstHash());

        // The T09 member set was produced by executing that exact plan, and the preview card is sample-free.
        assertThat(report.cases().get(0).attempts().get(0).evidence().memberSets()).hasSize(1);
        assertThat(report.cases().get(0).attempts().get(0).evidence().memberSetsMatched()).isTrue();
        assertThat(observation.previewCard().hasSample()).isFalse();
    }

    @Test
    void theObservationBoundaryIsDisabledByDefaultAndCannotBeReachedFromTheEnvelope() {
        // The interface's default instance is a no-op: every method inherits an empty body, so an environment
        // that injects nothing observes nothing and no stage result can depend on an observation.
        assertThat(SegmentStageObserver.NOOP).isNotNull();
        assertThat(SegmentStageObserver.class.getMethods())
                .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(void.class));
        assertThat(com.company.campaign.builder.agent.SegmentAgentRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("utterance", "continuation");
    }

    @Test
    void aCaseWithNoObservedIntentStillProducesAccountedEvidence(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-pipeline-02", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13TestHost host = new T13TestHost(T13Scenarios.alwaysProsePort());

        EvaluationReport report = T13Scenarios.run(root, "t13-pipeline-02", host);

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.observedStages()).isEmpty();
        assertThat(attempt.evidence().actualCanonicalAstHash()).isNull();
        assertThat(attempt.evidence().canonicalAstMatched()).isFalse();
        assertThat(attempt.evidence().memberSets()).isNotEmpty();
        assertThat(attempt.evidence().memberSets()).allSatisfy(
                set -> assertThat(set.actualCount()).isZero());
        assertThat(report.summary().executedAttemptCount()).isEqualTo(1);
    }
}