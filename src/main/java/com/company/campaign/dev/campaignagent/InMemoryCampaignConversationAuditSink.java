package com.company.campaign.dev.campaignagent;

import com.company.campaign.conversation.CampaignConversationAuditRecord;
import com.company.campaign.conversation.CampaignConversationAuditSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory {@link CampaignConversationAuditSink} for the local development slice only.
 *
 * <p>It is intentionally not thread-safe and is <strong>not production audit persistence</strong>. It receives
 * only the bounded fields of {@link CampaignConversationAuditRecord}, so it can never hold an utterance, an
 * answer, a prompt, a model output, a Proposal or a payload.
 */
public final class InMemoryCampaignConversationAuditSink implements CampaignConversationAuditSink {

    private final List<CampaignConversationAuditRecord> records = new ArrayList<>();

    @Override
    public void record(CampaignConversationAuditRecord record) {
        records.add(Objects.requireNonNull(record, "record"));
    }

    /** Immutable snapshot of the recorded events, in insertion order. */
    public List<CampaignConversationAuditRecord> records() {
        return List.copyOf(records);
    }

    public int size() {
        return records.size();
    }

    public void clear() {
        records.clear();
    }
}