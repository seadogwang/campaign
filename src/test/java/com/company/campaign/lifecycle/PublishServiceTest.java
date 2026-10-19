package com.company.campaign.lifecycle;

import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17 requirements 6-8: publication freezes exactly one immutable definition from an exact preparation, and
 * expired, stale, consumed, mismatched, unconfirmed or concurrently confirmed requests all fail closed.
 */
class PublishServiceTest {

    // ------------------------------------------------------------------ the one accepted case

    @Test
    void publishingAnExactPreparationFreezesOneImmutableDefinitionAndConsumesIt() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        PublishResult result = scenario.confirm(preparation, "publish-key-1", List.of());

        assertThat(result.preparationId()).isEqualTo(preparation.preparationId());
        assertThat(result.preparationHash()).isEqualTo(preparation.preparationHash());
        assertThat(result.idempotencyKey()).isEqualTo("publish-key-1");
        assertThat(result.draftIdentity().draftId()).isEqualTo(scenario.draft.draftId());
        assertThat(result.draftIdentity().revision()).isEqualTo(scenario.draft.draftRevision());
        assertThat(result.manifestHash()).isEqualTo(scenario.draft.manifestHash());
        assertThat(result.publishedRevision()).isEqualTo(1L);
        assertThat(result.confirmedRiskCodes()).isEmpty();

        assertThat(result.publishedIdentity().revision()).isEqualTo(1L);
        ContentHash contentChecksum = result.publishedIdentity().contentHash();

        assertThat(scenario.store.publishedCount()).isEqualTo(1);
        assertThat(scenario.store.publishReceiptCount()).isEqualTo(1);
        assertThat(scenario.store.isPreparationConsumed(preparation.preparationId())).isTrue();

