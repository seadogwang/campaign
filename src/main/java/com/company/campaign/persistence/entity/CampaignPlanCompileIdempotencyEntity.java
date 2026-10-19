package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Durable compile idempotency record.
 *
 * <p>One row per (idempotency_key, proposal_hash) combination. The unique constraint on
 * idempotency_key alone makes the conflict durable: an INSERT that reuses the same key with
 * a different proposal hash fails at the database level rather than in application code.
 */
@TableName("campaign_plan_compile_idempotency")
public class CampaignPlanCompileIdempotencyEntity {

    private String idempotencyKey;
    private String proposalHash;
    private String planId;
    private Instant createdAt;

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getProposalHash() {
        return proposalHash;
    }

    public void setProposalHash(String proposalHash) {
        this.proposalHash = proposalHash;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}