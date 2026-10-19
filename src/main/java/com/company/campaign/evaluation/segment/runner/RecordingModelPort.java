package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelCallKind;
import com.company.campaign.builder.segment.SegmentModelRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * The pass-through provider decorator the Evaluation Runner wraps around the host-owned model port.
 *
 * <p>It exists for one reason: the Agent result publishes whether an attempt failed the model-output contract,
 * but not whether the rejected call was the first pass or the single permitted repair, and not whether the
 * provider itself threw. An evaluation that reports First-pass Success and One-repair Success separately must
 * know both. The decorator records only the closed host-owned {@link SegmentModelCallKind} of each call and
 * whether the port threw; it never records a request body, a response, a byte, a credential or a provider
 * message, and it changes nothing about the call: the exact request is forwarded and the exact result returned.
 *
 * <p>A thrown provider failure is recorded and rethrown unchanged, so the decorator cannot convert an
 * infrastructure failure into a model failure.
 */
public final class RecordingModelPort implements SegmentIntentModelPort {

    private final SegmentIntentModelPort delegate;
    private final List<SegmentModelCallKind> calls = new ArrayList<>();
    private boolean providerFailed;

    public RecordingModelPort(SegmentIntentModelPort delegate) {
        if (delegate == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.HOST_SERVICE_MISSING, "A recording model port requires a delegate");
        }
        this.delegate = delegate;
    }

    @Override
    public byte[] invoke(SegmentModelRequest request) {
        if (request == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.HOST_SERVICE_MISSING, "A model call requires its frozen request");
        }
        SegmentModelCallKind kind = request.callContext().kind();
        calls.add(kind);
        try {
            return delegate.invoke(request);
        } catch (RuntimeException failure) {
            providerFailed = true;
            throw failure;
        }
    }

    /** The closed call kinds this attempt performed, in order. */
    public List<SegmentModelCallKind> calls() {
        return List.copyOf(calls);
    }

    /** True when a recorded call threw, so the attempt was an infrastructure failure rather than a model one. */
    public boolean providerFailed() {
        return providerFailed;
    }

    /** True when the first pass was rejected and the single bounded repair call was attempted. */
    public boolean repairAttempted() {
        return calls.contains(SegmentModelCallKind.SCHEMA_REPAIR);
    }

    /** True when the attempt performed exactly one first-pass call and no repair. */
    public boolean firstPassOnly() {
        return calls.size() == 1 && calls.get(0) == SegmentModelCallKind.FIRST_PASS;
    }

    /** True when no first-pass call was ever made, which is itself an unsound attempt. */
    public boolean noCall() {
        return calls.isEmpty();
    }
}