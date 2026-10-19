package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.lifecycle.ApplyCommit;
import com.company.campaign.lifecycle.ApplyReceipt;
import com.company.campaign.lifecycle.ForkCommit;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.PublishCommit;
import com.company.campaign.lifecycle.PublishPreparation;
import com.company.campaign.lifecycle.PublishPreparationCommit;
import com.company.campaign.lifecycle.PublishReceipt;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.lifecycle.SavedCampaignDraft;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link LifecycleStore} that forwards every call to a real store, so a test can inject exactly one failure
 * without re-implementing the whole port.
 *
 * <p>Subclasses override the single method under test; everything else keeps the genuine synchronized
 * adapter's behaviour, so the rollback a test observes is caused by the injected failure and not by a
 * stand-in store.
 */
public class DelegatingLifecycleStore implements LifecycleStore {

    protected final LifecycleStore delegate;

    public DelegatingLifecycleStore(LifecycleStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<SavedCampaignDraft> findDraft(String campaignId) {
        return delegate.findDraft(campaignId);
    }

    @Override
    public Optional<ApplyReceipt> findApplyReceipt(String planId) {
        return delegate.findApplyReceipt(planId);
    }

    @Override
    public ApplyReceipt commitApply(ApplyCommit commit) {
        return delegate.commitApply(commit);
    }

    @Override
    public Optional<PublishPreparation> findPreparation(String preparationId) {
        return delegate.findPreparation(preparationId);
    }

    @Override
    public boolean isPreparationConsumed(String preparationId) {
        return delegate.isPreparationConsumed(preparationId);
    }

    @Override
    public PublishPreparation commitPreparation(PublishPreparationCommit commit) {
        return delegate.commitPreparation(commit);
    }

    @Override
    public Optional<PublishReceipt> findPublishReceipt(
            String tenantId, String programId, String userId, String preparationId, String idempotencyKey) {
        return delegate.findPublishReceipt(tenantId, programId, userId, preparationId, idempotencyKey);
    }

    @Override
    public Optional<PublishedCampaignDefinition> findPublished(String publishedId) {
        return delegate.findPublished(publishedId);
    }

    @Override
    public Optional<PublishedCampaignDefinition> findLatestPublished(String campaignId) {
        return delegate.findLatestPublished(campaignId);
    }

    @Override
    public long nextPublishedRevision(String campaignId) {
        return delegate.nextPublishedRevision(campaignId);
    }

    @Override
    public PublishReceipt commitPublish(PublishCommit commit) {
        return delegate.commitPublish(commit);
    }

    @Override
    public SavedCampaignDraft commitFork(ForkCommit commit) {
        return delegate.commitFork(commit);
    }

    @Override
    public void recordCommandAudit(LifecycleAuditEvent event) {
        delegate.recordCommandAudit(event);
    }
}