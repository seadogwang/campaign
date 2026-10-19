package com.company.campaign.lifecycle;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.lifecycle.testsupport.InterferingLifecycleStore;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17-R1 requirement 4: the service's prechecks are not a transaction boundary.
 *
 * <p>Each test builds a deterministic interleaving — a real host command executed on the committing thread
 * at the exact instant between the service's precheck and the transaction's own re-proof — and proves the
 * commit refuses it on its own, with zero mutation. Nothing here sleeps or races: the interference is
 * injected, so the interleaving is the same on every machine and every run.
 */
class LifecycleTransactionCasTest {

    // ------------------------------------------------------------------ prepare

    /**
     * A concurrent Apply between the prepare precheck and the commit must make the preparation stale.
     *
     * <p>The service read Draft revision 1 and proved it; the interference advances it to 2 before the
     * commit. Only the transaction's own re-proof can catch this, and it stores nothing.
     */
    @Test
    void anEditAppliedBetweenPreparePrecheckAndCommitMakesThePreparationStale() {
        PublishScenario scenario = PublishScenario.active();
        CampaignPlan edit = editPlan(scenario);

        InterferingLifecycleStore store = new InterferingLifecycleStore(
                scenario.store, () -> applyScenarioEdit(scenario, edit));
        PublishPreparationService service = scenario.prepareServiceOver(store);

        assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE);
                    assertThat(failure.invariant()).isEqualTo("expectedRevision");
                });

        assertThat(store.interfered()).as("the interleaving really happened").isTrue();
        assertThat(scenario.store.preparationCount()).isZero();
        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftRevision())
                .isEqualTo(scenario.draft.draftRevision() + 1L);
    }

    // ------------------------------------------------------------------ publish

    /**
     * A concurrent Apply between the publish precheck and the commit must make the publication stale with
     * zero published mutation, even though every service-side precheck had already passed.
     */
    @Test
    void anEditAppliedBetweenPublishPrecheckAndCommitPublishesNothing() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        CampaignPlan edit = editPlan(scenario);

        InterferingLifecycleStore store = new InterferingLifecycleStore(
                scenario.store, () -> applyScenarioEdit(scenario, edit));
        PublishService service = scenario.publishServiceOver(store);

        assertThatThrownBy(() -> service.confirm(
                scenario.command(preparation, "key-1", List.of()), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE);
                    assertThat(failure.invariant()).isEqualTo("savedDraft");
                });

        assertThat(store.interfered()).isTrue();
        assertThat(scenario.store.publishedCount()).isZero();
        assertThat(scenario.store.publishReceiptCount()).isZero();
        assertThat(scenario.store.isPreparationConsumed(preparation.preparationId())).isFalse();
    }

    /**
     * Two different preparations for one Campaign must not both publish revision N.
     *
     * <p>Both services observed revision 1 as the next revision. The interference publishes the second
     * preparation before the first commit runs; the first commit must then be refused as a revision conflict
     * rather than replacing the latest published pointer with a stale revision.
     */
    @Test
    void twoPreparationsForOneCampaignCannotBothPublishRevisionOne() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult first = scenario.prepare();
        PreparePublishResult second = scenario.prepare();

        // The interference publishes the second preparation through the same store and identity source.
        InterferingLifecycleStore store = new InterferingLifecycleStore(
                scenario.store, () -> assertThat(scenario.confirm(second, "key-b", List.of())
                        .publishedRevision()).isEqualTo(1L));
        PublishService service = scenario.publishServiceOver(store);

        assertThatThrownBy(() -> service.confirm(
                scenario.command(first, "key-a", List.of()), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT);
                    assertThat(failure.invariant()).isEqualTo("publishedRevision");
                });

        assertThat(store.interfered()).isTrue();
        // Exactly one definition exists, it is the interference's own first revision, and the refused
        // publication consumed nothing.
        assertThat(scenario.store.publishedCount()).isEqualTo(1);
        assertThat(scenario.store.publishReceiptCount()).isEqualTo(1);
        assertThat(scenario.store.findLatestPublished(scenario.campaignId()).orElseThrow().publishedRevision())
                .isEqualTo(1L);
        assertThat(scenario.store.isPreparationConsumed(second.preparationId())).isTrue();
        assertThat(scenario.store.isPreparationConsumed(first.preparationId())).isFalse();
    }

    // ------------------------------------------------------------------ fork

    /**
     * A fork must refuse when the editable Draft moved between the service's read and the commit, and it
     * must never write a Published Definition.
     */
    @Test
    void anEditAppliedBetweenForkPrecheckAndCommitRefusesTheFork() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        CampaignPlan edit = editPlan(scenario);

        InterferingLifecycleStore store = new InterferingLifecycleStore(
                scenario.store, () -> applyScenarioEdit(scenario, edit));
        PublishedForkService service = scenario.forkServiceOver(store);

        assertThatThrownBy(() -> service.fork(
                new ForkPublishedCommand(published.publishedId(), published.publishedRevision()),
                scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_FORK_CONFLICT);
                    assertThat(failure.invariant()).isEqualTo("expectedRevision");
                });

        assertThat(store.interfered()).isTrue();
        // The interference's own Draft revision survives untouched and nothing was published.
        SavedCampaignDraft current = scenario.store.findDraft(scenario.campaignId()).orElseThrow();
        assertThat(current.draftRevision()).isEqualTo(scenario.draft.draftRevision() + 1L);
        assertThat(current.isFork()).isFalse();
        assertThat(scenario.store.publishedCount()).isEqualTo(1);
    }

    /**
     * The fork compare-and-set covers the pre-fork Draft's content hash, not only its revision: a commit
     * whose expected hash is not the stored Draft's content is refused.
     */
    @Test
    void aForkWhoseExpectedPreForkContentHashIsWrongIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        SavedCampaignDraft before = scenario.store.findDraft(scenario.campaignId()).orElseThrow();

        SavedCampaignDraft forked = SavedCampaignDraft.of(
                before.tenantId(), before.programId(), before.userId(),
                scenario.campaignId(), before.campaignRevision() + 1L,
                "draft-000000999999", 1L, published.publishedIdentity(), before.manifest(),
                published.publishedId(), before.savedAt());
        ForkCommit commit = new ForkCommit(
                scenario.campaignId(), before.campaignRevision(), before.draftIdentity(),
                otherHash("not-the-stored-draft"), forked,
                LifecycleAuditEvent.of(
                        LifecycleAction.FORK_PUBLISHED, LifecycleDisposition.APPLIED,
                        PublishedForkService.FORKED_REASON, before.savedAt(), scenario.context,
                        null, null, forked.draftHash()));

        assertThatThrownBy(() -> scenario.store.commitFork(commit))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_FORK_CONFLICT);
                    assertThat(failure.invariant()).isEqualTo("expectedRevision");
                });

        assertThat(scenario.store.findDraft(scenario.campaignId()).orElseThrow().draftId())
                .isEqualTo(scenario.draft.draftId());
    }

    /**
     * The publish transaction re-proves the definition is the preparation's own evidence: a definition
     * naming a different fork base than the preparation froze is refused even when every other field agrees.
     */
    @Test
    void aDefinitionWhoseForkBaseIsNotThePreparationsIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        PublishPreparation stored = scenario.store
                .findPreparation(preparation.preparationId()).orElseThrow();

        PublishedCampaignDefinition mismatched = PublishedCampaignDefinition.of(
                "published-definition-000000000009", 1L,
                stored.tenantId(), stored.programId(), stored.userId(), stored.dataScopeId(),
                stored.campaignId(), stored.campaignRevision(), stored.draftId(), stored.draftRevision(),
                new PublishedIdentity("published-definition-000000000001", 1L, otherHash("other")),
                stored.planId(), stored.planHash(), stored.draftHash(),
                stored.catalogVersion(), stored.catalogHash(), stored.policyVersion(), stored.policyHash(),
                stored.capabilityPins(), stored.previewCards(), stored.requiredRiskCodes(),
                stored.readinessCapability(), stored.readinessState(), stored.gateInputsHash(),
                stored.approvedDecisionHash(), scenario.draft.manifest(), scenario.draft.savedAt());
        PublishReceipt receipt = PublishReceipt.of(
                "publish-receipt-000000000009",
                stored.tenantId(), stored.programId(), stored.userId(), stored.dataScopeId(),
                stored.preparationId(), stored.preparationHash(), "key-1",
                new PublishCommand(stored.preparationId(), stored.preparationHash(), "key-1", List.of())
                        .requestHash(),
                mismatched.publishedId(), mismatched.publishedRevision(), mismatched.campaignId(),
                mismatched.campaignRevision(), mismatched.sourceDraftId(), mismatched.sourceDraftRevision(),
                mismatched.planId(), mismatched.planHash(), mismatched.manifestHash(),
                mismatched.contentChecksum(), mismatched.riskCodes(), mismatched.publishedAt());

        PublishCommit commit = new PublishCommit(
                stored.preparationId(), stored.preparationHash(), "key-1", receipt.requestHash(), 1L,
                mismatched, receipt,
                LifecycleAuditEvent.of(
                        LifecycleAction.PUBLISH, LifecycleDisposition.APPLIED, PublishService.PUBLISHED_REASON,
                        scenario.draft.savedAt(), scenario.context, null, stored.planHash(),
                        mismatched.contentChecksum()));

        assertThatThrownBy(() -> scenario.store.commitPublish(commit))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH);
                    assertThat(failure.invariant()).isEqualTo("preparationBinding");
                });

        assertThat(scenario.store.publishedCount()).isZero();
        assertThat(scenario.store.isPreparationConsumed(preparation.preparationId())).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    /** A genuine EDIT Plan that moves the scenario's Draft on exactly one revision. */
    private static CampaignPlan editPlan(PublishScenario scenario) {
        CampaignPlan edit = PublishTestFixtures.editPlan(
                scenario.draft, SegmentAuthoringFixtures.experimental());
        scenario.plans.store(edit);
        return edit;
    }

    /** The real host Apply the interference performs, as a concurrent editor would. */
    private static void applyScenarioEdit(PublishScenario scenario, CampaignPlan edit) {
        scenario.applyService().apply(new ApplyCommand(edit.planId(), edit.planHash()), scenario.context);
    }

    private static ContentHash otherHash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }
}