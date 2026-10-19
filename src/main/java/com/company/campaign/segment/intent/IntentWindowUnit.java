package com.company.campaign.segment.intent;

/**
 * Phase A duration units allowed in a ROLLING intent window.
 *
 * <p>Only {@code DAY} (24 hours) and {@code WEEK} (7 days) have a fixed duration. Calendar units are
 * intentionally absent: accepting {@code MONTH} or {@code YEAR} as a rolling amount would silently pick an
 * interval whose meaning depends on the calendar, which Phase A forbids.
 */
public enum IntentWindowUnit {

    DAY,
    WEEK
}