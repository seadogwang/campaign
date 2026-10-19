package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.contracts.ContractPatterns;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The closed, typed bound a capability pack payload field declares alongside its value kind.
 *
 * <p>A field schema is a kind plus exactly one constraint whose own kind must match: a text bound for
 * {@link CapabilityValueKind#STRING}, a numeric range for integer and decimal kinds, an enumeration for
 * {@link CapabilityValueKind#ORDERED_ENUM}, a reference kind for {@link CapabilityValueKind#REFERENCE}, and
 * the explicit unconstrained marker for the kinds that carry no bound at all. Because the union is sealed,
 * an unknown or mismatched bound cannot be represented — it fails closed while the contract is read.
 *
 * <p>Every bound is itself bounded: text has a positive maximum length, integers and durations have an
 * explicit finite range, decimals have a precision/scale pair with in-range endpoints, and an ordered
 * enumeration has a non-empty, duplicate-free, machine-code-only code list whose order is preserved because
 * the kind is ordered. An unbounded or contradictory declaration is refused rather than silently accepted.
 */
public sealed interface CapabilityFieldConstraint {

    /** Largest permitted text length of one string field. */
    int MAX_TEXT_LENGTH = 4096;

    /** Largest permitted absolute value of an integer bound. */
    long MAX_INTEGER_MAGNITUDE = 1_000_000_000_000_000L;

    /** Largest permitted decimal precision. */
    int MAX_PRECISION = 38;

    /** Largest permitted duration, in seconds (100 years). */
    long MAX_DURATION_SECONDS = 3_155_760_000L;

    /** Largest permitted number of codes in an ordered enumeration. */
    int MAX_ENUM_CODES = 64;

    /** The value kind this constraint belongs to. */
    CapabilityValueKind kind();

    /** The unconstrained marker for the kinds that declare no bound. */
    record Unconstrained(CapabilityValueKind kind) implements CapabilityFieldConstraint {

        private static final Set<CapabilityValueKind> UNBOUNDED =
                Set.of(CapabilityValueKind.BOOLEAN, CapabilityValueKind.DATE, CapabilityValueKind.DATETIME);

        public Unconstrained {
            if (kind == null || !UNBOUNDED.contains(kind)) {
                throw invalid("capabilityPackFieldConstraintKind");
            }
        }
    }

    /** A bounded text field. */
    record Text(int maxLength) implements CapabilityFieldConstraint {

        public Text {
            if (maxLength < 1 || maxLength > MAX_TEXT_LENGTH) {
                throw invalid("capabilityPackFieldTextBound");
            }
        }

        @Override
        public CapabilityValueKind kind() {
            return CapabilityValueKind.STRING;
        }
    }

    /** An inclusive integer range. */
    record IntegerBounds(long min, long max) implements CapabilityFieldConstraint {

        public IntegerBounds {
            if (min > max) {
                throw invalid("capabilityPackFieldIntegerRange");
            }
            if (Math.abs(min) > MAX_INTEGER_MAGNITUDE || Math.abs(max) > MAX_INTEGER_MAGNITUDE) {
                throw invalid("capabilityPackFieldIntegerBound");
            }
        }

        @Override
        public CapabilityValueKind kind() {
            return CapabilityValueKind.INTEGER;
        }
    }

    /** A bounded exact-decimal range with an explicit precision and scale. */
    record DecimalBounds(int precision, int scale, BigDecimal min, BigDecimal max)
            implements CapabilityFieldConstraint {

        public DecimalBounds {
            if (precision < 1 || precision > MAX_PRECISION) {
                throw invalid("capabilityPackFieldDecimalPrecision");
            }
            if (scale < 0 || scale > precision) {
                throw invalid("capabilityPackFieldDecimalScale");
            }
            if (min == null || max == null || min.compareTo(max) > 0) {
                throw invalid("capabilityPackFieldDecimalRange");
            }
            if (min.scale() > scale || max.scale() > scale) {
                throw invalid("capabilityPackFieldDecimalScale");
            }
            // Normalized to the declared scale so two equal ranges always compare and hash identically.
            min = min.setScale(scale);
            max = max.setScale(scale);
        }

        @Override
        public CapabilityValueKind kind() {
            return CapabilityValueKind.DECIMAL;
        }
    }

    /** An inclusive duration range, in seconds. */
    record DurationBounds(long minSeconds, long maxSeconds) implements CapabilityFieldConstraint {

        public DurationBounds {
            if (minSeconds < 0 || minSeconds > maxSeconds || maxSeconds < 1) {
                throw invalid("capabilityPackFieldDurationRange");
            }
            if (maxSeconds > MAX_DURATION_SECONDS) {
                throw invalid("capabilityPackFieldDurationBound");
            }
        }

        @Override
        public CapabilityValueKind kind() {
            return CapabilityValueKind.DURATION;
        }
    }

    /** A closed, ordered set of machine codes; the declared order is meaningful and preserved. */
    record EnumCodes(List<String> codes) implements CapabilityFieldConstraint {

        private static final Pattern CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

        public EnumCodes {
            if (codes == null || codes.isEmpty() || codes.size() > MAX_ENUM_CODES) {
                throw invalid("capabilityPackFieldEnumCodes");
            }
            Set<String> distinct = new LinkedHashSet<>(codes.size());
            for (String code : codes) {
                if (code == null || !CODE.matcher(code).matches()) {
                    throw invalid("capabilityPackFieldEnumCode");
                }
                CapabilityPackSafety.requireSafe(code, "capabilityPackFieldEnumCode");
                distinct.add(code);
            }
            if (distinct.size() != codes.size()) {
                throw invalid("capabilityPackFieldEnumCodes");
            }
            codes = List.copyOf(new ArrayList<>(distinct));
        }

        @Override
        public CapabilityValueKind kind() {
            return CapabilityValueKind.ORDERED_ENUM;
        }
    }

    /** A reference to another resolved component, named only by a bounded reference kind. */
    record ReferenceTarget(String referenceKind) implements CapabilityFieldConstraint {

        private static final Pattern KIND = Pattern.compile(ContractPatterns.MACHINE_CODE);

        public ReferenceTarget {
            if (referenceKind == null || !KIND.matcher(referenceKind).matches()) {
                throw invalid("capabilityPackFieldReferenceKind");
            }
            CapabilityPackSafety.requireSafe(referenceKind, "capabilityPackFieldReferenceKind");
        }

        @Override
        public CapabilityValueKind kind() {
            return CapabilityValueKind.REFERENCE;
        }
    }

    /** The unconstrained marker for one of the unbounded kinds. */
    static CapabilityFieldConstraint unconstrained(CapabilityValueKind kind) {
        return new Unconstrained(kind);
    }

    private static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                invariant,
                "A capability pack field constraint must be one bounded, kind-matched declaration.");
    }
}