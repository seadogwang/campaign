package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.PublishedCampaignDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The narrow, additive read port a Runtime delivery resolves exact committed T21 work and its frozen
 * definitions through.
 *
 * <p>It exists so the Runtime layer never reaches into an in-memory implementation's test-only getters: a
 * consumer asks for one exact work item by identity, or enumerates the committed work it may deliver, and the
 * host answers with immutable records. Nothing here mutates anything, acknowledges anything, claims a lease,
 * completes a task or exposes a mutable queue handle, and nothing here returns a Draft, a Plan, a Proposal, a
 * ChangeSet or a mutable latest pointer.
 *
 * <p>It is deliberately separate from {@link FullPublicationStore} and {@link ScheduleStore}: those are the
 * transactional write boundaries a publication crosses, while this is the read model a consumer crosses, and
 * an adapter may implement both without either contract growing a method the other needs.
 */
public interface RuntimeWorkDeliveryPort {

    /** Every committed scheduling-registration outbox event, in deterministic append order. */
    List<ScheduleRegistrationOutboxEvent> findRegistrationWork();

    /** The exact committed scheduling-registration event with the given identifier, or empty. */
    Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId);

    /** Every occurrence already committed for a Campaign, in deterministic nominal-instant order. */
    List<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId);

    /** The exact committed occurrence of one Campaign under the given stable execution key, or empty. */
    Optional<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId, String executionKey);

    /** The immutable full publication with the given identifier, or empty. */
    Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId);

    /**
     * The immutable base published definition a full publication binds, or empty.
     *
     * <p>It resolves the accepted T17 definition by its published identifier only. It never resolves a mutable
     * latest pointer, a Draft or a Plan, and the caller must still verify the returned definition's own content
     * checksum against the checksum the full definition pinned.
     */
    Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId);
}