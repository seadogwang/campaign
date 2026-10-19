package com.company.campaign.action;

/**
 * An Action audit sink backed by durable production storage.
 *
 * <p>The in-memory development sink deliberately implements only {@link ActionAuditSink}. Production hosts
 * require this narrower type so a bean name or ordering cannot promote an in-memory sink into the durable path.
 */
public interface DurableActionAuditSink extends ActionAuditSink {
}
