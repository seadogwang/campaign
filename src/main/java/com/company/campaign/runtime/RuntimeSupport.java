package com.company.campaign.runtime;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bounded, value-safe construction helpers every immutable Runtime record shares.
 *
 * <p>They exist so that one runtime record's identifier rule, machine-code rule and bounded collection rule
 * cannot drift from another's: a member identity accepted as a canonical identifier is accepted as one by
 * every record, and a failure names only the invariant it refused. Nothing here reads a clock, a locale, a
 * random source, a thread context or a mutable registry.
 */
public final class RuntimeSupport {

    /** The canonical bounded identifier form shared with the base contracts. */
    public static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** The bounded uppercase machine-code form shared with the base contracts. */
    public static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private RuntimeSupport() {
    }

    /** Requires a bounded canonical identifier. */
    public static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires a bounded uppercase machine code. */
    public static String requireMachineCode(String value, String field) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires an optional bounded machine code: present-but-invalid is refused, absent stays absent. */
    public static String requireOptionalMachineCode(String value, String field) {
        return value == null ? null : requireMachineCode(value, field);
    }

    /** Requires a non-negative count within an inclusive bound. */
    public static int requireCount(int value, int max, String field) {
        if (value < 0 || value > max) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, field,
                    "A runtime count must be non-negative and within its frozen bound.");
        }
        return value;
    }

    /** Requires a positive revision or generation number. */
    public static long requirePositive(long value, String field) {
        if (value < 1) {
            throw invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires a non-negative revision or attempt number. */
    public static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, field);
        }
        return value;
    }

    /** Requires a bounded, defensively copied, non-null collection. */
    public static <T> List<T> immutable(List<T> values, int max, String field) {
        if (values == null || values.size() > max) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, field,
                    "A runtime collection must be present and within its frozen bound.");
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                throw invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, field);
            }
            copy.add(value);
        }
        return List.copyOf(copy);
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

    /** A stable content hash of a fixture or evidence seed; used only for bounded, non-production evidence. */
    public static ContentHash seedHash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** One bounded rejection carrying only the failure code and the invariant name. */
    public static RuntimeBoundaryException invalid(String code, String field) {
        return new RuntimeBoundaryException(
                code, field, "A runtime contract value is not a bounded, self-binding immutable record.");
    }

    /** One bounded rejection for a value that is not a bounded, self-binding immutable record. */
    public static RuntimeBoundaryException malformed(String field) {
        return invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, field);
    }
}