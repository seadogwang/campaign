package com.company.campaign.contracts;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Host-supplied reference to a Plan the server already stored.
 *
 * <p>This is the only Plan handle a client or model may present: a client cannot upload its own
 * {@code ChangeSet} or a modified manifest, because apply re-loads the stored Plan by
 * {@link #planId() id} and re-checks {@link #planHash() hash} and {@link #expiresAt() expiry}.
 *
 * <p>Expiry is evaluated with an injected {@link Clock} and never with {@code Instant.now()}. Nothing in
 * this contract's construction, validation or canonical form reads the system clock, so the same Plan
 * reference produces identical bytes and identical validation results on every machine and at every
 * wall-clock moment.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PlanRef(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String planId,
        @NotNull @Valid ContentHash planHash,
        @NotNull Instant expiresAt
) {

    /**
     * True when {@code clock} reads at or after {@link #expiresAt()}.
     *
     * <p>The boundary is inclusive: a Plan is already unusable at its exact expiry instant.
     */
    public boolean isExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return !expiresAt.isAfter(clock.instant());
    }

    /** Throws {@link PlanExpiredException} when the Plan may no longer be used. */
    public void requireNotExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        if (isExpired(clock)) {
            throw new PlanExpiredException(planId, expiresAt, clock.instant());
        }
    }
}