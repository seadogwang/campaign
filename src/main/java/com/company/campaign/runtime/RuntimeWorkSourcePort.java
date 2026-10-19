package com.company.campaign.runtime;

import com.company.campaign.fullpublish.ScheduleRegistrationOutboxEvent;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;

import java.util.Optional;

/**
 * The narrow host port through which Runtime accepts exact, already-committed T21 scheduling work.
 *
 * <p>It is deliberately a lookup by identity rather than an enumeration or a test hook: a delivery names one
 * exact work item, the host answers with the immutable record it committed, and the consumer re-proves every
 * binding itself. There is no getter into an in-memory implementation's test state, no mutable queue handle and
 * no method that accepts a caller-supplied event, occurrence or security fact.
 *
 * <p>A host that cannot answer throws; a host that holds no such work returns empty, and the consumer turns
 * that into a bounded {@link RuntimeErrorCodes#RUNTIME_SOURCE_NOT_FOUND} rather than an empty success.
 */
public interface RuntimeWorkSourcePort {

    /** The exact committed scheduling-registration outbox event with the given identifier, or empty. */
    Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId);

    /** The exact committed occurrence of one Campaign under the given stable execution key, or empty. */
    Optional<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId, String executionKey);
}