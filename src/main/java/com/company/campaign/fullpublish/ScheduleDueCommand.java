package com.company.campaign.fullpublish;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.Objects;

/**
 * The only external input an explicit host due-occurrence planning operation accepts.
 *
 * <p>It names the Campaign, the exact active schedule state revision the caller read and the explicit
 * evaluation instant. There is deliberately no field for a schedule, Journey, occurrence, execution key,
 * materialized member set, readiness decision, owner, DataScope or version pin: the boundary re-loads the
 * active projection and the frozen published definition from server state and re-proves every one of those
 * facts.
 *
 * <p>{@code asOf} is an evaluation instant, never a trusted security fact, and it may not be in the future of
 * the host's injected clock: a caller cannot bring work forward by naming a later instant.
 *
 * <p>The record refuses unknown fields when it is read, so a forged payload that tries to smuggle a mutation
 * document or a readiness fact alongside the accepted fields fails to bind rather than being silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ScheduleDueCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String campaignId,
        @PositiveOrZero long expectedStateRevision,
        @NotNull Instant asOf
) {

    public ScheduleDueCommand {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(asOf, "asOf");
        if (expectedStateRevision < 0) {
            throw new IllegalArgumentException("an expected schedule state revision must not be negative");
        }
    }
}