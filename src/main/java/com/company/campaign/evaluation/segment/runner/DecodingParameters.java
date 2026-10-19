package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The closed, typed decoding contract of one frozen run.
 *
 * <p>T13 deliberately does not carry an unrestricted JSON object here. A run contract that froze decoding as
 * an open tree could silently change with the provider's own defaults, and a report that froze an open tree
 * would be reproducible only if the reader knew the same defaults. Every parameter below is typed, bounded and
 * part of the frozen contract hash, so "the same frozen inputs" is a property of this value rather than of the
 * provider's ambient configuration.
 *
 * <p>{@code samplingPolicy} is a required bounded code because a provider that samples and a provider that is
 * deterministic are not the same experiment, and a report must say which one it recorded. {@code seed} is
 * required for the same reason: an omitted seed is a different, non-reproducible experiment.
 */
public record DecodingParameters(
        String samplingPolicy,
        BigDecimal temperature,
        BigDecimal topP,
        Integer topK,
        int maxOutputTokens,
        List<String> stopSequences,
        long seed
) {

    /** The maximum number of stop sequences the contract admits. */
    public static final int MAX_STOP_SEQUENCES = 16;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int MAX_OUTPUT_TOKENS = 262_144;

    public DecodingParameters {
        if (samplingPolicy == null || !samplingPolicy.matches("^[a-z][a-z0-9-]{0,31}$")) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.DECODING_INVALID,
                    "Decoding parameters require a bounded sampling policy code");
        }
        if (temperature == null || temperature.compareTo(ZERO) < 0 || temperature.compareTo(ONE) > 0) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.DECODING_INVALID, "Temperature must be within [0, 1]");
        }
        if (topP != null && (topP.compareTo(ZERO) <= 0 || topP.compareTo(ONE) > 0)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.DECODING_INVALID, "topP must be within (0, 1]");
        }
        if (topK != null && (topK < 1 || topK > 4096)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.DECODING_INVALID, "topK must be within [1, 4096]");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.DECODING_INVALID, "maxOutputTokens is out of range");
        }
        if (stopSequences == null) {
            stopSequences = List.of();
        }
        if (stopSequences.size() > MAX_STOP_SEQUENCES) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.DECODING_INVALID, "The stop-sequence list exceeds its bound");
        }
        List<String> copy = new ArrayList<>(stopSequences.size());
        for (String stop : stopSequences) {
            if (stop == null || stop.isEmpty() || stop.length() > 64) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.DECODING_INVALID, "A stop sequence is not bounded");
            }
            copy.add(stop);
        }
        stopSequences = List.copyOf(copy);
    }

    /** The one decoding contract of a deterministic, model-free or replay-driven evaluation. */
    public static DecodingParameters deterministicReplay(int maxOutputTokens, long seed) {
        return new DecodingParameters("deterministic-replay", ZERO, null, null, maxOutputTokens, List.of(), seed);
    }

    /** True only when the contract declares a non-sampling decoder. */
    public boolean isDeterministic() {
        return "deterministic-replay".equals(samplingPolicy) || temperature.compareTo(ZERO) == 0;
    }

    /** The canonical hash of this decoding contract, computed from the exact typed fields. */
    public ContentHash contentHash() {
        return new CanonicalJson().hash(this);
    }
}