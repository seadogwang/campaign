package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationErrorCodes;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 2 of T13-R1: a genuine typed {@code SegmentValidationResult} is handed to the injected
 * observation boundary even when validation refuses the document, before the Agent terminates.
 *
 * <p>The evidence travels host-privately through the existing Tool artifact binding. The public result stays
 * bounded: an invalid run publishes no canonical AST and no failure prose beyond its stable code.
 */
class SegmentAgentValidationEvidenceTest {

    private static final class RecordingObserver implements SegmentStageObserver {

        private SegmentValidationResult validation;
        private ResolutionResult resolution;

        @Override
        public void validationAccepted(SegmentValidationResult accepted) {
            this.validation = accepted;
        }

        @Override
        public void resolutionAccepted(ResolutionResult accepted) {
            this.resolution = accepted;
        }
    }

    /** A policy too small for the nested document, so genuine T07 validation must refuse it. */
    private static SegmentValidationPolicy oneNodePolicy() {
        SegmentValidationPolicy standard = SliceTestFixtures.validationPolicy();
        return new SegmentValidationPolicy(
                1, standard.maxPredicateDepth(), standard.maxAnyWidth(), standard.maxAllWidth(),
                standard.maxRelationNestingDepth());
    }

    @Test
    void anInvalidValidationIsObservedBeforeTheAgentTerminates() {
        RecordingObserver observer = new RecordingObserver();
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                        AgentTestFixtures.portReturning(SliceTestFixtures.nestedGroupingJson()),
                        SliceTestFixtures.snapshot(), SliceTestFixtures.context())
                .withValidationPolicy(oneNodePolicy())
                .withStageObserver(observer);

        SegmentAgentResult result = AgentTestFixtures.run(
                environment, new SegmentAgentRequest(SliceTestFixtures.NESTED_UTTERANCE));

        // The public envelope stays bounded and honest: a refused validation publishes no canonical AST.
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentAgentErrorCodes.VALIDATION_REJECTED);
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.previewCard()).isNull();

        // The observation boundary received the genuine typed invalid result, with its exact issue codes.
        assertThat(observer.resolution).isNotNull();
        assertThat(observer.resolution.isResolved()).isTrue();
        assertThat(observer.validation).isNotNull();
        assertThat(observer.validation.valid()).isFalse();
        assertThat(observer.validation.issues()).extracting(Issue::code)
                .contains(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
    }
}