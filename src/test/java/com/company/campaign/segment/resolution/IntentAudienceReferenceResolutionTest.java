package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceReferenceDirectory;
import com.company.campaign.segment.reference.testsupport.AudienceReferenceFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 6: the T10 resolver resolves a first-class Existing Audience Reference through the host-owned
 * directory, asks one structured clarification when it must, and is completely unchanged without a directory.
 */
class IntentAudienceReferenceResolutionTest {

    private final SegmentIntent intent = IntentFixtures.referenceIntent();

    private IntentResolver resolver(InMemoryAudienceReferenceDirectory directory) {
        return new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.emptyRegistry(),
                directory == null ? null : directory.context());
    }

    private ResolutionResult resolve(InMemoryAudienceReferenceDirectory directory) {
        return resolver(directory).resolve(intent);
    }

    // ------------------------------------------------------------------ the accepted path

    @Test
    void referenceIntentResolvesThroughTheHostDirectory() {
        ResolutionResult result = resolve(AudienceReferenceFixtures.singleVersionDirectory());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.clause("c1").accepted()).isEqualTo(ResolutionFixtures.SPEND_DEFINITION_ID);
        assertThat(result.hasAudienceReferences()).isTrue();

        AudienceReferenceResolution reference = result.audienceReference("ref-1");
        assertThat(reference.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(reference.resolved())
                .isEqualTo(AudienceReferenceFixtures.pin(
                        AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SEVEN));
        assertThat(reference.evidence()).isNotNull();
        assertThat(reference.evidence().referenceId()).isEqualTo("ref-1");
        assertThat(reference.evidence().directoryVersion())
                .isEqualTo(InMemoryAudienceReferenceDirectory.DIRECTORY_VERSION);
    }

    @Test
    void dynamicWordingExposesTheImmutablePinInTheReviewResult() {
        SegmentIntent dynamic = IntentFixtures.referenceIntentWithReference(
                IntentFixtures.audienceReference(
                        "ref-1", IntentFixtures.DYNAMIC_NAMED_PHRASE, IntentFixtures.DYNAMIC_NAMED_PHRASE,
                        IntentFixtures.DYNAMIC_NAMED_START));

        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.emptyRegistry(),
                AudienceReferenceFixtures.singleVersionDirectory().context()).resolve(dynamic);

        AudienceReferenceResolution reference = result.audienceReference("ref-1");
        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(reference.dynamicWording()).isTrue();
        assertThat(reference.pinnedFromDynamicWording()).isTrue();
        assertThat(reference.resolved().versionId()).isEqualTo(AudienceReferenceFixtures.VERSION_SEVEN);
        assertThat(reference.resolved().versionHash()).isNotNull();
    }

    @Test
    void referenceWithoutAHostDirectoryFailsClosed() {
        assertThatThrownBy(() -> resolve(null))
                .isInstanceOf(ResolutionException.class)
                .extracting(failure -> ((ResolutionException) failure).code())
                .isEqualTo(ResolutionErrorCodes.REFERENCE_DIRECTORY_MISSING);
    }

    // ------------------------------------------------------------------ structured clarification

    @Test
    void anAmbiguousAudienceProducesOneStructuredClarificationWithSafeChoices() {
        ResolutionResult result = resolve(AudienceReferenceFixtures.twoAudienceDirectory());

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        Clarification clarification = result.clarification();
        assertThat(clarification.questionCode()).isEqualTo(QuestionCode.SELECT_AUDIENCE_REFERENCE);
        assertThat(clarification.clauseId()).isEqualTo("ref-1");
        assertThat(clarification.options()).hasSize(2);
        assertThat(clarification.options()).allSatisfy(option -> {
            assertThat(option.definitionId()).isIn(
                    AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.AUDIENCE_GOLD);
            assertThat(option.label()).isNotBlank();
        });
        assertThat(result.audienceReference("ref-1").resolved()).isNull();
    }

    @Test
    void anAppliedAudienceChoiceResolvesTheReferenceDeterministically() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.twoAudienceDirectory();
        IntentResolver resolver = resolver(directory);
        ResolutionResult ambiguous = resolver.resolve(intent);
        ClarificationOption gold = ambiguous.clarification().options().stream()
                .filter(option -> option.definitionId().equals(AudienceReferenceFixtures.AUDIENCE_GOLD))
                .findFirst()
                .orElseThrow();

        ClarificationChoiceResult applied = new ClarificationChoiceService(resolver)
                .apply(intent, choiceFor(ambiguous, gold));

        assertThat(applied.resolution().disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(applied.resolution().audienceReference("ref-1").resolved().audienceId())
                .isEqualTo(AudienceReferenceFixtures.AUDIENCE_GOLD);
        assertThat(applied.resolution().selectionReceipts()).hasSize(1);
    }

    @Test
    void anAmbiguousVersionIsAskedAndTheExactPinChoicePinsThatVersion() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.twoVersionDirectory();
        IntentResolver resolver = resolver(directory);
        ResolutionResult ambiguous = resolver.resolve(intent);

        assertThat(ambiguous.clarification().questionCode()).isEqualTo(QuestionCode.SELECT_AUDIENCE_VERSION);
        assertThat(ambiguous.clarification().clauseId()).isEqualTo("ref-1/version");

        ClarificationOption older = ambiguous.clarification().options().stream()
                .filter(option -> option.definitionId().contains(AudienceReferenceFixtures.VERSION_SIX))
                .findFirst()
                .orElseThrow();
        ClarificationChoiceResult applied = new ClarificationChoiceService(resolver)
                .apply(intent, choiceFor(ambiguous, older));

        AudienceVersionRef pinned = applied.resolution().audienceReference("ref-1").resolved();
        assertThat(pinned.audienceId()).isEqualTo(AudienceReferenceFixtures.AUDIENCE_VIP);
        assertThat(pinned.versionId()).isEqualTo(AudienceReferenceFixtures.VERSION_SIX);
    }

    @Test
    void aChoiceAnsweredAgainstAChangedDirectoryIsStale() {
        InMemoryAudienceReferenceDirectory before = AudienceReferenceFixtures.twoAudienceDirectory();
        IntentResolver resolver = resolver(before);
        ResolutionResult ambiguous = resolver.resolve(intent);
        ClarificationOption option = ambiguous.clarification().options().get(0);

        // A third audience appears: the offered option set is no longer the current one, so the old answer is
        // refused instead of being applied to a directory it never saw.
        before.registerAudience("audience-third", "第三人群", List.of(AudienceReferenceFixtures.SHARED_NAME));

        assertThatThrownBy(() -> new ClarificationChoiceService(resolver)
                .apply(intent, choiceFor(ambiguous, option)))
                .isInstanceOf(ResolutionException.class)
                .extracting(failure -> ((ResolutionException) failure).code())
                .isEqualTo(ResolutionErrorCodes.STALE_CLARIFICATION);
    }

    // ------------------------------------------------------------------ no-reference preservation

    @Test
    void anIntentWithoutReferencesIsUnaffectedByTheDirectory() {
        SegmentIntent plain = IntentFixtures.standardIntent();

        ResolutionResult withDirectory = resolver(AudienceReferenceFixtures.singleVersionDirectory()).resolve(plain);
        ResolutionResult withoutDirectory = resolver(null).resolve(plain);

        assertThat(withDirectory).isEqualTo(withoutDirectory);
        assertThat(withDirectory.hasAudienceReferences()).isFalse();
        assertThat(withDirectory.disposition()).isEqualTo(Disposition.RESOLVED);
    }

    private ClarificationChoice choiceFor(ResolutionResult result, ClarificationOption option) {
        return new ClarificationChoice(
                result.clarification().resolutionId(),
                option.optionId(),
                result.baseIntentHash(),
                result.pins(),
                result.clarification().questionCode(),
                result.clarification().clauseId());
    }
}