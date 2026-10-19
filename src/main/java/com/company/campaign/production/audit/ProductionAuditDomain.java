package com.company.campaign.production.audit;

/**
 * The closed audit domain vocabulary of the durable ledger.
 *
 * <p>One domain names one accepted audit port. It is closed on purpose: a stored row can only belong to a
 * boundary this module understands, so an unknown domain is refused by the codec rather than stored as an
 * opaque string. The wire name is the enum name, which is a bounded uppercase machine code by construction.
 */
public enum ProductionAuditDomain {

    /** {@code com.company.campaign.security.audit.AuditSink}. */
    SECURITY,

    /** {@code com.company.campaign.conversation.CampaignConversationAuditSink}. */
    CONVERSATION,

    /** {@code com.company.campaign.lifecycle.LifecycleAuditSink}. */
    LIFECYCLE,

    /** {@code com.company.campaign.runtime.RuntimeAuditSink}. */
    RUNTIME,

    /** {@code com.company.campaign.action.ActionAuditSink}. */
    ACTION;

    /** The bounded wire name of this domain. */
    public String wireName() {
        return name();
    }

    /**
     * Resolves one bounded wire name, refusing an unknown domain with the codec's stable code.
     *
     * <p>The match is exact: the vocabulary is closed and its names are already the canonical uppercase form, so
     * a differently cased or unknown value is a malformed document rather than a domain to guess at.
     */
    public static ProductionAuditDomain fromWireName(String wireName) {
        if (wireName != null) {
            for (ProductionAuditDomain domain : values()) {
                if (domain.name().equals(wireName)) {
                    return domain;
                }
            }
        }
        throw ProductionAuditCodec.invalid("domain");
    }
}