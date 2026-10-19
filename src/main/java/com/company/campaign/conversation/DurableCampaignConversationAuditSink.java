package com.company.campaign.conversation;

/**
 * The type marker of a durable Campaign conversation audit sink.
 *
 * <p>It exists so a production wiring requirement can be expressed as a type rather than as a runtime name
 * check or a comment: a production Campaign host that requires durable conversation audit persistence resolves
 * this interface, and the development in-memory sink — which implements only {@link CampaignConversationAuditSink}
 * — cannot satisfy it and is therefore rejected by type. The accepted durable adapter is the production
 * conversation audit sink over the append-only audit ledger, and a host that owns its own durable sink
 * implements this interface directly.
 *
 * <p>It adds no behavior of its own: an implementation still records only the bounded fields of
 * {@link CampaignConversationAuditRecord}.
 */
public interface DurableCampaignConversationAuditSink extends CampaignConversationAuditSink {
}