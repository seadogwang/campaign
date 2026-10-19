package com.company.campaign.lifecycle;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The only external input an explicit host Apply accepts.
 *
 * <p>It carries a Plan identifier and the exact Plan hash and nothing else. There is deliberately no field
 * for a Manifest, ChangeSet, component, capability pin, tenant, program, user, revision, policy or catalog
 * version, readiness fact or expiry: Apply re-loads the server-stored Plan by {@link #planId() id} and
 * re-checks {@link #planHash() hash}, ownership, expiry and every base revision against trusted server
 * state, so none of those facts can be supplied — or overridden — by a caller, a model or a prompt.
 *
 * <p>The record refuses unknown fields when it is read, so a forged payload that tries to smuggle a
 * mutation document alongside the two accepted fields fails to bind rather than being silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ApplyCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String planId,
        @NotNull @Valid ContentHash planHash
) {
}