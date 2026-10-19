package com.company.campaign.runtime.testsupport;

import com.company.campaign.fullpublish.CampaignScheduleState;
import com.company.campaign.fullpublish.FullPublishScenario;
import com.company.campaign.runtime.RuntimeCampaignState;
import com.company.campaign.runtime.RuntimeCampaignStatePort;
import com.company.campaign.runtime.RuntimeCampaignStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * The host-owned Campaign runtime/schedule projection over the accepted T21 schedule state.
 *
 * <p>It maps the real accepted {@link CampaignScheduleState} the T21 boundary commits onto the Runtime
 * activation projection, so a pause and a resume applied through the accepted scheduling boundary are exactly
 * what a Runtime worker observes — nothing here fabricates a status. A Campaign whose T21 state is absent, or
 * whose owner does not match the caller's, yields empty and the worker fails closed.
 */
public final class T21CampaignStatePort implements RuntimeCampaignStatePort {

    private final FullPublishScenario publication;

    public T21CampaignStatePort(FullPublishScenario publication) {
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    @Override
    public Optional<RuntimeCampaignState> findCampaignState(String tenantId, String campaignId) {
        CampaignScheduleState state = publication.store.findScheduleState(campaignId).orElse(null);
        if (state == null || !state.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(RuntimeCampaignState.of(
                state.tenantId(), state.programId(), state.campaignId(), state.fullDefinitionHash(),
                state.planningAllowed() ? RuntimeCampaignStatus.ACTIVE : RuntimeCampaignStatus.PAUSED,
                state.stateRevision(), state.updatedAt()));
    }

    /** Redacted diagnostics: the adapter holds no owner value. */
    @Override
    public String toString() {
        return "T21CampaignStatePort[]";
    }
}