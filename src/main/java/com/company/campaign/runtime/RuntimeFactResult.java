package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * The complete, bounded answer one host fact read returns.
 *
 * <p>Exactly one outcome may carry a typed value: {@link RuntimeFactOutcome#KNOWN} carries one canonical text of
 * its declared T19 value kind and nothing else. An {@link RuntimeFactOutcome#UNKNOWN} fact carries no value at
 * all — so "unresolved" is not representable as a value — an {@link RuntimeFactOutcome#ERROR} fact carries only
 * a bounded machine reason code, and a technical outcome carries neither.
 *
 * <p>The record re-proves that a known value is the one canonical spelling of its kind, so a host cannot answer
 * with a value the T19 contract could never have expressed. The consumer still decides whether the fact answers
 * the predicate it was asked about; this record only proves the answer is well formed.
 */
public record RuntimeFactResult(
        String formatVersion,
        RuntimeFactOutcome outcome,
        String factCode,
        String valueType,
        String value,
        String reasonCode,
        Instant observedAt,
        ContentHash resultHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RuntimeFactResult {
        if (!RuntimeVersions.FACT_RESULT_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(observedAt, "observedAt");
        if (outcome.isKnown()) {
            if (valueType == null || !RuntimeTypedValues.isCanonical(valueType, value)) {
                throw RuntimeSupport.invalid(
                        RuntimeErrorCodes.RUNTIME_CONDITION_VALUE_INVALID, "value");
            }
            if (reasonCode != null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "reasonCode");
            }
        } else {
            if (valueType != null || value != null) {
                // Only a known fact may carry a value; a failure never returns a partial one.
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "value");
            }
            if (outcome == RuntimeFactOutcome.ERROR) {
                RuntimeSupport.requireMachineCode(reasonCode, "reasonCode");
            } else if (reasonCode != null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "reasonCode");
            }
        }
        if (factCode != null) {
            // A fact code is optional evidence of which fact the host answered about, never authority.
            if (!RuntimeTypedValues.isFactCode(factCode)) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "factCode");
            }
        }
        Objects.requireNonNull(resultHash, "resultHash");
        if (!resultHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, outcome, factCode, valueType, value, reasonCode, observedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "resultHash");
        }
    }

    /** A known fact carrying the canonical text of one T19 value kind. */
    public static RuntimeFactResult known(
            String factCode, String valueType, String value, Instant observedAt) {
        return build(RuntimeFactOutcome.KNOWN, factCode, valueType, value, null, observedAt);
    }

    /** An explicitly unknown fact; it may never be treated as false. */
    public static RuntimeFactResult unknown(String factCode, Instant observedAt) {
        return build(RuntimeFactOutcome.UNKNOWN, factCode, null, null, null, observedAt);
    }

    /** An explicit fact error carrying only a bounded machine reason code. */
    public static RuntimeFactResult error(String factCode, String reasonCode, Instant observedAt) {
        return build(RuntimeFactOutcome.ERROR, factCode, null, null, reasonCode, observedAt);
    }

    /** The bounded technical answer a fact read records when it did not answer within its bound. */
    public static RuntimeFactResult timeout(String factCode, Instant observedAt) {
        return build(RuntimeFactOutcome.TIMEOUT, factCode, null, null, null, observedAt);
    }

    /** The bounded technical answer a fact read records when the host could not answer at all. */
    public static RuntimeFactResult unavailable(String factCode, Instant observedAt) {
        return build(RuntimeFactOutcome.UNAVAILABLE, factCode, null, null, null, observedAt);
    }

    private static RuntimeFactResult build(
            RuntimeFactOutcome outcome,
            String factCode,
            String valueType,
            String value,
            String reasonCode,
            Instant observedAt) {
        return new RuntimeFactResult(
                RuntimeVersions.FACT_RESULT_FORMAT_VERSION,
                outcome, factCode, valueType, value, reasonCode, observedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.FACT_RESULT_FORMAT_VERSION, outcome, factCode, valueType, value,
                        reasonCode, observedAt)));
    }

    private static Material material(
            String formatVersion,
            RuntimeFactOutcome outcome,
            String factCode,
            String valueType,
            String value,
            String reasonCode,
            Instant observedAt) {
        return new Material(formatVersion, outcome, factCode, valueType, value, reasonCode, observedAt);
    }

    /** Redacted diagnostics: no fact value and no fact identity is printed. */
    @Override
    public String toString() {
        return "RuntimeFactResult[outcome=" + outcome
                + ", valueType=" + valueType
                + ", reasonCode=" + reasonCode
                + ", observedAt=" + observedAt
                + ", resultHash=" + resultHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            RuntimeFactOutcome outcome,
            String factCode,
            String valueType,
            String value,
            String reasonCode,
            Instant observedAt) {
    }
}