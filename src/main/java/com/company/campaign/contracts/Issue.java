package com.company.campaign.contracts;

import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Machine-readable problem reported by a deterministic stage.
 *
 * <p>Every issue carries a {@link #code() stable code} that callers can branch on; {@link #message()} is
 * user-safe prose and is never the machine interface. Optional location fields are omitted from the wire
 * form when absent, so two issues that differ only in unset optional fields serialize identically.
 *
 * <p>{@link #details()} is defensively deep-frozen, so a caller cannot mutate an issue after it has been
 * hashed or attached to a result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record Issue(
        @NotBlank String code,
        @NotNull IssueSeverity severity,
        String path,
        String nodeId,
        @Valid SourceSpan sourceSpan,
        @NotBlank String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) @NotNull Map<String, Object> details
) {
    public Issue {
        details = ImmutableValues.freezeMap(details);
    }

    /** An error issue without location or details. */
    public static Issue error(String code, String message) {
        return new Issue(code, IssueSeverity.ERROR, null, null, null, message, Map.of());
    }

    public boolean blocksProcessing() {
        return severity.blocksProcessing();
    }
}