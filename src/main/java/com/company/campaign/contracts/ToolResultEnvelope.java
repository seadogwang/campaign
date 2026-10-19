package com.company.campaign.contracts;

import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Terminal result of a Tool call.
 *
 * <p>Invariants, all enforced in the canonical constructor so a contradictory envelope cannot be built
 * directly or read from JSON:
 *
 * <ol>
 *   <li>a result value is present if and only if {@link #disposition()} is {@link Disposition#RESOLVED};</li>
 *   <li>a {@link #failure()} is present if and only if the disposition is not {@link Disposition#RESOLVED};</li>
 *   <li>a failure may claim {@code retryable} only under a disposition that allows it, which is
 *       {@link Disposition#allowsRetryableFailure()};</li>
 *   <li>for a non-resolved result the top-level {@link #issues()} are the failure's issues — the failure is
 *       the single authoritative source, so the two cannot disagree.</li>
 * </ol>
 *
 * <p>{@link #inputHash()} binds the answer to the exact canonical bytes of the model-supplied arguments it
 * answered. That makes a Tool result replayable and citable in an audit record without copying restricted
 * payload content, and it detects a result being paired with different arguments after the fact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record ToolResultEnvelope<T>(
        @NotNull @Valid SchemaVersion schemaVersion,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String toolCallId,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String toolId,
        @NotNull Disposition disposition,
        @Valid T result,
        @Valid ToolFailure failure,
        @NotNull @Valid ContentHash inputHash,
        @NotNull List<@Valid Issue> issues
) {
    public ToolResultEnvelope {
        issues = ImmutableValues.freezeList(issues);
        boolean resolved = disposition != null && disposition.isResolved();

        if (resolved && result == null) {
            throw new ToolResultInvariantException(
                    "A RESOLVED Tool result must carry a result value",
                    disposition,
                    ToolResultInvariantException.RESULT_REQUIRED_FOR_RESOLVED);
        }
        if (resolved && failure != null) {
            throw new ToolResultInvariantException(
                    "A RESOLVED Tool result must not carry a failure",
                    disposition,
                    ToolResultInvariantException.FAILURE_FORBIDDEN_FOR_RESOLVED);
        }
        if (!resolved && result != null) {
            throw new ToolResultInvariantException(
                    "Only a RESOLVED Tool result may carry a result value",
                    disposition,
                    ToolResultInvariantException.RESULT_FORBIDDEN_FOR_UNRESOLVED);
        }
        if (!resolved && failure == null) {
            throw new ToolResultInvariantException(
                    "A non-RESOLVED Tool result must carry a failure",
                    disposition,
                    ToolResultInvariantException.FAILURE_REQUIRED_FOR_UNRESOLVED);
        }
        if (!resolved && failure.retryable() && !allowsRetryable(disposition)) {
            throw new ToolResultInvariantException(
                    "A " + disposition + " Tool failure is never retryable",
                    disposition,
                    ToolResultInvariantException.RETRYABLE_FORBIDDEN_FOR_DISPOSITION);
        }
        if (!resolved && !issues.equals(ImmutableValues.freezeList(failure.issues()))) {
            throw new ToolResultInvariantException(
                    "Envelope issues must equal failure.issues() for a non-RESOLVED Tool result",
                    disposition,
                    ToolResultInvariantException.ISSUES_DISAGREE_WITH_FAILURE);
        }
    }

    /** Builds a resolved result. */
    public static <T> ToolResultEnvelope<T> resolved(
            String toolCallId, String toolId, T result, ContentHash inputHash, List<Issue> issues) {
        return new ToolResultEnvelope<>(
                ContractVersions.CURRENT, toolCallId, toolId, Disposition.RESOLVED,
                result, null, inputHash, issues);
    }

    /**
     * Builds a non-resolved result whose top-level issues are taken from the failure.
     *
     * <p>The caller cannot supply a second, possibly divergent, issue list.
     */
    public static <T> ToolResultEnvelope<T> unresolved(
            String toolCallId, String toolId, Disposition disposition, ToolFailure failure, ContentHash inputHash) {
        return new ToolResultEnvelope<>(
                ContractVersions.CURRENT, toolCallId, toolId, disposition,
                null, failure, inputHash, failure.issues());
    }

    /** A null disposition already fails {@code @NotNull}; it is not a disposition that permits retrying. */
    private static boolean allowsRetryable(Disposition disposition) {
        return disposition != null && disposition.allowsRetryableFailure();
    }
}