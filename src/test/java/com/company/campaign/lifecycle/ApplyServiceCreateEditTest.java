package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.InMemoryCampaignPlanStore;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.CAPABILITY;
import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.createPlan;
import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.editPlan;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17 requirement 1: CREATE and EDIT Apply succeed with exact revisions, correct {@code clientRef}
 * bindings and deterministic, self-binding hashes.
 *
 * <p>The saved Draft's semantic content is proved to be <em>exactly</em> the applied Plan's resolved
 * Manifest, so this boundary introduces no second editable truth.
 */
class ApplyServiceCreateEditTest {

    @Test
    void createApplyAllocatesServerIdentityAndSavesTheExactPlanManifest() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        ApplyResult result = service.apply(command(plan), LifecycleTestFixtures.context());

        assertThat(result.campaignRevision()).isEqualTo(1L);
        assertThat(result.draftRevision()).isEqualTo(1L);
        assertThat(result.draftIdentity().revision()).isEqualTo(1L);

        // Apply returns the compiler-produced clientRef -> logicalId bindings.
        assertThat(result.clientRefBindings()).isNotEmpty();
        assertThat(result.clientRefBindings()).containsExactlyElementsOf(plan.clientRefBindings());

        SavedCampaignDraft draft = store.findDraft(result.campaignId()).orElseThrow();
        assertThat(draft.manifest()).isEqualTo(plan.manifest());
        assertThat(draft.draftRevision()).isEqualTo(1L);
        assertThat(draft.campaignRevision()).isEqualTo(1L);
        assertThat(draft.sourcePlanId()).isEqualTo(plan.planId());
        assertThat(draft.draftHash()).isEqualTo(result.draftHash());
        assertThat(draft.manifestHash()).isEqualTo(result.manifestHash());
        assertThat(draft.isFork()).isFalse();
        assertThat(draft.manifest().components())
                .extracting(component -> component.capabilityCode())
                .containsExactly(CAPABILITY);

        // The server-side receipt the result projects binds the exact Plan that was applied.
        ApplyReceipt receipt = store.findApplyReceipt(plan.planId()).orElseThrow();
        assertThat(receipt.planId()).isEqualTo(plan.planId());
        assertThat(receipt.planHash()).isEqualTo(plan.planHash());
        assertThat(receipt.manifestHash()).isEqualTo(plan.manifestHash());
        assertThat(receipt.contentHash()).isEqualTo(draft.draftHash());
        assertThat(receipt.campaignId()).isEqualTo(result.campaignId());
    }

    @Test
    void editApplyAdvancesTheCampaignAndDraftRevisionsExactly() {
        CampaignPlan createPlan = createPlan();
        InMemoryCampaignPlanStore plans = LifecycleTestFixtures.planStore(createPlan);
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(plans, store);

        ApplyResult created = service.apply(command(createPlan), LifecycleTestFixtures.context());
        SavedCampaignDraft base = store.findDraft(created.campaignId()).orElseThrow();
        assertThat(base.draftRevision()).isEqualTo(1L);

        CampaignPlan edit = editPlan(base, "updated");
        plans.store(edit);
        ApplyResult edited = service.apply(command(edit), LifecycleTestFixtures.context());

        assertThat(edited.campaignId()).isEqualTo(created.campaignId());
        assertThat(edited.draftId()).isEqualTo(created.draftId());
        assertThat(edited.campaignRevision()).isEqualTo(2L);
        assertThat(edited.draftRevision()).isEqualTo(2L);
        assertThat(edited.manifestHash()).isEqualTo(edit.manifestHash());

        SavedCampaignDraft updated = store.findDraft(base.campaignId()).orElseThrow();
        assertThat(updated.draftRevision()).isEqualTo(2L);
        assertThat(updated.campaignRevision()).isEqualTo(2L);
        assertThat(updated.manifest()).isEqualTo(edit.manifest());
        // Two distinct Plans produced exactly two receipts and one Campaign Draft.
        assertThat(store.receiptCount()).isEqualTo(2);
        assertThat(store.draftCount()).isEqualTo(1);
    }

    @Test
    void anIdenticalApplyProducesByteIdenticalReceiptsAndDraftHashes() {
        CampaignPlan plan = createPlan();

        ApplyResult first = freshService(plan).apply(command(plan), LifecycleTestFixtures.context());
        ApplyResult second = freshService(plan).apply(command(plan), LifecycleTestFixtures.context());

        assertThat(second.draftHash()).isEqualTo(first.draftHash());
        assertThat(second.manifestHash()).isEqualTo(first.manifestHash());
        assertThat(second.campaignId()).isEqualTo(first.campaignId());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void theReceiptAndDraftAreSelfBindingAndCarryNoRawPayload() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        ApplyResult result = service.apply(command(plan), LifecycleTestFixtures.context());

        ApplyReceipt receipt = store.findApplyReceipt(plan.planId()).orElseThrow();
        assertThat(receipt.receiptId()).isEqualTo("apply-receipt-000000000001");
        assertThat(receipt.receiptHash()).isNotNull();
        assertThat(receipt.toString()).doesNotContain("welcome").doesNotContain("label");
        assertThat(result.toString()).doesNotContain("welcome").doesNotContain("label");

        SavedCampaignDraft draft = store.findDraft(result.campaignId()).orElseThrow();
        assertThat(draft.toString()).doesNotContain("welcome").doesNotContain("label");
        assertThat(draft.draftHash()).isEqualTo(canonicalDraftHash(draft));
    }

    // ------------------------------------------------------------------ helpers

    private static ApplyService freshService(CampaignPlan plan) {
        return LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), LifecycleTestFixtures.store());
    }

    private static ApplyCommand command(CampaignPlan plan) {
        return new ApplyCommand(plan.planId(), plan.planHash());
    }

    /** Recomputes the Draft's canonical hash from its public material, proving it self-binds. */
    private static ContentHash canonicalDraftHash(SavedCampaignDraft draft) {
        return new CanonicalJson().hash(new SavedCampaignDraft.Material(
                draft.formatVersion(),
                draft.tenantId(),
                draft.programId(),
                draft.userId(),
                draft.campaignId(),
                draft.campaignRevision(),
                draft.draftId(),
                draft.draftRevision(),
                draft.forkBase(),
                draft.manifestHash(),
                draft.sourcePlanId(),
                draft.savedAt()));
    }
}