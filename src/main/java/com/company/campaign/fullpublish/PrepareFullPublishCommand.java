package com.company.campaign.fullpublish;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The only external input an explicit host Prepare Full Publish accepts.
 *
 * <p>It names the accepted server-stored T17 publish preparation the caller believes it is extending — its
 * identifier and exact hash — and nothing else. There is deliberately no field for a Manifest, Journey,
 * Schedule, topology, pack index, runtime-availability pin, owner, DataScope, expiry, risk code or version
 * pin: the boundary re-loads the preparation and the saved Draft from server state and re-proves ownership,
 * revision, frozen inputs, the Journey, the Schedule and host runtime availability.
 *
 * <p>The record refuses unknown fields when it is read, so a forged payload that tries to smuggle a mutation
 * document or a readiness fact alongside the accepted fields fails to bind rather than being silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PrepareFullPublishCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String basePreparationId,
        @NotNull ContentHash basePreparationHash
) {
}