        PublishedCampaignDefinition published =
                scenario.store.findLatestPublished(scenario.campaignId()).orElseThrow();
        assertThat(published.publishedId()).isEqualTo(result.publishedId());
        assertThat(published.contentChecksum()).isEqualTo(contentChecksum);
        assertThat(published.identity().contentHash()).isEqualTo(contentChecksum);
        assertThat(published.manifestHash()).isEqualTo(scenario.draft.manifestHash());
        assertThat(published.planHash()).isEqualTo(scenario.plan.planHash());
        assertThat(published.planId()).isEqualTo(scenario.plan.planId());
        assertThat(published.sourceDraftId()).isEqualTo(scenario.draft.draftId());
        assertThat(published.readinessState()).isEqualTo("ACTIVE");
        assertThat(published.previewCards()).hasSize(1);
        assertThat(published.manifest()).isSameAs(scenario.draft.manifest());
    }

    @Test
    void publishingRecordsExactlyOneSafeAuditEventCarryingOnlyCodesAndHashes() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        int before = scenario.audit.size();

        scenario.confirm(preparation, "publish-key-1", List.of());

        assertThat(scenario.audit.size()).isEqualTo(before + 1);
        LifecycleAuditEvent event = scenario.audit.events().get(before);
        assertThat(event.action()).isEqualTo(LifecycleAction.PUBLISH);
        assertThat(event.disposition()).isEqualTo(LifecycleDisposition.APPLIED);
        assertThat(event.reasonCode()).isEqualTo(PublishService.PUBLISHED_REASON);
        assertThat(event.resourceFingerprint()).isInstanceOf(AuditResourceFingerprint.class);
        assertThat(event.planHash()).isEqualTo(scenario.plan.planHash());
        assertThat(event.contentHash()).isNotNull();
        String printed = event.toString();
        assertThat(printed).doesNotContain(scenario.campaignId(), scenario.draft.draftId());
    }

    @Test
    void twoSequentialPublicationsOfOneCampaignAdvanceExactlyOnePublishedRevision() {
        PublishScenario scenario = PublishScenario.active();

        PublishResult first = scenario.confirm(scenario.prepare(), "key-1", List.of());
        PublishResult second = scenario.confirm(scenario.prepare(), "key-2", List.of());

        assertThat(first.publishedRevision()).isEqualTo(1L);
        assertThat(second.publishedRevision()).isEqualTo(2L);
        assertThat(scenario.store.publishedCount()).isEqualTo(2);
        assertThat(scenario.store.findLatestPublished(scenario.campaignId()).orElseThrow().publishedRevision())
                .isEqualTo(2L);
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void repeatingTheSameKeyAndRequestReturnsTheOriginalReceiptAndWritesNothing() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        PublishResult first = scenario.confirm(preparation, "key-1", List.of());
        int auditAfterFirst = scenario.audit.size();

        PublishResult second = scenario.confirm(preparation, "key-1", List.of());

        assertThat(second).isEqualTo(first);
        assertThat(second.publishedIdentity()).isEqualTo(first.publishedIdentity());
        assertThat(scenario.store.publishedCount()).isEqualTo(1);
        assertThat(scenario.store.publishReceiptCount()).isEqualTo(1);
        assertThat(scenario.audit.size()).isEqualTo(auditAfterFirst);
    }

    /**
     * R1 requirement 6: an idempotent replay is itself a mutation-command decision, so it must re-prove the
     * permission that produced it. A caller whose {@code campaign.publish} was revoked may not retrieve a
     * prior receipt through the mutation command.
     */
    @Test
    void anIdempotentReplayStillRequiresThePublishPermission() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "key-1", List.of());

        assertThatThrownBy(() -> scenario.publishService().confirm(
                scenario.command(preparation, "key-1", List.of()),
                PublishTestFixtures.contextWithoutPublish()))
                .isInstanceOf(PermissionDeniedException.class);

        assertThat(scenario.store.publishedCount()).isEqualTo(1);
        assertThat(scenario.store.publishReceiptCount()).isEqualTo(1);
    }

    /**
     * R1 requirement 6: an idempotent replay must also re-prove the frozen DataScope, so a caller who now
     * sees different data cannot be handed the receipt of a publication they can no longer verify.
     */
    @Test
    void anIdempotentReplayStillRequiresTheFrozenDataScope() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "key-1", List.of());

        assertThatThrownBy(() -> scenario.publishService().confirm(
                scenario.command(preparation, "key-1", List.of()),
                PublishTestFixtures.otherDataScopeContext()))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE);
                    assertThat(failure.invariant()).isEqualTo("dataScope");
                });

        assertThat(scenario.store.publishedCount()).isEqualTo(1);
    }

    /**
     * R1 requirement 6: a duplicate acknowledgement must not collapse onto the hash of an accepted request
     * and be replayed as one after the preparation was consumed.
     */
    @Test
    void aDuplicateConfirmationCannotReplayAnAcceptedRequest() {
        PublishScenario scenario = PublishScenario.of(
                PublishTestFixtures.withRisks(PublishTestFixtures.activeSegmentPlan(),
                        List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED")));
        PreparePublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "key-1", List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED"));
        int publicationsAfterFirst = scenario.store.publishedCount();

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1",
                List.of("HIGH_REACH", "HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED")))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH);
                    assertThat(failure.invariant()).isEqualTo("confirmationRepeat");
                });

        assertThat(scenario.store.isPreparationConsumed(preparation.preparationId())).isTrue();
        assertThat(scenario.store.publishedCount()).isEqualTo(publicationsAfterFirst);
    }

    @Test
    void theSameKeyWithAChangedRequestConflicts() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "key-1", List.of());

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1", List.of("SOME_RISK")))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PUBLISH_CONFLICT);
                    assertThat(failure.invariant()).isEqualTo("idempotencyKeyReuse");
                });
        assertThat(scenario.store.publishedCount()).isEqualTo(1);
    }

    @Test
    void aFreshKeyAgainstAConsumedPreparationIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "key-1", List.of());

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-2", List.of()))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_CONSUMED);
                    assertThat(LifecycleSupport.dispositionOf(failure))
                            .isEqualTo(LifecycleDisposition.REJECTED);
                });
        assertThat(scenario.store.publishedCount()).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsCreateAtMostOnePublishedDefinition() throws Exception {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        List<PublishResult> accepted = new ArrayList<>();
        int refusals = 0;
        for (Object outcome : runConcurrently(
                List.of(() -> scenario.confirm(preparation, "key-1", List.of()),
                        () -> scenario.confirm(preparation, "key-1", List.of()),
                        () -> scenario.confirm(preparation, "key-2", List.of()),
                        () -> scenario.confirm(preparation, "key-3", List.of())))) {
            if (outcome instanceof PublishResult result) {
                accepted.add(result);
            } else {
                refusals++;
            }
        }

        assertThat(scenario.store.publishedCount()).isEqualTo(1);
        assertThat(scenario.store.publishReceiptCount()).isEqualTo(1);
        assertThat(accepted.size() + refusals).isEqualTo(4);
        assertThat(accepted).isNotEmpty();
        // Every accepted confirmation shares one key and one request, so they share exactly one publication.
        assertThat(accepted).allSatisfy(result ->
                assertThat(result.publishedIdentity()).isEqualTo(accepted.get(0).publishedIdentity()));
    }

    // ------------------------------------------------------------------ preparation state

    @Test
    void anExpiredPreparationIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        PublishService late = scenario.publishServiceAt(LifecycleTestFixtures.clockAt(
                LifecycleTestFixtures.APPLIED_AT
                        .plus(PublishPreparation.DEFAULT_LIFETIME)
                        .plus(Duration.ofMinutes(1))));

        assertThatThrownBy(() -> late.confirm(
                scenario.command(preparation, "key-1", List.of()), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_EXPIRED);
                    assertThat(LifecycleSupport.dispositionOf(failure))
                            .isEqualTo(LifecycleDisposition.REJECTED);
                });
        assertThat(scenario.store.publishedCount()).isZero();
    }

    @Test
    void aMismatchedPreparationHashIsRefusedInConstantTime() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        PublishCommand forged = new PublishCommand(
                preparation.preparationId(),
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "forged".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "key-1", List.of());

        assertThatThrownBy(() -> scenario.publishService().confirm(forged, scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH);
                    assertThat(failure.invariant()).isEqualTo("preparationHash");
                });
        assertThat(scenario.store.publishedCount()).isZero();
    }

    @Test
    void anUnknownOrForeignPreparationIsNonEnumerablyNotFound() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        PublishCommand unknown = new PublishCommand(
                "publish-preparation-000000999999", preparation.preparationHash(), "key-1", List.of());
        assertThatThrownBy(() -> scenario.publishService().confirm(unknown, scenario.context))
                .isInstanceOf(ResourceNotFoundException.class);

        for (TrustedExecutionContext foreign : List.of(
                PublishTestFixtures.otherTenantContext(), PublishTestFixtures.otherUserContext())) {
            assertThatThrownBy(() -> scenario.publishService()
                    .confirm(scenario.command(preparation, "key-1", List.of()), foreign))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
        assertThat(scenario.store.publishedCount()).isZero();
    }

    /**
     * R1 requirement 1: preparation ownership is proved before the {@code campaign.publish} decision, so a
     * preparation owned by another user inside the same tenant is not distinguishable from an absent one even
     * for a caller who also lacks the permission.
     */
    @Test
    void aSameTenantPreparationOwnedByAnotherUserIsNotFoundEvenWithoutPublishPermission() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        TrustedExecutionContext lowPermissionOtherUser =
                LifecycleTestFixtures.otherUserWithoutPublishContext();
        assertThat(lowPermissionOtherUser.hasPermission(CampaignPermission.CAMPAIGN_PUBLISH)).isFalse();

        assertThatThrownBy(() -> scenario.publishService().confirm(
                scenario.command(preparation, "key-1", List.of()), lowPermissionOtherUser))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> scenario.publishService().confirm(
                new PublishCommand("publish-preparation-000000999999", preparation.preparationHash(),
                        "key-1", List.of()),
                lowPermissionOtherUser))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(scenario.store.publishedCount()).isZero();
    }

    /**
     * R1 requirement 2: a confirmation presented without its trusted context fails closed with a stable code
     * rather than a raw {@link NullPointerException}, and mutates nothing.
     */
    @Test
    void aMissingTrustedContextFailsClosedWithAStableCode() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        assertThatThrownBy(() -> scenario.publishService().confirm(
                scenario.command(preparation, "key-1", List.of()), null))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
                    assertThat(failure.invariant()).isEqualTo("trustedContext");
                });

        assertThat(scenario.store.publishedCount()).isZero();
        assertThat(scenario.store.isPreparationConsumed(preparation.preparationId())).isFalse();
    }

    @Test
    void publishingIndependentlyReauthorizesCampaignPublish() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        assertThatThrownBy(() -> scenario.publishService().confirm(
                scenario.command(preparation, "key-1", List.of()),
                PublishTestFixtures.contextWithoutPublish()))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(scenario.store.publishedCount()).isZero();
        assertThat(scenario.store.isPreparationConsumed(preparation.preparationId())).isFalse();
    }

    @Test
    void aDataScopeThatMovedSincePreparationMakesThePreparationStale() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        assertThatThrownBy(() -> scenario.publishService().confirm(
                scenario.command(preparation, "key-1", List.of()),
                PublishTestFixtures.otherDataScopeContext()))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE);
                    assertThat(failure.invariant()).isEqualTo("dataScope");
                });
        assertThat(scenario.store.publishedCount()).isZero();
    }

    // ------------------------------------------------------------------ drift between prepare and confirm

    @Test
    void aDraftRevisionThatMovedSincePreparationMakesThePreparationStale() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        // A real EDIT Apply moves the editable Draft on, exactly as a concurrent editor would.
        var editPlan = PublishTestFixtures.editPlan(
                scenario.draft, com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures
                        .experimental());
        scenario.plans.store(editPlan);
        scenario.applyService()
                .apply(new ApplyCommand(editPlan.planId(), editPlan.planHash()), scenario.context);
        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftRevision())
                .isEqualTo(scenario.draft.draftRevision() + 1L);

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1", List.of()))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE);
                    assertThat(failure.invariant()).isEqualTo("draftRevision");
                });
        assertThat(scenario.store.publishedCount()).isZero();
    }

    @Test
    void activationEvidenceThatChangedSincePreparationFailsClosed() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();

        for (var projection : List.of(
                PublishTestFixtures.experimentalProjection(),
                PublishTestFixtures.evaluationRequiredProjection(),
                PublishTestFixtures.staleInputsProjection(),
                PublishTestFixtures.supersededDecisionProjection())) {
            scenario.withReadiness(projection);
            assertThatThrownBy(() -> scenario.confirm(preparation, "key-1", List.of()))
                    .isInstanceOfSatisfying(LifecycleException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED));
            assertThat(scenario.store.publishedCount()).isZero();
        }
    }

    @Test
    void missingOrUnanswerableActivationEvidenceFailsClosedAtConfirmation() {
        PublishScenario missing = PublishScenario.active();
        PreparePublishResult preparedByMissing = missing.prepare();
        missing.withReadiness(PublishTestFixtures.missingReadiness());
        assertThatThrownBy(() -> missing.confirm(preparedByMissing, "key-1", List.of()))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED));

        PublishScenario unavailable = PublishScenario.active();
        PreparePublishResult preparedByUnavailable = unavailable.prepare();
        unavailable.withReadiness(PublishTestFixtures.failingReadiness());
        assertThatThrownBy(() -> unavailable.confirm(preparedByUnavailable, "key-1", List.of()))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_UNAVAILABLE));

        assertThat(missing.store.publishedCount()).isZero();
        assertThat(unavailable.store.publishedCount()).isZero();
    }

    @Test
    void failedFinalValidationOrDriftedFrozenInputsMakeThePreparationStale() {
        PublishScenario invalid = PublishScenario.active();
        PreparePublishResult prepared = invalid.prepare();
        PublishService strictValidation = PublishTestFixtures.publishService(
                invalid.plans, invalid.store,
                PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                LifecycleTestFixtures.compatible(),
                request -> List.of(LifecycleTestFixtures.blockingIssue()),
                LifecycleTestFixtures.CLOCK, new SequentialLifecycleIdGenerator());
        assertThatThrownBy(() -> strictValidation.confirm(
                invalid.command(prepared, "key-1", List.of()), invalid.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE);
                    assertThat(failure.invariant()).isEqualTo("finalManifest");
                });

        PublishScenario incompatible = PublishScenario.active();
        PreparePublishResult preparedAgain = incompatible.prepare();
        PublishService strictCompatibility = PublishTestFixtures.publishService(
                incompatible.plans, incompatible.store,
                PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                query -> CompatibilityDecision.incompatible("POLICY_MOVED"),
                LifecycleTestFixtures.valid(),
                LifecycleTestFixtures.CLOCK, new SequentialLifecycleIdGenerator());
        assertThatThrownBy(() -> strictCompatibility.confirm(
                incompatible.command(preparedAgain, "key-1", List.of()), incompatible.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE);
                    assertThat(failure.invariant()).isEqualTo("frozenCompatibility");
                });

        assertThat(invalid.store.publishedCount()).isZero();
        assertThat(incompatible.store.publishedCount()).isZero();
    }

    // ------------------------------------------------------------------ exact risk confirmations

    @Test
    void aPlanCarryingRisksIsPreparableAndFreezesExactlyThem() {
        PublishScenario scenario = PublishScenario.of(
                PublishTestFixtures.withRisks(PublishTestFixtures.activeSegmentPlan(),
                        List.of("SEGMENT_SAMPLE_EXPOSED", "HIGH_REACH")));

        PreparePublishResult preparation = scenario.prepare();

        // The risk codes are frozen in canonical order and covered by the preparation's own checksum.
        assertThat(preparation.requiredRiskCodes())
                .containsExactly("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED");
        assertThat(scenario.store.findPreparation(preparation.preparationId()).orElseThrow()
                .requiredRiskCodes()).containsExactly("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED");
        assertThat(scenario.store.publishReceiptCount()).isZero();
    }

    @Test
    void missingExtraAndRepeatedConfirmationsAllFailClosed() {
        PublishScenario scenario = PublishScenario.of(
                PublishTestFixtures.withRisks(PublishTestFixtures.activeSegmentPlan(),
                        List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED")));
        PreparePublishResult preparation = scenario.prepare();

        assertThat(preparation.requiredRiskCodes())
                .containsExactly("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED");

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1", List.of()))
                .as("a missing confirmation is refused")
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH));

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1",
                List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED", "UNKNOWN_RISK")))
                .as("an unknown confirmation is refused")
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH));

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1",
                List.of("HIGH_REACH", "HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED")))
                .as("a repeated confirmation is refused")
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH);
                    assertThat(failure.invariant()).isEqualTo("confirmationRepeat");
                });

        assertThatThrownBy(() -> scenario.confirm(preparation, "key-1", List.of("not-a-code")))
                .as("a confirmation outside the machine-code grammar is refused")
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH));

        assertThat(scenario.store.publishedCount()).isZero();
        assertThat(scenario.audit.events()).allSatisfy(event ->
                assertThat(event.action()).isIn(LifecycleAction.APPLY, LifecycleAction.PREPARE_PUBLISH,
                        LifecycleAction.PUBLISH));
    }

    @Test
    void exactlyTheFrozenRiskCodesPublish() {
        PublishScenario scenario = PublishScenario.of(
                PublishTestFixtures.withRisks(PublishTestFixtures.activeSegmentPlan(),
                        List.of("SEGMENT_SAMPLE_EXPOSED", "HIGH_REACH")));
        PreparePublishResult preparation = scenario.prepare();

        PublishResult result = scenario.confirm(preparation, "key-1",
                List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED"));

        assertThat(result.confirmedRiskCodes())
                .containsExactly("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED");
        PublishedCampaignDefinition published =
                scenario.store.findLatestPublished(scenario.campaignId()).orElseThrow();
        assertThat(published.riskCodes()).containsExactly("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED");
        assertThat(published.contentChecksum()).isEqualTo(result.publishedIdentity().contentHash());
    }

    @Test
    void aStructurallyInvalidConfirmationIsRefused() {
        PublishScenario scenario = PublishScenario.active();

        assertThatThrownBy(() -> scenario.publishService().confirm(
                new PublishCommand("", null, "", null), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
        assertThatThrownBy(() -> scenario.publishService().confirm(null, scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Object> runConcurrently(List<Callable<PublishResult>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<PublishResult> task : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException rejection) {
                        return rejection;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}