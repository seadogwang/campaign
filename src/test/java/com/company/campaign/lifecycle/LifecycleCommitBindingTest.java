package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.createPlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17-R1 requirement 3: a public commit constructor must not let a host-side caller pair artifacts that are
 * each individually self-valid but mutually inconsistent.
 *
 * <p>Every case below builds a genuine artifact from a real accepted command and then varies exactly one
 * field of a copy. The copy is still a valid, self-hashing record; only the pairing is wrong, and the commit
 * must refuse it before an adapter could ever store it.
 */
class LifecycleCommitBindingTest {

    // ------------------------------------------------------------------ Apply

    @Test
    void anApplyCommitRefusesAReceiptThatWitnessesDifferentMaterialThanItsDraft() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);
        ApplyResult result = service.apply(new ApplyCommand(plan.planId(), plan.planHash()),
                LifecycleTestFixtures.context());

        SavedCampaignDraft draft = store.findDraft(result.campaignId()).orElseThrow();
        ApplyReceipt receipt = store.findApplyReceipt(plan.planId()).orElseThrow();
        LifecycleAuditEvent audit = appliedAudit(plan, draft, LifecycleTestFixtures.context());

        // The control: the genuine, mutually consistent commit is representable.
        assertThat(new ApplyCommit(plan.planId(), plan.planHash(), null, null, null, null, draft, receipt,
                audit)).isNotNull();

        for (ApplyReceipt inconsistent : List.of(
                copyOf(receipt, receipt.tenantId(), receipt.programId(), "user-002", receipt.planId(),
                        receipt.manifestHash(), receipt.contentHash(), receipt.campaignRevision(),
                        receipt.draftRevision(), receipt.planHash(), receipt.campaignId(), receipt.draftId()),
                copyOf(receipt, receipt.tenantId(), receipt.programId(), receipt.userId(), receipt.planId(),
                        otherHash("other-manifest"), receipt.contentHash(), receipt.campaignRevision(),
                        receipt.draftRevision(), receipt.planHash(), receipt.campaignId(), receipt.draftId()),
                copyOf(receipt, receipt.tenantId(), receipt.programId(), receipt.userId(), receipt.planId(),
                        receipt.manifestHash(), otherHash("other-content"), receipt.campaignRevision(),
                        receipt.draftRevision(), receipt.planHash(), receipt.campaignId(), receipt.draftId()),
                copyOf(receipt, receipt.tenantId(), receipt.programId(), receipt.userId(), receipt.planId(),
                        receipt.manifestHash(), receipt.contentHash(), receipt.campaignRevision() + 1L,
                        receipt.draftRevision(), receipt.planHash(), receipt.campaignId(), receipt.draftId()),
                copyOf(receipt, receipt.tenantId(), receipt.programId(), receipt.userId(), receipt.planId(),
                        receipt.manifestHash(), receipt.contentHash(), receipt.campaignRevision(),
                        receipt.draftRevision(), otherHash("other-plan"),
                        receipt.campaignId(), receipt.draftId()))) {
            assertThatThrownBy(() -> new ApplyCommit(plan.planId(), plan.planHash(), null, null, null, null,
                    draft, inconsistent, audit))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // A receipt that binds the plan but a Draft that descends from another plan is refused too.
        SavedCampaignDraft foreignDraft = SavedCampaignDraft.of(
                draft.tenantId(), draft.programId(), draft.userId(), draft.campaignId(),
                draft.campaignRevision(), draft.draftId(), draft.draftRevision(), draft.forkBase(),
                draft.manifest(), "plan-000000000099", draft.savedAt());
        assertThatThrownBy(() -> new ApplyCommit(plan.planId(), plan.planHash(), null, null, null, null,
                foreignDraft, receipt, audit))
                .isInstanceOf(IllegalArgumentException.class);

        // And an audit event that records a different action for the same commit is refused.
        LifecycleAuditEvent wrongAction = LifecycleAuditEvent.of(
                LifecycleAction.PUBLISH, LifecycleDisposition.APPLIED, ApplyService.APPLIED_REASON,
                draft.savedAt(), LifecycleTestFixtures.context(), null, plan.planHash(), draft.draftHash());
        assertThatThrownBy(() -> new ApplyCommit(plan.planId(), plan.planHash(), null, null, null, null,
                draft, receipt, wrongAction))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ prepare

    @Test
    void aPreparationCommitRefusesAnAuditEventThatDoesNotWitnessItsPreparation() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult prepared = scenario.prepare();
        PublishPreparation stored = scenario.store.findPreparation(prepared.preparationId()).orElseThrow();

        LifecycleAuditEvent genuine = LifecycleAuditEvent.of(
                LifecycleAction.PREPARE_PUBLISH, LifecycleDisposition.APPLIED,
                PublishPreparationService.PREPARED_REASON, stored.createdAt(), scenario.context,
                null, stored.planHash(), stored.draftHash());
        assertThat(new PublishPreparationCommit(
                stored.campaignId(), stored.draftId(), stored.draftRevision(), stored, genuine)).isNotNull();

        LifecycleAuditEvent wrongAction = LifecycleAuditEvent.of(
                LifecycleAction.PUBLISH, LifecycleDisposition.APPLIED,
                PublishPreparationService.PREPARED_REASON, stored.createdAt(), scenario.context,
                null, stored.planHash(), stored.draftHash());
        assertThatThrownBy(() -> new PublishPreparationCommit(
                stored.campaignId(), stored.draftId(), stored.draftRevision(), stored, wrongAction))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ publish

    @Test
    void aPublishCommitRefusesAReceiptThatDisagreesWithItsDefinition() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult prepared = scenario.prepare();
        scenario.confirm(prepared, "key-1", List.of());

        PublishedCampaignDefinition definition = scenario.store
                .findLatestPublished(scenario.campaignId()).orElseThrow();
        PublishReceipt receipt = scenario.store.findPublishReceipt(
                scenario.context.tenantId(), scenario.context.programId(), scenario.context.userId(),
                prepared.preparationId(), "key-1").orElseThrow();
        LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                LifecycleAction.PUBLISH, LifecycleDisposition.APPLIED, PublishService.PUBLISHED_REASON,
                definition.publishedAt(), scenario.context, null, definition.planHash(),
                definition.contentChecksum());

        assertThat(new PublishCommit(
                prepared.preparationId(), prepared.preparationHash(), "key-1", receipt.requestHash(),
                definition.publishedRevision(), definition, receipt, audit)).isNotNull();

        // Every one of these is a self-valid receipt or pair; only the pairing with the definition is wrong.
        assertThatThrownBy(() -> new PublishCommit(
                prepared.preparationId(), prepared.preparationHash(), "key-1", receipt.requestHash(),
                definition.publishedRevision(), definition,
                publishReceiptCopy(receipt, receipt.campaignRevision() + 1L, receipt.draftRevision(),
                        receipt.planId(), receipt.manifestHash(), receipt.contentChecksum(),
                        receipt.confirmedRiskCodes()),
                audit))
                .as("a receipt naming a different campaign revision is refused")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PublishCommit(
                prepared.preparationId(), prepared.preparationHash(), "key-1", receipt.requestHash(),
                definition.publishedRevision(), definition,
                publishReceiptCopy(receipt, receipt.campaignRevision(), receipt.draftRevision(),
                        receipt.planId(), otherHash("other-manifest"), receipt.contentChecksum(),
                        receipt.confirmedRiskCodes()),
                audit))
                .as("a receipt naming a different manifest is refused")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PublishCommit(
                prepared.preparationId(), prepared.preparationHash(), "key-1", receipt.requestHash(),
                definition.publishedRevision() + 1L, definition, receipt, audit))
                .as("an expected revision that is not the definition's own is refused")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ fork

    @Test
    void aForkCommitRefusesADraftThatReusesTheIdentityItForksFrom() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult published = scenario.confirm(scenario.prepare(), "key-1", List.of());
        SavedCampaignDraft before = scenario.store.findDraft(scenario.campaignId()).orElseThrow();

        SavedCampaignDraft reusing = SavedCampaignDraft.of(
                before.tenantId(), before.programId(), before.userId(), scenario.campaignId(),
                before.campaignRevision() + 1L, before.draftId(), 1L, published.publishedIdentity(),
                before.manifest(), published.publishedId(), before.savedAt());
        LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                LifecycleAction.FORK_PUBLISHED, LifecycleDisposition.APPLIED,
                PublishedForkService.FORKED_REASON, before.savedAt(), scenario.context, null, null,
                reusing.draftHash());

        assertThatThrownBy(() -> new ForkCommit(
                scenario.campaignId(), before.campaignRevision(), before.draftIdentity(), before.draftHash(),
                reusing, audit))
                .isInstanceOf(IllegalArgumentException.class);

        // An audit event that does not witness the forked Draft's own content hash is refused as well.
        SavedCampaignDraft forked = SavedCampaignDraft.of(
                before.tenantId(), before.programId(), before.userId(), scenario.campaignId(),
                before.campaignRevision() + 1L, "draft-000000999999", 1L, published.publishedIdentity(),
                before.manifest(), published.publishedId(), before.savedAt());
        LifecycleAuditEvent wrongHash = LifecycleAuditEvent.of(
                LifecycleAction.FORK_PUBLISHED, LifecycleDisposition.APPLIED,
                PublishedForkService.FORKED_REASON, before.savedAt(), scenario.context, null, null,
                otherHash("other-draft"));
        assertThatThrownBy(() -> new ForkCommit(
                scenario.campaignId(), before.campaignRevision(), before.draftIdentity(), before.draftHash(),
                forked, wrongHash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static LifecycleAuditEvent appliedAudit(
            CampaignPlan plan, SavedCampaignDraft draft, TrustedExecutionContext context) {
        return LifecycleAuditEvent.of(
                LifecycleAction.APPLY, LifecycleDisposition.APPLIED, ApplyService.APPLIED_REASON,
                draft.savedAt(), context, null, plan.planHash(), draft.draftHash());
    }

    private static ApplyReceipt copyOf(
            ApplyReceipt receipt,
            String tenantId,
            String programId,
            String userId,
            String planId,
            ContentHash manifestHash,
            ContentHash contentHash,
            long campaignRevision,
            long draftRevision,
            ContentHash planHash,
            String campaignId,
            String draftId) {
        return ApplyReceipt.of(
                receipt.receiptId(), tenantId, programId, userId, planId, planHash, campaignId,
                campaignRevision, draftId, draftRevision, manifestHash, contentHash, receipt.appliedAt());
    }

    private static PublishReceipt publishReceiptCopy(
            PublishReceipt receipt,
            long campaignRevision,
            long draftRevision,
            String planId,
            ContentHash manifestHash,
            ContentHash contentChecksum,
            List<String> riskCodes) {
        return PublishReceipt.of(
                receipt.receiptId(), receipt.tenantId(), receipt.programId(), receipt.userId(),
                receipt.dataScopeId(), receipt.preparationId(), receipt.preparationHash(),
                receipt.idempotencyKey(), receipt.requestHash(), receipt.publishedId(),
                receipt.publishedRevision(), receipt.campaignId(), campaignRevision, receipt.draftId(),
                draftRevision, planId, receipt.planHash(), manifestHash, contentChecksum, riskCodes,
                receipt.publishedAt());
    }

    private static ContentHash otherHash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }
}