package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Host-injected execution context for one resolution, preview or evaluation.
 *
 * <p>Every field here comes from the server-side security context, a frozen snapshot or a fixture. It is
 * never accepted from model output, and no model-callable Tool payload may carry these field names — see
 * {@link ToolInput}. The context is modelled as an immutable reference rather than being copied into the
 * payload so that a forged same-named field in a payload has nothing to bind to.
 *
 * <p>{@code asOf} is the evaluation instant and the exclusive upper bound for event time unless Metadata
 * says otherwise; {@code dataWatermark} is what the data source had actually observed. A watermark later
 * than {@code asOf} is contradictory and is rejected at construction.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TrustedExecutionContextRef(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String tenantId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String programId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String userId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String dataScopeId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String metadataSnapshotId,
        @NotNull @Valid LocaleTag locale,
        @NotNull ZoneId timezone,
        @NotBlank @Pattern(regexp = ContractPatterns.CURRENCY) String reportingCurrency,
        @NotNull Instant asOf,
        @NotNull Instant dataWatermark
) {
    public TrustedExecutionContextRef {
        if (asOf != null && dataWatermark != null && dataWatermark.isAfter(asOf)) {
            throw new IllegalArgumentException(
                    "dataWatermark " + dataWatermark + " must not be after asOf " + asOf);
        }
    }
}