package com.company.campaign.builder.compiler;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A bounded extension slot for a future risk summary.
 *
 * <p>T15 records that a Plan carries a bounded risk summary without implementing any risk semantics: risk
 * entries are closed-set machine codes, never free text and never payload values. T19 and later tasks
 * populate this; T15 always leaves it empty.
 */
public record RiskSummary(List<String> riskCodes) {

    /** Maximum risk codes one summary may carry. */
    public static final int MAX_RISK_CODES = 32;

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public RiskSummary {
        riskCodes = List.copyOf(Objects.requireNonNull(riskCodes, "riskCodes"));
        if (riskCodes.size() > MAX_RISK_CODES) {
            throw new IllegalArgumentException("a risk summary must stay within its bound");
        }
        for (String code : riskCodes) {
            if (code == null || !CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("risk codes must be bounded uppercase machine codes");
            }
        }
    }

    /** The one empty summary T15 produces. */
    public static RiskSummary empty() {
        return new RiskSummary(List.of());
    }
}