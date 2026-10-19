package com.company.campaign.dev.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;

/** Redacted development-only result of the clarified Metric-expression slice. */
public record DevelopmentMetricExpressionResult(
        Disposition disposition,
        String failureCode,
        String predicateJson,
        ContentHash dslEditableHash,
        ContentHash canonicalAstHash,
        ContentHash planHash,
        String sql,
        List<ParameterBinding> parameters,
        ContentHash compiledHash,
        String promptVersion,
        String promptHash
) {
    public DevelopmentMetricExpressionResult {
        if (disposition == null) {
            throw new IllegalArgumentException("A result requires a disposition");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        if (disposition.isResolved() == (failureCode != null)) {
            throw new IllegalArgumentException("Only a failed result carries a failure code");
        }
        if (disposition.isResolved() && sql == null) {
            throw new IllegalArgumentException("A resolved result requires SQL");
        }
    }

    public boolean isCompiled() {
        return disposition.isResolved();
    }

    public record ParameterBinding(SqlParameterType type, String canonicalValue, boolean redacted) {
        public ParameterBinding {
            if (type == null || redacted == (canonicalValue != null)) {
                throw new IllegalArgumentException("A parameter binding is inconsistent");
            }
        }
    }
}
