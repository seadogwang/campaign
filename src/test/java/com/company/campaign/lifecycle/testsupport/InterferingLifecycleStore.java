package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.lifecycle.ForkCommit;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.PublishCommit;
import com.company.campaign.lifecycle.PublishPreparation;
import com.company.campaign.lifecycle.PublishPreparationCommit;
import com.company.campaign.lifecycle.PublishReceipt;
import com.company.campaign.lifecycle.SavedCampaignDraft;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link LifecycleStore} that runs one chosen interference immediately before the first commit delegates.
 *
 * <p>It exists so a test can build a deterministic interleaving instead of sleeping and hoping: the
 * interference runs on the committing thread, exactly once, at the precise instant between the service's
 * precheck and the transaction's own re-proof. Whatever it does — a real concurrent Apply, a second
 * publication, a second fork — the commit under test must then refuse that interleaving on its own.
 */
public final class InterferingLifecycleStore extends DelegatingLifecycleStore {

    private final Runnable interference;
    private final AtomicBoolean fired = new AtomicBoolean();

    public InterferingLifecycleStore(LifecycleStore delegate, Runnable interference) {
        super(delegate);
        this.interference = Objects.requireNonNull(interference, "interference");
    }

    @Override
    public PublishPreparation commitPreparation(PublishPreparationCommit commit) {
        interfereOnce();
        return delegate.commitPreparation(commit);
    }

    @Override
    public PublishReceipt commitPublish(PublishCommit commit) {
        interfereOnce();
        return delegate.commitPublish(commit);
    }

    @Override
    public SavedCampaignDraft commitFork(ForkCommit commit) {
        interfereOnce();
        return delegate.commitFork(commit);
    }

    /** True once the interference has run, so a test can prove the interleaving really happened. */
    public boolean interfered() {
        return fired.get();
    }

    private void interfereOnce() {
        if (fired.compareAndSet(false, true)) {
            interference.run();
        }
    }
}