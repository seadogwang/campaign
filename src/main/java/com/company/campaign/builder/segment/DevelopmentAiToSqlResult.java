package com.company.campaign.builder.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The redacted, immutable {@code DEVELOPMENT_ONLY} result of one development AI-to-SQL invocation.
 *
 * <p>On success it exposes the Intent / DSL / canonical / plan / compiled hashes, the disposition, the rendered
 * SQL text, redacted typed parameters and non-sensitive version pins. It never exposes a raw credential, the
 * trusted context, a tenant/user/DataScope value, a raw member id, the provider response, hidden Metadata or a
 * database connection fact: context-bind parameters are reported as redacted placeholders that preserve their
 * exact count, order and type.
 *
 * <p>It never executes SQL and carries no database handle, connection pool or repository. It can never be a
 * Gate decision, an {@code AI_READY} decision, a Save, a Publish or Runtime evidence.
 */
public record DevelopmentAiToSqlResult(
        String evidenceClass,
        Disposition disposition,
        String failureCode,
        ContentHash intentHash,
        Clarification clarification,
        ContentHash dslEditableHash,
        ContentHash canonicalAstHash,
        ContentHash planHash,
        boolean authorizationPredicatePresent,
        String sql,
        List<ParameterBinding> parameters,
        String catalogVersion,
        ContentHash catalogHash,
        ContentHash compiledHash,
        String compilerVersion,
        String astVersion,
        String renderFormatVersion
) {

    public DevelopmentAiToSqlResult {
        if (!DevelopmentEvidence.DEVELOPMENT_ONLY.equals(evidenceClass)) {
            throw new IllegalArgumentException("Only a DEVELOPMENT_ONLY result may be produced");
        }
        if (disposition == null) {
            throw new IllegalArgumentException("A result requires a disposition");
        }
        parameters = parameters == null ? List.of() : List.copyOf(new ArrayList<>(parameters));
        if (disposition.isResolved() == (failureCode != null)) {
            throw new IllegalArgumentException("Only a non-resolved result may carry a failure code");
        }
        if (disposition.isResolved() && sql == null) {
            throw new IllegalArgumentException("A resolved result must carry the compiled SQL");
        }
        if (disposition.isResolved() && clarification != null) {
            throw new IllegalArgumentException("A resolved result must not carry a clarification");
        }
    }

    /** True only when the slice produced a compiled SQL artifact. */
    public boolean isCompiled() {
        return disposition.isResolved();
    }

    /** True always: this slice is development-only and never production evidence. */
    public boolean developmentOnly() {
        return true;
    }

    /**
     * One typed parameter binding, preserving the exact marker order.
     *
     * <p>A host-context bind (tenant, program or DataScope) is reported with {@code redacted = true} and a null
     * canonical value so the count, order and type are exact while the restricted value is never disclosed.
     */
    public record ParameterBinding(SqlParameterType type, String canonicalValue, boolean redacted) {

        public ParameterBinding {
            if (type == null) {
                throw new IllegalArgumentException("A parameter binding requires its type");
            }
            if (redacted == (canonicalValue != null)) {
                throw new IllegalArgumentException("A redacted binding must not carry a canonical value");
            }
        }
    }

    static DevelopmentAiToSqlResult failed(Disposition disposition, String code, ContentHash intentHash) {
        return failed(disposition, code, intentHash, null);
    }

    static DevelopmentAiToSqlResult failed(
            Disposition disposition,
            String code,
            ContentHash intentHash,
            Clarification clarification) {
        return new DevelopmentAiToSqlResult(
                DevelopmentEvidence.DEVELOPMENT_ONLY, disposition, code, intentHash, clarification,
                null, null, null, false,
                null, List.of(), null, null, null, null, null, null);
    }

    static DevelopmentAiToSqlResult compiled(
            ContentHash intentHash,
            ContentHash dslEditableHash,
            ContentHash canonicalAstHash,
            ContentHash planHash,
            boolean authorizationPredicatePresent,
            String sql,
            List<ParameterBinding> parameters,
            String catalogVersion,
            ContentHash catalogHash,
            ContentHash compiledHash,
            String compilerVersion,
            String astVersion,
            String renderFormatVersion) {
        return new DevelopmentAiToSqlResult(
                DevelopmentEvidence.DEVELOPMENT_ONLY, Disposition.RESOLVED, null, intentHash, null, dslEditableHash,
                canonicalAstHash, planHash, authorizationPredicatePresent, sql, parameters, catalogVersion,
                catalogHash, compiledHash, compilerVersion, astVersion, renderFormatVersion);
    }
}
