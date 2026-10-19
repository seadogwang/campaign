package com.company.campaign.action;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bounded, value-safe construction helpers every immutable action record shares.
 *
 * <p>They exist so that one action record's identifier rule, machine-code rule, amount rule and bounded
 * collection rule cannot drift from another's. Nothing here reads a clock, a locale, a random source, a thread
 * context or a mutable registry: an identifier is always derived from canonical hash material, never from a
 * counter or an ambient value.
 */
public final class ActionSupport {

    /** The canonical bounded identifier form shared with the base contracts. */
    public static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** The bounded uppercase machine-code form shared with the base contracts. */
    public static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    /** The canonical ISO-4217 currency form shared with the frozen reward catalog. */
    public static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    /**
     * The bounded business-idempotency-key form.
     *
     * <p>It is deliberately wider than a plain identifier: the key is the four colon-joined bounded components
     * {@code tenantId:participantId:nodeLogicalId:actionKind}, so it must never be truncated to fit an
     * identifier bound. It is still a bounded, closed alphabet and never carries a separator or a member value.
     */
    public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,511}$");

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private ActionSupport() {
    }

    /** Requires a bounded canonical identifier. */
    public static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(ActionErrorCodes.ACTION_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires an optional bounded canonical identifier: present-but-invalid is refused, absent stays absent. */
    public static String requireOptionalIdentifier(String value, String field) {
        return value == null ? null : requireIdentifier(value, field);
    }

    /** Requires a bounded business idempotency key. */
    public static String requireKey(String value, String field) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid(ActionErrorCodes.ACTION_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires a bounded uppercase machine code. */
    public static String requireMachineCode(String value, String field) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw invalid(ActionErrorCodes.ACTION_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires a bounded, non-negative whole number within an inclusive bound. */
    public static int requireCount(int value, int max, String field) {
        if (value < 0 || value > max) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BOUND_EXCEEDED, field,
                    "An action count must be non-negative and within its frozen bound.");
        }
        return value;
    }

    /** Requires a non-negative revision or attempt number. */
    public static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw invalid(ActionErrorCodes.ACTION_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires a bounded, defensively copied, non-null collection. */
    public static <T> List<T> immutable(List<T> values, int max, String field) {
        if (values == null || values.size() > max) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BOUND_EXCEEDED, field,
                    "An action collection must be present and within its frozen bound.");
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                throw invalid(ActionErrorCodes.ACTION_INPUT_INVALID, field);
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    /**
     * Requires one present, non-negative amount within the frozen precision and normalizes it to the one exact
     * scale every budget amount compares and hashes at.
     */
    public static BigDecimal requireAmount(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, field);
        }
        if (value.scale() > ActionVersions.AMOUNT_SCALE) {
            // More precision than a budget amount may carry: refused rather than silently rounded.
            throw invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, field);
        }
        if (value.stripTrailingZeros().precision() > ActionVersions.MAX_AMOUNT_PRECISION) {
            throw invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, field);
        }
        return value.setScale(ActionVersions.AMOUNT_SCALE);
    }

    /** Requires a canonical ISO-4217 currency code. */
    public static String requireCurrency(String value, String field) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, field);
        }
        return value;
    }

    /** The canonical hash of a record's material object. */
    public static ContentHash hash(Object material) {
        return CANONICAL_JSON.hash(material);
    }

    /**
     * A deterministic bounded identity derived from canonical hash material.
     *
     * <p>It is never a random identifier: the same material always derives the same identity, and the derived
     * identity declares its own kind so two derivations can never collide by accident.
     */
    public static String derivedId(String prefix, Object material) {
        String digest = hash(material).value().substring(CanonicalJson.HASH_PREFIX.length());
        return prefix + '-' + digest.substring(0, 48);
    }

    /** A stable content hash of a bounded, non-production fixture seed. */
    public static ContentHash seedHash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** One bounded rejection carrying only the failure code and the invariant name. */
    public static ActionBoundaryException invalid(String code, String field) {
        return new ActionBoundaryException(
                code, field, "An action contract value is not a bounded, self-binding immutable record.");
    }

    /** One bounded rejection for material that is not a bounded, self-binding immutable record. */
    public static ActionBoundaryException malformed(String field) {
        return invalid(ActionErrorCodes.ACTION_INPUT_INVALID, field);
    }
}