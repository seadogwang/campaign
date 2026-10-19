package com.company.campaign.query.plan;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * The trusted context a plan is bound to: tenant, program, principal membership, DataScope, time and currency.
 *
 * <p>Every value here is copied from a host-created {@code TrustedExecutionContext}, never from DSL, model or
 * canonical input. The binding is part of the plan hash, so a plan authorized for one tenant/program/scope or
 * compiled at one {@code asOf}/{@code timezone}/{@code reportingCurrency}/{@code dataWatermark} can never be
 * replayed under another.
 *
 * <p>The timezone is retained as an explicit pin even where a concrete instant calculation does not use local
 * calendar arithmetic. A watermark after {@code asOf} is contradictory and is rejected, as is a timezone that
 * cannot be resolved.
 */
public record TrustedPlanContext(
        String tenantId,
        String programId,
        String membershipId,
        String userId,
        String dataScopeId,
        Instant asOf,
        String timezone,
        String reportingCurrency,
        Instant dataWatermark) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    public TrustedPlanContext {
        requireId(tenantId, "tenantId");
        requireId(programId, "programId");
        requireId(membershipId, "membershipId");
        requireId(userId, "userId");
        requireId(dataScopeId, "dataScopeId");
        if (asOf == null || dataWatermark == null) {
            throw invalid("timeContext", "A trusted plan context requires asOf and a data watermark.");
        }
        if (dataWatermark.isAfter(asOf)) {
            throw invalid("timeContext", "A data watermark must not be after the evaluation instant.");
        }
        if (timezone == null || timezone.isBlank()) {
            throw invalid("timezone", "A trusted plan context requires a timezone.");
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            // The rejected zone text is deliberately not attached.
            throw invalid("timezone", "The trusted plan context timezone is not resolvable.");
        }
        if (reportingCurrency == null || !CURRENCY.matcher(reportingCurrency).matches()) {
            throw invalid("reportingCurrency", "A trusted plan context requires an ISO-4217 reporting currency.");
        }
    }

    private static void requireId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw invalid(field, "A trusted plan context requires bounded reference identifiers.");
        }
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_TIME_CONTEXT_INVALID, invariant, message);
    }
}