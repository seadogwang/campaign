package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Envelope for one versioned Skill invocation.
 *
 * <p>{@link #skillId()} and {@link #skillVersion()} are recorded on every invocation because a Skill is
 * versioned usage guidance, and a result is only reproducible if the exact Skill version that produced it
 * is known. Like {@link AgentEnvelope}, this envelope carries no trusted identity fields.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SkillEnvelope<T>(
        @NotNull @Valid SchemaVersion schemaVersion,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String invocationId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String skillId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String skillVersion,
        @NotNull @Valid T payload
) {
}