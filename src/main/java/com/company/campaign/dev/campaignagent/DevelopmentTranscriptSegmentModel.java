package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelCallContext;
import com.company.campaign.builder.segment.SegmentModelCallKind;
import com.company.campaign.builder.segment.SegmentModelLimits;
import com.company.campaign.builder.segment.SegmentModelRequest;

import java.util.Objects;

/**
 * The observation decorator that records every raw Segment Intent/model response of the development host.
 *
 * <p>It is a pure pass-through: it delegates the one bounded Segment model call unchanged and returns exactly the
 * bytes the delegate returned, so the accepted T10 codec sees the identical document and the Segment flow
 * resolves, repairs or refuses exactly as before. It records only the response document — never the projected
 * Metadata, the utterance, the locale, the snapshot pin, the endpoint or a model setting.
 *
 * <p>The recorded call kind distinguishes the one first-pass call from the single bounded schema-repair call the
 * accepted Segment model-call context declares, so the file shows which attempt a response belongs to without
 * ever recording the repair issue codes as if they were model output.
 *
 * <p>When no conversation is being served on this thread, or when the transcript is disabled, it is inert.
 */
public final class DevelopmentTranscriptSegmentModel implements SegmentIntentModelPort {

    private final SegmentIntentModelPort delegate;
    private final DevelopmentTranscriptRecorder transcript;

    public DevelopmentTranscriptSegmentModel(
            SegmentIntentModelPort delegate, DevelopmentTranscriptRecorder transcript) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    @Override
    public byte[] invoke(SegmentModelRequest request) {
        byte[] output = delegate.invoke(request);
        transcript.recordModelResponse(modelCallKind(request.callContext()), output,
                SegmentModelLimits.MAX_MODEL_OUTPUT_BYTES);
        return output;
    }

    private static String modelCallKind(SegmentModelCallContext callContext) {
        return callContext != null && callContext.kind() == SegmentModelCallKind.SCHEMA_REPAIR
                ? DevelopmentTranscriptRecorder.SEGMENT_INTENT_SCHEMA_REPAIR
                : DevelopmentTranscriptRecorder.SEGMENT_INTENT;
    }

    /** A bounded diagnostic: the wiring facts only, never a projection or an output. */
    @Override
    public String toString() {
        return "DevelopmentTranscriptSegmentModel[observing=true, " + delegate + ']';
    }
}