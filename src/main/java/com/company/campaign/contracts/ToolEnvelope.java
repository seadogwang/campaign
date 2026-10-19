package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Envelope for one model-requested Tool call.
 *
 * <p>The payload is model-controlled, so it must be a contract that refuses trusted field names — see
 * {@link ToolInput} — and this envelope adds nothing that could reintroduce them. The host injects tenant,
 * user, program and DataScope from the server-side security context when it actually runs the Tool.
 *
 * <p>{@link #payload()} is deliberately typed as {@link ToolInput} rather than as a type parameter. A
 * generic envelope would let a caller (or a JSON document naming a permissive type) bind the payload to a
 * plain {@code Map}, which carries no reserved-field rule at all and would quietly reopen the boundary this
 * envelope exists to close. Fixing the payload type makes that substitution impossible to express rather
 * than merely discouraged.
 *
 * <p>{@link #dryRun()} is the only execution-affecting flag a model may set, and it can only make a call
 * <em>less</em> powerful: a dry run performs no external action. Apply and Publish are not Tools at all and
 * are reserved names in {@link ToolInput}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ToolEnvelope(
        @NotNull @Valid SchemaVersion schemaVersion,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String toolCallId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String toolId,
        boolean dryRun,
        @NotNull @Valid ToolInput payload
) {
}