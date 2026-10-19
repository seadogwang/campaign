package com.company.campaign.preview;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Immutable, minimized Preview response data.
 *
 * <p>It carries exactly the v1.3 Preview data fields: status, the canonical AST hash, the deterministic
 * query-plan id derived from the full plan hash, the full plan hash, the exact matched count, optional
 * policy-bounded condition statistics, an optional policy-bounded sample and the safe execution context.
 *
 * <p>It never carries a raw member identifier or attribute, a tenant/program/user/membership/DataScope
 * identifier, a permission, a Metadata label or inaccessible identifier, a DSL operand, a fixture identifier,
 * path or row, SQL or AST text, a bind parameter, an internal authorization reason, a secret, an exception
 * message or a stack trace: none of those is representable in this type.
 *
 * <p>Optional fields are omitted on the wire when absent, so "statistics disabled" and "no sample" are
 * genuinely absent rather than an empty stand-in. {@link #contentHash()} covers every projected fact and is
 * computed from strict canonical bytes, so identical calls produce byte-identical material.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewResponse(
        String status,
        String canonicalAstHash,
        String queryPlanId,
        String queryPlanHash,
        int matchedCount,
        List<PreviewConditionStatistic> conditionStats,
        List<String> sample,
        PreviewExecutionContext executionContext
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public PreviewResponse {
        if (status == null || canonicalAstHash == null || queryPlanId == null || queryPlanHash == null
                || executionContext == null) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "previewResponse");
        }
        if (matchedCount < 0) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "matchedCount");
        }
        conditionStats = copyBounded(conditionStats, "conditionStatistic");
        sample = copyBounded(sample, "sample");
    }

    /**
     * Copies an optional list, refusing a null element with the one bounded code rather than a raw NPE.
     *
     * <p>The lists are internally produced and already bounded, but a public factory must still fail closed:
     * a null element is an incoherent response, not an unhandled {@link NullPointerException}.
     */
    private static <T> List<T> copyBounded(List<T> values, String invariant) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value == null) {
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, invariant);
            }
        }
        return List.copyOf(values);
    }

    /** The canonical content hash of every projected fact; deterministic for identical inputs. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(new Material(
                status, canonicalAstHash, queryPlanId, queryPlanHash, matchedCount,
                conditionStats, sample, executionContext));
    }

    /** The exact material the response content hash covers. */
    private record Material(
            String status,
            String canonicalAstHash,
            String queryPlanId,
            String queryPlanHash,
            int matchedCount,
            List<PreviewConditionStatistic> conditionStats,
            List<String> sample,
            PreviewExecutionContext executionContext) {
    }
}