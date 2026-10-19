package com.company.campaign.conversation;

/**
 * Port that receives bounded, safe Campaign conversation audit records.
 *
 * <p>Production persistence belongs to a later integration task; this port exists so the turn service can
 * record a bounded fingerprint of a turn without knowing where it goes. An implementation records only the
 * fields of {@link CampaignConversationAuditRecord}, so it can never receive an utterance, an answer, a prompt,
 * a model output, a Proposal or a payload.
 */
@FunctionalInterface
public interface CampaignConversationAuditSink {

    void record(CampaignConversationAuditRecord record);

    /** An inert sink that records nothing, for a build with no audit wired. */
    static CampaignConversationAuditSink none() {
        return record -> {
        };
    }
}