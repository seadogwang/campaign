package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Envelope for one Agent run request or response.
 *
 * <p>The envelope carries only Agent identity and a typed payload. It deliberately has no tenant, user,
 * permission or DataScope field: the host pairs an Agent run with a {@link TrustedExecutionContextRef}
 * from the server-side security context, so a model cannot assert its own identity by shaping the
 * envelope.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AgentEnvelope<T>(
        @NotNull @Valid SchemaVersion schemaVersion,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String agentRunId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String conversationId,
        @NotNull @Valid T payload
) {
}