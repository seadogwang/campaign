package com.company.campaign.runtime.testsupport;

import com.company.campaign.runtime.RuntimeCampaignState;
import com.company.campaign.runtime.RuntimeCampaignStatePort;
import com.company.campaign.runtime.RuntimeCampaignStatus;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A deterministic, scriptable host Campaign activation projection for T22 tests.
 *
 * <p>It answers for exactly one owner, so a test can also prove that another tenant, program or Campaign sees
 * nothing at all. It can be paused, resumed and made absent, which is the whole of what a worker rechecks; it
 * holds no member data and calls nothing.
 */
public final class ScriptedCampaignStatePort implements RuntimeCampaignStatePort {

    private final String tenantId;
    private final String programId;
    private final String campaignId;
    private ContentHash fullDefinitionHash;
    private RuntimeCampaignStatus status = RuntimeCampaignStatus.ACTIVE;
    private long stateRevision = 1L;
    private Instant updatedAt;
    private boolean present = true;

    public ScriptedCampaignStatePort(
            String tenantId,
            String programId,
            String campaignId,
            ContentHash fullDefinitionHash,
            Instant updatedAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.programId = Objects.requireNonNull(programId, "programId");
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.fullDefinitionHash = Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public Optional<RuntimeCampaignState> findCampaignState(String tenantId, String campaignId) {
        if (!present || !this.tenantId.equals(tenantId) || !this.campaignId.equals(campaignId)) {
            return Optional.empty();
        }
        return Optional.of(RuntimeCampaignState.of(
                this.tenantId, programId, this.campaignId, fullDefinitionHash, status, stateRevision,
                updatedAt));
    }

    /** Pauses the Campaign so no new local node and no new handoff may start. */
    public ScriptedCampaignStatePort pause() {
        return transition(RuntimeCampaignStatus.PAUSED);
    }

    /** Resumes the Campaign so guarded due work becomes eligible again. */
    public ScriptedCampaignStatePort resume() {
        return transition(RuntimeCampaignStatus.ACTIVE);
    }

    /** Makes the host hold no projection at all, so a worker must fail closed. */
    public ScriptedCampaignStatePort absent() {
        this.present = false;
        return this;
    }

    /** Makes the host hold a projection again. */
    public ScriptedCampaignStatePort present() {
        this.present = true;
        return this;
    }

    /** Replaces the active full-definition hash, as a newer publication would. */
    public ScriptedCampaignStatePort published(ContentHash replaced) {
        this.fullDefinitionHash = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** The activation status this projection currently answers. */
    public RuntimeCampaignStatus status() {
        return status;
    }

    private ScriptedCampaignStatePort transition(RuntimeCampaignStatus replaced) {
        this.status = replaced;
        this.stateRevision++;
        return this;
    }

    /** Redacted diagnostics: the fixture holds no owner value. */
    @Override
    public String toString() {
        return "ScriptedCampaignStatePort[status=" + status + ", revision=" + stateRevision + ']';
    }
}