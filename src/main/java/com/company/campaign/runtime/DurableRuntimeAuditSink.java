package com.company.campaign.runtime;

/**
 * A Runtime audit sink backed by durable production storage.
 *
 * <p>The in-memory development sink deliberately implements only {@link RuntimeAuditSink}. Production hosts
 * require this narrower type so a bean name or ordering cannot promote an in-memory sink into the durable path.
 */
public interface DurableRuntimeAuditSink extends RuntimeAuditSink {
}
