package com.company.campaign.builder.journey;

import com.company.campaign.contracts.ContractPatterns;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One typed topology edge declaration of a Journey authoring request.
 *
 * <p>Both endpoints are references to node declarations of the same request, and the branch is the closed
 * {@code DEFAULT}, {@code TRUE}, {@code FALSE} vocabulary. The reference here is the caller's own declaration
 * identity, never a final component identity: the compiler is what turns it into a real one, so a caller can
 * never name an identity it does not own.
 */
public record JourneyEdgeDeclaration(String edgeId, String fromRef, String toRef, String branchName) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern BRANCH = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    public JourneyEdgeDeclaration {
        if (edgeId == null || !IDENTIFIER.matcher(edgeId).matches()
                || fromRef == null || !IDENTIFIER.matcher(fromRef).matches()
                || toRef == null || !IDENTIFIER.matcher(toRef).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.EDGE_INVALID);
        }
        if (branchName == null || !BRANCH.matcher(branchName).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.EDGE_INVALID);
        }
        if (fromRef.equals(toRef)) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.EDGE_SELF);
        }
        Objects.requireNonNull(branchName, "branchName");
    }

    /** Redacted diagnostics: the edge identity and its branch only, never an endpoint value. */
    @Override
    public String toString() {
        return "JourneyEdgeDeclaration[edgeId=" + edgeId + ", branch=" + branchName + ']';
    }
}