package com.company.campaign.contracts;

import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Explicit statement of why a Tool call did not resolve.
 *
 * <p>A Tool call that returns anything other than {@link Disposition#RESOLVED} must say so with a stable
 * {@link #code() code} rather than by returning an empty or partial result. {@link #retryable()} describes
 * the infrastructure outcome only: {@link Disposition#DENIED}, {@link Disposition#UNSUPPORTED} and
 * {@link Disposition#INVALID} are never retryable, because retrying a deterministic refusal would just
 * repeat it.
 *
 * <p>A failure on its own cannot know which disposition it belongs to, so the rule is enforced where the
 * two meet — the {@link ToolResultEnvelope} constructor — against {@link Disposition#allowsRetryableFailure()}.
 * This record also carries the authoritative {@link #issues()} for a non-resolved result: the envelope's
 * top-level issue list is required to equal it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record ToolFailure(
        @NotBlank String code,
        @NotBlank String message,
        boolean retryable,
        @NotNull List<@Valid Issue> issues
) {
    public ToolFailure {
        issues = ImmutableValues.freezeList(issues);
    }

    public static ToolFailure of(String code, String message, Issue... issues) {
        return new ToolFailure(code, message, false, List.of(issues));
    }
}