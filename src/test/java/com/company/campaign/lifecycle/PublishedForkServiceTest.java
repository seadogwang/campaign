package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

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
 * T17 requirement 9: forking a Published Definition creates a distinct editable Draft with the exact
 * immutable fork base and never alters the published bytes.
 */
class PublishedForkServiceTest {

    @Test
    void forkingCreatesADistinctEditableDraftDescendingFromTheExactPublishedVersion() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        PublishedCampaignDefinition before =
                scenario.store.findPublished(published.publishedId()).orElseThrow();

        ForkResult result = scenario.forkService().fork(
                new ForkPublishedCommand(before.publishedId(), before.publishedRevision()),
                scenario.context);

        assertThat(result.draftIdentity().draftId()).isNotEqualTo(scenario.draft.draftId());
        assertThat(result.draftIdentity().revision()).isEqualTo(1L);
        assertThat(result.campaignRevision()).isEqualTo(scenario.draft.campaignRevision() + 1L);
        assertThat(result.forkBase()).isEqualTo(before.identity());
        assertThat(result.forkBase()).isEqualTo(published.publishedIdentity());

        SavedCampaignDraft forked = scenario.store.findDraft(scenario.campaignId()).orElseThrow();
        assertThat(forked.draftId()).isEqualTo(result.draftIdentity().draftId());
        assertThat(forked.draftRevision()).isEqualTo(1L);
        assertThat(forked.isFork()).isTrue();
        assertThat(forked.forkBase()).isEqualTo(before.identity());
        assertThat(forked.sourcePlanId()).isEqualTo(before.planId());
        assertThat(forked.manifest()).isSameAs(before.manifest());
        assertThat(forked.draftHash()).isEqualTo(result.draftHash());
    }

    @Test
    void forkingNeverAltersThePublishedBytes() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        PublishedCampaignDefinition before =
                scenario.store.findPublished(published.publishedId()).orElseThrow();

        scenario.forkService().fork(
                new ForkPublishedCommand(before.publishedId(), before.publishedRevision()),
                scenario.context);

        PublishedCampaignDefinition after =
                scenario.store.findPublished(published.publishedId()).orElseThrow();
        assertThat(after).isSameAs(before);
        assertThat(after.contentChecksum()).isEqualTo(before.contentChecksum());
        assertThat(after.identity()).isEqualTo(before.identity());
        assertThat(scenario.store.publishedCount()).isEqualTo(1);
        assertThat(scenario.store.publishReceiptCount()).isEqualTo(1);
    }

    @Test
    void forkingRecordsExactlyOneSafeAuditEvent() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        int before = scenario.audit.size();

        scenario.forkService().fork(
                new ForkPublishedCommand(published.publishedId(), published.publishedIdentity().revision()),
                scenario.context);

        assertThat(scenario.audit.size()).isEqualTo(before + 1);
        LifecycleAuditEvent event = scenario.audit.events().get(before);
        assertThat(event.action()).isEqualTo(LifecycleAction.FORK_PUBLISHED);
        assertThat(event.disposition()).isEqualTo(LifecycleDisposition.APPLIED);
        assertThat(event.reasonCode()).isEqualTo(PublishedForkService.FORKED_REASON);
        assertThat(event.resourceFingerprint()).isNotNull();
        assertThat(event.contentHash()).isNotNull();
    }

    @Test
    void aForkedDraftCanBePreparedAndPublishedAsTheNextRevision() {
        PublishScenario scenario = PublishScenario.active();
        PublishedCampaignDefinition first = scenario.store
                .findPublished(scenario.confirm(scenario.prepare(), "key-1", List.of()).publishedId())
                .orElseThrow();

        scenario.forkService().fork(
                new ForkPublishedCommand(first.publishedId(), first.publishedRevision()),
                scenario.context);

        // The forked Draft is a first-class editable Draft: it prepares and publishes exactly like any other.
        PublishResult second = scenario.confirm(scenario.prepare(), "key-2", List.of());

        assertThat(second.publishedRevision()).isEqualTo(2L);
        assertThat(second.draftIdentity().revision()).isEqualTo(1L);
        assertThat(second.manifestHash()).isEqualTo(first.manifestHash());
        assertThat(second.publishedIdentity().contentHash())
                .isNotEqualTo(first.identity().contentHash());
        assertThat(scenario.store.publishedCount()).isEqualTo(2);
        assertThat(scenario.store.findPublished(first.publishedId()).orElseThrow()).isSameAs(first);
    }

    @Test
    void forkingRequiresTheIndependentCampaignEditPermission() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());

        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand(published.publishedId(), published.publishedIdentity().revision()),
                PublishTestFixtures.contextWithoutEdit()))
                .isInstanceOf(PermissionDeniedException.class)
                .satisfies(failure -> assertThat(((PermissionDeniedException) failure).details())
                        .containsEntry("requiredPermission", "campaign.edit")
                        .containsEntry("resourceKind", "CAMPAIGN"));

        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }

    @Test
    void anUnknownOrForeignPublishedDefinitionIsNonEnumerablyNotFound() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        long revision = published.publishedIdentity().revision();

        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand("published-definition-000000999999", revision), scenario.context))
                .isInstanceOf(ResourceNotFoundException.class);

        for (TrustedExecutionContext foreign : List.of(
                PublishTestFixtures.otherTenantContext(), PublishTestFixtures.otherUserContext())) {
            assertThatThrownBy(() -> scenario.forkService().fork(
                    new ForkPublishedCommand(published.publishedId(), revision), foreign))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }

    @Test
    void aPublishedRevisionTheCommandGotWrongIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());

        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand(published.publishedId(), 7L), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PUBLISHED_MISMATCH);
                    assertThat(failure.invariant()).isEqualTo("publishedRevision");
                });
        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }

    @Test
    void aStructurallyInvalidForkCommandIsRefused() {
        PublishScenario scenario = PublishScenario.active();

        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand("", 1L), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
        assertThatThrownBy(() -> scenario.forkService().fork(null, scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
    }

    @Test
    void concurrentForksNeverLeaveMoreThanOneEditableDraft() throws Exception {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        ForkPublishedCommand command = new ForkPublishedCommand(
                published.publishedId(), published.publishedIdentity().revision());

        List<Object> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(pool.submit((Callable<Object>) () -> {
                    start.await();
                    try {
                        return scenario.forkService().fork(command, scenario.context);
                    } catch (RuntimeException rejection) {
                        return rejection;
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<ForkResult> accepted = new ArrayList<>();
        int refusals = 0;
        for (Object outcome : outcomes) {
            if (outcome instanceof ForkResult result) {
                accepted.add(result);
            } else {
                refusals++;
                assertThat(outcome)
                        .isInstanceOfSatisfying(LifecycleException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FORK_CONFLICT));
            }
        }

        // A fork that read the Campaign before another committed is refused; one that read it afterwards is
        // a legitimate later fork. Either way exactly one editable Draft survives, every committed fork has
        // its own identity, and the published bytes are untouched.
        assertThat(accepted.size() + refusals).isEqualTo(4);
        assertThat(accepted).isNotEmpty();
        assertThat(accepted).extracting(result -> result.draftIdentity().draftId())
                .doesNotHaveDuplicates();
        assertThat(accepted).extracting(ForkResult::campaignRevision).doesNotHaveDuplicates();
        assertThat(scenario.store.draftCount()).isEqualTo(1);
        assertThat(scenario.store.publishedCount()).isEqualTo(1);

        ForkResult last = accepted.stream()
                .max(java.util.Comparator.comparingLong(ForkResult::campaignRevision)).orElseThrow();
        SavedCampaignDraft forked = scenario.store.findDraft(scenario.campaignId()).orElseThrow();
        assertThat(forked.draftId()).isEqualTo(last.draftIdentity().draftId());
        assertThat(forked.campaignRevision()).isEqualTo(last.campaignRevision());
        assertThat(forked.forkBase()).isEqualTo(published.publishedIdentity());
    }

    @Test
    void aForkBaseThatNamesBytesThisBoundaryNeverPublishedIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        SavedCampaignDraft before = scenario.store.findDraft(scenario.campaignId()).orElseThrow();

        // An identical-looking fork base with a different content checksum cannot name the stored version.
        PublishedIdentity forged = new PublishedIdentity(
                published.publishedId(), published.publishedIdentity().revision(),
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "forged".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        SavedCampaignDraft forked = SavedCampaignDraft.of(
                before.tenantId(), before.programId(), before.userId(),
                scenario.campaignId(), before.campaignRevision() + 1L,
                "draft-000000999999", 1L, forged, before.manifest(),
                published.publishedId(), before.savedAt());

        assertThatThrownBy(() -> scenario.store.commitFork(new ForkCommit(
                scenario.campaignId(), before.campaignRevision(), before.draftIdentity(), before.draftHash(),
                forked,
                LifecycleAuditEvent.of(
                        LifecycleAction.FORK_PUBLISHED, LifecycleDisposition.APPLIED,
                        PublishedForkService.FORKED_REASON, before.savedAt(), scenario.context,
                        null, null, forked.draftHash()))))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_FORK_CONFLICT);
                    assertThat(failure.invariant()).isEqualTo("forkBase");
                });
        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }

    /**
     * R1 requirement 1: Published Definition ownership is proved before {@code campaign.edit}, so a version
     * owned by another user inside the same tenant is not distinguishable from an absent one even for a
     * caller who also lacks the permission.
     */
    @Test
    void aSameTenantPublishedDefinitionOwnedByAnotherUserIsNotFoundEvenWithoutEditPermission() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        long revision = published.publishedIdentity().revision();
        TrustedExecutionContext lowPermissionOtherUser =
                LifecycleTestFixtures.otherUserWithoutEditContext();
        assertThat(lowPermissionOtherUser.hasPermission(CampaignPermission.CAMPAIGN_EDIT)).isFalse();

        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand(published.publishedId(), revision), lowPermissionOtherUser))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand("published-definition-000000999999", revision),
                lowPermissionOtherUser))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }

    /** R1 requirement 2: a fork command without its trusted context fails closed with a stable code. */
    @Test
    void aMissingTrustedContextFailsClosedWithAStableCode() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());

        assertThatThrownBy(() -> scenario.forkService().fork(
                new ForkPublishedCommand(published.publishedId(), published.publishedIdentity().revision()),
                null))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
                    assertThat(failure.invariant()).isEqualTo("trustedContext");
                });
        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }
}