package com.company.campaign.query.plan;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * The mandatory, closed semantic DataScope requirement every valid plan carries.
 *
 * <p>It states one fact and nothing more: the plan's root subject must belong to one host-resolved DataScope.
 * It deliberately does not pretend that the scope identifier is a database column or a raw predicate;
 * storage-specific lowering belongs to a later task. It is kept strictly apart from the user predicate tree
 * and is derived only from the trusted context, never from the DSL or the canonical AST.
 *
 * <p>{@link Requirement#SUBJECT_IN_DATASCOPE} is the caller's own trusted scope and is the only shape a plan
 * without an Existing Audience Reference may carry, so such a plan is byte-identical to what it was before
 * references existed.
 *
 * <p>{@link Requirement#SUBJECT_IN_EFFECTIVE_DATASCOPE} is the host-computed <em>intersection</em> of the
 * caller scope with every referenced version's effective scope. A document that references another audience
 * can only ever be evaluated under that intersection, so a plan that narrowed nothing would be claiming more
 * reach than the caller actually has. Because an intersection is a proof rather than a label, this shape also
 * carries the exact content hash of the effective scope: two scopes sharing an identifier but not their
 * contents compare unequal, so a widened scope cannot be passed off as a narrower one under a familiar name.
 */
public record DataScopePredicate(
        Requirement kind,
        SubjectKind subject,
        String dataScopeId,
        ContentHash dataScopeHash
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public DataScopePredicate {
        if (kind == null || subject == null) {
            throw invalid("dataScopePredicate", "A DataScope predicate requires a kind and a subject.");
        }
        if (dataScopeId == null || !REFERENCE_ID.matcher(dataScopeId).matches()) {
            throw invalid("dataScopeId", "A DataScope predicate requires a bounded host-resolved scope identifier.");
        }
        if (kind == Requirement.SUBJECT_IN_DATASCOPE && dataScopeHash != null) {
            throw invalid("dataScopeHash",
                    "A caller-scope requirement must not claim an effective-scope hash.");
        }
        if (kind == Requirement.SUBJECT_IN_EFFECTIVE_DATASCOPE && dataScopeHash == null) {
            throw invalid("dataScopeHash",
                    "An effective-scope requirement requires the hash of its exact scope contents.");
        }
    }

    /** The caller's own trusted scope, with no effective-scope proof attached. */
    public DataScopePredicate(Requirement kind, SubjectKind subject, String dataScopeId) {
        this(kind, subject, dataScopeId, null);
    }

    /** True when this requirement binds a host-computed effective intersection rather than the caller scope. */
    public boolean isEffectiveIntersection() {
        return kind == Requirement.SUBJECT_IN_EFFECTIVE_DATASCOPE;
    }

    /** The closed set of DataScope requirements a plan can express. */
    public enum Requirement {
        /** The root subject must belong to the caller's host-resolved DataScope. */
        SUBJECT_IN_DATASCOPE,

        /** The root subject must belong to the host-computed intersection of the caller scope and every reference. */
        SUBJECT_IN_EFFECTIVE_DATASCOPE
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }
}