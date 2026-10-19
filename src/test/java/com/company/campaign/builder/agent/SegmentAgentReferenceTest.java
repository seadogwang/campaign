package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.testsupport.AudienceReferenceFixtures;
import com.company.campaign.segment.resolution.AudienceReferenceResolution;
import com.company.campaign.segment.resolution.ResolutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18 section 6: the Agent flow resolves a first-class Existing Audience Reference through the host-owned
 * directory, with the exact immutable pin and its resolution evidence, and fails closed when no directory was
 * injected — so reference-free behavior is preserved and a model can never supply a trusted reference fact.
 */
class SegmentAgentReferenceTest {

    @Test
    void aReferenceIntentResolvesThroughTheInjectedHostDirectoryToTheExactImmutablePin() {
        AudienceReferenceDirectoryContext directory =
                AudienceReferenceFixtures.singleVersionDirectory().context();
        List<ResolutionResult> observed = new ArrayList<>();

        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                        AgentTestFixtures.portReturning(IntentFixtures.referenceIntentJson()))
                .withReferenceDirectory(directory)
                .withStageObserver(new RecordingObserver(observed));

        AgentTestFixtures.run(environment, new SegmentAgentRequest(IntentFixtures.REFERENCE_UTTERANCE));

        assertThat(observed).as("the admitted resolution stage must observe its genuine result").isNotEmpty();
        AudienceReferenceResolution reference = observed.get(0).audienceReference("ref-1");
        assertThat(reference).isNotNull();
        assertThat(reference.isResolved()).isTrue();
        // The pin is the host directory's own immutable version, never a value the model declared.
        assertThat(reference.resolved().audienceId()).isEqualTo(AudienceReferenceFixtures.AUDIENCE_VIP);
        assertThat(reference.resolved().versionId()).isEqualTo(AudienceReferenceFixtures.VERSION_SEVEN);
        assertThat(reference.resolved().versionHash()).isNotNull();
        assertThat(reference.evidence()).isNotNull();
        assertThat(reference.evidence().referenceId()).isEqualTo("ref-1");
        assertThat(reference.evidence().resolved()).isEqualTo(reference.resolved());
        assertThat(reference.evidence().directoryVersion()).isEqualTo(directory.directoryVersion());
    }

    @Test
    void aReferenceIntentWithoutAHostDirectoryFailsClosedAndProducesNoArtifact() {
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(IntentFixtures.referenceIntentJson()));

        SegmentAgentResult result = AgentTestFixtures.run(
                environment, new SegmentAgentRequest(IntentFixtures.REFERENCE_UTTERANCE));

        assertThat(result.isReadyForPreview()).isFalse();
        assertThat(result.dslEditableHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.previewHash()).isNull();
        // The reference stage refused, and the refusal is a bounded code on the admitted Tool trace.
        assertThat(result.toolTrace())
                .filteredOn(record -> SegmentToolIds.INTENT_RESOLVE.equals(record.toolId()))
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record.code()).isNotNull());
    }

    @Test
    void aReferenceFreeRunIsUnchangedWhenNoDirectoryIsInjected() {
        // The accepted reference-free happy path must be untouched: the environment simply carries no directory.
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(IntentFixtures.referenceIntentJson()));
        assertThat(environment.referenceDirectory()).isNull();

        SegmentAgentEnvironment withDirectory = environment
                .withReferenceDirectory(AudienceReferenceFixtures.singleVersionDirectory().context());
        assertThat(withDirectory.referenceDirectory()).isNotNull();
        // Every other host fact is carried through unchanged by the copy.
        assertThat(withDirectory.skill()).isEqualTo(environment.skill());
        assertThat(withDirectory.catalog().skillHash()).isEqualTo(environment.catalog().skillHash());
        assertThat(withDirectory.resolutionPolicy()).isSameAs(environment.resolutionPolicy());
        assertThat(withDirectory.registeredDefaults()).isSameAs(environment.registeredDefaults());
    }

    @Test
    void theModelRequestCarriesNoReferenceTrustedFact() {
        // The model-controllable payload is exactly an utterance and opaque continuation receipts: there is no
        // field through which a model could declare a directory, a version pin, an owner, a scope or readiness.
        assertThat(SegmentAgentRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("utterance", "continuation");
        for (RecordComponent component : SegmentAgentRequest.class.getRecordComponents()) {
            assertThat(AudienceReferenceDirectoryContext.class.isAssignableFrom(component.getType()))
                    .as(component.getName()).isFalse();
        }
    }

    /** Records the accepted resolution through the host-owned observation boundary without changing it. */
    private static final class RecordingObserver implements SegmentStageObserver {

        private final List<ResolutionResult> observed;

        private RecordingObserver(List<ResolutionResult> observed) {
            this.observed = observed;
        }

        @Override
        public void resolutionAccepted(ResolutionResult resolution) {
            observed.add(resolution);
        }
    }
}