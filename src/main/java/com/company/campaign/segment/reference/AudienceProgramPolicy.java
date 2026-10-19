package com.company.campaign.segment.reference;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * Host-only policy that decides whether one program may reference a version owned by another.
 *
 * <p>The decision is a host fact, never a client boolean: neither the DSL, the canonical AST, the plan nor a
 * model payload carries an "allowed" flag, so a caller cannot grant itself access to a sibling program's
 * audience. The port receives only the two program identifiers the trusted context and the trusted catalog
 * supplied, so it cannot be steered by anything else.
 *
 * <p>A same-program reference is still asked about rather than assumed: the host owns the whole relation
 * table, and a policy that denies even same-program references must be honoured.
 */
@FunctionalInterface
public interface AudienceProgramPolicy {

    Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** True when {@code callerProgramId} may reference a version owned by {@code referencedProgramId}. */
    boolean allowsProgramRelation(String callerProgramId, String referencedProgramId);

    /** A host policy that allows every same-program reference and no cross-program one. */
    static AudienceProgramPolicy sameProgramOnly() {
        return (caller, referenced) -> caller != null && caller.equals(referenced);
    }

    /** Validates that one side of the relation is a bounded program identifier. */
    static void requireProgramId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw ReferenceException.invalid("A bounded '" + field + "' identifier is required");
        }
    }
}