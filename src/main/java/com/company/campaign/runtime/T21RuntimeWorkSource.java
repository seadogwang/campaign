package com.company.campaign.runtime;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.RuntimeWorkDeliveryPort;
import com.company.campaign.fullpublish.ScheduleRegistrationOutboxEvent;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;

import java.util.Objects;
import java.util.Optional;

/**
 * The one adapter that projects accepted T21 committed work onto the Runtime source and definition-lookup ports.
 *
 * <p>It holds a single {@link RuntimeWorkDeliveryPort} reference and delegates every lookup unchanged. There is
 * no caching, no re-resolution of a latest pointer, no reach into an in-memory implementation's test state and
 * no method that lets a caller name a definition the host does not hold.
 *
 * <p>A host store that cannot answer propagates its own failure; this adapter never converts an unavailable
 * host into an empty success, because "the host is down" and "this work does not exist" must stay
 * distinguishable.
 */
public final class T21RuntimeWorkSource implements RuntimeWorkSourcePort, RuntimeDefinitionLookupPort {

    private final RuntimeWorkDeliveryPort work;

    public T21RuntimeWorkSource(RuntimeWorkDeliveryPort work) {
        this.work = Objects.requireNonNull(work, "work");
    }

    @Override
    public Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId) {
        return work.findRegistrationWork(eventId);
    }

    @Override
    public Optional<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId, String executionKey) {
        return work.findOccurrenceWork(campaignId, executionKey);
    }

    @Override
    public Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId) {
        return work.findFullDefinition(fullDefinitionId);
    }

    @Override
    public Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId) {
        return work.findBaseDefinition(publishedId);
    }

    /** Redacted diagnostics: the adapter holds no owner value. */
    @Override
    public String toString() {
        return "T21RuntimeWorkSource[]";
    }
}