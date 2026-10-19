package com.company.campaign.fullpublish;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The only external input an explicit host pause or resume operation accepts.
 *
 * <p>It names the Campaign and the exact active schedule state revision the caller read, and nothing else. It
 * never carries an occurrence, an execution key, an occurrence decision, a duration or a version pin: the
 * boundary re-loads the active projection from server state and re-proves ownership and the compare-and-set.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ScheduleStateCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String campaignId,
        @PositiveOrZero long expectedStateRevision
) {

    public ScheduleStateCommand {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("a schedule state command requires a Campaign");
        }
        if (expectedStateRevision < 0) {
            throw new IllegalArgumentException("an expected schedule state revision must not be negative");
        }
    }
}