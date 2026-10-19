package com.company.campaign.security.audit;

/**
 * Port that receives safe security audit events.
 *
 * <p>Production persistence belongs to a later integration task; this port exists so the security core can
 * emit an audit event without knowing where it goes.
 */
@FunctionalInterface
public interface AuditSink {

    void record(SecurityAuditEvent event);
}