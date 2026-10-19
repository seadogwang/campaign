package com.company.campaign.metadata.domain;

/** Closed event-time semantics for a metric or an optional relation event-time capability. */
public enum EventTimeSemantics {

    /** The referenced field is the business event time. */
    EVENT_TIME,

    /** The referenced field is the ingestion/arrival time. */
    INGESTION_TIME,

    /** No event time is defined. */
    NOT_APPLICABLE
}