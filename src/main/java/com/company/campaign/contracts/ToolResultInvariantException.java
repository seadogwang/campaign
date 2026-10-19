package com.company.campaign.contracts;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Raised when a Tool result envelope contradicts one of its documented invariants. */
public final class ToolResultInvariantException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** A {@link Disposition#RESOLVED} envelope carried no result value. */
    public static final String RESULT_REQUIRED_FOR_RESOLVED = "RESULT_REQUIRED_FOR_RESOLVED";

    /** A {@link Disposition#RESOLVED} envelope carried a failure. */
    public static final String FAILURE_FORBIDDEN_FOR_RESOLVED = "FAILURE_FORBIDDEN_FOR_RESOLVED";

    /** A non-resolved envelope carried a result value. */
    public static final String RESULT_FORBIDDEN_FOR_UNRESOLVED = "RESULT_FORBIDDEN_FOR_UNRESOLVED";

    /** A non-resolved envelope carried no failure. */
    public static final String FAILURE_REQUIRED_FOR_UNRESOLVED = "FAILURE_REQUIRED_FOR_UNRESOLVED";

    /** A failure claimed to be retryable under a disposition whose verdict is deterministic. */
    public static final String RETRYABLE_FORBIDDEN_FOR_DISPOSITION = "RETRYABLE_FORBIDDEN_FOR_DISPOSITION";

    /** The envelope issue list disagreed with {@code failure.issues()}. */
    public static final String ISSUES_DISAGREE_WITH_FAILURE = "ISSUES_DISAGREE_WITH_FAILURE";

    public ToolResultInvariantException(String detail, Disposition disposition, String invariant) {
        super(
                ContractErrorCodes.TOOL_RESULT_INVARIANT,
                detail,
                // LinkedHashMap so details serialize in a stable order; the discriminator comes first
                // because it is what a branch on this failure will read.
                details(disposition, invariant));
    }

    private static Map<String, Object> details(Disposition disposition, String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        details.put("disposition", String.valueOf(disposition));
        return details;
    }
}