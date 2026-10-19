package com.company.campaign.lifecycle;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * The only external input an explicit host Prepare Publish accepts.
 *
 * <p>It names the saved Draft the caller believes it is preparing — its Campaign, its Draft identity and
 * the exact Draft revision — and nothing else. There is deliberately no field for a Manifest, ChangeSet,
 * component, capability pin, Published Definition, readiness projection, risk code, expiry, tenant,
 * program, user or DataScope: the boundary re-loads the Draft from server state and re-proves ownership,
 * revision, frozen inputs, risks and production Segment activation.
 *
 * <p>The record refuses unknown fields when it is read, so a forged payload that tries to smuggle a
 * mutation document or a readiness fact alongside the accepted fields fails to bind rather than being
 * silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PreparePublishCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String campaignId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String draftId,
        @Positive long draftRevision
) {
}