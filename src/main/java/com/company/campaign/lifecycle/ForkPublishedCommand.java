package com.company.campaign.lifecycle;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * The only external input an explicit host fork of a Published Definition accepts.
 *
 * <p>It names the immutable published version the caller wants to edit — its identifier and its exact
 * revision — and nothing else. There is deliberately no field for a Campaign, Draft, Manifest, ChangeSet,
 * component, version pin, tenant, program, user or DataScope: the boundary loads the Published Definition
 * from server state and re-proves ownership, revision and permission before it copies anything.
 *
 * <p>The record refuses unknown fields when it is read, so a forged payload that tries to smuggle a
 * mutation document or a trusted fact alongside the accepted fields fails to bind rather than being
 * silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ForkPublishedCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String publishedId,
        @Positive long publishedRevision
) {
}